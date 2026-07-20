# Sub-project #3d — Dining Tables Admin (design)

_Date: 2026-07-20. Branch: `feat/terminal-ui-restaurant-slice`._
_Part of the [go-live roadmap](2026-07-19-go-live-roadmap.md) item 3 (Store setup console), which
decomposes into four independent config areas: settings, **dining tables (this sub-project)**,
kitchen routing (shipped, #3c), and the menu builder. Dining tables chosen second._

## Problem

A restaurant configures its floor: which tables exist, their labels, their seat counts, and which
are takeaway counters. The backend can **create** (`POST /dining/tables`), **soft-delete**
(`DELETE /dining/tables/{id}` → `active=false`), and **list** tables — but a table's `label` and
`seats` are **immutable after creation** (the only `DiningTable` mutator is `setActive`), there is
**no edit endpoint**, and **no reactivate endpoint**. There is also **no UI** — tables can only be
managed by raw HTTP or the dev seeder. This sub-project is **full-stack**: it adds backend
edit/reactivate (plus a deactivate guard and event-based auditing) and a terminal admin screen to
create, edit, deactivate, and restore tables.

## Decisions (locked)

- **Operations: create + edit (rename/reseat) + soft delete/restore.** No hard delete — the
  `dining_order.table_id` FK (no cascade) makes physical deletion of a referenced table unsafe, and
  soft-delete already exists. Reactivate is added so a soft-deleted table can be brought back.
- **Counter pseudo-tables are managed too, via a Dine-in / Counter toggle.** Takeaway counters are
  modeled by a label prefix (terminal `TerminalConfig.takeawayLabelPrefix`, property
  `dining.takeaway.label-prefix`, default `"Counter "`). The form's type toggle applies/strips that
  prefix so the admin never hand-types the magic string. The screen lists both kinds and shows a
  derived Type column. There is **no** `serviceType`/type column on `DiningTable` — the prefix
  convention is the single source of truth, reused from the terminal config, not duplicated.
- **Access: MANAGER or ADMIN.** Matches the existing table endpoints (all `@PreAuthorize`
  `hasRole('MANAGER')`) and the Kitchen tile's gate (`services.session.isManager()`) — broader than
  the ADMIN-only Staff/Products tiles.
- **Auditing is event-based (mirrors sub-project #2, product catalogue).** `dining` publishes a
  `TableChanged` domain event on every table write; the `audit` module listens (async, post-commit).
  A synchronous `dining → audit` call is avoided — it would also risk the single-writer SQLite audit
  deadlock and a needless coupling. One-way `audit → dining :: api` dependency; no module cycle
  (dining does not depend on audit).
- **Deactivate is guarded against open orders.** Today `deactivateTable` blindly sets
  `active=false`, which could hide a table that still has an OPEN order from the floor map. The new
  behaviour rejects deactivation when the table has an OPEN order
  (`existsByTableIdAndStatus(id, OPEN)` → `DomainException.conflict`).

## What already exists (build-on inventory)

**Backend — module `com.company.pos.dining`:**
- `dining/domain/DiningTable.java` — `UUID id` (PK), `String label` (≤60, **unique**, NOT NULL),
  `int seats`, `boolean active` (default true). Constructor `DiningTable(UUID, String label, int
  seats)`. Only mutator: `setActive(boolean)`. **No section/zone; no type field.**
- `dining/api/DiningService.java` — `TableView registerTable(RegisterTableCommand)`,
  `void deactivateTable(UUID)`, `List<TableView> listTables()`, plus the order methods.
- `dining/api/RegisterTableCommand.java` — `record RegisterTableCommand(String label, Integer
  seats)` (seats nullable → service fills `SettingKey.DINING_TABLE_DEFAULT_SEATS`).
- `dining/api/TableView.java` — `record TableView(UUID id, String label, int seats, boolean active)`.
- `dining/application/DefaultDiningService.java` — `@Transactional` at class level. `registerTable`
  validates blank label, checks `tables.findByLabel` for a conflict, defaults seats, saves, and
  `publishFloorChanged(TABLE_REGISTERED, …)`. `deactivateTable` finds-or-404, `setActive(false)`,
  `publishFloorChanged(TABLE_DEACTIVATED, …)`. Events go through `events.publish(new
  DiningFloorChanged(...))`.
- `dining/api/FloorChangeType.java` — enum incl. `TABLE_REGISTERED, TABLE_DEACTIVATED`, order changes.
- `dining/api/DiningFloorChanged.java` — the realtime invalidation ping (slice 14).
- `dining/infrastructure/DiningOrderRepository.java` — `boolean existsByTableIdAndStatus(UUID
  tableId, OrderStatus status)` (reused for the open-order guard).
- `dining/web/DiningController.java` — `POST /dining/tables` (MANAGER), `DELETE
  /dining/tables/{tableId}` (MANAGER), `GET /dining/tables` (any authenticated).
- `dining/application/DevDiningSeeder.java` — `@Profile("dev")`, idempotent, seeds T1..T6 +
  Counter 1..3 via `registerTable`.
- Highest Flyway version across ALL modules: **V34**. (No migration needed here — `label`/`seats`
  columns already exist.)

**Audit — module `com.company.pos.audit`:**
- Already event-driven: `@ApplicationModuleListener` listeners (e.g. `ProductChanged` from
  sub-project #2), `DefaultAuditService.append(...)`, `AuditAction` enum. Already depends on
  `product :: api`; adding `dining :: api` is the same one-way pattern.

**Terminal (`pos-terminal/`):**
- `api/DiningApi.java` — `tables()` (GET `/dining/tables`), order methods.
- `api/dto/TableView.java` — `record TableView(UUID id, String label, int seats, boolean active)`.
- `config/TerminalConfig.java` — `takeawayLabelPrefix` (property `dining.takeaway.label-prefix`,
  default `"Counter "`), consumed by `TableMapViewModel.isCounter(...)`.
- Admin area: `AdminController` + `admin.fxml` (Staff + Products ADMIN-only, Kitchen MANAGER+ADMIN);
  `Navigator` `toAdmin/toStaff/toProducts/toKitchenRouting`; `Services` holds public-final API
  clients. Products screen (#2) and Kitchen screen (#3c) are the 1:1 templates: thin admin API
  client, synchronous VM (`errorMessage`-only-via-`ui.accept`, async-dispatcher regression test),
  controller + fxml, I/O-free dialog, `FxTasks` off-thread with `holder[]` in `onDone`.

## Backend design (`dining` + `audit`)

### Domain
- `DiningTable`: add `void rename(String label)` and `void reseat(int seats)` mutators. Reactivate
  reuses `setActive(true)`.

### API (`dining :: api`)
- `UpdateTableCommand.java` (new) — `record UpdateTableCommand(String label, Integer seats)`.
- `TableChanged.java` (new) — `record TableChanged(UUID tableId, String label, int seats, boolean
  active, TableChangeType type, Instant at)`.
- `TableChangeType.java` (new) — `enum TableChangeType { CREATED, UPDATED, DEACTIVATED, REACTIVATED }`.
- `FloorChangeType`: add `TABLE_UPDATED, TABLE_REACTIVATED`.
- `DiningService`: add `TableView updateTable(UUID id, UpdateTableCommand cmd)` and `void
  reactivateTable(UUID id)`.

### Application (`DefaultDiningService`)
- `updateTable(id, cmd)`: find-or-404; validate blank label; if the trimmed label differs from the
  current one, check `tables.findByLabel(newLabel)` and reject a conflict
  (`DomainException.conflict`); validate seats > 0 (default from config only applies to
  create — an explicit edit requires a positive value); `rename`/`reseat`; publish
  `TableChanged(UPDATED, …)` and `DiningFloorChanged(TABLE_UPDATED, …)`; return `TableView`.
- `reactivateTable(id)`: find-or-404; `setActive(true)`; publish `TableChanged(REACTIVATED, …)` and
  `DiningFloorChanged(TABLE_REACTIVATED, …)`.
- `deactivateTable(id)`: **add guard** — if `orders.existsByTableIdAndStatus(id, OrderStatus.OPEN)`
  throw `DomainException.conflict("Table has an open order")`; else `setActive(false)` and publish
  `TableChanged(DEACTIVATED, …)` (in addition to the existing `DiningFloorChanged(TABLE_DEACTIVATED,
  …)`).
- `registerTable(...)`: after save, also publish `TableChanged(CREATED, …)` (keep the existing
  `DiningFloorChanged(TABLE_REGISTERED, …)`).

### Web (`DiningController`)
- `PUT /dining/tables/{tableId}` body `UpdateTableCommand` → `TableView`, `@PreAuthorize`
  `hasRole('MANAGER')`.
- `POST /dining/tables/{tableId}/reactivate` → `TableView`, `@PreAuthorize` `hasRole('MANAGER')`.

### Audit
- `AuditAction`: add `TABLE_CREATED, TABLE_UPDATED, TABLE_DEACTIVATED, TABLE_REACTIVATED`.
- `TableChangedAuditListener` (new) — `@ApplicationModuleListener void on(TableChanged)` →
  `DefaultAuditService.append(...)` mapping `TableChangeType` → `AuditAction`, entity id = table id,
  detail = label/seats/active.
- `audit` `package-info.java`: add `dining :: api` to `allowedDependencies`.

## Terminal UI design (`pos-terminal/`)

### API client
- `api/TableAdminApi.java` (new, non-final for test subclassing): `List<TableView> list()` (GET
  `/dining/tables`, includes inactive), `TableView create(String label, Integer seats)` (POST
  `RegisterTableCommand`-shaped body), `TableView update(UUID id, String label, Integer seats)` (PUT
  `/dining/tables/{id}`), `void deactivate(UUID id)` (DELETE, null `TypeReference`), `TableView
  reactivate(UUID id)` (POST `/dining/tables/{id}/reactivate`).
- `api/dto/TableChangeRequest.java` (new) — `record TableChangeRequest(String label, Integer seats)`
  (outbound body for create + update; matches the server commands). Reuse of the existing
  `dto.TableView` for reads.

### View-model
- `viewmodel/TablesViewModel.java` — **synchronous methods returning plain values**; the controller
  runs them off the FX thread via `FxTasks.run`; the only off-thread observable write is
  `errorMessage` inside `ui.accept(...)`. Methods:
  - `List<TableView> loadTables()` (null on failure);
  - `TableView create(String label, Integer seats)` (null on failure);
  - `TableView update(UUID id, String label, Integer seats)` (null on failure);
  - `boolean deactivate(UUID id)`;
  - `TableView reactivate(UUID id)`.
  Validation before the server call (friendly `errorMessage`, mirroring `ProductAdminViewModel`):
  blank label rejected; seats must be a positive integer.

### Join / row helper (pure, unit-tested)
- `viewmodel/TableRows.java` — `List<TableRow> build(List<TableView> tables, String counterPrefix)` →
  `TableRow(UUID id, String label, int seats, boolean active, boolean counter)` where `counter =
  label starts with counterPrefix`. The controller renders a "Counter"/"Dine-in" Type column and an
  Active/Inactive Status column from these flags.

### Screen
- `resources/fxml/tables.fxml` + `view/TablesController.java` — mirrors `products.fxml`/`kitchen-routing.fxml`:
  - Header: title "Dining tables", a **search** `TextField` (client-side filter by label), a bound
    `errorLabel`, a Back button (→ `navigator.toAdmin()`).
  - `TableView<TableRow>` columns: Label, Seats, Type (Dine-in / Counter), Status (Active / Inactive).
  - Actions bar: **New** (form dialog → `create`), **Edit** (form dialog for the selected row →
    `update`), **Deactivate** (selected active row → `deactivate`; disabled for inactive rows),
    **Reactivate** (selected inactive row → `reactivate`; disabled for active rows).
  - `reload()` fetches tables in one `FxTasks` work lambda, rebuilds the cached rows + applies the
    search filter in `onDone`. Each mutation's `onDone` re-kicks `reload()` (never a blocking VM/HTTP
    call in `onDone`).

### Dialog
- `view/TableFormDialog.java` — **I/O-free** (collects input only; controller does all HTTP).
  `promptForTable(String counterPrefix, TableRow current /* null on create */)` → `Optional<Result>`
  where `Result(String label, Integer seats)`: a label `TextField`, a seats `Spinner`
  (min 1), and a **Dine-in / Counter** toggle. On save, the effective label is normalized so that a
  Counter selection carries the `counterPrefix` exactly once (prepend if absent; a Dine-in selection
  strips a leading prefix). Static `normalizeLabel(String rawLabel, boolean counter, String prefix)`
  is the unit-tested surface; Save is disabled while the label entry is blank.

### Admin area entry
- Add a **Tables** tile to `admin.fxml` + `AdminController`, **visible to MANAGER or ADMIN**
  (`services.session.isManager()`). `Navigator` gains `toTables()`. `Services` gains a
  `tableAdminApi` field and exposes the `counterPrefix` (from `TerminalConfig.takeawayLabelPrefix`).
  The screen owns no timers/sockets, so no `Navigator.Screen` lifecycle work is required. Existing
  tiles/wiring untouched (additive only; exact fx:id bijection in both FXML/controller pairs).

## Testing

**Backend**
- `DefaultDiningService` tests: `updateTable` renames + reseats; rename onto an existing label →
  conflict; seats ≤ 0 → validation error; `deactivateTable` blocked when the table has an OPEN order
  → conflict; `deactivateTable` succeeds with no open order; `reactivateTable` restores; each write
  path (`registerTable`/`updateTable`/`deactivateTable`/`reactivateTable`) publishes the expected
  `TableChanged` type. (Use the existing embedded-profile `@SpringBootTest` pattern; assert published
  events via the test's event collector, as the product tests do.)
- `TableChangedAuditListener` test: a `TableChanged` event yields the mapped `AuditAction` audit row.
- `ModularityTests`: passes with the new one-way `audit → dining :: api` dependency and no cycle.

**Terminal**
- `TablesViewModelTest` including the **async-dispatcher regression test** (deferred, undrained `ui`
  dispatcher — assert `errorMessage` "" pre-drain then the error post-drain) and tests that `create`
  with a blank label and `update`/`create` with seats ≤ 0 are rejected **without** calling the API.
- `TableRowsTest` (pure): counter vs dine-in derivation from the prefix; active/inactive flag.
- `TableFormDialogTest` (headless): `normalizeLabel` — Counter prepends the prefix once (idempotent
  when already prefixed); Dine-in strips a leading prefix; blank → rejected by the Save gate.

## Non-goals

Sections/zones or floor-plan coordinates; hard delete; drag-to-arrange visual layout; per-table
QR / self-order; changing `DINING_TABLE_DEFAULT_SEATS` (belongs to the Store-settings sub-project);
a `serviceType`/type column on `DiningTable` (the label-prefix convention stays the source of truth).

## Definition of done

- Backend gains `updateTable` + `reactivateTable` (service + `PUT`/`POST` endpoints, MANAGER),
  a deactivate open-order guard, and `TableChanged` events consumed by an `audit` listener.
- Terminal Admin area gains a functional **Tables** tile (MANAGER+ADMIN) → screen that lists tables
  with Type + Status, creates/edits (label, seats, dine-in/counter), deactivates (blocked when an
  order is open, surfaced as a friendly error), and reactivates — against a running backend.
- `./mvnw verify` green (new dining/audit tests + `ModularityTests`); `./mvnw -f pos-terminal/pom.xml
  clean test` green (new VM/helper/dialog tests + full suite).
- No migration; the only new module dependency is `audit → dining :: api`.
