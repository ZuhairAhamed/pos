# Returns & Refunds — Design Spec

**Slice:** Terminal slice 19 (**full-stack** — one new read-only backend endpoint + terminal UI)
**Branch:** `feat/terminal-ui-restaurant-slice`
**Date:** 2026-07-24
**Status:** Approved design, pending implementation plan

## Problem

The backend has a complete Returns & Refunds capability — `POST /returns` (process a return against a
prior sale) and `GET /returns/{returnId}`, both MANAGER-gated, with full refund/tax/tender-allocation
logic (`DefaultReturnService`: proportional net/tax refund, allocation across the original tenders,
`ReturnCompleted` event → inventory reversal + ERP outbox, best-effort credit-note print). But the
JavaFX terminal surfaces **none** of it: there is no returns screen, no `ReturnApi`, no way to refund a
customer. A cashier cannot process a return at all.

There is also a structural gap that blocks the natural workflow: **the only way to fetch a completed
sale is `GET /sales/{saleId}` by UUID.** There is no lookup-by-receipt-number and no sales search. A
real return starts from the printed **receipt number** a customer brings back — which nothing today can
resolve to a sale. This slice closes both gaps: it adds a minimal receipt lookup and builds the
terminal returns flow on top of the existing returns engine.

## Scope decisions (locked)

| Decision | Choice |
|---|---|
| Slice shape | **Full-stack** — one new backend read endpoint (`GET /sales/by-receipt/{receiptNumber}`) + terminal UI. `POST /returns` already exists and is unchanged. |
| Reach the sale | **Receipt-number lookup** — type the receipt number, fetch the sale, then select lines. |
| Who initiates | **Cashier-initiated, manager approves.** A `Returns` tile on **Home, visible to all** signed-in users. Any cashier can look up + stage; the refund requires a **manager PIN** at submit. |
| Lookup auth | **Authenticated** (any signed-in user) — matches the codebase's "preview ungated, enforcement gated" principle. |
| Refund enforcement | `POST /returns` keeps its existing **`@PreAuthorize("hasRole('MANAGER')")`** gate, satisfied by the one-shot manager-PIN token. |
| Refund tender | **Not a UI choice** — the backend allocates the refund across the *original* sale's tenders (cash → drawer pay-out, card/wallet → terminal refund). The UI only *displays* the resulting breakdown. |
| Partial returns | **Per-line return quantity** (0..sold, `BigDecimal`). Over-return / re-return beyond the cumulative sold quantity fails atomically server-side and surfaces as an error. |

## Architecture

Full-stack, but the backend change is a single additive read endpoint — no schema change, no Flyway
migration (Flyway version ceiling is untouched), no new module dependency (all within the `sales`
module), so `ModularityTests` is unaffected. Everything else is terminal-side and follows the
established conventions: Controller + synchronous ViewModel + `FxTasks` off-thread I/O; the only
off-thread observable a VM writes is `errorMessage` inside the injected `Consumer<Runnable> ui`; new
DTOs are `@JsonIgnoreProperties(ignoreUnknown = true)`; the manager gate rides the one-shot bearer
token via the `ApiClient` override overload.

### Backend change (minimal, additive)

`GET /sales/by-receipt/{receiptNumber}` on `SalesController`, returning the existing `SaleView`.
- **Auth:** authenticated only (no `@PreAuthorize` role gate) — same as `GET /sales/{saleId}`. A cashier
  may retrieve a sale to *stage* a return; the MANAGER gate lives only on `POST /returns`.
- **Service:** add `findByReceiptNumber(String receiptNumber)` to the sales query service, backed by a
  derived repository query (receipt numbers are unique per store — `{storeId}-{terminalId}-{seq}`).
- **Not found:** `404` when no sale matches (surfaced on the terminal as "No sale found for that
  receipt").
- No change to `POST /returns`, `ReturnCommand`, or `ReturnView`.

### Backend DTO shapes (for reference — already exist)

```
SaleView(UUID id, String receiptNumber, String status, String currencyCode,
         BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal, Instant createdAt,
         List<SaleLineView> lines, List<SalePaymentView> payments,
         BigDecimal discountTotal, BigDecimal txnDiscountAmount, String txnDiscountType,
         String txnDiscountReason, BigDecimal serviceChargeAmount)
SaleLineView(int lineNo, String sku, String name, BigDecimal quantity, BigDecimal unitPrice,
             BigDecimal netAmount, BigDecimal taxAmount, BigDecimal lineTotal, String currencyCode,
             BigDecimal grossAmount, BigDecimal lineDiscountAmount, String lineDiscountType,
             String lineDiscountReason, List<SaleLineModifierView> modifiers)

ReturnCommand(UUID originalSaleId, String receiptNumber, List<ReturnLineRequest> lines)
ReturnLineRequest(int lineNo, BigDecimal quantity)          // quantity > 0; cumulative <= sold
ReturnView(UUID id, String creditNoteNumber, UUID originalSaleId, String status, String currencyCode,
           BigDecimal refundSubtotal, BigDecimal refundTaxTotal, BigDecimal refundGrandTotal,
           Instant createdAt, List<SaleReturnLineView> lines, List<ReturnPaymentView> refunds)
SaleReturnLineView(int lineNo, String sku, String name, BigDecimal quantity, BigDecimal unitPrice,
                   BigDecimal netAmount, BigDecimal taxAmount, BigDecimal lineTotal, String currencyCode)
ReturnPaymentView(String method, BigDecimal amount, String maskedPan)
```

## Terminal changes

### DTO fix
Add `lineNo` (int) and `unitPrice` (BigDecimal) to the terminal `SaleLineView`
(`pos-terminal/.../api/dto/SaleLineView.java`). `lineNo` is **required** to build
`ReturnLineRequest(lineNo, quantity)` — the terminal DTO currently drops it. `unitPrice` is for
per-row display. Existing consumers of `SaleLineView` (payment success screen) are unaffected (added
fields, `@JsonIgnoreProperties`).

### New terminal DTOs (`api/dto/`, all `@JsonIgnoreProperties(ignoreUnknown = true)`)
- `ReturnCommand(UUID originalSaleId, String receiptNumber, List<ReturnLineRequest> lines)`
- `ReturnLineRequest(int lineNo, BigDecimal quantity)`
- `ReturnView(...)`, `SaleReturnLineView(...)`, `ReturnPaymentView(String method, BigDecimal amount,
  String maskedPan)` — mirroring the backend fields above.

### New API clients
- **`SalesApi.getSaleByReceipt(String receiptNumber)`** → `GET /sales/by-receipt/{receiptNumber}`
  (session auth). A `404`/miss throws `ApiException`; the VM turns it into "No sale found".
- **`ReturnApi`** (new, `public` non-final, one `ApiClient`): `ReturnView process(ReturnCommand cmd,
  String managerToken)` → `client.post("/returns", cmd, ReturnView.class, managerToken)` using the
  bearer-override overload. Returns is **always** manager-gated, so the terminal always passes the
  token. Register `public final ReturnApi returnApi;` in `Services`.

### ReturnsViewModel (synchronous)
Holds `SalesApi`, `ReturnApi`, `AuthApi`; ctors `(SalesApi, ReturnApi, AuthApi)` and
`(SalesApi, ReturnApi, AuthApi, Consumer<Runnable> ui)`. No `clock` (no time math).
- `SaleView lookup(String receiptNumber)` — returns the sale, or `null` on failure (sets
  `errorMessage`).
- `ReturnView process(UUID saleId, List<ReturnLineRequest> lines, String cashierCode, String pin)` —
  synchronously: `ManagerAuth auth = authApi.pinLoginForToken(cashierCode, pin)`; if
  `!auth.isManager()` set `errorMessage("This account is not a manager")` and return `null`; else
  `returnApi.process(new ReturnCommand(saleId, null, lines), auth.token())`. Returns the `ReturnView`
  or `null` on failure. The only off-thread observable write is `errorMessage`, inside `ui.accept(...)`.
- A **deferred-dispatcher regression test** proves the synchronous `null` return precedes the queued
  `errorMessage` write.

### Screen & flow — `returns.fxml` + `ReturnsController`, `Navigator.toReturns()`
`ReturnsController implements Navigator.Screen`. Three regions:

1. **Lookup bar** — a receipt-number `TextField` + a "Find sale" `Button`. On action, the controller
   runs `vm.lookup(receiptNumber)` in the `FxTasks` **work** lambda (into a holder) and renders the
   sale in `onDone`. Empty/miss → error text, no crash.
2. **Stage area** (shown once a sale is found) — a header (receipt #, `createdAt`, grand total) and a
   `TableView` of the sale's lines: columns **Item** (name), **SKU**, **Qty sold**, **Line total**, and
   an editable **Return qty** per row (`BigDecimal`, validated/clamped to `[0, soldQty]`). A **"Return
   all"** convenience sets each row to its full sold quantity. A client-side **≈ estimated refund**
   label sums the selected rows' proportional line totals — *informational only*; the server's
   `ReturnView` is authoritative on the result screen. Money renders `amount + " " + currencyCode`.
3. **Process** — a "Process return" `Button`. On action (FX thread): guard that ≥1 row has qty > 0;
   collect the PIN via `ManagerPinDialog.promptForApproval("Manager approval required to process this
   return")` (a modal — UI, not I/O). If cancelled, no-op. Otherwise the `FxTasks` **work** lambda calls
   `vm.process(saleId, lines, creds.cashierCode(), creds.pin())` (which chains `pinLoginForToken` +
   `POST /returns` off-thread), and `onDone` shows the result. **No blocking VM call in `onDone`.**
4. **Result** — replace the stage area with the credit note: `creditNoteNumber`, authoritative
   `refundGrandTotal` (+ subtotal/tax), and a per-tender **refund breakdown** table
   (`method`, `amount`, `maskedPan`). A "Done" button returns to Home; a "New return" button resets the
   screen to the lookup bar. (No reprint button — the backend already best-effort prints the credit
   note when the return commits.)

### Navigation & entry
`Navigator.toReturns()` mirrors the existing routes. A **Returns** `home-tile` `Button` is added to the
Home tile row, wired `navigator.toReturns()` and **visible to all** signed-in users (no role gate on the
tile — the manager gate is enforced at submit). Back button → `navigator.toHome()`.

## Error handling

- **Lookup miss / bad receipt** → `ApiException` (404) → friendly "No sale found for that receipt";
  screen stays on the lookup bar.
- **Non-manager PIN** → the VM's `isManager()` check fails → "This account is not a manager"; the
  cashier session is untouched (the 401/override semantics never clear the session).
- **Over-return / stale or already-returned lines** → the server rejects atomically (the sale is
  unchanged, no partial refund) → the message surfaces as `errorMessage`; the staged selection stays so
  the user can correct quantities and retry.
- **Cancelled PIN dialog** → no-op (nothing submitted).
- **Empty selection** (no row with qty > 0) → the "Process return" guard blocks submit with a hint; no
  request is sent.

## Testing strategy

**Backend** (`./mvnw test`, embedded profile):
- A `SalesController`/web test for `GET /sales/by-receipt/{receiptNumber}`: seeds a committed sale,
  asserts a `200` returns the matching `SaleView` (right `id`/`receiptNumber`/lines), and a `404` for an
  unknown receipt. Existing return-service tests already cover `POST /returns` behavior.
- `ModularityTests` — unchanged (no new module dependency); re-run to confirm.

**Terminal** (`./mvnw -f pos-terminal/pom.xml test`, headless):
- **`ReturnsViewModelTest`** — fake `SalesApi`/`ReturnApi`/`AuthApi` (anonymous subclasses):
  `lookup` returns the sale; error path sets `errorMessage`; `process` happy path returns the
  `ReturnView`; a non-manager `ManagerAuth` → `errorMessage` + `null` (no `POST /returns` issued); an
  `ApiException` from `process` → `errorMessage`; and an **async-dispatcher regression test** with a
  deferred, undrained `ui` (assert the synchronous `null` return before draining, `errorMessage` only
  after).
- **`SalesApiGetByReceiptTest`** (StubServer) — `getSaleByReceipt` hits `/sales/by-receipt/<n>` and
  parses the `SaleView`; a non-2xx throws.
- **`ReturnApiTest`** (StubServer) — `process` POSTs to `/returns`, serializes the `ReturnCommand`
  (`originalSaleId` + `lines[lineNo,quantity]`), sends the manager bearer token, and parses the
  `ReturnView`.
- **FXML/CSS** — the existing `FxmlContractTest` (every `@FXML` ↔ `fx:id`) and `AppCssTest` cover the
  new `returns.fxml` and any new style block.

## Non-goals (YAGNI — deliberately deferred)

A returns-history / list screen; lookup by customer or by date; a credit-note **reprint** button (the
backend prints best-effort on commit; `GET /returns/{id}` is left unwrapped); **exchanges**
(return-then-resell in one flow); any refund-method override (tender allocation is server-owned); UI
awareness of prior partial returns on a line (the server enforces the cumulative-quantity cap and the
`SaleView` carries no returned-so-far figure — the UI cannot pre-disable exhausted lines, and a
re-return simply fails atomically); any sales **search** beyond exact receipt-number match; ZATCA/fiscal
credit-note formatting.

## Terminal-convention checklist (must hold)

- ViewModel synchronous; controller runs it off-thread via `FxTasks.run`; results read only in the
  FX-thread `onDone` (holder pattern). The only off-thread observable write is `errorMessage` inside
  `ui.accept`. **No blocking VM method is called inside `onDone`** — the PIN dialog is collected on the
  FX thread *before* the task, and `pinLoginForToken` + `POST /returns` are chained in the same `work`
  lambda.
- Manager approval rides **exactly one** call via the `ApiClient` bearer-override overload
  (`returnApi.process(cmd, token)`); a 401 on that call never clears the cashier session.
- New Api classes are `public` (non-final, so VM tests subclass with fakes), hold one `ApiClient`,
  delegate via `client.get(...)` / `client.post(...)`.
- New DTOs `@JsonIgnoreProperties(ignoreUnknown = true)`; money is `BigDecimal` + `currencyCode`, never
  `double`; status/emphasis uses colour + text.
- Backend: no schema change / no Flyway migration; no new module dependency (`ModularityTests` green).
