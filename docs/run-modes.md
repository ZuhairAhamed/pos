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
