# Slice 5 — Transaction Discount + Manager Approval (Design)

**Date:** 2026-07-13
**Branch:** `feat/terminal-ui-restaurant-slice`
**Scope:** Both tracks (retail sale + dine-in close). Terminal UI + the discount-aware-quote backend gap.

## Goal

A cashier can apply one whole-bill (transaction) discount on the payment screen — percent or
amount, with a reason code — and the big total is always the **discounted, server-authoritative
quote** before tendering. Discounts above the cashier cap are approved by a manager entering
their code + PIN in a one-shot modal; the manager's token is used for exactly one HTTP call and
the cashier stays signed in.

## Decisions (settled during brainstorming)

| Decision | Choice |
|---|---|
| Tracks | Both: retail (`POST /sales`) and dine-in close (`POST /dining/orders/{id}/close`) |
| Granularity | Transaction discount only; per-line stays a later slice |
| UI placement | Shared payment screen (one implementation for both tracks) |
| Policy source | New `GET /sales/discount-policy` endpoint |
| Cap enforcement | Quote is a **pure calculator** (prices any discount); checkout/close remain the sole enforcement point |
| Approval UX | One-shot manager PIN modal via existing `POST /auth/pin-login`; per-request bearer override |

## Part 1 — Backend

### 1.1 Discount-aware `SalesService.quote`

New overload on `sales :: api` `SalesService`:

```java
QuoteView quote(UUID cartId,
                Map<String, DiscountInput> lineDiscounts,
                DiscountInput transactionDiscount,
                boolean applyServiceCharge);
```

- Routes through the existing `priceDiscountTax` → `DiscountCalculator.apply` with
  `callerIsManager = true`: quote commits nothing, so the cashier cap is **not** enforced here.
- Reason codes **are** still validated (same `DISCOUNT_REASON_CODES` check as checkout) — a bad
  reason fails fast at quote time.
- Existing `quote(UUID)` / `quote(UUID, boolean)` delegate with `Map.of()` / `null`.
- `QuoteView` is unchanged in shape; its `discountTotal` field becomes non-zero when a discount
  is passed. Tax and service charge compute on the discounted base exactly as checkout does
  (shared code path — no duplicated math).

### 1.2 `POST /sales/quote` request extension

`QuoteRequest` grows two optional fields (backward compatible — nulls normalize to empty):

```java
record QuoteRequest(UUID cartId,
                    Map<String, DiscountInput> lineDiscounts,
                    DiscountInput transactionDiscount) { }
```

### 1.3 Dining: `POST /dining/orders/{id}/quote`

- New endpoint accepting a body; the existing `GET /dining/orders/{id}/quote` stays as the
  no-discount convenience.

```java
record QuoteOrderRequest(Map<String, DiscountInput> lineDiscounts,
                         DiscountInput transactionDiscount) { }
```

- New `DiningService.quoteOrder(UUID orderId, Map<String, DiscountInput> lineDiscounts,
  DiscountInput transactionDiscount)` overload threading through `priceCartFor` into the new
  `sales.quote(...)` overload, with the same service-charge resolution as the existing
  `quoteOrder`. This resolves the documented no-discount caveat on `quoteOrder`.
- No `waiveServiceCharge` in the quote body — waiver UI stays out of scope.

### 1.4 `GET /sales/discount-policy`

New endpoint in `sales.web`, any authenticated user:

```java
record DiscountPolicyView(BigDecimal cashierMaxPercent,
                          BigDecimal cashierMaxAmount,
                          List<String> reasonCodes) { }
```

Values read from the configuration store keys already used by checkout:
`DISCOUNT_CASHIER_MAX_PERCENT` (default 10), `DISCOUNT_CASHIER_MAX_AMOUNT` (default 20.00),
`DISCOUNT_REASON_CODES` (default `DAMAGED,PRICE_MATCH,LOYALTY,MANAGER_COMP`).

### 1.5 Unchanged (deliberately)

- Checkout/close enforcement: `DiscountCalculator` still throws
  "Discount exceeds cashier limit; manager approval required" unless the **JWT of the checkout
  caller** carries `ROLE_MANAGER`. The server is the only safety authority.
- `DiscountOverridden` audit event keeps firing on manager over-cap checkouts.
- No schema change → no Flyway migration.

## Part 2 — Terminal UI (payment screen, shared by both tracks)

### 2.1 Discount button + applied-discount chip

- Secondary **Discount** button (56px) beside the totals block. Gated like the tenders:
  disabled until the first quote loads, and **disabled permanently once any tender has been
  added** (the total must not change mid-split).
- Applying a discount re-fetches the quote **with** the discount (tenders re-lock until it
  lands, reusing the slice-4 quote gate), then:
  - total + "✓ server" badge update to the discounted figure,
  - a removable chip row appears: `Discount −12.50 · LOYALTY  ✕`.
- Tapping ✕ re-quotes without the discount and hides the row.
- The estimate line ("Estimate at order: …") is untouched — it never reflects discounts.

### 2.2 DiscountDialog (pure view, StartShiftDialog pattern)

- Percent / Amount toggle (percent default), numeric pin-pad, reason chips built from the
  policy endpoint's `reasonCodes` (one must be selected), Apply / Cancel.
- Live amber hint when the entry exceeds the cashier cap **and** the signed-in user is not a
  manager: `⚠ Needs manager approval at payment`. The over-cap test mirrors the server's:
  discount amount > `cashierMaxAmount` OR effective percent > `cashierMaxPercent`, computed
  against the current quoted subtotal (post-line-discount base). Static, unit-testable helper.
- Returns `Optional<DiscountInput>`; no server access inside the dialog.

### 2.3 Typed discount DTOs

- New terminal DTO `DiscountInput(String type, BigDecimal value, String reasonCode)` with
  `type ∈ {"PERCENT","AMOUNT"}`, mirroring the server record field-for-field.
- `CheckoutRequest.transactionDiscount` and `CloseOrderRequest.transactionDiscount` change from
  opaque `Object` to `DiscountInput`.
- New `DiscountPolicyView(BigDecimal cashierMaxPercent, BigDecimal cashierMaxAmount,
  List<String> reasonCodes)` DTO + `SalesApi.discountPolicy()`.
- `SalesApi.quote(UUID cartId, DiscountInput transactionDiscount)` overload (POST body gains the
  field) and `DiningApi.quoteOrder(UUID orderId, DiscountInput transactionDiscount)` overload
  hitting the new POST endpoint. Existing no-discount methods unchanged.

### 2.4 Controller/VM wiring

- `PaymentController` owns the current discount (plain volatile field — control-flow state,
  not an observable) and the fetched policy (loaded once with the initial quote).
- The `CheckoutGateway` closures include the current discount in the request bodies.
- `PaymentViewModel` is unchanged except that `setAuthoritativeTotal` is simply called again on
  re-quote (already supported).

## Part 3 — Manager approval (one-shot token)

### 3.1 Flow

1. Cashier taps a tender. Controller checks locally: applied discount over policy caps AND
   `!session.isManager()` → open **ManagerPinDialog** (manager code + PIN, pin-pad).
2. Controller calls new `AuthApi.pinLoginForToken(cashierCode, pin)` → `POST /auth/pin-login`,
   returns the raw JWT **without mutating `SessionManager`** (the cashier stays signed in).
   Wrong PIN / non-manager account → error shown, dialog re-opens.
3. The manager token rides **exactly one call** — the checkout/close POST — via a per-request
   bearer override on `ApiClient` (e.g. an overload taking an optional token). It is held in a
   local variable for that call and discarded; never stored in the session.
4. Cancel in the dialog → back to the payment screen, nothing tendered.

### 3.2 Defensive server-rejection path

If checkout/close returns the "exceeds cashier limit" validation error anyway (stale local
policy), the controller opens the same ManagerPinDialog and retries the checkout with the
manager token. The tender list is preserved (checkout never partially commits — it either
creates the sale or throws).

### 3.3 Non-manager PIN

If the PIN login succeeds but the account lacks `ROLE_MANAGER`, the retried checkout will be
rejected by the server. To fail earlier, `pinLoginForToken` also fetches `/auth/me` with the
override token and returns token + roles; the controller rejects non-manager approvals in the
dialog with "This account is not a manager".

## Part 4 — Testing

### Backend (mirror existing test classes)

- `SalesQuoteDiscountTest` (pattern: `SalesQuoteTest`): percent and amount transaction
  discounts priced correctly; tax on discounted base; **over-cap discount quotes fine without
  manager role**; invalid reason code rejected; cart remains OPEN.
- Invariant test: `quote(cartId, discount).grandTotal == checkout(…same discount…).grandTotal`
  (manager caller), retail track.
- `DiningQuoteDiscountTest` (pattern: `DiningQuoteServiceTest`): dine-in
  `quoteOrder(orderId, discount).grandTotal == close(…same discount…).grandTotal`, service
  charge included; order stays OPEN after quote.
- Web tests: `POST /sales/quote` with discount body; `POST /dining/orders/{id}/quote`;
  `GET /sales/discount-policy` returns configured values (jwt() pattern from existing web tests).
- `ModularityTests` after any cross-module change.

### Terminal (headless — StubServer / sync VMs / resource contracts; no TestFX)

- `SalesApiTest` / `DiningApiTest`: quote-with-discount request bodies serialize the
  `transactionDiscount` field exactly; `discountPolicy()` parses the view.
- `ApiClient` token override: StubServer asserts the `Authorization: Bearer <override>` header
  on the overridden call and the session token on the next call.
- `AuthApiTest`: `pinLoginForToken` returns token+roles and does **not** mutate
  `SessionManager` (token, username, roles all unchanged).
- Dialog helpers: needs-approval math (boundary: exactly at cap = no approval; just over = 
  approval), amount/percent parse, reason-required validation.
- `FxmlContractTest` + `AppCssTest`: new fx:ids (`discountButton`, `discountChipRow`, …) and new
  CSS classes (`.discount-chip`, `.approval-hint`, …) present; tokens-only styling.

### Manual GUI E2E (documented in README, human-run)

Retail: apply 5% (under cap) → no PIN → paid. Apply 15% (over cap) → amber hint → PIN modal at
tender → manager approve → paid; cashier still signed in after. Dine-in: same over-cap path at
close. Remove-discount ✕ re-quotes to the undiscounted total.

## Out of scope (unchanged by this slice)

Per-line discounts in the terminal; service-charge waiver UI; discounts on EVEN split; per-bill
discounts in a future split UI; discount UI anywhere other than the payment screen.
