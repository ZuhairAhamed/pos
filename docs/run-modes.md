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
