# Sub-project #3c — Kitchen Routing Admin (design)

_Date: 2026-07-20. Branch: `feat/terminal-ui-restaurant-slice`._
_Part of the [go-live roadmap](2026-07-19-go-live-roadmap.md) item 3 (Store setup console), which
decomposes into four independent config areas: settings, dining tables, **kitchen routing (this
sub-project)**, and the menu builder. Kitchen routing was chosen first — it is pure UI over an
already-complete backend._

## Problem

A restaurant configures which products print at which kitchen station (grill, fryer, bar, …) so
that firing an order sends each line to the right place. The backend for this is complete
(`kitchen` module: assign/unassign/list, MANAGER+ADMIN), but there is **no UI** — routing can only
be set via raw HTTP today. This sub-project adds a terminal admin screen to manage per-SKU station
routing. It is **terminal-only**: no backend, no migration, no config change.

## Decisions (locked)

- **Access:** MANAGER **or** ADMIN — matches the existing endpoint gates (unlike Staff/Products,
  which are ADMIN-only). The tile sits in the existing Admin area (already reachable by MANAGER+ADMIN).
- **Stations stay free-text.** `StationAssignment` models a station as a plain `stationName` string;
  there is no Station entity. A managed station list is a **non-goal** (it would need a new
  entity/endpoints/migration, outside "UI over existing"). The UI mitigates typo-duplicates by
  offering the set of already-used station names in the picker.
- **Route per-SKU.** Category-level routing is a **non-goal** (the backend has no category→station
  mapping; `StationAssignment.sku` is the primary key).
- **Unrouted rows show a generic "Default"** — not the actual default station name. The terminal
  cannot read `KITCHEN_DEFAULT_STATION` yet (there is no `GET /config`; that read endpoint is the
  Store-settings sub-project's gap). Enrich to the real name once settings ships.

## What already exists (build-on inventory)

**Backend — module `com.company.pos.kitchen` (no change in this sub-project):**
- `kitchen/domain/StationAssignment.java` — `String sku` (PK, ≤64), `String stationName` (≤100,
  NOT NULL).
- `kitchen/api/KitchenService.java` — `StationAssignmentView assignSku(String sku, String
  stationName)` (**upsert**: creates or changes the SKU's station), `void unassignSku(String sku)`,
  `List<StationAssignmentView> listAssignments()`, `String stationFor(String sku)` (explicit
  assignment or `KITCHEN_DEFAULT_STATION` fallback).
- `kitchen/api/AssignStationCommand.java` — `record AssignStationCommand(String sku, String
  stationName)`; `kitchen/api/StationAssignmentView.java` — `record StationAssignmentView(String
  sku, String stationName)`.
- `kitchen/web/KitchenController.java` endpoints:
  - `POST /kitchen/stations/assignments` body `AssignStationCommand` → `StationAssignmentView`, `@PreAuthorize` MANAGER|ADMIN.
  - `DELETE /kitchen/stations/assignments/{sku}` → 204, MANAGER|ADMIN.
  - `GET /kitchen/stations/assignments` → `List<StationAssignmentView>`, public (any authenticated).
- `SettingKey.KITCHEN_DEFAULT_STATION` (default `"Kitchen"`) — the fire-time fallback for an
  unrouted SKU. Not read by this UI (see Decisions).

**Backend — product reads (reused):** `GET /products` → server `ProductView` (the terminal's
`dto.ProductView` mirrors `sku, name, categoryName, barcode, unitPrice`) provides product names.

**Terminal (`pos-terminal/`) — what exists to build on / mirror:**
- Admin area: `AdminController` + `admin.fxml` (tiles Staff + Products, each ADMIN-gated via
  `boolean admin = services.session.roles().contains("ADMIN")`); reached from Home when
  `services.session.isManager()` (MANAGER||ADMIN). `Navigator` has `toAdmin()/toStaff()/toProducts()`.
- `api/ProductApi.java` — `list()` (GET `/products`) → `List<dto.ProductView>`.
- The Products screen (sub-project #2) is the 1:1 template: `ProductAdminApi` (thin client),
  `ProductAdminViewModel` (synchronous, `errorMessage`-only-via-`ui.accept`, async-dispatcher
  regression test), `ProductsController`/`products.fxml`, I/O-free dialog, `FxTasks` off-thread with
  `holder[]` in `onDone`.

## Terminal UI design (`pos-terminal/`)

### API client

- `api/KitchenApi.java` (new, non-final for test subclassing — the `UsersApi`/`ProductAdminApi`
  pattern): `List<StationAssignmentView> listAssignments()` (GET), `StationAssignmentView
  assign(String sku, String stationName)` (POST `AssignStationCommand`), `void unassign(String sku)`
  (DELETE, null `TypeReference`).
- `api/StationAssignmentView.java` (new, `@JsonIgnoreProperties(ignoreUnknown = true)`) — `record
  StationAssignmentView(String sku, String stationName)`.
- `api/AssignStationRequest.java` (new) — `record AssignStationRequest(String sku, String
  stationName)` (outbound body; matches the server command).

### View-model

- `viewmodel/KitchenRoutingViewModel.java` — **synchronous methods returning plain values**; the
  controller runs them off the FX thread via `FxTasks.run(work, onDone, onError)` and reads results
  in `onDone` via a `holder[]`; the only off-thread observable write is `errorMessage` inside
  `ui.accept(...)`. Methods:
  - `List<StationAssignmentView> loadAssignments()` (null on failure);
  - `List<dto.ProductView> loadProducts()` — reuse the existing `ProductApi.list()` (GET `/products`)
    for SKU+name; no admin fields are needed, so the read-only `dto.ProductView` is used, **not**
    `ProductAdminView`;
  - `StationAssignmentView assign(String sku, String stationName)` (null on failure);
  - `boolean unassign(String sku)`.
  Validation: `assign` rejects a blank `stationName` **before** the server call
  (`DomainException`-style friendly message via `errorMessage`), mirroring how `ProductAdminViewModel`
  short-circuits invalid input.

### Join helper (pure, unit-tested)

- `viewmodel/RoutingRows.java` (or a static in the VM) — `List<RoutingRow> build(List<dto.ProductView>
  products, List<StationAssignmentView> assignments)` → one row per product: `RoutingRow(String sku,
  String name, String station, boolean routed)` where `station` is the explicit assignment or `null`
  (rendered as "Default" when `!routed`). Assignments are indexed by SKU (a `Map`), so the join is
  O(n). Products with no matching product row for an assignment (orphan assignment for a deleted SKU)
  are still surfaced as a row (SKU + "(unknown product)") so the admin can clear stale routing.

### Screen

- `resources/fxml/kitchen-routing.fxml` + `view/KitchenRoutingController.java` — mirrors
  `products.fxml`/`ProductsController`:
  - Header: title "Kitchen routing", a **search** `TextField` (filters the cached rows by name/SKU,
    client-side — no re-fetch), a bound `errorLabel`, a Back button (→ `navigator.toAdmin()`).
  - `TableView<RoutingRow>` columns: SKU, Name, Station (shows the station, or a muted "Default" when
    unrouted).
  - Actions bar: **Assign / Change** (opens the station picker for the selected row → `assign`),
    **Clear routing** (unassign the selected row; disabled when the row is already unrouted).
  - `reload()` fetches products + assignments together in one `FxTasks` work lambda, rebuilds the
    cached row list + the distinct station-name set in `onDone`, then applies the search filter. Each
    mutation's `onDone` re-kicks `reload()` (never a blocking VM/HTTP call in `onDone`).

### Dialog

- `view/StationPickerDialog.java` — **I/O-free** (collects input only; controller does all HTTP).
  `promptForStation(List<String> existingStations, String current)` → `Optional<String>`: an
  editable combo pre-populated with the distinct existing station names (+ the current value
  preselected on Change); the user picks an existing name or types a new one. A static
  `normalize(String)` (trim; return null if blank) is the unit-tested surface; Save is disabled while
  the entry is blank.

### Admin area entry

- Add a **Kitchen** tile to `admin.fxml` + `AdminController`, **visible to MANAGER or ADMIN**
  (`services.session.isManager()`) — a different gate from the ADMIN-only Staff/Products tiles.
  `Navigator` gains `toKitchenRouting()`. The screen owns no timers/sockets, so no `Navigator.Screen`
  lifecycle work is required. Home→Admin entry and existing Staff/Products wiring are untouched
  (additive only; exact fx:id bijection in both FXML/controller pairs).

## Testing

**Terminal**
- `KitchenRoutingViewModelTest` including the **async-dispatcher regression test** (deferred,
  undrained `ui` dispatcher — assert `errorMessage` "" pre-drain then the error post-drain) and a
  test that `assign` with a blank station is rejected **without** calling the API.
- `RoutingRowsTest` (pure): products×assignments join — routed vs unrouted (`station` null →
  `routed=false`), an orphan assignment (SKU not in products) surfaces as an "(unknown product)" row,
  distinct-station-name extraction.
- `StationPickerDialogTest` (headless): `normalize` trims and blank→null.

No backend tests (no backend change). `ModularityTests` is unaffected (terminal is a separate build).

## Non-goals

Managed station list / Station entity; category-level routing; editing `KITCHEN_DEFAULT_STATION`
(belongs to the Store-settings sub-project); showing the actual default station name (needs
`GET /config`); bulk assign; a live view of what is currently firing (that is the KDS sub-project #8).

## Definition of done

- Terminal Admin area gains a functional **Kitchen** tile (MANAGER+ADMIN) → routing screen that
  lists products with their effective station, assigns/changes a SKU's station (picker with existing
  names + free-type), and clears routing — against a running backend.
- `./mvnw -f pos-terminal/pom.xml clean test` green (new VM/helper/dialog tests + full suite).
- No backend change, no migration, no new module dependency.
