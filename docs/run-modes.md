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
