# Slice 6 — Split Bill by Guest (Design)

**Date:** 2026-07-13
**Branch:** `feat/terminal-ui-restaurant-slice`
**Scope:** Dine-in split billing in the terminal (both BY_ITEM and EVEN modes) + the
quote-for-splits backend gap.

## Goal

At dine-in close, a cashier can split the bill: assign items to guests (BY_ITEM) or divide the
total N ways (EVEN). Each guest's amount shown before tendering is the **server-authoritative
quote** for exactly the partition being closed; all bills close in the backend's existing single
atomic `close-split` call.

## Decisions (settled during brainstorming)

| Decision | Choice |
|---|---|
| Modes | Both: BY_ITEM (assign lines to guests) and EVEN (N ways) |
| Discounts | None anywhere in the split flow (whole-order discounts remain on the single-bill payment screen; per-bill discounts are a later slice) |
| Quote gap | New mirror endpoint `POST /dining/orders/{id}/quote-split` sharing the close-split pricing code paths (approach A; client-side estimates and CartApi workarounds rejected) |
| Tenders per guest | Exactly one tender per bill/share this slice (BillInput's multi-tender list unused by the UI) |
| Navigation | New full-scene `Navigator.toSplit(orderId)` — the slice's one Navigator addition |

## Backend facts this design builds on (verified on the branch)

- `POST /dining/orders/{orderId}/close-split` takes `SplitCloseCommand(mode, bills, even,
  waiveServiceCharge)` and closes everything in ONE `@Transactional` call — any bill failing
  rolls back the whole split, order stays OPEN.
- BY_ITEM: each `BillInput(lineIds, tenders, lineDiscounts, transactionDiscount)` is priced via
  its own ephemeral cart (`closeByItem`), service charge applied **per bill**; validation:
  every order line in exactly one bill, no duplicates, ≥1 line per bill.
- EVEN: `EvenSplitInput(int ways, List<PaymentMethod> methods)` — one cart, one SaleView with N
  payments; base share = `grandTotal / ways` HALF_UP scale 2, **last share absorbs the
  remainder**; each tender is `(method, share, share)` so cash shares are exact-amount (no
  change); validation: ways ≥ 2, methods.size() == ways.
- Terminal `OrderLineView` already carries the line `UUID id` the BY_ITEM partition needs.

## Part 1 — Backend: `POST /dining/orders/{id}/quote-split`

### 1.1 API shape

Request mirrors `SplitCloseCommand` minus tenders/discounts:

```java
// dining.web request body
record QuoteSplitRequest(SplitMode mode, List<QuoteBillInput> bills, QuoteEvenInput even) { }
record QuoteBillInput(List<UUID> lineIds) { }
record QuoteEvenInput(int ways) { }
```

Response (new `dining.api` record — it aggregates sales' `QuoteView`s):

```java
public record SplitQuoteView(List<QuoteView> bills,   // BY_ITEM: one per bill, order-aligned; null for EVEN
                             QuoteView order,          // EVEN: whole-order quote; null for BY_ITEM
                             List<BigDecimal> shares)  // EVEN: per-share amounts, last absorbs remainder; null for BY_ITEM
{ }
```

### 1.2 Service

New `DiningService` methods:

```java
/** Prices a BY_ITEM partition exactly as closeOrderSplit would (per-bill carts + per-bill
 *  service charge). Pure calculator: creates no sale, closes nothing, order stays OPEN.
 *  Same partition validation as close so mistakes fail at quote time. */
SplitQuoteView quoteSplitByItem(UUID orderId, List<List<UUID>> billLineIds);

/** Prices an EVEN split: whole-order quote + the exact share amounts closeEven would tender. */
SplitQuoteView quoteSplitEven(UUID orderId, int ways);
```

The controller dispatches on `mode` and calls the matching method; these two methods are the
final API shape (no command-object variant).

### 1.3 Shared code paths (no drift by construction)

- Extract the per-bill partition-validation + ephemeral-cart construction out of `closeByItem`
  into private helpers reused by the quote path (`validatePartition(order, billLineIds)` and
  `cartForLines(order, lineIds)`).
- Extract the share math out of `closeEven` into a static helper
  `List<BigDecimal> evenShares(BigDecimal grandTotal, int ways)` used by both quote and close.
- Both quote paths call the existing `sales.quote(cartId, applyServiceCharge)` (no discounts)
  with the same `resolveApplyServiceCharge(order, false, false)` close uses.
- Plain `@Transactional`, NOT `readOnly` (ephemeral carts are writes); ephemeral carts are
  closed before returning.

### 1.4 Unchanged

`closeOrderSplit` behavior, `SplitCloseCommand`/`BillInput`/`EvenSplitInput` shapes, discount
and waiver enforcement. No schema change → no migration.

## Part 2 — Terminal: split screen

### 2.1 Entry + navigation (Navigator change — flagged)

- `OrderController` gains a **Split bill** secondary button beside Pay (same enable gate as
  Pay: order + catalog loaded, order has lines).
- New `Navigator.toSplit(UUID orderId)` full scene swap (pattern of every other screen).
  Cancel → `toOrder(orderId)`; after a successful split-close, Done → `toTableMap()`.

### 2.2 Split screen (SplitController + split.fxml + SplitViewModel)

Two phases on one scene:

**Phase 1 — partition:**
- Mode toggle: "By item" | "Split evenly" (`.mode-toggle`, ToggleButtons).
- BY_ITEM: guest tabs ("Guest 1", "Guest 2", "+ Guest" up to 6; starts at 2). The selected
  guest is active; tapping an order line assigns it to that guest (tapping an assigned line
  unassigns it). Each line chip shows name × qty and, when assigned, the guest number badge.
  An "Unassigned: N" counter; **Continue** enabled only when N == 0. Guests left with zero
  lines are dropped from the request (backend rejects empty bills).
- EVEN: a ways stepper 2–8.
- Continue → `POST quote-split` (tenders lock behind the fetch, slice-3/4/5 gate pattern).

**Phase 2 — tender per guest, then close:**
- One row per guest: the guest label, the **authoritative amount** from the quote-split
  response (`.money`, with the "✓ server" badge on the screen header once loaded), a
  Cash/Card/Wallet method choice, and — only for CASH on a BY_ITEM bill — a tendered field
  with live change preview. EVEN cash shares show "exact amount" (backend fixes
  tendered == share; no change).
- "Back" returns to phase 1 (assignments preserved); any partition change invalidates the
  quote and requires a new Continue.
- **Close all bills** enables when every guest has a valid tender (CASH tendered ≥ amount).
  One `POST /dining/orders/{id}/close-split`; on success show the per-guest result list
  (receipt number, total, change for cash bills) + success banner + "Done ▸ Tables". On
  failure (server rolls back everything) the error banner shows and phase 2 stays editable.

### 2.3 SplitViewModel (sync, injected `Consumer<Runnable> ui`, plain fields = truth)

- State: mode, ways, `Map<UUID, Integer> assignment` (lineId → guest index), quote result,
  per-guest tender entries.
- Pure logic, unit-tested: assignment toggling, unassigned count, guest-dropping,
  client-side validation mirroring the backend (all lines exactly once; ways ≥ 2),
  building `QuoteSplitRequest` and `SplitCloseRequest`, cash-short rejection per guest.
- Amounts NEVER computed client-side — bills/shares come only from the quote-split response.
- Async-dispatcher regression test included (the slice-2 lesson).

### 2.4 Terminal API + DTOs

```java
// dtos mirroring server JSON field-for-field
record QuoteSplitRequest(String mode, List<QuoteBillInput> bills, QuoteEvenInput even) { }
record QuoteBillInput(List<UUID> lineIds) { }
record QuoteEvenInput(int ways) { }
record SplitQuoteView(List<QuoteView> bills, QuoteView order, List<BigDecimal> shares) { }
record SplitCloseRequest(String mode, List<BillRequest> bills, EvenSplitRequest even,
        boolean waiveServiceCharge) { }
record BillRequest(List<UUID> lineIds, List<TenderInput> tenders,
        Map<String, DiscountInput> lineDiscounts, DiscountInput transactionDiscount) { }
record EvenSplitRequest(int ways, List<String> methods) { }
```

`DiningApi` gains:

```java
SplitQuoteView quoteSplit(UUID orderId, QuoteSplitRequest req);   // POST .../quote-split
List<SaleView> closeSplit(UUID orderId, SplitCloseRequest req);   // POST .../close-split
```

The split flow always sends `lineDiscounts = Map.of()`, `transactionDiscount = null`,
`waiveServiceCharge = false`.

### 2.5 CSS (tokens only)

New classes: `.mode-toggle`, `.guest-tab` (+ `:selected`), `.split-line` (+ assigned state),
`.guest-badge`, `.split-amount-row` — all from existing emerald tokens/derive(); tap targets
≥ 48px (guest tabs and line rows ≥ 56px).

## Part 3 — Testing

### Backend

- `DiningQuoteSplitTest` (service): BY_ITEM invariant — for a seeded order (BURGER 30.00 +
  FRIES 12.00 + WATER 5.00, 15% VAT) with service charge ON, each `quoteSplitByItem` bill
  grandTotal equals the corresponding `closeOrderSplit` sale grandTotal; EVEN invariant —
  `shares` equal the payment amounts `closeEven` produces (40.25 / 3 → 13.42, 13.42, 13.41);
  order remains OPEN after quoting; partition validation parity (duplicate line, missing line,
  empty bill, unknown id → same validation errors as close).
- Web test: `POST /dining/orders/{id}/quote-split` for both modes + anonymous 401.
- `ModularityTests`.

### Terminal (headless)

- `SplitViewModelTest`: toggle/assign/unassign, unassigned count, empty-guest dropping,
  request building for both modes, cash-short rejection, ways bounds, async-dispatcher test.
- `DiningApiTest` additions (StubServer): `quoteSplit` and `closeSplit` request bodies
  serialize mode/lineIds/ways/methods/tenders exactly; `SplitQuoteView` parses.
- `FxmlContractTest` + `AppCssTest`: new fx:ids and classes.
- Manual GUI E2E documented in `pos-terminal/README.md` (BY_ITEM 2 guests with cash change on
  one bill; EVEN 3 ways exact-cash; partition-error path; atomic-failure path).

## Out of scope (unchanged by this slice)

Discounts in any split path; multi-tender per bill; service-charge waiver; mixed mode
(some items assigned, remainder even); retail cart splits; per-guest receipts printing UI
beyond the existing server-side print-per-sale.
