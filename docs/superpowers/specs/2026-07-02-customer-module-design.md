# Customer Module Design (MVP)

**Date:** 2026-07-02
**Status:** Approved (brainstorming), pending implementation plan
**Phase:** Phase 4 (MVP completion) — first of `customer` → `reporting` → `dashboard`

## Goal

Add a `customer` module to the POS: register/edit/search customers, attach a
customer to a transaction, and view a customer's purchase history. This
completes the `customer` slice of the plan's §14 MVP line
(`customer (registration/search/history)`).

## Scope

**In scope (MVP core):**
- Customer master CRUD: register, edit, soft-delete (deactivate), view.
- Search by name / phone / email.
- Attach a customer to an in-progress cart (validated), carry the link through
  checkout onto the `Sale`, and expose per-customer **purchase history**.

**Explicitly deferred (not this phase):**
- Loyalty (points/rewards) — a separate module in the plan (§5).
- Customer groups / segments.
- Credit customers / store-credit / accounts-receivable tender.
- ERP sync (down- or up-). Schema affordances are included so sync is a later
  drop-in, but **no `ErpClient` calls and no sync listeners** are built now.

## Architecture

`customer` is a Tier-2 module following the house hexagonal layout
(`api` / `web` / `application` / `domain` / `infrastructure`), mirroring the
`product` module. Purchase history is a **read projection** the module builds
itself by subscribing to the `SaleCompleted` domain event ("events for facts").

### Dependency direction (must stay acyclic)

The four design choices interact to risk a cycle:

- History via event projection ⇒ `customer → sales::api` (to see `SaleCompleted`).
- Validated attach on the cart ⇒ if the cart validated, `cart → customer::api`.
- Checkout reads the cart ⇒ `sales → cart::api` (already exists).

Together: `sales → cart → customer → sales` — a cycle `ModularityTests` rejects.

**Resolution (tier-correct):** all dependency edges point *from* `customer`
*toward* `cart`/`sales`; nothing points back at `customer`.

- **`customer` owns the attach action.** The validate-then-attach endpoint lives
  in the customer module. It validates the customer is active (internal call),
  then calls a new `cart::api` method `assignCustomer(cartId, customerId)` that
  stores the id **opaquely**. `cart` never imports `customer`.
- **`cart` stores an opaque `customerId`** and exposes it on `CartView`.
  Detaching is pure cart state, so it stays in the cart controller.
- **`sales` copies the opaque id** onto the `Sale` at checkout and into the
  `SaleCompleted` event. `sales` never imports `customer`.
- **`customer` listens to `SaleCompleted`** and writes its own history row.

Resulting edges: `customer → { cart::api, sales::api, common, database }`.
No module depends on `customer`. Acyclic.

Visible consequence: the "attach customer to cart" endpoint is namespaced under
`/customers/...`, not `/carts/...`.

## Data model

### `customer` table (migration V20)

| Column | Type | Notes |
|---|---|---|
| `id` | VARCHAR(36) PK | UUID |
| `name` | VARCHAR(200) NOT NULL | required |
| `phone` | VARCHAR(40) | indexed; primary lookup. **Not unique** — families/businesses share numbers; dedup via search |
| `email` | VARCHAR(200) | nullable, indexed |
| `address` | VARCHAR(300) | nullable, single-line |
| `notes` | TEXT | nullable |
| `active` | BOOLEAN NOT NULL | soft-delete flag, default true |
| `external_id` | VARCHAR(64) | nullable — ERP sync hook (deferred) |
| `erp_version` | BIGINT NOT NULL | default 0 — optimistic-merge hook (mirrors `product`) |
| `created_at` | TIMESTAMP NOT NULL | |
| `updated_at` | TIMESTAMP NOT NULL | |

Indexes: `ix_customer_phone (phone)`, `ix_customer_email (email)`.

### `customer_purchase` table (migration V20) — history projection

| Column | Type | Notes |
|---|---|---|
| `id` | VARCHAR(36) PK | UUID |
| `customer_id` | VARCHAR(36) NOT NULL | indexed |
| `sale_id` | VARCHAR(36) NOT NULL | **UNIQUE** — idempotency guard for at-least-once event redelivery |
| `receipt_number` | VARCHAR(64) | |
| `occurred_at` | TIMESTAMP NOT NULL | |
| `grand_total` | NUMERIC(19,4) NOT NULL | `BigDecimal` |
| `currency_code` | VARCHAR(3) NOT NULL | |

Indexes: `ux_customer_purchase_sale (sale_id)` UNIQUE, `ix_customer_purchase_customer (customer_id)`.

The unique `sale_id` makes the listener idempotent, closing one of the "local
listeners aren't yet idempotent" gaps noted in CLAUDE.md.

### Other migrations
- **V21** (`cart` module folder): `ALTER TABLE cart ADD COLUMN customer_id VARCHAR(36)` (nullable).
- **V22** (`sales` module folder): `ALTER TABLE sales ADD COLUMN customer_id VARCHAR(36)` (nullable).

Only `store-server` runs Flyway; `embedded` uses Hibernate `ddl-auto`. Versions
are globally sequential — V20/V21/V22 are the next free numbers after V19.

## API surface

### `customer::api` facade — `CustomerService` (returns DTOs only)

- `CustomerView register(RegisterCustomerCommand cmd)`
- `CustomerView update(UUID id, UpdateCustomerCommand cmd)`
- `void deactivate(UUID id)` — soft delete (sets `active=false`)
- `Optional<CustomerView> findById(UUID id)`
- `List<CustomerView> search(String query)` — matches name / phone / email (case-insensitive contains)
- `List<PurchaseHistoryEntry> purchaseHistory(UUID customerId)`

DTOs (records, in `api`):
- `CustomerView(UUID id, String name, String phone, String email, String address, String notes, boolean active, Instant createdAt)`
- `PurchaseHistoryEntry(UUID saleId, String receiptNumber, Instant occurredAt, BigDecimal grandTotal, String currencyCode)`
- `RegisterCustomerCommand(String name, String phone, String email, String address, String notes)`
- `UpdateCustomerCommand(String name, String phone, String email, String address, String notes)`

`package-info.java` for `customer.api` tagged `@NamedInterface("api")`.

### HTTP — `CustomerController` (method security)

| Endpoint | Role | Purpose |
|---|---|---|
| `POST /customers` | CASHIER | register |
| `GET /customers/{id}` | CASHIER | view (404 if unknown) |
| `GET /customers?q=` | CASHIER | search |
| `PUT /customers/{id}` | CASHIER | edit (404 if unknown) |
| `DELETE /customers/{id}` | **MANAGER** | deactivate (soft) |
| `GET /customers/{id}/purchases` | CASHIER | purchase history |
| `POST /customers/{customerId}/cart/{cartId}` | CASHIER | validate customer active + attach to cart |

Plus, added to the **cart** controller:

| Endpoint | Role | Purpose |
|---|---|---|
| `DELETE /carts/{cartId}/customer` | CASHIER | detach (clears cart's customerId; no customer validation) |

Authorization is `@PreAuthorize` method security (roles map with the `ROLE_`
prefix), consistent with the rest of the codebase. Reads/writes require at least
CASHIER; only `DELETE /customers/{id}` requires MANAGER.

### `cart::api` additions
- `void assignCustomer(UUID cartId, UUID customerId)` — stores the id opaquely (also used with a clear path for detach).
- `CartView` gains a nullable `customerId` field.

## Cross-module integration and event flow

1. Cashier attaches a customer: `POST /customers/{customerId}/cart/{cartId}` →
   customer service validates the customer exists **and is active** → calls
   `cart.assignCustomer(cartId, customerId)`.
2. At checkout, `sales` reads `CartView.customerId` and copies it (nullable) onto
   the `Sale` entity (new `customer_id` column, V22).
3. `sales` adds two fields to the **`SaleCompleted`** event record: a nullable
   `UUID customerId` and an `Instant occurredAt` (the sale's completion time),
   and populates both. `customerId` closes the plan §6 gap where the event was
   specified to carry it but did not; `occurredAt` gives the history projection
   the true sale time rather than the (later, after-commit) projection-write
   time. The current event carries no timestamp, so it must be added here.
4. `customer`'s `@ApplicationModuleListener` on `SaleCompleted` records a
   `customer_purchase` row **only when `customerId != null`**, deduping on
   `sale_id` (insert guarded by existence check + the unique constraint).

Existing `SaleCompleted` subscribers (inventory, cashdrawer, sync, receipt,
audit) simply see one extra field they ignore — adding a record component does
not break consumers. Only the `sales` publisher constructs the event; tests
asserting the event shape are updated.

## Error handling

- Register with blank/missing `name` → `DomainException.validation`.
- `GET`/`PUT`/`DELETE` on unknown id → 404.
- Attach of an unknown or **inactive** customer → validation error (not attached).
- Listener: a `SaleCompleted` with `customerId == null` is a no-op; a duplicate
  `sale_id` is a no-op (idempotent). A history-write failure leaves an incomplete
  event publication (redelivered), never rolls back the committed sale.
- No `ErpClient` calls anywhere in this phase.

## Testing

- **Service** (`@SpringBootTest @ActiveProfiles("embedded")`): register (incl.
  blank-name rejection), update, deactivate (soft — row remains, `active=false`),
  search matches across name/phone/email.
- **Controller** (`MockMvc` + house `jwt()` post-processor with
  `SimpleGrantedAuthority("ROLE_...")`): endpoint happy paths; **CASHIER gets 403
  on `DELETE /customers/{id}`**, MANAGER succeeds; 404s.
- **Attach**: attaching a valid active customer sets `CartView.customerId`;
  attaching an unknown/inactive customer errors and leaves the cart unchanged.
- **History listener** (committing test via `DatabaseCleaner` + Awaitility):
  publishing `SaleCompleted` with a `customerId` produces one history row;
  **publishing the same `saleId` twice produces exactly one row** (idempotency);
  a null `customerId` produces none.
- **Checkout flow**: a sale finalized from a cart with an attached customer
  carries `customerId` onto the `Sale` and into `SaleCompleted`.
- **`ModularityTests`**: confirms the acyclic graph and that no module imports
  `customer` internals.
- **Full `./mvnw verify`** — including the Testcontainers Postgres run — so the
  V20–V22 migrations are validated against real Postgres (per the Phase 6 lesson
  that partial test runs hid a migration mismatch).

## Out-of-scope notes (future phases)
- ERP sync will populate `external_id` / `erp_version` and reconcile via
  field-level merge (plan §8). The columns exist now; the machinery does not.
- `sync`'s ERP sale upload may later include `customerId`; it ignores it now.
- Loyalty, groups, and store credit are separate future slices.
