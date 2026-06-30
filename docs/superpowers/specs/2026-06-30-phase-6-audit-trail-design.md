# Phase 6 — Audit Trail: Design

**Status:** Approved (brainstorming complete)
**Date:** 2026-06-30
**Branch:** `phase-6-audit-trail` (cut from `phase-5-manual-discounts`)
**Module:** new Tier-3 module `audit`, plus small producer-side additions to
`auth`, `configuration`, `product`, and `sales`.

## Goal

Introduce the **tamper-evident audit trail** the architecture plan (§11) calls
for — a single, append-only, hash-chained record of the security- and
fraud-relevant actions taken in the store. This is the first of the four
modules (`customer`, `reporting`, `dashboard`, `audit`) the plan defines as
"Phase 4 — completes the MVP"; we are building them one at a time and starting
with `audit` because it is an independent, passive subscriber with no coupling
to the sell path.

Out of scope for this phase: a search/review UI beyond the JSON query endpoint,
audit-record archival/pruning, item-void / sale-cancel auditing (no void event
exists yet — explicitly deferred), and SMS/email export of the trail.

## Locked Decisions

| # | Decision | Choice |
|---|----------|--------|
| 1 | Coverage | Sales & returns · logins & auth · manager overrides · config & price changes |
| 2 | Capture mechanism | **Hybrid** — domain events (outbox, after-commit) for transactional facts; synchronous `AuditService.record(...)` facade for non-transactional security events (login failures) |
| 3 | Tamper-evidence | **Hash-chained** records: each row stores `SHA-256(content ‖ prevHash)`; a `verify` routine detects any edit/deletion/reorder |
| 4 | Read surface | `GET /audit` (filtered), `GET /audit/{id}`, `POST /audit/verify` — all **ADMIN-only** |
| 5 | Config endpoint | **Add** ADMIN `PUT /config/{key}` so `SETTING_CHANGED` audits a real runtime mutation (also makes `tax.rate` / discount caps runtime-tunable) |
| 6 | Voids | **Deferred** — no item-void / sale-cancel event exists today |
| 7 | Retention | **Indefinite** for MVP — pruning would break the chain; checkpoint-based archival is later work |

## Module shape & boundaries

New Tier-3 module `com.company.pos.audit`, light hexagonal layout:

```
audit/
├── api/            AuditService (facade), AuditAction (enum), AuditRecordView,
│                   AuditVerifyResult, package-info.java (@NamedInterface "api")
├── web/            AuditController
├── application/    DefaultAuditService, HashChainer, event listeners,
│                   package-info.java (allowedDependencies)
├── domain/         AuditRecord entity (package-private)
└── infrastructure/ AuditRecordRepository (Spring Data JPA)
```

**`audit` allowedDependencies:** `sales :: api` (consumes `SaleCompleted`,
`ReturnCompleted`, and the new `DiscountOverridden`), `product :: api` (consumes
the new `ProductPriceChanged`), `common`, `database`.

**New "calls-up" coupling (accepted):** `auth` and `configuration` gain
`audit :: api` in their `allowedDependencies` so they can call the synchronous
`AuditService.record(...)` facade. This is acceptable because `audit` is a
cross-cutting platform service exposing a command facade (not a domain
dependency), analogous to how any module may use `common`. `ModularityTests`
must be re-run after these edits.

## Capture — the hybrid model

`AuditAction` enum and how each value is captured:

| Action | Source | Mechanism |
|---|---|---|
| `SALE_COMPLETED` | `SaleCompleted` event (existing) | `@ApplicationModuleListener` — async, after-commit, outbox-tracked |
| `RETURN_COMPLETED` | `ReturnCompleted` event (existing) | listener |
| `DISCOUNT_OVERRIDE` | **new** `DiscountOverridden` event from `sales` | listener |
| `LOGIN_SUCCEEDED` | `auth` | synchronous `auditService.record(...)` |
| `LOGIN_FAILED` | `auth` | synchronous (no committing txn for a failed login) |
| `PIN_LOGIN_FAILED` | `auth` | synchronous |
| `PRICE_CHANGED` | **new** `ProductPriceChanged` event from `ProductErpSyncService` | listener |
| `SETTING_CHANGED` | **new** ADMIN `PUT /config/{key}` → `ConfigurationService.put` | synchronous `auditService.record(...)` |

Producer-side changes:

- **`sales`** — `DiscountCalculator` already knows when a role lifts a cap
  (manager applying a discount above the cashier limit). Emit a
  `DiscountOverridden(saleId, actor, sku-or-null, requestedValue, capExceeded,
  reasonCode)` event when that branch is taken. Line into `sales :: api`.
- **`product`** — `ProductErpSyncService` already upserts by ERP version. When
  an **existing** SKU's price differs from the prior persisted price during a
  sync, publish `ProductPriceChanged(sku, oldPrice, newPrice, erpVersion)`.
  First-time inserts are not price *changes* and are not emitted.
- **`configuration`** — add a `web/` layer with ADMIN-only `PUT /config/{key}`
  that calls `ConfigurationService.put(key, value)` then
  `auditService.record(SETTING_CHANGED, ...)`. Bootstrap/env-override seeding
  does **not** go through this endpoint and is therefore **not** audited (by
  design — we audit operator changes, not deployment config).
- **`auth`** — `AuthService` calls `auditService.record(...)` on each
  login/pin-login success and failure, capturing the attempted identity and
  (where available) source. A failed login has no business transaction, which
  is precisely why the synchronous facade exists.

## Storage & hash chain

New entity `AuditRecord` → table `audit_record`, created by Flyway migration
**`V19`** under `src/main/resources/db/migration/audit/` (registered in
`application-store-server.yml`); `embedded` mode creates it via Hibernate
`ddl-auto`.

| column | type | notes |
|---|---|---|
| `id` | UUID PK | client-generated (`Identifiers`) |
| `seq` | BIGINT | per-store monotonic sequence; defines chain order |
| `store_id` | varchar | from `configuration` (`STORE_ID`) |
| `occurred_at` | timestamp | event/action time |
| `actor` | varchar | username / `cashierCode`; `system` for sync-driven records |
| `action` | varchar | `AuditAction` enum name |
| `entity_ref` | varchar | salient id: saleId / receiptNo / sku / setting key |
| `payload` | text | canonical JSON of the action's salient fields |
| `prev_hash` | char(64) | previous record's `hash`; `GENESIS` for `seq = 1` |
| `hash` | char(64) | `SHA-256(seq ‖ occurred_at ‖ actor ‖ action ‖ entity_ref ‖ payload ‖ prev_hash)` |

**Append-only:** `AuditRecordRepository` exposes only `save` and read methods —
no update/delete anywhere in code. `DefaultAuditService.append(...)` is the
single writer.

**Chain integrity under concurrent terminals:** appends arrive from async
listeners (each in its own transaction) and from synchronous facade calls.
Two appends must never claim the same `prev_hash`. The writer serializes on a
per-store chain head:

- A `audit_chain_head(store_id PK, last_seq, last_hash)` row is locked
  `SELECT ... FOR UPDATE` at the start of each append (within the append's own
  transaction), the new record is written with `prev_hash = last_hash` and
  `seq = last_seq + 1`, and the head row is updated. The row lock forces
  appends into a strict order.

Canonical JSON for `payload`/hashing uses sorted keys and fixed money scale so
the hash is reproducible by `verify`.

## Read & verify API

`AuditController`, every method `@PreAuthorize("hasRole('ADMIN')")`:

```
GET  /audit?from=&to=&actor=&action=&page=&size=
        → page of AuditRecordView (newest first)
GET  /audit/{id}
        → AuditRecordView
POST /audit/verify[?from=&to=]
        → AuditVerifyResult { intact, recordsChecked, firstBrokenSeq? }
```

`verify` walks records in `seq` order, recomputes each `hash` from stored
content + `prev_hash`, and checks both the recomputed hash and the linkage to
the previous record. On the first mismatch it returns `intact:false` with
`firstBrokenSeq` set; otherwise `intact:true` with the count checked.

## Testing

- **Unit** — `HashChainer`: deterministic hash for fixed input; linkage of a
  two-record chain; tamper detection when a record's payload is mutated.
- **Integration** (`@SpringBootTest @ActiveProfiles("embedded")`):
  - cash sale → exactly one `SALE_COMPLETED` record appears after commit;
  - failed login → one `LOGIN_FAILED` record (synchronous, no sale needed);
  - manager over-cap discount checkout → `DISCOUNT_OVERRIDE` record;
  - ERP product down-sync that changes an existing price → `PRICE_CHANGED`
    record; a brand-new SKU produces none;
  - `PUT /config/{key}` as ADMIN → value changes **and** a `SETTING_CHANGED`
    record; same call as MANAGER → 403, no record;
  - `POST /audit/verify` → `intact:true`; then a direct repository mutation of a
    record's payload → `verify` returns `intact:false` with the right
    `firstBrokenSeq`.
  - `GET /audit` filters by `actor`/`action`/date and is 403 for non-ADMIN.
- **`ModularityTests`** — must pass after the new `audit` deps and the
  `auth`/`configuration` → `audit :: api` additions.

## Known limits / deferred (state honestly, per house style)

- **Voids not audited** — no item-void / sale-cancel domain event exists yet;
  added when that path is built.
- **Deployment config not audited** — only runtime changes via `PUT /config`
  are recorded; env/bootstrap seeding is intentionally excluded.
- **No archival/pruning** — the chain grows unbounded; checkpoint-based
  archival is later work. Retention is indefinite.
- **Synchronous auth records are not outbox-backed** — a crash between the
  business action and the synchronous `record(...)` could drop a single
  security record. Accepted for MVP: login failures have no transaction to ride,
  and the alternative (synthetic events) was rejected in brainstorming.
- **Per-store chain assumes single store** — consistent with the system's
  single-store scope; multi-store would need a chain per `store_id` (the schema
  already keys on it).
