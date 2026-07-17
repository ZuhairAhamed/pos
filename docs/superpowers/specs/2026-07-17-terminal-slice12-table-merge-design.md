# Terminal Slice 12 — Table merge (design)

**Date:** 2026-07-17
**Branch:** `feat/terminal-ui-restaurant-slice` (continues the terminal slice series)
**Status:** Approved for planning

## Summary

Let staff **combine two occupied dine-in tables' open orders into one** from the terminal.
This is the deferred second half of slice 11 (transfer): where transfer relocated an order to a
*free* table, merge folds one order's lines into *another occupied* order and disposes of the
emptied one. Full-stack: a new `dining` backend operation (`mergeOrders`) plus terminal UI (a
"Merge" action on the order screen).

No database migration: merge recreates lines (existing `order_line` rows) and reuses the existing
`VOIDED` status — no schema change.

## Motivation

Slice 11 deliberately shipped transfer-only and deferred merge for its data-integrity subtleties
(see `2026-07-17-terminal-slice11-table-transfer-design.md`). Those subtleties are now the focus:
`OrderLine.orderId` is a `nullable=false` column with **no setter**, and both
`DiningOrder.lines` and `OrderLine.modifiers` use `orphanRemoval=true` — so a line cannot be
cheaply reassigned between orders (removing it from one order's collection would delete it). Merge
therefore **recreates** each line on the survivor. Modifiers are stored as immutable snapshots
(`optionId`/`name`/`priceDelta`), so they copy faithfully without re-resolution, and per-line
`firedAt` copies across to preserve kitchen state.

## Decisions (resolved during brainstorming)

1. **Direction / survivor:** the order the user is **viewing survives**; the user picks an occupied
   table whose order is **absorbed** into the viewed one. After a successful merge the UI **stays**
   on the (now-combined) order screen and refreshes it. (Natural for "guests joined my table.")
2. **Manager gate:** none — cashier-level, like transfer. Merge is a **consolidation, not a
   cancellation**: the absorbed lines reappear on the survivor, so no revenue is lost. Consistent
   with `openOrder`/`addLine`/`fire`/`close`/`transfer` being cashier-level.
3. **Absorbed-order disposal:** reuse the existing **`VOIDED`** status (via `DiningOrder.voidOrder()`).
   Zero new surface — no enum value, no migration, no other module touched. Accepted trade-off: a
   merged order is indistinguishable from a cancelled one in reports. A dedicated `MERGED` status is
   explicitly **out of scope**.
4. **Service-type restriction:** **dine-in ↔ dine-in only.** Both orders must be `DINE_IN`; a
   takeaway/`QUICK_SERVICE` order is neither a valid survivor nor absorbed target. This sidesteps
   the semantics of applying the survivor's service charge to absorbed takeaway items.

## Design

### Backend (`dining` module)

**Service — `DiningService.mergeOrders(UUID survivorOrderId, UUID absorbedOrderId) → OrderView`**

```
DiningOrder survivor = load(survivorOrderId);            // unknown → notFound
DiningOrder absorbed = load(absorbedOrderId);            // unknown → notFound
requireOpen(survivor);                                   // non-open → validation
requireOpen(absorbed);                                   // non-open → validation
if (survivorOrderId.equals(absorbedOrderId))             // same order → validation
    throw validation("Cannot merge an order into itself");
if (survivor.getServiceType() != ServiceType.DINE_IN
        || absorbed.getServiceType() != ServiceType.DINE_IN)   // non-dine-in → validation
    throw validation("Only dine-in orders can be merged");
if (absorbed.getLines().isEmpty())                       // nothing to move → validation
    throw validation("The selected order has no lines to merge");
for (OrderLine src : absorbed.getLines()) {
    OrderLine copy = new OrderLine(Identifiers.newId(), survivor.getId(), src.getSku(),
            src.getQty(), src.getNote(), src.getCourse(), src.getAddedBy(), src.getAddedAt());
    for (OrderLineModifier m : src.getModifiers())
        copy.addModifier(m.getOptionId(), m.getName(), m.getPriceDelta());
    if (src.isFired())
        copy.fire(src.getFiredAt());                     // preserve fired state
    survivor.addLine(copy);
}
absorbed.voidOrder();                                    // → VOIDED, frees the absorbed table
return toOrderView(orders.save(survivor));
```

Reuses existing domain methods (`addLine`, `OrderLine.addModifier`/`fire`, `voidOrder`) — **no new
domain method**. `absorbed` persists via dirty-checking inside the transaction (same pattern as the
existing `voidOrder` service method, which also does not call `save`). The whole operation runs
under the class-level `@Transactional` (like every sibling write method — do **not** add an explicit
one); the `@Version` optimistic lock on `DiningOrder` guards concurrent edits to either order.

Line-copy fidelity (verified against the entities): `OrderLine` carries
`sku`/`qty`/`note`/`course`/`addedBy`/`addedAt`/`firedAt` and a `modifiers` collection of
`(optionId, name, priceDelta)` snapshots — all copyable with public accessors + the
`addModifier(optionId, name, priceDelta)` and `fire(Instant)` methods. No product/menu re-resolution
occurs (prices are snapshots), so the copy is faithful and offline-safe.

**Web — `POST /dining/orders/{survivorOrderId}/merge?absorbedOrderId=<uuid>` → `OrderView` (200)**
No `@PreAuthorize` (cashier-level). Query-param style mirrors `transfer`.

**Boundary:** self-contained in `dining`; no `allowedDependencies` change; no migration.

### Terminal (`pos-terminal`)

**`api/DiningApi.mergeOrders(UUID survivorOrderId, UUID absorbedOrderId) → OrderView`**
`POST /dining/orders/{survivorOrderId}/merge?absorbedOrderId=...` (session token). Returns the
merged survivor `OrderView`.

**`order/MergeTargets.occupiedTargets(List<TableView> tables, List<OpenOrderView> openOrders,
UUID currentTableId) → List<OpenOrderView>`**
The mirror of slice-11 `MoveTargets`: returns the open orders whose table is active, **dine-in**,
occupied (i.e. every open dine-in order **except** the one on the current table). Returns
`OpenOrderView` (not `TableView`) because the caller needs the target **order id** and the table
label/line count for the picker. Pure static helper, headless-tested.

**`viewmodel/OrderViewModel.merge(UUID absorbedOrderId) → boolean`**
Mirrors `transfer`/`voidOrder`: calls `dining.mergeOrders(order.id(), absorbedOrderId)`; returns
`true` on success, on `ApiException` sets `errorMessage` (via `ui.accept`) and returns `false`.
Synchronous; only `errorMessage` written off-thread. The caller **reloads the current order** on
success (the survivor is the viewed order — stay). Reuses the existing `setError` for the
"no other occupied tables" message.

**`view/MergeTableDialog.promptForTarget(List<OpenOrderView> targets) → Optional<UUID>`**
A picker listing each occupied dine-in table (label + line count, e.g. "Table 6 · 3 items") as a
touch button; returns the chosen **order id**, or empty on cancel. Pure view, display-dependent →
manual E2E (mirrors `MoveTableDialog`).

**`view/OrderController`** — a **"Merge"** button (`.btn-secondary`) in the action bar, enabled once
the order has loaded. On tap:
1. `FxTasks.run` off-thread: fetch `services.diningApi.tables()` + `openOrders()`, compute
   `MergeTargets.occupiedTargets(tables, openOrders, vm.currentOrder().tableId())` into an
   `AtomicReference`.
2. In `onDone`: if the list is empty → `vm.setError("No other occupied tables to merge")`; else
   `MergeTableDialog.promptForTarget(targets)` → on a chosen order id, `FxTasks.run(() ->
   holder[0] = vm.merge(absorbedId), ...)` → on success `vm.load(orderId)` (refresh the combined
   check — **stay** on the order screen). `onError` logs only (never `setText` the bound
   `errorLabel`).

**`resources/fxml/order.fxml`** — add `mergeButton` (styleClass `btn-secondary`) to the action-bar
HBox (beside `moveButton`).

**`resources/css/app.css`** — a `.merge-box` polish rule (unasserted), mirroring `.move-box`.

### FX-threading (the recurring bug class)

`merge` is synchronous, returns a plain boolean, and writes only `errorMessage` inside `ui.accept`.
The controller runs VM/API calls off the FX thread via `FxTasks.run` and reads results in `onDone`
through an `AtomicReference` (target list) / `boolean[]` holder (merge result); the dialog is shown
only in `onDone`. `MergeTargets` is pure. `onError` callbacks log only — never `setText` a bound
label. A new async-dispatcher regression test covers `merge`.

## Testing

- **Backend — `DiningMergeServiceTest`** (`@SpringBootTest @ActiveProfiles("embedded")
  @Import(DatabaseCleaner)`, seeding products via `FakeErpClient` + `ProductSync` as
  `DiningTransferServiceTest`/`DiningLineServiceTest` do):
  - merge folds the absorbed order's lines onto the survivor (survivor line count = sum;
    the survivor keeps its own lines);
  - a fired line on the absorbed order arrives on the survivor **still fired** (`firedAt` preserved);
  - note + course + qty are preserved on the copied lines;
  - (modifier fidelity — optionId/name/priceDelta — is guaranteed by the same snapshot-copy the
    absorbed loop uses, identical to `priceCartFor`; it is exercised by the manual E2E rather than a
    unit test, to avoid seeding menu modifiers in the service test);
  - the absorbed order is `VOIDED` after merge and its table is free
    (`existsByTableIdAndStatus(absorbedTable, OPEN)` is false);
  - rejects: same order (validation), a non-open survivor (validation), a non-open absorbed
    (validation), an absorbed order with no lines (validation), a non-dine-in survivor or absorbed
    (validation), an unknown order (notFound).
- **Backend — `DiningMergeControllerTest`** (`@AutoConfigureMockMvc`, cashier JWT): the endpoint
  returns 200 with the survivor's id and a merged line count, and a cashier (no manager role) is
  allowed.
- **Terminal — `DiningApiTest`**: `mergeOrders` POSTs to
  `/dining/orders/{survivorId}/merge` with `absorbedOrderId` in the query and parses the returned
  `OrderView`.
- **Terminal — `OrderViewModelTest`**: `merge` returns `true` on success, `false` +
  `errorMessage` on `ApiException`, plus an **async-dispatcher regression** (deferred `ui`).
- **Terminal — `MergeTargetsTest`**: excludes the current table, keeps other occupied dine-in
  tables, excludes free tables, excludes inactive tables, and excludes non-dine-in (takeaway)
  occupied tables; empty when no other occupied dine-in tables exist.
- **Manual E2E:** a README slice-12 section (merge one occupied table into another; verify the
  no-other-occupied-tables path and that the absorbed table frees).

## Out of scope

- **Unmerge / undo** — a merge is not reversible from the terminal.
- **Cross-service-type merge** (takeaway ↔ dine-in) and any service-charge re-derivation.
- A dedicated **`MERGED`** order status (reuse `VOIDED`); merge **audit events**; kitchen
  re-notification for lines that were already fired on the absorbed table.
- Retail-cart merges; merging closed/voided orders; partial (line-subset) merges.
- Any backend change outside `dining`; no migration; no new dependencies.

## Files touched

| File | Track | Change |
|------|-------|--------|
| `dining/api/DiningService.java` | BE | `mergeOrders(UUID, UUID)` |
| `dining/application/DefaultDiningService.java` | BE | implement `mergeOrders` |
| `dining/web/DiningController.java` | BE | `POST .../{id}/merge` |
| `src/test/.../dining/DiningMergeServiceTest.java` | BE | new |
| `src/test/.../dining/DiningMergeControllerTest.java` | BE | new |
| `pos-terminal/.../api/DiningApi.java` | FE | `mergeOrders` |
| `pos-terminal/.../order/MergeTargets.java` | FE | new pure helper |
| `pos-terminal/.../viewmodel/OrderViewModel.java` | FE | `merge(UUID) → boolean` |
| `pos-terminal/.../view/MergeTableDialog.java` | FE | new picker |
| `pos-terminal/.../view/OrderController.java` | FE | Merge button + flow |
| `pos-terminal/.../resources/fxml/order.fxml` | FE | `mergeButton` |
| `pos-terminal/.../resources/css/app.css` | FE | `.merge-box` |
| `pos-terminal/.../api/DiningApiTest.java`, `viewmodel/OrderViewModelTest.java`, `order/MergeTargetsTest.java` | FE | tests |
| `pos-terminal/README.md` | FE | slice-12 manual E2E |

## Verification

Backend (root reactor): `./mvnw test -Dtest='com.company.pos.dining.*'` and
`./mvnw test -Dtest=ModularityTests` (boundary check after the `dining` change).
Terminal: `./mvnw -f pos-terminal/pom.xml clean test`. Both green; manual E2E per the README.
