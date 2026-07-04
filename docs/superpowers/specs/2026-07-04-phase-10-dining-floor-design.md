# Phase 10 — Dining Floor: Tables & Open Orders (Design)

**Date:** 2026-07-04
**Status:** Approved (brainstorming), pending implementation plan
**Phase:** Phase 10 — first slice of the restaurant-floor track
(`dining (tables/orders)` → menu modifiers/variants → kitchen routing → split billing)

## Goal

Add a `dining` module that turns this retail POS into a restaurant floor for
**dine-in** service: a server opens an order against a table, adds items to it
over the course of a meal (with prep notes and course tags), and later closes
the whole table as **one bill** through the *existing* checkout pipeline. The
open order is a shared, store-wide artifact any terminal can pick up.

This is the foundational slice. Menu modifiers/variants, kitchen station
routing, and split billing are explicitly later phases that build on the
`DiningOrder` aggregate introduced here.

## Scope

**In scope (phase-10 core):**

- **Configured table registry** — an admin-managed set of tables (label, seat
  count, active flag). Orders reference a real table.
- **Open dine-in orders (tickets)** — open an order on a table; add / update /
  remove lines over time; each line carries an optional free-text **note** and
  a **course tag** (STARTER / MAIN / DESSERT / DRINK). Hold/resume is implicit:
  the order simply stays OPEN until closed.
- **Shared floor** — orders belong to the **store**, not a terminal. Any
  terminal/server can list open orders, view a table's order, add to it, and
  close it. (Contrast with today's terminal-scoped held carts, which are
  untouched and remain the quick-service mechanism.)
- **Close via existing checkout** — closing an order materializes its lines into
  a fresh cart, runs the existing multi-tender `SalesService.checkout(...)`
  (pricing, tax, manual discounts, receipt, `SaleCompleted` fan-out to
  inventory/cashdrawer/sync — all unchanged), then stamps the order CLOSED with
  the resulting `saleId` and frees the table.
- **Manager-gated destructive actions** — voiding an order or removing a line
  after it exists requires `ROLE_MANAGER` (`@PreAuthorize`), landing the V1
  requirement "voiding an item requires a manager."
- **Floor query for the dashboard** — a facade query (`listOpenOrders()`) so the
  existing `dashboard` module can show open-table state, following the
  established `inventory.listLowStock()` / `shift.listOpenShifts()` pattern.

**Explicitly deferred (not this phase):**

- **Menu modifiers & variants** (forced/optional modifiers, sizes) — next phase.
  A line references a plain `sku` for now.
- **Kitchen station routing / printing / KDS** — phase 12. Phase-10 *captures*
  notes and course tags but produces **no kitchen output** and has no "fire"
  action. Lines are persisted, not routed.
- **Split billing** (by guest or by item) and **service charges / auto-gratuity**
  — phase 13. A table closes as exactly one bill in phase-10.
- **Quick-service changes.** Quick service continues to use the existing
  `cart → checkout` path unchanged. `serviceType` is recorded on the order for
  reporting/future use, but phase-10 only introduces the DINE_IN flow.
- **Reservations, visual floor-plan mapping, table merge/transfer/seat moves.**

## Architecture

`dining` is a Tier-2 module following the house hexagonal layout
(`api` / `web` / `application` / `domain` / `infrastructure`), mirroring
`customer` and `product`. It **owns its own aggregate** (tables + orders +
order lines) and orchestrates other modules only through their `api` facades.

### Why "dining owns the order, reuses checkout at close" (Approach A)

Rejected alternatives:

- **Extend `cart` into long-lived shared orders** — overloads the ephemeral
  basket with dine-in + kitchen concerns and changes its terminal-scoped
  `hold/listHeld` semantics. Muddy boundaries.
- **`dining` holds only floor metadata; one `cartId` per order for live lines**
  — forces notes/course onto `cart` lines anyway and makes every edit a
  cross-module call (tight `dining↔cart` coupling).

Chosen: `dining` stores lines in its own `OrderLine` (with note + course), and
only at **close** converts them into a throwaway cart to reuse the entire
committed checkout pipeline. Small, deliberate line duplication in exchange for
`sales` and `cart` staying untouched in spirit and a self-contained, testable
`dining` aggregate. Notes/course live where the phase-12 kitchen consumer will
look for them.

### Dependency direction (must stay acyclic)

All edges point *out* of `dining`; nothing points back at it.

- Validate a sku when a line is added ⇒ `dining → product::api`
  (`ProductCatalog.findBySku`).
- Build the close-time cart ⇒ `dining → cart::api`
  (`createCart` / `addLine` / `close`).
- Run payment ⇒ `dining → sales::api` (`SalesService.checkout`,
  `CheckoutCommand`, `TenderInput`, `DiscountInput`).
- Read tunables (course-tag vocabulary, default seat count) ⇒
  `dining → configuration::api`.

`package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
            "common", "database",
            "product :: api", "cart :: api", "sales :: api", "configuration :: api" })
package com.company.pos.dining;
```

`sales` and `cart` gain **no dependency on `dining`** and require **no code
change**. `dashboard` will later add `dining :: api` to *its* allowed list to
consume `listOpenOrders()`; that edge is added in the dashboard change, not
here.

## Components

### `api` (the only thing other modules may import)

- `DiningService` — the facade:
  - `TableView registerTable(RegisterTableCommand)` *(MANAGER/ADMIN)*
  - `TableView deactivateTable(UUID tableId)` *(MANAGER/ADMIN)*
  - `List<TableView> listTables()`
  - `OrderView openOrder(OpenOrderCommand)` — binds an OPEN order to a free table
  - `OrderView addLine(AddLineCommand)` — sku + qty + optional note + course
  - `OrderView updateLine(UUID orderId, UUID lineId, BigDecimal qty, String note, CourseTag course)`
  - `OrderView removeLine(UUID orderId, UUID lineId)` *(MANAGER)*
  - `OrderView getOrder(UUID orderId)`
  - `List<OpenOrderView> listOpenOrders()` — shared-floor view (+ dashboard feed)
  - `SaleView closeOrder(CloseOrderCommand)` — tenders + optional discounts → Sale
  - `void voidOrder(UUID orderId, String reason)` *(MANAGER)*
- DTOs: `TableView`, `OrderView`, `OrderLineView`, `OpenOrderView`.
- Commands: `RegisterTableCommand`, `OpenOrderCommand`, `AddLineCommand`,
  `CloseOrderCommand` (carries `List<TenderInput>`, optional per-line
  `Map<sku,DiscountInput>`, and/or a transaction `DiscountInput`, reusing
  `sales::api` types). Line discounts are keyed by **sku** to match the existing
  `CheckoutCommand` contract (see close flow: order lines aggregate by sku).
- Enums: `ServiceType { QUICK_SERVICE, DINE_IN }`, `CourseTag { STARTER, MAIN,
  DESSERT, DRINK }`, `OrderStatus { OPEN, CLOSED, VOIDED }`.
- Events (published facts, minimal — only if a consumer exists): none required
  in phase-10. The dashboard reads via `listOpenOrders()` ("calls for queries").
  `SaleCompleted` still fires from `sales` at close. *(No speculative events —
  YAGNI.)*

### `web` — `DiningController`

REST surface (method security via `@PreAuthorize`, per house convention — not
URL rules). Authenticated by default; role checks on destructive/admin actions:

| Method & path | Role | Action |
|---|---|---|
| `POST /dining/tables` | MANAGER | register a table |
| `DELETE /dining/tables/{id}` | MANAGER | deactivate a table |
| `GET /dining/tables` | any auth | list tables |
| `POST /dining/orders` | any auth | open an order on a table |
| `GET /dining/orders` | any auth | list open orders (floor) |
| `GET /dining/orders/{id}` | any auth | view one order |
| `POST /dining/orders/{id}/lines` | any auth | add a line |
| `PUT /dining/orders/{id}/lines/{lineId}` | any auth | update qty/note/course |
| `DELETE /dining/orders/{id}/lines/{lineId}` | MANAGER | remove/void a line |
| `POST /dining/orders/{id}/close` | any auth | close → checkout → Sale |
| `POST /dining/orders/{id}/void` | MANAGER | void the whole order |

### `application` — `DefaultDiningService` (`@Transactional` boundary)

Orchestrates the aggregate and the close-time conversion. Close flow:

1. Load the OPEN order; guard status (reject if not OPEN).
2. `cartId = cartService.createCart()`; **aggregate order lines by sku** (sum
   qty across duplicate skus) and call `cartService.addLine(cartId, sku, totalQty)`
   once per distinct sku. Aggregating first sidesteps any add-vs-update merge
   ambiguity in `cart` and keeps the cart's one-line-per-sku shape, so
   sku-keyed line discounts map cleanly.
3. `sale = salesService.checkout(new CheckoutCommand(cartId, tenders,
   lineDiscounts, transactionDiscount))` — reuses pricing/tax/discount/
   multi-tender/receipt/`SaleCompleted` unchanged.
4. `cartService.close(cartId)`; stamp order `status=CLOSED`, `saleId=sale.id`,
   `closedAt`; free the table.
5. Return the `SaleView`.

*(Note/course tags are floor/kitchen concerns and do not flow onto the cart or
Sale in phase-10; the phase-12 kitchen consumer reads them from `OrderLine`.)*

### `domain` (package-private entities)

- `DiningTable` — `id`, `label`, `seats`, `active`.
- `DiningOrder` — `id`, `tableId`, `serviceType`, `status`, `openedBy`,
  `openedAt`, `closedAt` (nullable), `saleId` (nullable), `@Version` for
  optimistic locking (see Concurrency), `List<OrderLine>`.
- `OrderLine` — `id`, `orderId`, `sku`, `qty` (`BigDecimal`), `note` (nullable),
  `course` (`CourseTag`, nullable), `addedBy`, `addedAt`.

### `infrastructure`

Spring Data JPA repositories (`DiningTableRepository`, `DiningOrderRepository`),
package-private.

## Data model & migrations

Flyway per-module directory `src/main/resources/db/migration/dining/`, with
**globally sequential** version numbers starting at the next free number
**V23** (current max is V22):

- `V23__create_dining_table.sql` — `dining_table (id uuid pk, label varchar
  unique, seats int, active boolean)`.
- `V24__create_dining_order.sql` — `dining_order (id uuid pk, table_id uuid fk,
  service_type varchar, status varchar, opened_by varchar, opened_at timestamp,
  closed_at timestamp null, sale_id uuid null, version bigint)`. Index on
  `status` (open-order listing).
- `V25__create_dining_order_line.sql` — `dining_order_line (id uuid pk, order_id
  uuid fk, sku varchar, qty numeric, note varchar null, course varchar null,
  added_by varchar, added_at timestamp)`.

Register the new location in `application-store-server.yml` by appending
`classpath:db/migration/dining` to `spring.flyway.locations`. Only `store-server`
runs Flyway; `embedded` uses Hibernate `ddl-auto` (Flyway 10 lacks SQLite
support).

## Configuration (typed settings, env-overridable)

Add to `configuration :: api SettingKey` (read through `configuration::api`, not
hardcoded):

- `dining.course.tags` = `"STARTER,MAIN,DESSERT,DRINK"` — course vocabulary.
- `dining.table.default.seats` = `"4"` — default seat count when unspecified.

## Concurrency (shared floor)

Two servers may act on the same order. Reads serialize under embedded SQLite
(pool=1, single-writer); store-server Postgres does not. `DiningOrder` carries a
JPA `@Version` so a concurrent close/edit fails optimistic-lock rather than
double-charging. `closeOrder` and `voidOrder` additionally guard on
`status == OPEN` and reject otherwise, so a double-close surfaces as a clean
domain error, never a second Sale. Opening an order checks the target table has
no existing OPEN order.

## Error handling

- Add/close against a non-OPEN order → `DomainException.validation`.
- Open on an inactive/nonexistent/occupied table → validation error.
- Unknown sku on add → validation error (via `ProductCatalog`).
- Optimistic-lock clash on concurrent write → surfaced as a conflict, retryable
  by the caller.
- Close reuses existing checkout validation (short tender, over-cap discount
  requiring MANAGER, etc.) verbatim — no new payment rules here.

## Testing

`@SpringBootTest @ActiveProfiles("embedded")` (in-memory SQLite, no Docker):

- Register a table; open an order on it; a second open on the same table is
  rejected.
- Add lines with notes + course tags across two "terminals" (shared floor):
  both see the same order via `listOpenOrders()` / `getOrder`.
- Close the order → a `Sale` is produced (assert `SaleView`, `saleId` stamped),
  the order is CLOSED, and the table is freed. Verify the sale went through the
  real pipeline (tax applied, `SaleCompleted` observed by an inventory/sync
  listener) — reusing existing checkout assertions.
- Manager-only guards: line removal and order void as CASHIER are rejected
  (`@PreAuthorize`), succeed as MANAGER.
- Double-close / close-after-void rejected (status guard).
- Multi-tender close (cash + card) closes as one bill.
- **`ModularityTests`** passes: `dining`'s `allowedDependencies` are honored and
  it touches only `api` named interfaces of `product` / `cart` / `sales` /
  `configuration`.

## Out-of-scope reminders (guardrails for implementation)

Do **not**, in this phase: add modifier/variant models; route or print to any
station; add a "fire to kitchen" action; implement split billing or service
charges; add per-guest checks; add events with no consumer; or change `sales` /
`cart` internals. Each is a named later phase.
