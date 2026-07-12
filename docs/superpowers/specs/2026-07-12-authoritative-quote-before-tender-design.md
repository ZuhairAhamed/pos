# Authoritative Quote Before Tender — Design

**Date:** 2026-07-12
**Modules:** backend `sales` + `dining`; `pos-terminal/` JavaFX client
**Branch:** `feat/terminal-ui-restaurant-slice`

## Problem

Discovered by running the retail flow against the dev backend (15% VAT on): the
terminal is **rejected at checkout** with `400 "Tenders 39.00 do not match the
total 44.85"`. The terminal tenders the cart's **client-side pre-tax estimate**
as the payment amount, but the server requires the sum of tender amounts to cover
the tax- (and, for dine-in, service-charge-) inclusive `grandTotal`. The terminal
has no way to learn that authoritative total before charging (no quote endpoint is
exposed), so `PaymentViewModel` tenders `remaining() == estimatedTotal` (pre-tax).

The bug lives in the shared `PaymentViewModel`, so it affects **both** the retail
(`POST /sales`) and dine-in (`POST /dining/orders/{id}/close`) checkout paths under
any nonzero tax or an enabled service charge. Every automated test missed it
because tests drive a `StubServer` returning canned `SaleView` JSON — none performs
a real tax-applying checkout.

## Goal

The terminal must tender against the **authoritative** total, computed by the same
pricing path as checkout/close, for both retail and dine-in. Close the 400.

## Ground truth (verified)

- `SalesService.quote(UUID cartId)` and `quote(UUID cartId, boolean applyServiceCharge)`
  **already exist and are implemented** (`DefaultSalesService`): a read-only pricing
  pass — no sale, no payment, no event — returning
  `QuoteView(currencyCode, subtotal, discountTotal, serviceChargeAmount, taxTotal, grandTotal)`.
  `quote(cartId)` delegates with `applyServiceCharge = false`. Used today by dining
  EVEN-split. **Not exposed over HTTP.**
- Dining prices an order by building an **ephemeral cart** from its order lines
  (`carts.createCart()` + `carts.addLinePreResolved(sku, qty, mods)` per `OrderLine`,
  snapshotting modifier deltas) and calling `sales.quote(cartId, applyServiceCharge)`
  (EVEN split) or `sales.checkout(...)` (`closeOrder`). `closeOrder` resolves the
  service-charge flag via `resolveApplyServiceCharge(order, waiveServiceCharge, callerIsManager)`
  = `config.SERVICE_CHARGE_ENABLED && order.serviceType == DINE_IN && !waive`.
- `dining` already declares a dependency on `sales :: api` (it imports `SaleView`,
  `SalesService`, `QuoteView`), so adding a dining quote that returns `QuoteView`
  needs **no** `allowedDependencies` change and keeps `ModularityTests` green.
- Terminal `PaymentController` already carries a `Mode { DINE_IN, RETAIL }` and the
  target id (orderId or cartId), and constructs `PaymentViewModel` with the pre-tax
  `estimatedTotal`. `PaymentViewModel` (post prior fix) accumulates tenders in a plain
  `committed` list (synchronous source of truth) mirrored to an observable for chips.

## Architecture

No new pricing logic anywhere — we expose the existing `quote` and make the terminal
call it before tendering.

### Backend — `sales` module

`SalesController` (in `sales/web`) gains:

```
POST /sales/quote      body: { "cartId": "<uuid>" }      → 200 QuoteView
```

Handler calls `salesService.quote(cartId)` (service charge off — retail never
applies it, matching `POST /sales` which forces `applyServiceCharge=false`).
Authenticated; no manager gate (any cashier may quote). In-module call; no boundary
change.

### Backend — `dining` module

- `DiningService.api` gains `QuoteView quoteOrder(UUID orderId)`.
- `DefaultDiningService`: extract the ephemeral-cart snapshot loop currently inline
  in `closeOrder` into a shared private helper, e.g.
  `private UUID priceCartFor(DiningOrder order)` that creates a cart and adds one
  pre-resolved line per `OrderLine` (with snapshotted modifier inputs). **`closeOrder`
  is refactored to call this helper** so quote and close build the cart identically.
  `quoteOrder(orderId)`:
  1. `load(orderId)`, `requireOpen`, reject empty (same guards as `closeOrder`).
  2. `UUID cartId = priceCartFor(order);`
  3. `boolean applyServiceCharge = resolveApplyServiceCharge(order, false, false);`
     (waive=false, non-manager — the default charge the guest will pay).
  4. `QuoteView q = sales.quote(cartId, applyServiceCharge);`
  5. `carts.close(cartId);` (discard the ephemeral cart, mirroring `closeEven`).
  6. `return q;`
  Read-only w.r.t. the order (no `order.close`, no sale, no event).
- `DiningController` (in `dining/web`) gains:

```
GET /dining/orders/{orderId}/quote      → 200 QuoteView
```

Handler calls `diningService.quoteOrder(orderId)`. Authenticated.

Sharing `priceCartFor` + `resolveApplyServiceCharge(order, false, false)` guarantees
`quoteOrder(order).grandTotal` equals what `closeOrder` charges when the terminal
closes with `waiveServiceCharge=false` (the happy path).

### Terminal — `pos-terminal/`

- New DTO `api/dto/QuoteView` (`@JsonIgnoreProperties(ignoreUnknown = true)`):
  `record QuoteView(String currencyCode, BigDecimal subtotal, BigDecimal discountTotal,
   BigDecimal serviceChargeAmount, BigDecimal taxTotal, BigDecimal grandTotal)`.
- `SalesApi.quote(UUID cartId)` → `POST /sales/quote` body `{cartId}` → `QuoteView`.
- `DiningApi.quoteOrder(UUID orderId)` → `GET /dining/orders/{orderId}/quote` → `QuoteView`.
- `PaymentViewModel`: the tendered "due" becomes an **authoritative total** set after
  the quote returns, replacing the pre-tax estimate as the basis for `remaining()`:
  - Add `private BigDecimal authoritativeTotal; // null until quote loaded`.
  - `setAuthoritativeTotal(BigDecimal grandTotal)` — sets it, updates `remainingText`
    via `ui.accept`.
  - `remaining()` computes against `authoritativeTotal` when set; while it is null,
    tender actions (`addTender`/`payFull`/`finalizeSale`) no-op with a clear message
    ("Total not loaded yet") and make **no** gateway call.
  - The constructor still accepts the pre-tax `estimatedTotal` for an initial display
    hint only; it is never used as a tender amount.
- `PaymentController.initialize()`:
  1. Disable the Cash/Card/Wallet/Add-partial buttons; show a "Loading total…" state.
  2. `FxTasks.run` fetch the authoritative quote off the FX thread:
     retail → `services.salesApi.quote(cartId)`; dine-in → `services.diningApi.quoteOrder(orderId)`.
  3. On success (FX thread): populate authoritative subtotal / tax / service-charge /
     grand-total labels from the `QuoteView`, call `vm.setAuthoritativeTotal(q.grandTotal())`,
     enable the tender buttons.
  4. On failure: surface the error banner; tenders stay disabled (never charge against
     a stale estimate). `onError` LOGs; business errors flow through the bound
     `errorMessage`.
  The receipt still renders exclusively from the authoritative `SaleView` after
  checkout (unchanged).

## Data flow (retail; dine-in identical but via `quoteOrder`)

1. Charge → `PaymentController` (Mode.RETAIL, cartId, est).
2. `initialize` → tenders disabled → `salesApi.quote(cartId)` → `QuoteView{…, grandTotal=44.85}`.
3. Labels show authoritative subtotal/tax/grand total; `vm.setAuthoritativeTotal(44.85)`; tenders enabled.
4. Cashier taps Cash → `payFull("CASH", cash)` → tenders **44.85** (authoritative) → `POST /sales` → 201 `SaleView`.
5. Receipt renders from `SaleView`.

## Error handling

- Quote fetch fails (network / cart not open / empty) → error banner; tenders remain
  disabled; the cashier can go back. No checkout is attempted without a loaded total.
- All existing checkout error handling is unchanged.

## Testing

**Backend:**
- `SalesController` web test: `POST /sales/quote {cartId}` → 200 `QuoteView` with the
  cart's tax-inclusive `grandTotal`.
- `DiningController` web test: `GET /dining/orders/{id}/quote` → 200 `QuoteView`.
- **Invariant tests (the point of the fix):**
  - retail: for a cart, `salesService.quote(cartId).grandTotal()` equals the
    `grandTotal` of `salesService.checkout(...)` on the same cart.
  - dine-in: for an order, `diningService.quoteOrder(orderId).grandTotal()` equals the
    `grandTotal` of `closeOrder(orderId, waiveServiceCharge=false)` on an equivalent order.
  Both asserted with tax enabled (and service charge enabled for the dine-in case) so
  the totals are non-trivial.
- Full backend suite + `ModularityTests` stay green.

**Terminal (headless, StubServer):**
- `SalesApi.quote` posts to `/sales/quote` with `cartId` body and parses `QuoteView`.
- `DiningApi.quoteOrder` GETs `/dining/orders/{id}/quote` and parses `QuoteView`.
- `PaymentViewModel`: `setAuthoritativeTotal` drives `remaining()`; a tender attempted
  before the total is set makes no gateway call and surfaces the "not loaded" message;
  after `setAuthoritativeTotal`, `payFull` tenders the authoritative amount (assert the
  amount passed to the gateway equals `grandTotal`, not the estimate).
- Existing 89 terminal tests stay green.

## Out of scope

- Discounts at quote time (retail quote passes no discounts; matches `POST /sales` in
  this slice). Manager-override / discounts remain the deferred advanced-actions follow-up.
- Showing a live authoritative total in the **cart** (the cart stays a client-side
  "est."); authoritative numbers appear on the payment screen and receipt.

## Success criteria

1. Retail: login → add items → Charge → the payment screen shows the authoritative
   grand total and a cash tender **completes** (no 400) against a VAT-enabled backend,
   producing a `SaleView` receipt.
2. Dine-in: the same, via `quoteOrder`, with service charge applied.
3. `quote`/`quoteOrder` grandTotal provably equals the corresponding checkout/close
   grandTotal (invariant tests green).
4. `./mvnw verify` (backend, incl `ModularityTests`) and
   `./mvnw -f pos-terminal/pom.xml clean test` both green.
