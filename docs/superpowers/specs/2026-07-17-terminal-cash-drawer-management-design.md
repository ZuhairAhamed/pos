# Terminal Cash-Drawer Management (Pay-in / Pay-out + Blind-safe Activity Peek) — Design

**Date:** 2026-07-17
**Branch:** `feat/terminal-ui-restaurant-slice` (continues the terminal slice sequence; slice 16)
**Status:** Approved — ready for implementation plan.

## Problem

The terminal already opens and closes shifts and shows a full drawer reconciliation **at close**
(slice 10: `StartShiftDialog` → `CloseShiftDialog` blind count → `ShiftResultDialog` variance grid).
But the mid-shift drawer-management surface is missing: the backend exposes
`POST /cash-drawer/pay-in`, `POST /cash-drawer/pay-out`, and `GET /cash-drawer/reconciliation`,
yet the terminal has **no `CashDrawerApi` and no UI** for any of them. A cashier cannot record a
cash drop, a petty-cash payout, or a change-fund top-up, and cannot confirm a movement landed
until the shift closes. This slice adds that surface.

## Scope (Option A1)

Deliver, terminal-only:

1. **Pay-in / Pay-out UI** — record a cash movement (amount + reason) against the open drawer
   session, cashier-level (no manager PIN).
2. **A blind-safe activity peek** — a mid-shift view of *recorded activity* that does **not**
   un-blind the close.

Explicitly **not** in scope: manager gating (deferred "Option B"), persisting a per-denomination
breakdown, a full-screen shift/drawer report, and a redacted server endpoint (see Integrity below).

## The blind-count integrity constraint (the crux of this slice)

The close count is deliberately blind so a cashier cannot tune their counted cash to match
expected. Expected is computable as:

```
expectedCash = openingFloat + cashSales(amount) + payIns − payOuts
```

Therefore the activity peek must **never** display `expectedCash`, `variance`, or the **cash-sales
amount** — and, because the other three terms would let a cashier back-compute expected, the peek
shows the cash-sales **count** (how many cash transactions) rather than their summed amount. The
displayed set is exactly:

- Opening float (the cashier entered it — no new information)
- Cash sales **count** (a number of transactions, not an amount)
- Pay-ins total
- Pay-outs total

Withholding the cash-sales *amount* is what preserves blindness; the other three carry no way to
derive expected on their own. This property is enforced by a unit test on the pure row-builder
(`DrawerActivityDialog.activityRows`), so it cannot silently rot.

### Accepted limitation (documented, not fixed here)

`GET /cash-drawer/reconciliation` returns the full `DrawerReconciliation` (including
`expectedCash`, `variance`, and the cash-sales amount) over the wire; the terminal simply does not
render those fields. A determined operator with network access to the terminal could still read
them. Closing that gap needs a redacted server endpoint (e.g. `GET /cash-drawer/activity`) and is
deferred — it is a backend change out of scope for this terminal-only slice. Recorded as a
non-goal.

## Architecture

Terminal-only. No backend change, no migration, no config key, no Maven change. All three endpoints
already exist and are cashier-level (no `@PreAuthorize` role gate on the cash-drawer controller).

```
Home (shift open) ── "Drawer" button ──▶ HomeController.openDrawer()
        │  FxTasks.run(work = drawerVm.loadActivity())            [off FX thread]
        ▼
  DrawerReconciliation (or null on error)                         [onDone, FX thread]
        │
   null → shiftLabel shows drawerVm.errorMessage
   ok   → DrawerActivityDialog.promptForAction(activity)          [I/O-free modal]
              │  returns Optional<DrawerAction> (PAY_IN | PAY_OUT | empty=Done)
              ▼
        CashMovementDialog.prompt(action)                         [I/O-free modal]
              │  returns Optional<CashMovementInput>(amount, reason)
              ▼
        HomeController.submitMovement(action, input)
              │  FxTasks.run(work = drawerVm.payIn/​payOut(...))    [off FX thread]
              ▼
        CashMovementView (or null on error)                       [onDone, FX thread]
              │
         ok  → openDrawer() again (re-fetch → show refreshed activity → loop)
         null→ shiftLabel shows drawerVm.errorMessage
```

**FX-threading (the codebase's recurring bug class).** Every dialog is I/O-free (it collects input
or displays a value passed in). All HTTP runs inside `FxTasks` **work** lambdas via synchronous VM
methods; results are read in `onDone` on the FX thread through a holder array; the VM writes only
`errorMessage`, off-thread, inside `ui.accept(...)`. No `onDone` performs a blocking VM/HTTP call —
`showAndWait()` and the follow-on `submitMovement`/`openDrawer` calls kick *new* `FxTasks` tasks
rather than blocking. This mirrors the existing `HomeController.closeShift` seam exactly.

## Components

### New terminal files

- **`api/dto/CashMovementView.java`** — `record(UUID id, UUID sessionId, String type,
  BigDecimal amount, String reference, Instant createdAt)`, `@JsonIgnoreProperties(ignoreUnknown =
  true)`. Mirrors the server `cashdrawer.api.CashMovementView`.
- **`api/dto/CashMovementRequest.java`** — `record(BigDecimal amount, String reason)`. POST body for
  pay-in / pay-out (mirrors the server `CashMovementRequest{amount, reason}`).
- **`api/CashDrawerApi.java`** — thin typed client (constructor takes `ApiClient`, subclassable for
  test stubs like `ShiftApi`):
  - `DrawerReconciliation reconciliation()` → `GET /cash-drawer/reconciliation`
  - `CashMovementView payIn(BigDecimal amount, String reason)` → `POST /cash-drawer/pay-in`
  - `CashMovementView payOut(BigDecimal amount, String reason)` → `POST /cash-drawer/pay-out`
- **`viewmodel/CashDrawerViewModel.java`** — synchronous VM (constructor `(CashDrawerApi,
  Consumer<Runnable> ui)`), one deferred observable `errorMessage`:
  - `DrawerReconciliation loadActivity()` — `try` the GET; on `ApiException` → `ui.accept` sets
    `errorMessage`, returns `null`.
  - `CashMovementView payIn(BigDecimal amount, String reason)` — client-side validation first:
    `amount == null || amount.signum() <= 0` → error "Amount must be greater than zero", return
    `null` (no server call); `reason == null || reason.isBlank()` → error "Enter a reason", return
    `null`. Otherwise POST `amount.setScale(2, HALF_UP)` + `reason.trim()`; on success clear
    `errorMessage` and return the view; on `ApiException` set `errorMessage`, return `null`.
  - `CashMovementView payOut(BigDecimal amount, String reason)` — identical, calls `api.payOut`.
  - `messageOf(ApiException)` — same detail→title→message→"Request failed" precedence as the other
    shift VMs.
- **`view/DrawerActivityDialog.java`** — I/O-free modal. `promptForAction(DrawerReconciliation
  activity, String terminalId) → Optional<DrawerAction>` (nested `enum DrawerAction { PAY_IN,
  PAY_OUT }`; empty = the "Done" button). Renders the blind-safe grid built by the **pure**
  `static List<Row> activityRows(DrawerReconciliation r)` (nested `record Row(String label, String
  value)`), styled with the existing `.field-label`/`.money` classes and a new `.drawer-modal` pane
  class. `activityRows` is the tested contract; `promptForAction` is display-only.
- **`view/CashMovementDialog.java`** — I/O-free modal. `prompt(DrawerAction action, String
  terminalId) → Optional<CashMovementInput>` (nested `record CashMovementInput(BigDecimal amount,
  String reason)`). Collects amount via `Keypads.numericPad` + a reason `TextField`; the submit
  button is disabled until `parseAmount(text) != null` **and** reason is non-blank. `static
  BigDecimal parseAmount(String raw)` (positive non-zero decimal → value, else `null`) is the tested
  helper.

### Modified terminal files

- **`app/Services.java`** — add `public final CashDrawerApi cashDrawerApi;` wired to `apiClient`.
- **`view/HomeController.java`** — add `@FXML Button drawerButton`, a `CashDrawerViewModel drawerVm`
  (`new CashDrawerViewModel(services.cashDrawerApi, Platform::runLater)`), and `openDrawer()` /
  `submitMovement(DrawerAction, CashMovementInput)`. Show/hide `drawerButton` together with
  `closeShiftButton` in `showShift`/`showNoShift` (drawer actions require an open drawer session).
- **`src/main/resources/fxml/home.fxml`** — add `<Button fx:id="drawerButton" text="Drawer"
  styleClass="btn-secondary"/>` immediately before `closeShiftButton`.
- **`src/main/resources/css/app.css`** — add a `.drawer-modal` rule from the existing emerald tokens.
- **`src/test/java/com/company/pos/terminal/AppCssTest.java`** — add `definesSliceSixteenDrawerClass`
  asserting `.drawer-modal` (mirrors the slice-9/15 guard pattern).

## Testing strategy

**Terminal only** (`./mvnw -f pos-terminal/pom.xml clean test`, headless — no display/TestFX):

- **`CashDrawerViewModelTest`**:
  - `loadActivity` success returns the reconciliation.
  - `loadActivity` `ApiException` → `null`, `errorMessage` set, state intact.
  - `payIn` valid → returns the `CashMovementView`, `errorMessage` cleared.
  - `payIn` zero/negative amount → `null`, error "Amount must be greater than zero", **no API call**
    (stub throws if called).
  - `payIn` blank reason → `null`, error "Enter a reason", no API call.
  - `payIn` `ApiException` → `null`, error surfaced.
  - `payOut` valid → returns the view (proves the pay-out wiring).
  - `deferredDispatcherHoldsErrorUntilDrained` — a rejected `payIn` returns `null` synchronously
    while `errorMessage` stays empty until the queued `ui` dispatcher drains (the mandated
    async-dispatcher regression).
- **`DrawerActivityDialogTest`** (blind-safety contract): given a `DrawerReconciliation` whose
  `expectedCash`, cash-sales **amount**, and `variance` are distinct known values, `activityRows`
  returns exactly four rows labelled `Opening float`, `Cash sales`, `Pay-ins`, `Pay-outs`; the
  `Cash sales` value is the **count** (e.g. `"37"`), and **no** row value equals the formatted
  `expectedCash`, cash-sales amount, or `variance`.
- **`CashMovementDialogTest`**: `parseAmount` accepts a positive decimal, rejects zero, negative,
  blank, and non-numeric (→ `null`).
- **`AppCssTest`**: `.drawer-modal` present.
- Existing terminal tests stay green.

**Manual E2E** (documented): backend `embedded,dev`; open a shift; tap **Drawer** → record a
pay-in (amount + reason) → confirm the activity view's Pay-ins total increases and no
expected/variance figure is shown → record a pay-out → close the shift and confirm the
reconciliation reflects both movements.

## Non-goals / deferred

- Manager-PIN gate on pay-out or close (Option B).
- A redacted server endpoint so expected never crosses the wire (see Accepted limitation).
- Persisting the per-denomination breakdown (only the summed amount is sent).
- A dedicated full-screen drawer/shift report (dialogs suffice for one store, one terminal).
- Stale/live-push refresh of the activity view (the peek is fetched on open; no WebSocket wiring).

## Module-boundary / build impact

- **No backend change**, no migration, no config key, no Maven change.
- Terminal-only: new `CashDrawerApi`, `CashMovementView`, `CashMovementRequest`,
  `CashDrawerViewModel`, `DrawerActivityDialog`, `CashMovementDialog`; modified `Services`,
  `HomeController`, `home.fxml`, `app.css`, `AppCssTest`.
- No `ModularityTests` impact (terminal is a separate build, not in the root reactor).
