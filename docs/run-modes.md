# POS Run Modes

The same `pos.jar` runs in two persistence modes, selected by Spring profile.

## Store-server (recommended, >= 2 registers)

PostgreSQL is the local source of truth; Flyway owns the schema.

```bash
java -jar target/pos.jar \
  --spring.profiles.active=store-server \
  --POS_DB_URL=jdbc:postgresql://localhost:5432/pos \
  --POS_DB_USER=pos \
  --POS_DB_PASSWORD=•••
```

## Embedded (single register)

SQLite in-process; Hibernate manages the schema. Flyway is disabled (Flyway 10
has no first-class SQLite support — see the Phase 0 plan's Design note). Set a
file path for durable storage:

```bash
java -jar target/pos.jar \
  --spring.profiles.active=embedded \
  --POS_DB_URL=jdbc:sqlite:file:/var/lib/pos/pos.db
```

> Encryption at rest (SQLCipher for SQLite, TDE for PostgreSQL) is added in the
> security-hardening pass; it is not part of Phase 0.

## Phase 1 — Identity & catalogue (API surface)

Authentication is JWT bearer (HS256). Obtain a token, then send it as `Authorization: Bearer <token>`.

```
POST /auth/login        {"username","password"}      -> {"token"}
POST /auth/pin-login    {"cashierCode","pin"}        -> {"token"}
GET  /auth/me           (any authenticated)          -> {"username","roles"}
GET  /products[?q=]     (any authenticated)          -> [ProductView...]
GET  /products/{sku}    (any authenticated)          -> ProductView
GET  /inventory/{sku}   (any authenticated)          -> {"sku","quantityOnHand"}
POST /sync/erp          (ROLE_MANAGER)               -> {"products","stock"}  (manual ERP down-sync)
```

Config (env overridable):
- `POS_JWT_SECRET` (≥ 32 bytes; a dev default is baked in — override in any real deployment), `POS_JWT_TTL` (minutes).
- `POS_SYNC_ERP_SCHEDULED` — background ERP down-sync timer. Default **off**; enabled automatically on the `store-server` profile. `POS_SYNC_ERP_DELAY_MS` sets the interval.

> The ERP integration runs against an in-memory **fake** adapter in Phase 1. A concrete vendor adapter implements `com.company.pos.integration.api.ErpClient` later with no change to the sync engine.

## Phase 2a — Checkout core (cash sell path)

All endpoints require a bearer token. Any authenticated user (cashier) may sell.

```
POST   /carts                          -> 201 {"cartId"}
GET    /carts/{cartId}                 -> CartView
POST   /carts/{cartId}/lines           {"sku","quantity"}   -> CartView
PUT    /carts/{cartId}/lines/{sku}     {"quantity"}         -> CartView
DELETE /carts/{cartId}/lines/{sku}                          -> CartView
POST   /sales                          {"cartId","amountTendered"} -> 201 SaleView
GET    /sales/{saleId}                 -> SaleView
POST   /sales/{saleId}/reprint         -> 204
POST   /sales/{saleId}/send-receipt    {"email"}            -> 204
```

Checkout prices the cart, applies VAT, takes a cash tender (rejecting short payment),
persists an immutable sale with a `{storeId}-{terminalId}-{seq}` receipt number, prints
through the device `Printer` port, and decrements stock (writing a movement-ledger row).

Config (env overridable via the `configuration` settings store):
- `tax.rate` (default `0.15`), `tax.inclusive` (default `false`)
- `store.id` (default `S01`), `terminal.id` (default `T01`), `inventory.location` (default `MAIN`)
- `store.name`, `currency.code` (default `SAR`), `locale` (default `en`)

> Receipts print to an in-memory fake `Printer`/`CashDrawer` in this phase. A real
> JavaPOS/ESC-POS adapter implements `com.company.pos.device.api.Printer` later with no
> change to the `receipt`/`sales` modules. Card/QR tenders, split payment, void, hold/resume,
> and cashdrawer/shift reconciliation arrive in Phase 2b; returns/exchanges are a later plan.

- `POST /sales/{saleId}/send-receipt` — emails the stored sale's receipt to the address in the
  `{ "email": "..." }` body. Renders the same content as the printed slip via the `Emailer` port
  (an `InMemoryEmailer` fake this phase — logs and records mail; a real SMTP adapter drops in
  later). 204 on success; 400 for a blank/malformed address; 404 for an unknown sale. Any
  authenticated user (same as reprint).

## Event outbox & resilience (Phase 3a)

Domain events (`SaleCompleted`, …) are delivered through the Spring Modulith **Event Publication
Registry** — a transactional outbox. Each `(event, listener)` pair is written as an
`event_publication` row inside the publishing transaction (the sale), and its `completion_date`
is stamped only when the listener finishes successfully. Listeners (`inventory` stock decrement,
`cashdrawer` cash capture) are `@ApplicationModuleListener`s: they run **after the sale commits,
asynchronously, in their own transaction**.

Consequences:
- A side-effect failure can never roll back a committed sale; it leaves an *incomplete*
  publication instead.
- Incomplete publications are **re-delivered on application restart**
  (`spring.modulith.events.republish-outstanding-publications-on-restart=true`) and can be
  resubmitted programmatically via `IncompleteEventPublications`.
- On `store-server` the table is created by Flyway migration `V14` (`db/migration/events`); on
  `embedded` Hibernate creates it automatically.

ERP upload of sales/movements over this outbox, and operator notifications for stuck
publications, are Phase 3b and 3c respectively.

### Known operational limits (hardening deferred to Phase 3b)

The foundation deliberately omits two pieces of production hardening:

- **No retry cap / dead-letter.** A deterministically-failing publication (a permanently
  invalid event or an unrecoverable downstream error) is retried on every restart with no
  backoff and no maximum-attempts cutoff. The negative-stock path only logs (it never throws,
  so it cannot become a poison message), but a genuine listener exception will be replayed
  indefinitely until it succeeds or is cleared. A retry cap + dead-letter handling (surfaced via
  the Phase 3c `notification` path) is a Phase 3b/3c follow-up.
- **Unbounded async executor.** `@EnableAsync` uses Spring's default `SimpleAsyncTaskExecutor`,
  which starts a new thread per task (unbounded). This is fine for the current local listeners
  but must be replaced with a bounded `ThreadPoolTaskExecutor` before the outbox carries the
  ERP-upload workload in Phase 3b.

## ERP up-sync (Phase 3b)

Completed sales and their stock-movement deltas are uploaded to the ERP over the Phase 3a outbox.
A `sync`-module `@ApplicationModuleListener` on `SaleCompleted` fetches the full sale (`sales :: api`),
maps it to a `SaleUpload`, derives the per-line stock deltas (−quantity at the sale's location), and
calls the ERP adapter's idempotent `uploadSale` / `uploadStockMovements`.

- **Offline-first:** the upload runs only after the sale commits, asynchronously. If the ERP is
  down the call throws and the publication stays incomplete — the sale is unaffected. The store
  keeps trading; uploads queue in `event_publication`.
- **Drain:** incomplete publications are resubmitted on restart (republish-on-restart), on a
  schedule when `pos.sync.erp.upload.scheduled=true` (on by default for `store-server`), and on
  demand via `POST /sync/erp/upload` (MANAGER-only).
- **Idempotency:** uploads are at-least-once; the ERP adapter dedupes on `saleId`, so replay never
  double-posts. Sales/movements are immutable facts, so there is no merge/conflict logic — just
  idempotent delivery.
- The ERP adapter is still the in-memory `FakeErpClient`; a real vendor adapter replaces it later
  without touching the `sync` module.

Operator notifications for stuck (persistently failing) uploads and low-stock are Phase 3c. A
bounded async executor and a poison-publication retry cap (see the Phase 3a operational limits
above) should land before this path carries real ERP load.

> **Drain blast radius (hardening follow-up).** `POST /sync/erp/upload` and the scheduled drain
> resubmit *all* incomplete outbox publications, not only `sync` uploads. This is safe today
> because the local `inventory`/`cashdrawer` listeners normally complete on first run, so only the
> `sync` upload is ever incomplete. But those local listeners are **not idempotent** (each
> unconditionally decrements stock / appends a ledger row), so the outbox's at-least-once replay
> has a narrow double-apply window (a crash between a listener's side-effect commit and its
> completion stamp). Before this carries real load, make the local listeners idempotent (or scope
> the drain to `sync` publications) alongside the bounded-executor work above.

## Notifications (Phase 3c)

The `notification` module raises operator alerts through a `Notifier` port (in-memory/log fake this
phase; real SMS/email/push later, no module change). Two alert types:

- **LOW_STOCK** — `inventory` carries a per-SKU `reorderLevel` (set from `pos.inventory.reorder-level`,
  default `0` = disabled). When a sale's stock decrement edge-crosses below it, `inventory` publishes
  `LowStockDetected`; `notification` turns it into a LOW_STOCK alert (after-commit, async).
- **SYNC_ERROR** — a scheduled monitor (`pos.notification.stuck-upload.scheduled`, off by default,
  on for `store-server`) polls the Phase 3a outbox for publications still incomplete beyond
  `pos.notification.stuck-upload.min-age-ms` (default 5 min) — e.g. ERP uploads stuck because the
  link is down — and raises a SYNC_ERROR alert, deduped per publication so a stuck row alerts once.
  The monitor scans *all* incomplete publications, so the alert means "a stuck outbox publication"
  in general; in practice the local `inventory`/`cashdrawer` listeners complete on first run, so a
  persistently-stuck row is almost always an ERP upload.

This is observability only: there is still no automatic retry-cap/dead-letter and the async executor
is still unbounded (see the Phase 3a operational limits). Real channels, those hardening items, and
auto-reordering are later work.

## Returns & Refunds (Phase 4)

A MANAGER processes a **receipted return** via `POST /returns` (cashiers get `403`). The request
references the original sale (by `originalSaleId` or `receiptNumber`) and the lines/quantities to
return. The `sales` module records an immutable `SalesReturn` (its own credit-note number) in one
synchronous transaction:

- **Over-return guard** — cumulative returned quantity per original line can never exceed the sold
  quantity, across repeated partial returns.
- **Proportional refund** — each returned line refunds the original line's net/tax/total scaled by
  `returnQty / soldQty`, so VAT stays exactly proportional.
- **Mirror-tender refund** — the refund is allocated across the original tenders proportionally;
  cash goes back as a drawer pay-out, card/wallet via a terminal refund (the in-memory terminal
  approves). Refunds are stored as `payment` rows tagged `REFUND`, keyed by the return id.

After the return commits, three after-commit outbox listeners reverse the sale effects (replayable,
mirroring the sale fan-out): `inventory` adds stock back (positive `RETURN` movement); `cashdrawer`
pays the cash refund out of the open drawer (a no-op, not an error, when no drawer is open);
`sync` uploads the return to the ERP as an idempotent credit note (stuck behind the Phase 3b
drain/replay when the ERP is offline).

**Terminal surfaces Returns (terminal slice 19)** — the JavaFX terminal now exposes returns as a **Returns** screen (Home tile, visible to all; the refund is manager-PIN-gated at submit) that looks up a sale by receipt via the new **`GET /sales/by-receipt/{receiptNumber}`** (authenticated) and posts the existing MANAGER-gated `POST /returns` with a one-shot manager token — terminal slice 19.

Deferred: blind/unreferenced returns, cashier returns with manager-approval thresholds,
damaged-goods/no-restock, exchanges, refunding to a different tender, and returns reporting.

## Manual Discounts (Phase 5)

A cashier or manager may apply discounts at checkout by extending the `POST /sales` body. Two
discount surfaces are available:

- **Line discounts** — `lineDiscounts` is a `Map<sku, DiscountInput>` keyed by the cart SKU.
- **Transaction discount** — `transactionDiscount` is a single `DiscountInput` applied to the
  whole transaction.

Both surfaces accept the same `DiscountInput` shape: `{ DiscountType type, BigDecimal value,
String reasonCode }`. `DiscountType` is `PERCENT` (value is a percentage, e.g. `10` for 10 %) or
`AMOUNT` (value is an absolute monetary deduction, e.g. `5.00`). `reasonCode` is required and must
be one of the values in `DISCOUNT_REASON_CODES` (default `DAMAGED,PRICE_MATCH,LOYALTY,MANAGER_COMP`);
an unrecognised code is rejected with HTTP 400.

**Application order** — discounts are applied after pricing and before tax. Each line's extended
amount is reduced by its discount first; VAT is then computed on that discounted base. This keeps
`TaxService` and both tax modes (`tax.inclusive=false` and `=true`) correct without change, and
the persisted `net_amount` per line is the post-discount net so Phase 4 returns need no
adjustment.

**Transaction discount allocation** — the resolved transaction discount is spread
proportionally across lines by each line's share of the pre-discount subtotal. The last line
absorbs any rounding remainder so the per-line shares always sum exactly to the full transaction
discount. This mirrors Phase 4's proportional refund allocation.

**Role-based cap** — to prevent unauthorised over-discounting, cashiers are limited by two config
keys (env-overridable via the `configuration` settings store):

- `DISCOUNT_CASHIER_MAX_PERCENT` (default `10`) — maximum percentage discount per line or for the
  transaction.
- `DISCOUNT_CASHIER_MAX_AMOUNT` (default `20.00`) — maximum absolute-amount discount per line or
  for the transaction.

A caller authenticated with `ROLE_MANAGER` is uncapped. A cashier whose discount exceeds either
cap gets HTTP 400.

**Discount-aware quotes (terminal slice 5)** — both quote surfaces accept the same optional
discount fields as their checkout counterparts, and price them identically (shared code path):

- `POST /sales/quote` body: `{ cartId, lineDiscounts?, transactionDiscount? }`.
- `POST /dining/orders/{orderId}/quote` body: `{ lineDiscounts?, transactionDiscount? }`
  (the discount-less `GET /dining/orders/{orderId}/quote` remains).

Quotes are pure calculators: the cashier cap is **not** enforced at quote time (a quote commits
nothing), but reason codes are validated. Checkout/close remain the sole enforcement point.

**Split-bill quote (terminal slice 6)** — `POST /dining/orders/{orderId}/quote-split` prices a
proposed partition using the same code path that `close-split` charges (any authenticated user).

- **BY_ITEM** body: `{"mode":"BY_ITEM","bills":[{"lineIds":[...]}, ...]}` — returns `bills`, one
  `QuoteView` per bill in request order, with service charge applied per bill.
- **EVEN** body: `{"mode":"EVEN","even":{"ways":N}}` — returns `order` (the whole-order quote)
  plus `shares`: HALF_UP scale-2 shares, the last absorbing the rounding remainder so they sum
  exactly (e.g. 40.25 ÷ 3 → 13.42 + 13.42 + 13.41), matching the exact-amount tenders
  `close-split` creates.

Pure calculator: creates no sale, the order stays OPEN. The partition is validated exactly as at
close — every line in exactly one bill, no duplicates, ≥ 1 line per bill — so mistakes fail at
quote time rather than at close. No discount/waiver fields in the body — the terminal split UI
carries no discounts; a discounted split close is out of scope.

**`GET /sales/discount-policy`** returns `{ cashierMaxPercent, cashierMaxAmount, reasonCodes }`
so terminals can render reason-code choices and prompt for manager approval before tendering.
Advisory only — the server still enforces the cap at checkout.

**Receipt and ERP** — discounts are itemised on the printed receipt (one discount line per
affected sale line) and are included in the `SaleUpload` sent to the ERP, so the back-office sees
the pre-discount price, the discount amount, and the net. The persisted `SaleLine` stores the
discount type, reason code, and resolved discount amount alongside the line net and tax.

**Returns** — because the persisted line net is already post-discount, Phase 4's proportional
refund logic refunds the discounted amount automatically. No change to `POST /returns` is needed.

Config summary (all env-overridable via the `configuration` settings store):
- `DISCOUNT_REASON_CODES` (default `DAMAGED,PRICE_MATCH,LOYALTY,MANAGER_COMP`)
- `DISCOUNT_CASHIER_MAX_PERCENT` (default `10`)
- `DISCOUNT_CASHIER_MAX_AMOUNT` (default `20.00`)

Deferred: manager-approval workflow for cashier discounts that exceed a soft threshold,
discount reporting and analytics, coupon/promo-code–driven discounts, and time-limited
promotional pricing.

## Audit Trail (Phase 6)

A new `audit` module records the security- and fraud-relevant actions in the store into a single
**append-only, hash-chained** log. New endpoints (all `ROLE_ADMIN`, method-secured):

- `GET /audit?actor=&action=&from=&to=&page=&size=` — filtered page of audit records, newest
  first. `from`/`to` are ISO-8601 instants; omitting them spans all time.
- `GET /audit/{id}` — a single record (404 if unknown).
- `POST /audit/verify` — walk the whole chain and recompute every hash; returns
  `{ intact, recordsChecked, firstBrokenSeq }`. `firstBrokenSeq` is null when intact.
- `PUT /config/{key}` — set a runtime setting (`key` is a `SettingKey` name, e.g. `VAT_RATE`);
  body `{ "value": "..." }`. Unknown key → HTTP 400. This is the only runtime setting-mutation
  surface, and every change it makes is audited (bootstrap/env seeding is not).

**What is captured, and how** — a hybrid of two mechanisms:

- **Domain events (asynchronous, outbox-backed after-commit)** for transactional facts:
  `SALE_COMPLETED` (from `SaleCompleted`), `RETURN_COMPLETED` (`ReturnCompleted`),
  `DISCOUNT_OVERRIDE` (a new `DiscountOverridden` fact, published when a manager applies a discount
  above the cashier cap), `PRICE_CHANGED` (a new `ProductPriceChanged` fact, published when an ERP
  down-sync changes an existing SKU's price — first-time inserts are not changes), and
  `SETTING_CHANGED` (a new `SettingChanged` fact from `PUT /config`).
- **A synchronous `AuditService.record(...)` facade** for non-transactional security events:
  `LOGIN_SUCCEEDED` / `LOGIN_FAILED` / `PIN_LOGIN_SUCCEEDED` / `PIN_LOGIN_FAILED`. A failed login
  has no business transaction to ride, so `auth` calls the facade directly; the record commits
  before the login's exception propagates, so the failed attempt is durably recorded.

**Tamper-evidence** — each `audit_record` stores a per-store monotonic `seq`, the `prev_hash`, and
`hash = SHA-256(seq ‖ occurredAt ‖ actor ‖ action ‖ entityRef ‖ payload ‖ prevHash)`. Because each
hash binds the previous record's hash, any edit, deletion, or reorder breaks the chain and is
detected by `POST /audit/verify`. Writes are append-only (no update/delete in application code) and
serialise on a per-store `audit_chain_head` row.

**Embedded persistence note** — the login-audit write must commit independently of the login flow,
and single-writer SQLite cannot service two concurrent write connections. The embedded profile
therefore runs with `maximum-pool-size: 1` (all DB access, including the async after-commit
listeners, serialises onto one connection) and `AuthService` is intentionally non-transactional so
its audit write needs only that one connection. Do not raise the embedded pool above 1 without
re-running the full end-to-end suite — a larger pool un-serialises the async listeners and
deadlocks SQLite (`SQLITE_BUSY`). `busy_timeout=5000` and `journal_mode=WAL` are configured for the
benefit of a production file-based override (`POS_DB_URL=jdbc:sqlite:file:...`).

Known limits:
- **Actor on sales/returns is the terminal id, not the cashier/manager.** `SaleCompleted` and
  `ReturnCompleted` do not carry the acting user, so those rows record `terminalId` and the
  receipt/credit-note number. Login, setting-change, and discount-override records do carry the
  real actor. Capturing the sale/return operator requires enriching those events — deferred.
- **Audit listeners are not idempotent.** An outbox replay (e.g. after a crash) can append a second
  chain-valid row for the same event; the chain stays verifiable but may contain a duplicate.
- **No archival/pruning.** The chain grows unbounded; retention is indefinite (pruning would break
  the chain). Checkpoint-based archival is later work.
- **`verify` covers the whole chain only** — there is no partial-range verification, since a range
  cannot check linkage to records outside it.

Deferred: item-void / sale-cancel auditing (no void event exists yet), real tamper-alerting, and a
richer search/review UI beyond the JSON query endpoint.

## Customer (Phase 7)

A new `customer` module manages shopper profiles, cart attachment, and purchase history. Endpoints:

```
POST   /customers                              {"name","phone","email","notes","loyaltyCode"}  -> CustomerView
GET    /customers/{id}                                                                         -> CustomerView
GET    /customers?q=                           (name/phone/email substring search)             -> [CustomerView...]
PUT    /customers/{id}                         {"name","phone","email","notes","loyaltyCode"}  -> CustomerView
DELETE /customers/{id}                         ROLE_MANAGER — soft-delete (active=false)       -> 204
GET    /customers/{id}/purchases                                                               -> [PurchaseHistoryEntry...]
POST   /customers/{customerId}/cart/{cartId}   (any authenticated — attach customer to cart)  -> CartView
DELETE /carts/{cartId}/customer                (any authenticated — detach customer from cart) -> CartView
```

All endpoints require an authenticated bearer token. Delete is `ROLE_MANAGER`-only (method-secured
with `@PreAuthorize`); all others require only a valid token (authenticated baseline).

**Attach flow** — `POST /customers/{customerId}/cart/{cartId}` validates that the customer exists
and is active (400 on an inactive customer, 404 on unknown) before stamping the `customerId` onto
the open cart. Both endpoints ultimately mutate cart state; what determines placement is module
ownership. The attach endpoint lives under `/customers` because validating the customer requires
`customer` code, and the `customer` module issues the write through `cart :: api` — keeping the
dependency acyclic (`customer → cart :: api`, `customer → sales :: api`; nothing depends on
`customer`). The detach endpoint `DELETE /carts/{cartId}/customer` needs no customer validation, so
it lives in the `cart` module's own controller — `cart` never depends on `customer`, avoiding the
reverse crossing. Moving the attach endpoint into `cart` or `sales` would introduce a reverse
dependency that `ModularityTests` enforces against.

**Purchase history** — `GET /customers/{id}/purchases` returns entries ordered by `occurredAt`
descending. Each entry carries `saleId`, `receiptNumber`, `occurredAt`, `grandTotal`, and
`currencyCode`. The projection is built by `SaleCompletedCustomerListener`, an
`@ApplicationModuleListener` that fires after-commit on `SaleCompleted`. Because it runs
asynchronously after the sale transaction commits, history may lag the checkout response by a
short moment (Awaitility polling works reliably in tests). The listener is **idempotent on
`sale_id`**: a duplicate delivery (outbox replay after a crash) inserts nothing — the
`customer_purchase` table has a unique constraint on `sale_id`.

**`SaleCompleted` enrichment** — Phase 7 adds two trailing fields to the existing `SaleCompleted`
record: `customerId` (nullable `UUID`) and `occurredAt` (`Instant`). The first carries the attached
customer forward to the listener; the second gives the history entry a stable wall-clock timestamp
from the sale event rather than the listener's execution time. Both fields are appended to the end
of the record to keep all existing listeners source-compatible.

**Migrations** — three Flyway migrations (store-server only; embedded uses Hibernate `ddl-auto`):

- `V20` — `customers` table: `id` VARCHAR(36) PK, `name`, `phone`, `email`, `notes` TEXT,
  `loyalty_code`, `active` BOOLEAN, `created_at`, `updated_at`, `external_id`, `erp_version`.
- `V21` — `customer_purchases` table: `id` VARCHAR(36) PK, `customer_id` FK → `customers.id`,
  `sale_id` VARCHAR(36) UNIQUE (idempotency key), `receipt_number`, `occurred_at`, `grand_total`
  NUMERIC(19,4), `currency_code`.
- `V22` — adds `customer_id` column (VARCHAR(36), nullable) to the `carts` table.

Column types follow the project convention: VARCHAR(36) for all UUID columns, NUMERIC(19,4) for
money, TEXT for free-text notes.

**ERP sync deferred** — the `customers` table has `external_id` and `erp_version` columns to hold
the ERP's customer key and change-version when sync machinery is added. No `customer` data is
uploaded or downloaded from the ERP in this phase; those columns exist to avoid a migration churn
later.

Known limits:
- **History listener is not idempotent for different events.** The unique constraint on `sale_id`
  prevents double-projection for the *same* sale event, but there is no guard against two distinct
  `SaleCompleted` events that happen to carry the same `customerId` (both will appear in history,
  as they should). No known issue here, just clarifying scope.
- **Soft-delete only.** Deactivated customers remain in the database with `active=false`. There is
  no hard-delete or GDPR erasure path yet.
- **No loyalty/points engine.** The `loyalty_code` field is stored and returned but drives no
  discount or points calculation in this phase.

Deferred: ERP customer sync (upload/download), loyalty points engine, GDPR erasure, purchase
reporting/analytics, and customer-specific pricing.

## Reporting (Phase 8)

A new `reporting` module exposes five read-only aggregate reports over the store's sales data.
All endpoints are `ROLE_MANAGER` or `ROLE_ADMIN` — method-secured with
`@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")` on the controller class. Cashiers receive
`403`.

### Endpoints

| Method | Path                  | Required params  | Optional params                    | Description                                        |
|--------|-----------------------|------------------|------------------------------------|----------------------------------------------------|
| GET    | `/reports/sales`      | `from`, `to`     | `format` (default `json`)          | Sales summary: counts, subtotals, discounts, tax, gross/net sales, and returns |
| GET    | `/reports/payments`   | `from`, `to`     | `format`                           | Payment breakdown by method: collected vs refunded |
| GET    | `/reports/tax`        | `from`, `to`     | `format`                           | Tax summary: taxable amount, VAT collected, return VAT, net VAT |
| GET    | `/reports/cashiers`   | `from`, `to`     | `format`                           | Per-cashier sale count, revenue, and discounts given |
| GET    | `/reports/products`   | `from`, `to`     | `limit` (default `50`), `format`   | Top products by revenue: quantity, revenue, discounts |

`from` and `to` are ISO-8601 date strings (`YYYY-MM-DD`). Supplying `from` after `to` returns
HTTP 400.

### CSV output

Every endpoint accepts `?format=csv` to receive the report as `text/csv` instead of JSON. The
CSV is a single-header flat file; multi-row reports (payments, cashiers, products) emit one row
per line. The default (`format=json`) returns the full report record as JSON.

### Date-range semantics

`from` and `to` are treated as UTC-day boundaries. `from=2024-01-01` opens at
`2024-01-01T00:00:00Z`; `to=2024-01-31` closes at `2024-02-01T00:00:00Z` (exclusive). Records
are matched on the `created_at` column of the source table (UTC timestamp). This means a sale
recorded at `2024-01-31T23:59:59Z` is included in a range ending `to=2024-01-31`, while one
recorded at `2024-02-01T00:00:01Z` is not.

> `STORE_TIMEZONE` support (adjusting day boundaries to the store's local clock) is deferred.
> Until then, operators whose store is not in UTC should apply a manual offset when choosing
> `from`/`to` values.

### `limit` clamping

The `/reports/products` `limit` parameter is clamped to `[1, 500]` at the service layer. Values
below 1 are raised to 1; values above 500 are reduced to 500. The default is 50.

### Design: read-only native SQL, no owned tables

The `reporting` module owns **no database tables and runs no Flyway migrations**. It reads the
`sale`, `sale_line`, `sales_return`, and `payment` tables of the `sales` and `payment` modules
directly via `JdbcTemplate` (native SQL). This is a deliberate schema-level coupling — accepted
because reports are read-only aggregates and the table shapes are stable — with no code
dependency (no imports of another module's JPA entities or repositories). Module-boundary
enforcement (`ModularityTests`) stays green because `reporting`'s declared
`allowedDependencies` are limited to `common`, `database`, and `configuration :: api`.

The `JdbcTemplate` binding uses `java.sql.Timestamp.from(Instant)` for the UTC-day boundary
parameters. This binding is portable across both supported databases: it works on Postgres
(tested by `ReportingPostgresTest` against a real PostgreSQL 16 container) and on SQLite
(tested by `SalesSummaryReportTest`, `PaymentAndTaxReportTest`, and `CashierAndProductReportTest`
against the embedded profile).

On the `store-server` (PostgreSQL) profile, tables live in the `pos` schema. HikariCP is
configured with `connection-init-sql: "SET search_path TO pos"` so that all connections —
including JdbcTemplate connections — resolve unqualified table names to `pos` without requiring
the SQL to carry explicit schema prefixes.

### Module allowed dependencies

`reporting` is permitted to import only `common`, `database`, and `configuration :: api`. It
never imports sales/payment JPA entities, Spring Data repositories, or any other module's
internals. This is declared in `com.company.pos.reporting`'s `package-info.java` and enforced
by `ModularityTests`.

### Currency

The currency code is read from the `CURRENCY_CODE` configuration key (via
`configuration :: api`) and stamped onto every report response. It is not hardcoded in the
`reporting` module.

### Known limits

- **No profit / COGS.** Cost-of-goods data is not available in this phase; all reports show
  revenue and discount figures only. A margin report requires a cost price on each
  `sale_line`, which is deferred.
- **No hourly or intra-day granularity.** All five reports aggregate over the full `from`/`to`
  window with no finer time breakdown.
- **No inventory-level reporting.** Stock-on-hand, stock-movement history, and reorder-status
  reports are not in scope for this phase.

Deferred: profit/margin reporting (requires COGS data), hourly/shift-level breakdowns,
inventory/movement reports, JasperReports integration for printable PDF reports, and an
event-sourced read model (materialised projections maintained by async listeners) to replace the
direct-read native SQL as query volume grows.

**Terminal surfaces Reports (terminal slice 18)** — the JavaFX terminal now exposes these five reports as a **Reports** screen (Admin hub, MANAGER/ADMIN) with preset/custom date ranges and CSV export via the new `ApiClient.getText` raw-text GET — terminal slice 18, no new backend endpoints.

## Dashboard (Phase 9 — MANAGER/ADMIN)

Operational insight, composed on-demand from module facades (no new tables). "Today" is the UTC day, consistent with reporting.

- `GET /dashboard` — full snapshot: today's sales, revenue (today + rolling window), best sellers, low-stock items, open shifts, active cashiers.
- `GET /dashboard/sales-today` — today's sales summary.
- `GET /dashboard/revenue` — today's net + rolling-window net (window = `dashboard.revenue.window.days`, default 7).
- `GET /dashboard/best-sellers?limit=` — top SKUs by revenue today (`limit` default 5, clamped `[1, 50]`).
- `GET /dashboard/low-stock` — SKUs below reorder level, with product name.
- `GET /dashboard/open-shifts` — currently-open shifts + derived active cashiers.

Config key: `dashboard.revenue.window.days` (default `7`) sets the revenue rolling window.

**Terminal surfaces Dashboard (terminal slice 18)** — the JavaFX terminal now exposes the dashboard as a manager **Dashboard** screen (Home tile, MANAGER/ADMIN) rendering KPI tiles, best-sellers, low-stock, and open-shifts tables over `GET /dashboard` — terminal slice 18, no new backend endpoints.

## Dining (Restaurant track: Phases 10–13b)

A parallel track for table-service restaurant operations, with modules `dining`, `menu`,
`kitchen`, and `shift`. The core API includes:

```
POST   /dining/tables                      {"label","serviceType"}              -> TableView
GET    /dining/tables                                                          -> [TableView...]
POST   /dining/orders                      {"tableId","serviceType"}          -> OrderView
GET    /dining/orders/{orderId}                                               -> OrderView
GET    /dining/orders                      (open-order summaries)             -> [OpenOrderView...]
POST   /dining/orders/{orderId}/close      {"tendered","change",...}         -> 201 SaleView
```

**Open-order summaries** — `GET /dining/orders` returns a list of `OpenOrderView` with:
`{ id, tableLabel, serviceType, openMinutes, lineCount, attention }`. The `serviceType`
field indicates `DINE_IN` (table with applied service charge) or `QUICK_SERVICE` (counter or takeaway,
no service charge). Takeaway is not a separate concept — a takeaway order is a `QUICK_SERVICE`
order opened against a counter-labelled table. Service charge is already gated on `DINE_IN`
in the quote/close paths (`resolveApplyServiceCharge`), so takeaway orders quote and close
untaxed with no dedicated code path. No schema change — `serviceType` is a projection of the
existing `service_type` column on the `dining_order` table.

**Table registration** — `POST /dining/tables` registers a new dining table or counter (identified
by `serviceType`: `DINE_IN` for floor tables, `QUICK_SERVICE` for counter service like takeaway).
The terminal consumes this list to populate the table map and counter selector.

**Quote and split-bill** — `POST /dining/orders/{orderId}/quote` (with optional discounts) and
`POST /dining/orders/{orderId}/quote-split` (with partition) price orders before close, excluding
service charge from `QUICK_SERVICE` orders by construction.

**Dwell and attention** — the `openMinutes` is the wall-clock duration since the order was opened;
the `attention` flag indicates whether the order has exceeded a configured dwell threshold
(`dining.dwell.attention.minutes`, default 45 min) and may need intervention.

Deferred: menu modifiers/variants, shift-level reconciliation, and customer notifications.

## Kitchen Display System (KDS — Restaurant track)

The `kitchen` module manages ticket lifecycle for display-station screens. Tickets are created
automatically when an order is fired and follow the state machine:
`QUEUED → IN_PROGRESS → READY → SERVED` (with `CANCELLED` as the terminal void state).

```
GET    /kitchen/tickets?station=          (authenticated)               -> [KitchenTicketView...]
POST   /kitchen/tickets/{id}/advance      {"expectedState":"<state>"}   -> KitchenTicketView
POST   /kitchen/tickets/{id}/recall       {"expectedState":"<state>"}   -> KitchenTicketView
```

- `GET /kitchen/tickets?station=` — returns all active (non-SERVED, non-CANCELLED) tickets,
  optionally filtered by station label. Authenticated; any role.
- `POST /kitchen/tickets/{id}/advance` — advances the ticket one step forward in the state
  machine (e.g. `QUEUED → IN_PROGRESS`). Body `{"expectedState":"<current>"}` provides
  optimistic-concurrency guard; returns **409 Conflict** if the ticket's actual state does
  not match `expectedState`.
- `POST /kitchen/tickets/{id}/recall` — steps the ticket one state back (e.g.
  `IN_PROGRESS → QUEUED`). Subject to the configurable recall window: a recall is rejected
  after `KITCHEN_TICKET_RECALL_WINDOW_SECONDS` seconds (default `180`) have elapsed since
  the last transition. Body `{"expectedState":"<current>"}` — same 409 guard as advance.

**Config key:** `KITCHEN_TICKET_RECALL_WINDOW_SECONDS` (default `180`) sets the recall
time-limit in seconds via the `configuration` settings store.

**Realtime push:** every ticket state change publishes a `KitchenTicketChanged` event through
the outbox; the `realtime` module re-broadcasts it as an invalidation ping on `/ws/floor`
with `"topic":"KITCHEN"`. The terminal KDS screen subscribes to the `KITCHEN` topic and
re-fetches `GET /kitchen/tickets` on each ping (polling retained as a ~45 s fallback).
