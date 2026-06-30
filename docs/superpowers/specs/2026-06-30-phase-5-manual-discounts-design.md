# Phase 5 — Manual Discounts: Design

**Status:** Approved (brainstorming complete)
**Date:** 2026-06-30
**Branch:** `phase-5-manual-discounts` (cut from `phase-4-returns-refunds`)
**Module:** extends `sales` (no new module), mirroring how Phase 4 (Returns & Refunds) extended it.

## Goal

Let an operator apply **manual discounts** during checkout — at the **line** level
and the **transaction** level, as a **percentage** or a **fixed amount** — with a
**role-based cap** (cashier up to a configured limit, manager unlimited), a
**required reason code**, and a fully **itemized** audit trail that flows through
tax, persistence, the receipt, the ERP upload, and returns.

This is *manual* discounting only. A rule-based / automatic promotions engine
(coupons, "buy 2 get 1 free", category/date rules, stacking precedence) is
explicitly **out of scope** and is a natural later phase that can build on the
line/transaction discount model introduced here.

## Locked Decisions

| # | Decision | Choice |
|---|----------|--------|
| 1 | Discount family | **Manual** discounts only (no automatic promotions engine) |
| 2 | Level | **Both** line-level and transaction-level |
| 3 | Form | **Both** percentage and fixed amount |
| 4 | Authorization | **Role-based cap**: cashier up to a configured limit, manager unlimited |
| 5 | Where applied | **At checkout time**, carried in `CheckoutCommand` (cart stays "dumb") |
| 6 | Recording | **Itemized** — original amount + discount detail persisted; receipt + ERP carry the breakout |
| 7 | Reason | **Required reason code** from a configured fixed set |

## Architecture

The only structural change to the sell pipeline is a **discount step inserted
between pricing and tax** in `DefaultSalesService.checkout`:

```
price → APPLY DISCOUNTS (line, then allocate transaction) → tax (on discounted nets) → tenders → persist
```

Two consequences fall out for free:

- **Tax is correct automatically.** Discounts reduce each line's extended price
  *before* `TaxService` runs, so VAT is computed on the discounted base with no
  change to the tax module.
- **Returns are correct automatically.** The persisted `SaleLine.netAmount` is
  already post-discount, and Phase 4 refunds key off the persisted
  net/tax/lineTotal — so returning a discounted line refunds the discounted
  amount with **zero changes to the returns code**.

All new domain logic lives in the `sales` module. A dedicated, unit-testable
`DiscountCalculator` holds the money math (resolve, cap, allocate) so
`DefaultSalesService` stays an orchestrator.

## Components

### `sales.api` — command surface
- **`DiscountType`** (enum): `PERCENT`, `AMOUNT`.
- **`DiscountInput`** (record): `{ DiscountType type, BigDecimal value, String reasonCode }`.
- **`CheckoutCommand`** gains two fields:
  - `Map<String, DiscountInput> lineDiscounts` — keyed by **SKU** (cart lines are
    unique per SKU; see `DefaultCartService.findLine(sku)`). Nullable/empty = none.
  - `DiscountInput transactionDiscount` — nullable.

### `sales.application` — `DiscountCalculator`
Pure logic, no persistence. Given the priced lines, the discount inputs, the
caller's manager flag, and the config caps/reason-codes, it returns the
per-line discounted nets plus the resolved transaction discount, or throws a
`DomainException` (validation/forbidden) on a bad reason code or a cap breach.

Responsibilities:
1. Validate every `reasonCode` against the configured set.
2. Resolve each discount to an amount against its base (see Cap Math).
3. Enforce the role cap per discount.
4. Allocate the transaction discount proportionally across lines.

### `sales.application` — `DefaultSalesService.checkout`
Wires the calculator into the pipeline and passes the caller's role. The
controller (`web`) extracts whether the authenticated principal holds
`ROLE_MANAGER` and passes it as `callerIsManager`; the endpoint stays open to
both `CASHIER` and `MANAGER` so an over-cap attempt is a clean domain
rejection, not an HTTP 403 wall.

### `sales.domain` — persistence
`Sale` and `SaleLine` gain discount fields (see Schema). `SaleLine.netAmount`
keeps its meaning as the taxable net, now post-discount.

## Data Model / Schema

Migration `V18__sales_discount.sql` (PostgreSQL / store-server profile). The
embedded SQLite profile applies the same columns via Hibernate `ddl-auto=update`.
Existing rows predate discounts and backfill cleanly.

**`sale_line` — add:**

| Column | Type | Meaning |
|--------|------|---------|
| `gross_amount` | NUMERIC(19,2) | qty × unitPrice, pre-discount. Backfill existing rows = `net_amount`. |
| `line_discount_amount` | NUMERIC(19,2) NOT NULL DEFAULT 0 | line-level discount only |
| `line_discount_type` | VARCHAR(8) NULL | `PERCENT` \| `AMOUNT` |
| `line_discount_reason` | VARCHAR(32) NULL | reason code; null if no line discount |

The discount reduces the line's **extended** amount to
`discountedExtended = gross_amount − line_discount_amount − allocated_txn_share`.
`TaxService` then derives `net_amount` / `tax_amount` / `line_total` from
`discountedExtended` exactly as it does today, for **both** tax modes:
- tax-exclusive → `net_amount = discountedExtended`, `tax = net × rate`;
- tax-inclusive → `net_amount = discountedExtended / (1 + rate)`,
  `tax = discountedExtended − net_amount`, `line_total = discountedExtended`.

So discounting plugs in upstream of tax and the tax module is unchanged. The
allocated transaction share per line is recoverable
(`gross − line_discount − discountedExtended`), so it needs no column.

**`sale` — add:**

| Column | Type | Meaning |
|--------|------|---------|
| `txn_discount_amount` | NUMERIC(19,2) NOT NULL DEFAULT 0 | resolved transaction discount |
| `txn_discount_type` | VARCHAR(8) NULL | `PERCENT` \| `AMOUNT` |
| `txn_discount_reason` | VARCHAR(32) NULL | reason code |
| `discount_total` | NUMERIC(19,2) NOT NULL DEFAULT 0 | Σ line discounts + txn discount |

**Reconciliation invariant** (mode-agnostic; asserted in tests):
`Σ gross_amount − Σ line_discount_amount − txn_discount_amount = Σ discountedExtended`,
and `Σ net_amount + Σ tax_amount = grand_total`. (In tax-exclusive mode
`discountedExtended = net_amount`; in tax-inclusive mode
`discountedExtended = line_total`.)

## Cap Math & Allocation

### Resolve a discount to amount `D` against base `B`
- `PERCENT`: `D = B × value/100`, scale 2, HALF_UP. Require `0 < value ≤ 100`.
- `AMOUNT`: `D = min(value, B)` — capped to the base so it can never exceed its
  target. Require `value > 0`.
- Base `B`:
  - line discount → that line's `gross_amount`;
  - transaction discount → `Σ(gross_amount − line_discount_amount)` over all lines
    (the cart total *after* line discounts).

### Role cap (evaluated per discount)
`effectivePercent = D / B × 100` (guard `B > 0`).
- Caller is **manager** → always allowed (unlimited).
- Caller is **cashier** → allowed only if
  `effectivePercent ≤ DISCOUNT_CASHIER_MAX_PERCENT`
  **and** `D ≤ DISCOUNT_CASHIER_MAX_AMOUNT`;
  otherwise `DomainException` → "Discount exceeds cashier limit; manager approval required."

### Transaction-discount allocation (only when present)
Proportional across lines by post-line-discount extended amount, with the
**last line absorbing the rounding remainder** — the identical pattern proven
in Phase 4 returns:

```
postLine_i  = gross_i − lineDiscount_i
cartBase    = Σ postLine_i
D_txn       = resolve(transactionDiscount, cartBase)         // capped to cartBase
share_i     = round(D_txn × postLine_i / cartBase, 2 HALF_UP) // i < last
share_last  = D_txn − Σ share_i                               // remainder
discountedExtended_i = postLine_i − share_i
```

`discountedExtended_i` is the per-line extended amount handed to `TaxService`,
which derives net/tax/line_total per the tax mode (see Schema).

### Validation rules
- Each `reasonCode` ∈ `DISCOUNT_REASON_CODES` (else validation error).
- A `lineDiscounts` key must match a SKU present in the cart (else validation error).
- `value` positive; `PERCENT` value ≤ 100.
- Every resulting line `net ≥ 0` and `grand_total ≥ 0` (guaranteed by capping;
  asserted defensively).

### New configuration keys
Added to the `SettingKey` enum with inline defaults (the config service falls
back to the enum default when a row is absent, so no seed migration is needed):

| Key | Setting | Default |
|-----|---------|---------|
| `DISCOUNT_REASON_CODES` | `discount.reason.codes` | `DAMAGED,PRICE_MATCH,LOYALTY,MANAGER_COMP` |
| `DISCOUNT_CASHIER_MAX_PERCENT` | `discount.cashier.max.percent` | `10` |
| `DISCOUNT_CASHIER_MAX_AMOUNT` | `discount.cashier.max.amount` | `20.00` |

## Ripple (kept faithful, as in Phase 4)

- **Receipt** (`receipt` module): `ReceiptLineData` gains `grossAmount` +
  `lineDiscountAmount`; `ReceiptData` gains `discountTotal`, `txnDiscountAmount`,
  `txnDiscountReason`. The renderer shows a discounts block in the totals so the
  printed receipt reconciles. Receipt printing stays best-effort (never fails the
  sale).
- **ERP** (`integration` + `sync`): `SaleUpload.Line` gains `grossAmount` +
  `discountAmount`; `SaleUpload` header gains `discountTotal`, `txnDiscountAmount`,
  `txnDiscountType`, `txnDiscountReason`. `FakeErpClient` and the sale-upload
  mapping in the sync listener carry the breakout. (Idempotency / outbox behavior
  is unchanged.)
- **Returns** (`sales`): **no code change** — refunds already key off the
  persisted post-discount net.

## Error Handling

All discount failures raise the existing `DomainException` taxonomy used across
the codebase:
- bad reason code, unknown-SKU line discount, non-positive / >100% value →
  `validation` (HTTP 400);
- cashier over the cap → `validation` carrying the "manager approval required"
  message (a clean rejection; the endpoint is not role-walled).

Money math uses `BigDecimal` throughout (amount scale 2 HALF_UP), compared via
`compareTo`/`signum` — never `double`.

## Testing

- **`DiscountCalculatorTest`** (unit): percent resolution; fixed-amount capped to
  base; transaction allocation with last-line remainder; reason-code validation;
  role cap allowed/blocked on both percent and amount thresholds for a cashier;
  manager unlimited.
- **Checkout e2e**: line discount; transaction discount; mixed line + transaction;
  assert VAT is computed on the discounted base and the reconciliation invariant
  holds.
- **Authorization e2e**: cashier over-cap rejected; same discount allowed when
  `callerIsManager`.
- **Returns e2e**: returning a discounted line refunds the discounted amount
  (proves the no-code-change claim).
- **Receipt**: rendered receipt shows the discount breakout and reconciles.
- **ERP**: `SaleUpload` carries the per-line and header discount fields.
- **Persistence**: new `sale` / `sale_line` columns round-trip.
- **`ModularityTests`**: module boundaries stay green (`sales` still owns the new
  types; no new cross-module leaks).

## Deferred / Out of Scope

- Automatic, rule-based **promotions engine** (coupon codes, category/date rules,
  multi-buy, stacking precedence).
- **Cart-resident** discounts (applying/visualizing discounts while ringing, and
  persisting them on a held cart). This design keeps the cart "dumb" and applies
  discounts at checkout time.
- **Supervisor-PIN / override** flow distinct from the authenticated principal's
  role. The cap keys off the role already on the request.
- Per-reason-code **discount reporting** dashboards (the itemized data makes this
  possible later, but no report is built here).
