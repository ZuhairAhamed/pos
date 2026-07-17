# Terminal Slice 11 — Table transfer (design)

**Date:** 2026-07-17
**Branch:** `feat/terminal-ui-restaurant-slice` (continues the terminal slice series)
**Status:** Approved for planning

## Summary

Let staff **relocate an open dine-in order to a different, free table** from the terminal.
This is the first **full-stack** slice of the terminal series: a new `dining` backend
operation (`transferOrder`) plus terminal UI (a "Move table" action on the order screen).
**Merge** (combining two occupied tables' orders) is explicitly a separate later slice.

No database migration: transfer only changes an existing `dining_order.table_id`.

## Motivation

The slice audit flagged table transfer/merge as missing on both the backend and the
terminal. Guests move; staff need to move the check with them. The one-open-order-per-table
invariant (`existsByTableIdAndStatus(tableId, OPEN)`, enforced in `openOrder`) makes this a
clean, well-bounded operation: move an order to a table that has no open order.

## Decisions (resolved during brainstorming)

1. **Scope:** transfer only. Merge (move lines across orders + void source + fired-state
   preservation) is deferred to slice 12, where its data-integrity subtleties get focus.
2. **Manager gate:** none — transfer is a non-destructive relocation and a routine floor
   operation, consistent with `openOrder`/`addLine` being cashier-level.
3. **UI entry point:** a "Move table" button in the order screen's action bar (beside
   Fire/Split/Pay/Void), opening a free-table picker; on success it returns to the table map.
4. **Target-table rule:** the target must be an active table with no open order. An occupied
   target returns a clear conflict ("already has an open order"); an inactive/unknown target
   is rejected. A moved order keeps its `serviceType` (which lives on the order, not the
   table) — no table-type restriction is imposed.

## Design

### Backend (`dining` module)

**Domain — `DiningOrder.moveToTable(UUID targetTableId)`**
A setter for the currently-unsettable `tableId`. The application service enforces OPEN state
before calling it; the domain method just reassigns the field. Lines are untouched (they
carry their own `order_id`, which does not change), so quantities, notes, courses, modifiers,
and per-line `firedAt` all ride along automatically.

**Service — `DiningService.transferOrder(UUID orderId, UUID targetTableId) → OrderView`**
```
DiningOrder order = load(orderId);
requireOpen(order);                                   // non-open → validation
if (order.getTableId().equals(targetTableId))          // no-op guard
    throw validation("Order is already on that table");
DiningTable target = tables.findById(targetTableId)    // unknown → notFound
    .orElseThrow(...);
if (!target.isActive())                                // inactive → validation
    throw validation("Table " + target.getLabel() + " is inactive");
if (orders.existsByTableIdAndStatus(targetTableId, OrderStatus.OPEN))  // occupied → conflict
    throw conflict("Table " + target.getLabel() + " already has an open order");
order.moveToTable(targetTableId);
return toOrderView(orders.save(order));
```
The `@Version` optimistic lock on `DiningOrder` covers concurrent edits. Reuses the existing
`existsByTableIdAndStatus` finder — no new repository method.

**Web — `POST /dining/orders/{orderId}/transfer?targetTableId=<uuid>` → `OrderView` (200)**
No `@PreAuthorize` (cashier-level). Query param mirrors the existing `updateLine`/`void`
param style.

**Boundary:** self-contained in `dining`; no `allowedDependencies` change.

### Terminal (`pos-terminal`)

**`api/DiningApi.transferOrder(UUID orderId, UUID targetTableId) → OrderView`**
`POST /dining/orders/{orderId}/transfer?targetTableId=...` (session token). Returns the moved
`OrderView`.

**`order/MoveTargets.freeTargets(List<TableView> tables, List<OpenOrderView> openOrders,
UUID currentTableId) → List<TableView>`**
A pure static helper: active tables, excluding the current table and any table whose id
appears among the open orders' `tableId`s. Headless-testable — the established pattern for
pure logic (`varianceText`, `CloseShiftDialog.parse`, `MenuCache`).

**`viewmodel/OrderViewModel.transfer(UUID targetTableId) → boolean`**
Mirrors `voidOrder`: calls `dining.transferOrder(order.id(), targetTableId)`, returns `true`
on success, on `ApiException` sets `errorMessage` (via `ui.accept`) and returns `false`.
Synchronous; only `errorMessage` written off-thread. The caller navigates to the table map on
success — no line refresh. (Reuses the existing `setError` from slice 10 for the "no free
tables" message.)

**`view/MoveTableDialog.promptForTarget(List<TableView> freeTables) → Optional<UUID>`**
A picker listing each free table (label + seats) as a touch button; returns the chosen
table id, or empty on cancel. Pure view, display-dependent → manual E2E.

**`view/OrderController`** — a **"Move table"** button (`.btn-secondary`) in the action bar,
enabled once the order has loaded. On tap:
1. `FxTasks.run` off-thread: fetch `services.diningApi.tables()` + `openOrders()`, compute
   `MoveTargets.freeTargets(tables, openOrders, vm.currentOrder().tableId())` into a holder.
2. In `onDone`: if the free list is empty → `vm.setError("No free tables available")`; else
   `MoveTableDialog.promptForTarget(free)` → on a chosen id, `FxTasks.run(() ->
   holder[0] = vm.transfer(targetId), ...)` → on success `navigator.toTableMap()`.
   `onError` logs only (never `setText` the bound `errorLabel`).

**`resources/fxml/order.fxml`** — add `moveButton` (styleClass `btn-secondary`).

### FX-threading (the recurring bug class)

`transfer` is synchronous, returns a plain boolean, and writes only `errorMessage` inside
`ui.accept`. The controller runs VM/API calls off the FX thread via `FxTasks.run` and reads
results in `onDone` through a `holder` array. `MoveTargets` is pure (no FX, no I/O). A new
async-dispatcher regression test covers `transfer`.

## Testing

- **Backend — `DiningTransferServiceTest`** (`@SpringBootTest @ActiveProfiles("embedded")
  @Import(DatabaseCleaner)`, seeding a product via `FakeErpClient` + `ProductSync` as
  `DiningLineServiceTest` does):
  - transfer to a free table moves the order (`order.tableId()` == target);
  - a line added + fired before transfer survives on the moved order (lines + `firedAt`
    preserved);
  - rejects an occupied target (conflict), an inactive target (validation), an unknown table
    (notFound), a non-open order (validation), and the no-op same-table case (validation).
- **Backend — `DiningTransferControllerTest`** (`@AutoConfigureMockMvc`, cashier JWT): the
  endpoint returns 200 with the moved order's `tableId`, and a cashier (no manager role) is
  allowed.
- **Terminal — `DiningApiTest`**: `transferOrder` POSTs to `/dining/orders/{id}/transfer`
  with `targetTableId` in the query and parses the returned `OrderView`.
- **Terminal — `OrderViewModelTest`**: `transfer` returns `true` on success, `false` +
  `errorMessage` on `ApiException`, plus an **async-dispatcher regression** (deferred `ui`).
- **Terminal — `MoveTargetsTest`**: excludes the current table, occupied tables, and inactive
  tables; keeps other active free tables.
- **Manual E2E:** a README slice-11 section (move an order to a free table; verify the
  occupied-target and no-free-tables paths).

## Out of scope

- **Merge** (occupied target) — slice 12.
- Cross-service-type semantics beyond keeping the order's existing `serviceType`.
- Any table-type restriction (counters vs dine-in tables), transfer audit events, and
  moving closed/voided orders.
- Any backend change outside `dining`; no migration; no new dependencies.

## Files touched

| File | Track | Change |
|------|-------|--------|
| `dining/domain/DiningOrder.java` | BE | `moveToTable(UUID)` |
| `dining/api/DiningService.java` | BE | `transferOrder(UUID, UUID)` |
| `dining/application/DefaultDiningService.java` | BE | implement `transferOrder` |
| `dining/web/DiningController.java` | BE | `POST .../{id}/transfer` |
| `src/test/.../dining/DiningTransferServiceTest.java` | BE | new |
| `src/test/.../dining/DiningTransferControllerTest.java` | BE | new |
| `pos-terminal/.../api/DiningApi.java` | FE | `transferOrder` |
| `pos-terminal/.../order/MoveTargets.java` | FE | new pure helper |
| `pos-terminal/.../viewmodel/OrderViewModel.java` | FE | `transfer(UUID) → boolean` |
| `pos-terminal/.../view/MoveTableDialog.java` | FE | new picker |
| `pos-terminal/.../view/OrderController.java` | FE | Move-table button + flow |
| `pos-terminal/.../resources/fxml/order.fxml` | FE | `moveButton` |
| `pos-terminal/.../api/DiningApiTest.java`, `viewmodel/OrderViewModelTest.java`, `order/MoveTargetsTest.java` | FE | tests |
| `pos-terminal/README.md` | FE | slice-11 manual E2E |

## Verification

Backend (root reactor): `./mvnw test -Dtest='com.company.pos.dining.*'` and
`./mvnw test -Dtest=ModularityTests` (boundary check after the `dining` change).
Terminal: `./mvnw -f pos-terminal/pom.xml clean test`. Both green; manual E2E per the README.
