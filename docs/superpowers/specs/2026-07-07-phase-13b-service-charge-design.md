# Phase 13b — Service Charge / Auto-Gratuity (Design)

**Date:** 2026-07-07
**Status:** Approved (brainstorming), pending implementation plan
**Phase:** Phase 13b — service charge, the companion to Phase 13 split billing
(`dining` → `menu` → `kitchen` → `split billing (13)` → **service charge (13b)**)

## Goal

Add a configurable, **taxable** service charge that is auto-applied at **DINE_IN**
dining closes (single-bill, by-item split, and even split), is **manager-waivable**
on a given order, and is excluded from retail checkout and QUICK_SERVICE orders.
The charge is **off by default** — existing behaviour is unchanged until the store
enables it.

This reuses the `sales.quote` read-only pricing pass added in Phase 13 as the seam
that keeps even-split shares exact once a charge is in play.

## Scope

**In scope (phase-13b core):**

- **Config-driven service charge** — a store-set percent of the discounted
  subtotal, gated by an enabled flag, read from the `configuration` settings store.
- **Auto-applied at DINE_IN dining closes only** — `dining` decides whether to
  apply it; retail `POST /sales` and QUICK_SERVICE dining orders never carry it.
- **Taxable** — the charge is part of the VAT base, so `taxTotal` and `grandTotal`
  reflect VAT on it (correct for a mandatory charge under KSA VAT; the store runs
  SAR / 15%).
- **Manager waiver** — a manager can waive the charge (drop it to zero) on a
  specific close; a non-manager waiver attempt is rejected. The waiver is
  order-level (applies to all bills of a split).
- **Persisted, receipted, uploaded** — a `serviceChargeAmount` total on the sale,
  shown on the receipt, and carried to the ERP so the invoice reconciles.
- **Correct across all close modes** — single, by-item (each bill charged on its
  own subtotal), and even split (one charge; even shares still sum exactly).

**Explicitly deferred (not this phase):**

- **Party-size / per-cover auto-gratuity** (apply only when party ≥ N) — needs a
  covers/guest-count model that does not exist today (only `DiningTable.seats`,
  fixture capacity). A clean later increment.
- **Per-sale percent/amount override** — this phase supports config-percent +
  manager *waive* only, not a manager-set alternative rate (which would need a
  discount-style override with a cap).
- **Post-tax (non-taxable) gratuity mode.**
- **Service-charge revenue as a distinct reporting line / GL split** beyond the
  total on the sale + ERP upload.
- **Per-payer service-charge apportionment** beyond the natural per-bill behaviour
  (by-item: each bill charged on its own subtotal; even: one charge on the whole).

## Architecture

The charge is computed in `sales` (a pricing-pipeline concern) but the **policy**
(when to apply it) lives in `dining`. `sales` never decides on its own whether a
sale is a dine-in bill — the caller tells it via a flag.

### Configuration (new `SettingKey`s)

| Key | Default | Meaning |
|-----|---------|---------|
| `SERVICE_CHARGE_ENABLED` (`service.charge.enabled`) | `false` | Master on/off |
| `SERVICE_CHARGE_PERCENT` (`service.charge.percent`) | `0` | Percent of discounted subtotal (e.g. `10`) |
| `SERVICE_CHARGE_LABEL` (`service.charge.label`) | `Service Charge` | Receipt line label |

Read via `ConfigurationService.getBoolean` / `getString` (+ `new BigDecimal(...)`).
Defaulting to disabled / 0% keeps all existing tests and current behaviour intact.

### Pipeline placement — taxable, via a separate tax call

In `DefaultSalesService.priceDiscountTax` (the shared method used by both
`checkout` and `quote`), after the existing discount + product-tax stages:

1. `scBase = taxed.subtotal()` — the discounted product net subtotal.
2. `scNet = scBase × percent / 100`, scale-2 HALF_UP.
3. Run `scNet` through `tax.applyTax(...)` as a **single separate one-line call**
   (same `rate` / `inclusive` as the product pass) to get `scNet` and `scTax`
   consistently under both tax modes.

Totals produced:
- `Sale.subtotal` stays the discounted product net (unchanged meaning).
- **new** `serviceChargeAmount = scNet`.
- `taxTotal = productTax + scTax`.
- `grandTotal = productGrand + scNet + scTax`.
- Invariant: `grandTotal = subtotal + serviceChargeAmount + taxTotal`.

**Why a separate tax call rather than a synthetic "SERVICE_CHARGE" tax line:** it
leaves the per-line loops untouched — the charge never appears as a pseudo-product
line in `SaleLine`s or in `SaleCompleted.SoldLine`s, so `inventory` never tries to
decrement stock for a phantom "SERVICE_CHARGE" sku. Only the charge's money flows
into the totals.

`priceDiscountTax` gains a `boolean applyServiceCharge` parameter and returns the
`scNet` / `scTax` breakdown alongside the existing `PricedCart` data. When
`applyServiceCharge` is false or config is disabled or percent is 0, `scNet` and
`scTax` are zero and the totals equal today's.

### Flag flow (scoping + even-split correctness)

- `CheckoutCommand` gains `boolean applyServiceCharge`. Convenience constructors
  default it to `false`, so retail `POST /sales` (JSON without the field) and every
  existing caller are unaffected.
- `SalesService.quote(UUID cartId, boolean applyServiceCharge)` gains the same
  flag. The even-split path passes the **same** value it passes to `checkout`, so
  `quote`'s grand total includes the charge and the N even shares still sum exactly
  to the real grand total — preserving the invariant Phase 13 proved.
- `checkout` computes and persists `serviceChargeAmount` only when the flag is set
  and config-enabled.

### `dining` — where the policy lives

`dining` computes, per close:

```
apply = SERVICE_CHARGE_ENABLED (config) && order.serviceType() == DINE_IN && !waiveServiceCharge
```

and passes `applyServiceCharge = apply` into each `checkout` (and into `quote` for
the even path).

- `CloseOrderCommand` and `SplitCloseCommand` gain `boolean waiveServiceCharge`.
- **Waiver is manager-gated:** if `waiveServiceCharge && !callerIsManager`, throw
  `DomainException` (validation) — a cashier cannot waive.
- The waiver is order-level: for a by-item split it suppresses the charge on all
  bills. Each by-item bill is otherwise charged on its own subtotal (natural, since
  each bill is a full `checkout`); the even split produces one charge on the whole.

### Persistence, receipt, ERP

- **Migration V34** (globally sequential; V33 is latest):
  `ALTER TABLE sale ADD COLUMN service_charge_amount NUMERIC(19, 2) NOT NULL
  DEFAULT 0`, in `src/main/resources/db/migration/sales/`. Store-server only;
  embedded via `ddl-auto`.
- `Sale` entity + constructor gain `serviceChargeAmount`; `SaleView` gains
  `serviceChargeAmount`.
- `ReceiptData` gains `serviceChargeAmount`; the receipt prints a charge line
  between the discount and the grand total, shown only when > 0. The existing
  9-arg convenience constructor (returns / credit notes) is preserved and defaults
  it to zero.
- `SaleUpload` (integration.api) gains `serviceChargeAmount`; the `sync`
  `SaleUploadListener` maps it from the `SaleView`, so the ERP reconciles
  `subtotal − discountTotal + serviceChargeAmount + taxTotal = grandTotal`.
- `SaleCompleted` is **unchanged** — `grandTotal` already includes the charge and
  no listener needs the breakdown.

## Module boundaries

- No new module and no new module dependency. Changes stay within `sales`,
  `dining`, `receipt`, `integration`, and `configuration`, all within existing
  `allowedDependencies`. `ModularityTests` stays green.

## Testing

- **`sales`**: with the charge enabled and `applyServiceCharge = true`, a checkout
  yields the correct `serviceChargeAmount`, `taxTotal` (including VAT on the
  charge), and `grandTotal` (invariant holds); with the flag off (retail default)
  all are as today and `serviceChargeAmount` is 0; `quote(cart, true)` and the
  matching `checkout` agree on the grand total.
- **`dining`**: a DINE_IN close with the charge enabled applies it; a QUICK_SERVICE
  close does not; a non-manager `waiveServiceCharge` is rejected; a manager waiver
  zeroes it; by-item and even split both carry the charge and the even shares still
  sum exactly to the grand total.
- **Receipt / ERP**: the receipt shows the charge line; `SaleUpload` carries
  `serviceChargeAmount`.
- `ModularityTests` and full `./mvnw verify` green, with V34 validated under
  Testcontainers PostgreSQL.

## Success criteria

1. With the store config enabled, a DINE_IN close (single, by-item, or even)
   applies a taxable service charge; `taxTotal` and `grandTotal` include it and the
   `grandTotal = subtotal + serviceChargeAmount + taxTotal` invariant holds.
2. Retail checkout and QUICK_SERVICE dining closes never carry the charge; with the
   config disabled, behaviour is identical to today.
3. A manager can waive the charge on a close; a non-manager waiver is rejected.
4. Even-split shares still sum exactly to the grand total with the charge applied.
5. The charge is persisted on the sale, shown on the receipt, and uploaded to the
   ERP; `./mvnw verify` is green in both modes with V34 Postgres-validated.
