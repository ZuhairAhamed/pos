# Terminal Slice 13 — Service-charge waiver (design)

**Date:** 2026-07-17
**Branch:** `feat/terminal-ui-restaurant-slice` (continues the terminal slice series)
**Status:** Approved for planning

## Summary

Let a **manager waive the restaurant service charge** on a dine-in order at checkout, from the
terminal — on both the **whole-order payment screen** and the **split-bill screen**. The backend
*close* path already supports and enforces this (`CloseOrderCommand.waiveServiceCharge` /
`SplitCloseCommand.waiveServiceCharge` → `resolveApplyServiceCharge`, manager-only). This slice adds
the missing **quote** counterpart (so the previewed/tendered total reflects the waiver) and the
**terminal UI** (a manager-gated "Waive service charge" toggle that re-quotes and carries the
approval into close).

No database migration, no config/enum change: waiver is an existing behaviour of the close path;
this slice makes it reachable and preview-accurate.

## Motivation

The exploration found the close/split-close backend and the terminal `CloseOrderRequest`/
`SplitCloseRequest` DTOs already carry `waiveServiceCharge` — but the terminal always sends `false`
and there is no UI. Critically, the **quote** path ignores waiver: `quoteOrder` and the split-quote
methods hardcode `resolveApplyServiceCharge(order, false, false)`, and their request DTOs have no
waive field (an in-code comment anticipates this: *"thread `waiveServiceCharge` through here if that
ever lands"*). Because the terminal tenders the **quote's** authoritative `grandTotal`, a waiver
applied only at close would leave the tendered amount overstated by the service charge. So the quote
must reflect the waiver for the amounts to be correct.

## Decisions (resolved during brainstorming)

1. **Scope:** both flows — the whole-order **payment screen** and the **split-bill screen**. Each
   needs its quote path threaded (`quoteOrder` and `quoteSplitByItem`/`quoteSplitEven`) and its
   controller given the waive toggle.
2. **Approval timing:** **upfront, at waive-time.** Tapping "Waive service charge" immediately
   prompts the manager PIN; only on approval does the waived preview + chip appear and the one-shot
   token get captured (mirrors slice-10 void). This prevents a cashier from tendering a waived total
   they cannot actually close (the close is the real gate — a non-manager close with
   `waiveServiceCharge=true` is rejected 400).
3. **Quote is an ungated preview.** The quote overloads compute the waived total **without** the
   manager throw (call `resolveApplyServiceCharge(order, waive, /*callerIsManager*/ true)`) — exactly
   slice 5's discount-quote approach ("checkout stays the sole enforcement point"). Enforcement stays
   at close/split-close, which are already manager-gated.
4. **All-or-nothing, order-level.** The waiver removes the entire service charge for the order (and,
   in a split, for every bill — the backend already applies one SC decision across all bills). No
   partial waiver, no per-reason vocabulary.

## Design

### Backend (`dining` module) — quote path only

**`DiningService.quoteOrder(UUID orderId, DiscountInput transactionDiscount /* + existing
lineDiscounts overload */, boolean waiveServiceCharge)`** — add a `waiveServiceCharge` parameter to
the discount-aware quote overload. Implementation computes
`applyServiceCharge = resolveApplyServiceCharge(order, waiveServiceCharge, true)` (the `true`
bypasses the manager throw for the preview) and passes it to `sales.quote(...)`. The existing no-arg
and no-waive overloads delegate with `waiveServiceCharge=false`.

**`DiningService.quoteSplitByItem(UUID orderId, List<List<UUID>> billLineIds, boolean
waiveServiceCharge)` and `quoteSplitEven(UUID orderId, int ways, boolean waiveServiceCharge)`** —
same treatment: add the flag, compute `resolveApplyServiceCharge(order, waiveServiceCharge, true)`,
thread it into every per-bill `sales.quote(...)`. Existing overloads delegate with `false`.

**Web:**
- `POST /dining/orders/{id}/quote` — `QuoteOrderRequest` gains `boolean waiveServiceCharge`
  (defaults `false` for absent/legacy bodies).
- `POST /dining/orders/{id}/quote-split` — `QuoteSplitRequest` gains `boolean waiveServiceCharge`.

**Boundary:** self-contained in `dining`; no `allowedDependencies` change; no migration.

**Invariant (tested):** `quoteOrder(…, waive=true).grandTotal == closeOrder(…, waive=true).grandTotal`
and the split equivalent — a waived quote prices identically to a waived close (both call the same
`resolveApplyServiceCharge` + `sales.quote`/`sales.checkout`). Also: with SC enabled and DINE_IN,
`waive=true` yields a strictly smaller `grandTotal` than `waive=false`.

### Terminal (`pos-terminal`)

**`api/DiningApi`:**
- `quoteOrder(UUID orderId, DiscountInput transactionDiscount, boolean waiveServiceCharge)` — new
  overload sending `waiveServiceCharge` in the `POST .../quote` body.
- `quoteSplit(UUID orderId, QuoteSplitRequest req)` — the client `QuoteSplitRequest` DTO gains
  `boolean waiveServiceCharge`.
- **`closeSplit(UUID orderId, SplitCloseRequest req, String bearerToken)`** — new bearer-token
  overload (the plain `closeSplit(orderId, req)` has none today; `close` already has its token
  overload). The one-shot manager token must ride the split close.

**Terminal DTOs:** the client `QuoteOrderRequest` and `QuoteSplitRequest` gain a
`boolean waiveServiceCharge` field (mirroring the server request records). `CloseOrderRequest` and
`SplitCloseRequest` already carry `waiveServiceCharge`.

**Payment screen (`view/PaymentController`, whole-order):**
- New `private volatile boolean waiveServiceCharge;`
- A **"Waive service charge"** button (`waiveButton`), visible **only** when the order is dine-in
  (`mode != RETAIL`) **and** the loaded quote's `serviceChargeAmount > 0` (set in `onQuoteLoaded`).
- On tap: reuse `requestApprovalThen(...)` (the existing ManagerPinDialog → `pinLoginForToken` →
  `isManager` check → sets `pendingApprovalToken`). The approved action sets
  `waiveServiceCharge = true` and calls `requote()`. A removable **"Service charge waived"** chip
  (`waiveChipRow` + `removeWaiveButton`) shows while waived; Remove sets the flag false and re-quotes.
- `loadQuote()` passes the flag: `services.diningApi.quoteOrder(id, discount, waiveServiceCharge)`.
- `gatewayFor(...)` builds the `CloseOrderRequest` with `waiveServiceCharge` (and the existing
  `pendingApprovalToken`), so the approved waiver + token reach close.

**Split screen (`view/SplitController` + `viewmodel/SplitViewModel`):**
- `SplitViewModel` gains `setWaiveServiceCharge(boolean)` and a `setApprovalToken(String)` (the split
  VM currently has no manager-token path). `quoteSplit(orderId)` sends the flag in its
  `QuoteSplitRequest`; `closeAll(orderId)` builds the `SplitCloseRequest` with the flag and calls the
  new **token** overload `dining.closeSplit(orderId, req, token)` when a token is present (else the
  plain overload).
- `SplitController` gets a **"Waive service charge"** button in the **tender phase**, visible when the
  split quote shows a service charge (`quote.order()` / per-bill `serviceChargeAmount > 0`). On tap:
  `ManagerPinDialog.promptForApproval` → `pinLoginForToken` → `isManager` → `vm.setApprovalToken` +
  `vm.setWaiveServiceCharge(true)` → re-run `vm.quoteSplit(orderId)` and refresh the phase-2 amounts;
  show a "Service charge waived" chip. Remove → flag false + re-quote.

### Authorization & one-shot token

Waiver is **manager-only**, enforced server-side in `resolveApplyServiceCharge` at close/split-close.
The terminal reuses the proven one-shot manager token (`AuthApi.pinLoginForToken` → `ApiClient`
bearer override; a 401 on the overridden call never clears the cashier session). The token is
captured at waive-time and consumed by the close/split-close call. The quote is unauthenticated as
manager (cashier session) and returns the waived preview without a gate — safe because close rejects
an unapproved waiver.

### FX-threading (the recurring bug class)

Quote and close/split-close run inside `FxTasks.run` **work** lambdas; results are read in `onDone`
via holders; VMs write only `errorMessage` inside `ui.accept`; `onError` logs only (never `setText`
a bound label). No blocking VM call sits in an `onDone`. `SplitViewModel.quoteSplit`/`closeAll` stay
synchronous, returning plain booleans; `setWaiveServiceCharge`/`setApprovalToken` are plain setters
read on the calling thread. A `SplitViewModel` async-dispatcher regression covers the waived-quote
error path.

## Testing

- **Backend — `DiningServiceChargeWaiverQuoteTest`** (`@SpringBootTest @ActiveProfiles("embedded")
  @Import(DatabaseCleaner)`, seeding a product + enabling SC via the `configuration` store):
  - `quoteOrder(waive=true)` grandTotal == `closeOrder(waive=true, manager)` grandTotal;
  - `quoteOrder(waive=true)` grandTotal < `quoteOrder(waive=false)` when SC enabled + DINE_IN;
  - `quoteOrder(waive=true)` does **not** throw for a non-manager caller (ungated preview);
  - split: `quoteSplitEven(waive=true)` / `quoteSplitByItem(waive=true)` bills carry `serviceChargeAmount == 0` and sum to the waived order total.
- **Backend — endpoint tests** (extend the existing dining quote controller tests): `POST .../quote`
  and `POST .../quote-split` with `waiveServiceCharge=true` return a quote whose service charge is 0;
  a cashier JWT is accepted (quote is ungated).
- **Terminal — `DiningApiTest`:** `quoteOrder(...true)` sends `waiveServiceCharge:true` in the quote
  body; `quoteSplit(...)` with a waive-true request serializes the flag; `closeSplit(orderId, req,
  token)` sends the bearer header and body flag.
- **Terminal — `SplitViewModelTest`:** `setWaiveServiceCharge(true)` makes `quoteSplit` send the
  flag (stub captures the request); `closeAll` with a token uses the token overload; **async-dispatcher
  regression** on the waived-quote error path.
- **Manual E2E:** README slice-13 section — waive on the payment screen (manager PIN → SC drops →
  chip → pay), waive on the split screen, and the non-manager path (PIN rejected → no waiver).

## Out of scope

- **Retail** service charge (it is dine-in only — the retail quote/checkout force `applyServiceCharge=false`).
- **Partial** waiver (all-or-nothing), a per-reason waiver vocabulary, a waiver audit event.
- Any config/enum/migration change; any backend change outside `dining`'s quote path; new dependencies.

## Files touched

| File | Track | Change |
|------|-------|--------|
| `dining/api/DiningService.java` | BE | `quoteOrder`/`quoteSplitByItem`/`quoteSplitEven` waive overloads |
| `dining/application/DefaultDiningService.java` | BE | implement waive overloads (ungated preview) |
| `dining/web/DiningController.java` | BE | `QuoteOrderRequest`/`QuoteSplitRequest` gain `waiveServiceCharge` |
| `src/test/.../dining/DiningServiceChargeWaiverQuoteTest.java` (+ endpoint tests) | BE | new |
| `pos-terminal/.../api/DiningApi.java` | FE | `quoteOrder` waive overload, `quoteSplit` flag, `closeSplit` token overload |
| `pos-terminal/.../api/dto/QuoteOrderRequest.java`, `QuoteSplitRequest.java` | FE | `waiveServiceCharge` field |
| `pos-terminal/.../view/PaymentController.java` | FE | waive state + button + PIN + chip + thread into quote/close |
| `pos-terminal/.../viewmodel/SplitViewModel.java` | FE | `setWaiveServiceCharge`/`setApprovalToken` + thread into quote-split/close-split |
| `pos-terminal/.../view/SplitController.java` | FE | waive button + PIN + chip + re-quote |
| `pos-terminal/.../resources/fxml/payment.fxml`, `split.fxml` | FE | waive button + chip |
| `pos-terminal/.../resources/css/app.css` | FE | waive chip/button styling (reuse discount-chip tokens) |
| terminal tests: `DiningApiTest`, `SplitViewModelTest` | FE | as above |
| `pos-terminal/README.md` | FE | slice-13 manual E2E |

## Verification

Backend (root reactor): `./mvnw test -Dtest='com.company.pos.dining.*'` and
`./mvnw test -Dtest=ModularityTests`. Terminal: `./mvnw -f pos-terminal/pom.xml clean test`. Both
green; manual E2E per the README.
