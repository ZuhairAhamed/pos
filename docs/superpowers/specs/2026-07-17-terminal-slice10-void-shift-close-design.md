# Terminal Slice 10 — Void order + Shift close (design)

**Date:** 2026-07-17
**Branch:** `feat/terminal-ui-restaurant-slice` (continues the terminal slice series)
**Status:** Approved for planning

## Summary

Add two operations the JavaFX terminal is missing but the backend already fully
supports, in two independent tracks:

- **Track A — Void order:** a **manager-gated** action on the dine-in order screen that
  voids the whole open order (`POST /dining/orders/{id}/void`).
- **Track B — Shift close:** a **cashier-accessible** action on the home screen that
  closes the terminal's open shift with a **blind cash count**, then reveals the drawer
  reconciliation and variance (`POST /shifts/{id}/close`).

This is a **UI-only** slice. No backend, DTO-contract (server-side), migration, config,
or Maven changes.

## Motivation

The slice audit found both endpoints built and tested on the backend but with no terminal
UI:

- `POST /dining/orders/{orderId}/void?reason=` — `@PreAuthorize("hasRole('MANAGER')")`,
  204, optional `reason`, whole-order only, requires the order OPEN (works even after
  firing — e.g. a walkout), publishes no event. Terminal `DiningApi` has no void method.
- `POST /shifts/{shiftId}/close {countedCash}` → `ShiftSummary` with a full
  `DrawerReconciliation`. Not manager-gated (`closedBy` = principal). Terminal `ShiftApi`
  has only `findOpenShift`/`openShift`; the home screen can start a shift but never close
  one.

## Decisions (resolved during brainstorming)

1. **Void reason:** optional free-text. The confirm dialog has one free-text field the
   manager may fill or leave blank; matches the backend (`reason` optional, defaults `""`;
   no reason-code vocabulary exists for void).
2. **Shift-close cash flow:** blind count → reveal variance. The cashier counts the till
   (reusing the start-shift denomination helper) without seeing the expected amount,
   submits `countedCash`, and the close response then reveals opening float / cash sales /
   expected / variance. Better cash control (no anchoring).
3. **Void manager gate:** reuse the proven slice-5 one-shot manager-token machinery
   (`ManagerPinDialog` → `AuthApi.pinLoginForToken` → `ApiClient` bearer override; a 401 on
   the overridden call never clears the cashier session).
4. **Shift-close gate:** none (cashier-accessible), matching the backend.

## Design

### Track A — Void order (dine-in order screen)

**`api/DiningApi.voidOrder(UUID orderId, String reason, String bearerToken)`**
Issues `POST /dining/orders/{orderId}/void?reason=<url-encoded>` via
`client.post(path, null, new TypeReference<Void>(){}, bearerToken)` — the 204-No-Content
POST pattern (as `SalesApi.reprint`) combined with the bearer override
(as `DiningApi.close(orderId, req, bearerToken)`). `reason` is URL-encoded (free text);
when blank, `?reason=` is still sent (backend defaults empty — harmless).

**`viewmodel/OrderViewModel.voidOrder(String reason, String bearerToken) → boolean`**
Calls `dining.voidOrder(order.id(), reason, bearerToken)`; returns `true` on success. On
`ApiException` sets `errorMessage` via `ui.accept(...)` and returns `false`. Synchronous;
returns a plain value; the only observable written off-thread is `errorMessage`. No line
refresh — the caller navigates away. Mirrors `PaymentViewModel.emailReceipt`.

**`view/OrderController`** — a **"Void order"** button styled `.btn-danger` in the bottom
action bar, enabled once the order has loaded (void is valid for any OPEN order, including
one with fired lines). On tap:
1. Show a **void-confirm dialog** (`VoidConfirmDialog`): an optional free-text reason field
   + Confirm/Cancel. Cancel aborts.
2. Show `ManagerPinDialog.promptForApproval("Manager approval to void this order")`. Cancel
   aborts.
3. `FxTasks.run`: `ManagerAuth auth = authApi.pinLoginForToken(creds.code, creds.pin)` then
   `boolean ok = vm.voidOrder(reason, auth.token())`, captured in a `boolean[] holder`; in
   `onDone`, if `holder[0]` → `navigator.toTableMap()`, else leave the screen (error shows
   via the bound `errorLabel`). `onError` logs only (never `setText` the bound label).

The void-confirm and manager dialogs are display-dependent (JavaFX `Dialog`s) → exercised
by manual E2E, like `ModifierPickerDialog`/`ManagerPinDialog`.

### Track B — Shift close (home screen)

**New terminal DTOs** (`@JsonIgnoreProperties(ignoreUnknown = true)`), mirroring the server
`shift.api` / `cashdrawer.api` records field-for-field:
- `ShiftSummary(UUID shiftId, String terminalId, String openedBy, String closedBy,
  String status, Instant openedAt, Instant closedAt, DrawerReconciliation cash)`
- `DrawerReconciliation(UUID sessionId, BigDecimal openingFloat, BigDecimal cashSales,
  int cashSalesCount, BigDecimal payIns, BigDecimal payOuts, BigDecimal expectedCash,
  BigDecimal countedCash, BigDecimal variance, String currencyCode)`

**`api/ShiftApi.closeShift(UUID shiftId, BigDecimal countedCash) → ShiftSummary`**
Issues `POST /shifts/{shiftId}/close` with body `{countedCash}` (a small
`CloseShiftRequest(BigDecimal countedCash)` DTO) and parses the returned `ShiftSummary`.

**`viewmodel/CloseShiftViewModel`** (new, focused) —
`closeShift(UUID shiftId, BigDecimal countedCash) → boolean`: on success stores the
returned `ShiftSummary` in a read-only property and returns `true`; on `ApiException` sets
`errorMessage` (via `ui.accept`) and returns `false`. Synchronous; only `errorMessage`
written off-thread. Reuses the static `StartShiftViewModel.total(Map)` and
`StartShiftViewModel.DENOMINATIONS` for the count math (no duplication).

**`view/HomeController`** — retain the open `ShiftView` in a field (currently discarded).
Add a **"Close shift"** button (`closeShiftButton`) whose visible/managed is bound to
"a shift is open". On tap:
1. **Blind count** dialog (`CloseShiftDialog`): the denomination-counting helper (reused
   pattern from `StartShiftDialog`) with NO expected figure shown; produces `countedCash`.
   Confirm/Cancel.
2. `FxTasks.run`: `boolean ok = vm.closeShift(shift.shiftId(), countedCash)`, captured in a
   `boolean[] holder`.
3. In `onDone`, if `holder[0]`: show a **reconciliation result** dialog (`ShiftResultDialog`)
   rendering the stored `ShiftSummary.cash()` — opening float, cash sales (+ count),
   pay-ins, pay-outs, expected, counted, and **variance labelled** `Over` (variance > 0) /
   `Short` (variance < 0) / `Balanced` (0), styled `.variance-over`/`.variance-short`/
   `.variance-balanced` (word + colour, never colour alone). Then update the home shift line
   to the no-shift state and hide the Close button. Else the error shows on `shiftLabel`.
   `onError` logs only.

### CSS & tests

**`resources/css/app.css`** — add a slice-10 block: `.btn-danger`, `.variance-over`,
`.variance-short`, `.variance-balanced`, `.shift-result`. Reuse existing button/dialog/token
styles otherwise.

**Headless tests:**
- `api/DiningApiTest` — `voidOrder` POSTs to `/dining/orders/{id}/void`, `reason`
  URL-encoded in the query, and the `Authorization: Bearer <token>` override header is sent.
- `viewmodel/OrderViewModelTest` — `voidOrder` returns `true` on success; returns `false`
  and sets `errorMessage` on `ApiException`; **async-dispatcher regression** (deferred `ui`)
  proving the error write is deferred.
- `api/ShiftApiTest` — `closeShift` posts `{countedCash}` to `/shifts/{id}/close` and parses
  `ShiftSummary` + nested `DrawerReconciliation` (including `variance`).
- `viewmodel/CloseShiftViewModelTest` — `closeShift` stores the summary and returns `true`;
  `ApiException` → `errorMessage` + `false`; **async-dispatcher regression**.
- `AppCssTest` — a `definesSliceTenClasses` asserting the new classes.

**Manual E2E:** a README slice-10 section walking both flows against a running
`embedded,dev` backend (`manager`/`manager` for the void manager PIN).

### FX-threading (the recurring bug class)

Both VM methods are synchronous, return plain `boolean`, and write only `errorMessage`
inside `ui.accept(...)`. Controllers run them off the FX thread via `FxTasks.run` and read
results in `onDone` through a `boolean[] holder`; the stored `ShiftSummary` is a plain
read-only property read in `onDone` (never mid-work). Each new VM gets an async-dispatcher
regression test. `onError` callbacks log only — never `setText` a bound label.

## Out of scope

- Line-level void (backend is whole-order only); void audit events (backend publishes none).
- A manager gate on shift-close (backend does not require one).
- X-report / mid-shift drawer reads, pay-in/pay-out entry, multi-terminal shift management.
- Any backend change.

## Files touched

| File | Track | Change |
|------|-------|--------|
| `pos-terminal/.../api/DiningApi.java` | A | add `voidOrder(orderId, reason, bearerToken)` |
| `pos-terminal/.../viewmodel/OrderViewModel.java` | A | add `voidOrder(reason, bearerToken) → boolean` |
| `pos-terminal/.../view/VoidConfirmDialog.java` | A | new optional-reason confirm dialog |
| `pos-terminal/.../view/OrderController.java` | A | Void button + confirm→PIN→void→navigate |
| `pos-terminal/.../api/dto/ShiftSummary.java`, `DrawerReconciliation.java`, `CloseShiftRequest.java` | B | new DTOs |
| `pos-terminal/.../api/ShiftApi.java` | B | add `closeShift(shiftId, countedCash)` |
| `pos-terminal/.../viewmodel/CloseShiftViewModel.java` | B | new VM |
| `pos-terminal/.../view/CloseShiftDialog.java`, `ShiftResultDialog.java` | B | new count + result dialogs |
| `pos-terminal/.../view/HomeController.java` | B | retain shift, Close button, close flow |
| `pos-terminal/.../resources/fxml/home.fxml` | B | `closeShiftButton` |
| `pos-terminal/.../resources/css/app.css` | A,B | `.btn-danger`, variance + result classes |
| tests: `DiningApiTest`, `OrderViewModelTest`, `ShiftApiTest`, `CloseShiftViewModelTest`, `AppCssTest` | A,B | as above |
| `pos-terminal/README.md` | A,B | slice-10 manual E2E |

## Verification

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -f pos-terminal/pom.xml clean test
```

All terminal tests green (currently 187; this slice adds cases). Manual E2E per the new
README section.
