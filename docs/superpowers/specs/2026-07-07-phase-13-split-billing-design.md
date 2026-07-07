# Phase 13 — Split Billing (Design)

**Date:** 2026-07-07
**Status:** Approved (brainstorming), pending implementation plan
**Phase:** Phase 13 — fourth slice of the restaurant-floor track
(`dining (tables/orders)` → `menu (modifiers/variants)` → `kitchen (routing)` → **split billing** → service charge [13b])

## Goal

Let one dine-in order close as **multiple bills in a single atomic operation**, in
two modes:

- **By item** — the server partitions the order's lines across N bills; each bill
  becomes its own itemized sale with its own receipt (this is also how "split by
  guest" is expressed — the server groups a guest's items onto one bill).
- **Even split** — the whole bill is divided into N equal shares and settled as
  one sale paid by N payers.

A table closes as exactly one bill today (Phase 10); this phase generalizes the
close path to N bills while keeping the order lifecycle (`OPEN → CLOSED`) and the
existing single-bill close unchanged.

## Scope

**In scope (phase-13 core):**

- **By-item split → N itemized sales.** Whole-line partition: each order line is
  assigned to exactly one bill, and every line must be assigned. Each bill closes
  as its own cart → its own `sales.checkout` → its own immutable `Sale` (own
  receipt number, tax, and `SaleCompleted` fan-out). Each bill may carry its own
  tenders and (optionally) its own line / transaction discounts.
- **Even split → one sale, N equal payments.** All lines go on one cart; the
  server prices it, divides the grand total into N equal shares (last share
  absorbs the rounding remainder), and settles **one** sale with N payments (one
  per payer, using the method each supplied).
- **Atomic settlement.** All N checkouts and the order close happen in one
  transaction. Any failure rolls the whole split back — no partial sales, order
  stays `OPEN`.
- **A small read-only `sales.quote(cartId)`** so `dining` can learn the grand
  total before building the even-split tenders (also the basis for the Phase 13b
  service-charge preview).
- **Order → sales traceability** via a new `dining_order_sale` link table, so a
  split-closed order's N sales are as traceable as a single-closed order's one.

**Explicitly deferred (not this phase):**

- **Service charge / auto-gratuity** — Phase 13b. The reason `sales.quote` is
  added now.
- **Pay-as-you-go / incremental settlement** — guests paying their portions at
  different times while the table stays open. Would need a partial-settled order
  state and per-portion tracking; this phase settles all bills at once.
- **Splitting a single line's quantity across bills** (e.g. "2 × beer" as 1 + 1,
  or a shared bottle as 0.5 / 0.5). Whole-line partition only; shared items are
  rung on their own line or placed on one bill.
- **Per-payer receipt slips for the even-split sale.** The even split produces one
  sale with one receipt showing the N-way payment; separate slips per payer come
  later if needed.
- **Even split with discounts.** Even mode carries no discounts — use by-item or
  the single-bill close when a discount is required.
- **Seat / guest / cover modeling** on the order or line.

## Architecture

`dining` already orchestrates the single close (build a throwaway cart →
`sales.checkout` → stamp `CLOSED`). Split billing generalizes this to a loop; the
only change outside `dining` is one read-only method on `sales`.

### The one `sales` change: `quote`

```java
QuoteView quote(UUID cartId);

record QuoteView(String currencyCode, BigDecimal subtotal, BigDecimal discountTotal,
                 BigDecimal taxTotal, BigDecimal grandTotal)
```

- Read-only (`@Transactional(readOnly = true)`): prices the cart with **no
  discounts**, applies tax, returns the totals. Creates no `Sale`, takes no
  payment, fires no event, prints no receipt.
- Implemented by extracting the pricing → discount → tax computation that already
  lives inside `checkout` into a shared private method that both `checkout` and
  `quote` call. This is a small refactor within `sales` — no new dependency, no
  behavioural change to `checkout`.
- Because even-split carries no discounts, `quote` (discount-free) returns exactly
  the grand total the subsequent `checkout` (called with empty discounts) will
  compute — the shares are authoritative.

### The `dining` API surface

```java
List<SaleView> closeOrderSplit(UUID orderId, SplitCloseCommand cmd,
                               String cashierUsername, boolean callerIsManager);
List<UUID>     listOrderSaleIds(UUID orderId);

enum SplitMode { BY_ITEM, EVEN }

record SplitCloseCommand(SplitMode mode, List<BillInput> bills, EvenSplitInput even)

record BillInput(List<UUID> lineIds, List<TenderInput> tenders,
                 Map<String, DiscountInput> lineDiscounts, DiscountInput transactionDiscount)

record EvenSplitInput(int ways, List<PaymentMethod> methods)   // methods.size() == ways
```

- `BY_ITEM`: `bills` is populated, `even` is null. `EVEN`: `even` is populated,
  `bills` is null.
- `closeOrderSplit` returns `List<SaleView>` — size N for by-item, size 1 for even
  (the N payers appear in that one sale's `payments`).
- `listOrderSaleIds` unions the scalar `saleId` (set by a single close) with the
  `dining_order_sale` rows (written by a split close), so callers get a uniform
  view regardless of how the order closed.

### Endpoints

- `POST /dining/orders/{orderId}/close-split` → `List<SaleView>` (201). Body is
  `SplitCloseCommand`. Authenticated (cashier and up), identical to the existing
  close; `callerIsManager` is derived from the JWT authorities exactly as the
  close endpoint does, and passed into each bill's checkout so an over-cap
  discount on a bill still requires a manager.
- `GET /dining/orders/{orderId}/sales` → `List<SaleView>` (resolves each linked
  sale id via `sales.getSale`).
- The existing `POST /dining/orders/{orderId}/close` (single bill) is **untouched**.

### Orchestration & atomicity

All N checkouts plus the order close run in one `@Transactional` boundary
(`DefaultDiningService` is `@Transactional`; `sales.checkout` is REQUIRED and joins
it). Any failure — invalid partition, a bill's tenders not covering its total, an
unknown sku, any checkout throw — rolls the whole split back: no sales created, the
order stays `OPEN`. The N `SaleCompleted` publications are registered inside the
transaction and delivered by the outbox only after the single commit, so all N
fire or none do.

- **By-item loop** — for each `BillInput`: `carts.createCart()`; for each `lineId`,
  find the `OrderLine` and `carts.addLinePreResolved(cart, sku, qty, modifiers)`
  (the same Phase 11b snapshot seam the single close uses); then
  `sales.checkout(new CheckoutCommand(cart, bill.tenders, bill.lineDiscounts,
  bill.transactionDiscount), cashier, isManager)`; then `carts.close(cart)`.
  Collect the N `SaleView`s.
- **Even loop** — one cart built from all order lines; `sales.quote(cart)` →
  `grandTotal`; compute N shares (`share = grandTotal / N` at scale 2 HALF_UP for
  the first N−1, last = `grandTotal −` the sum of the rest); build N
  `TenderInput(methods[i], share[i], share[i])`; one `sales.checkout`; `carts.close`.
- **On success** — `order.close(...)` sets `CLOSED` + `closedAt`; the resulting
  sale id(s) are written to `dining_order_sale`.

### Traceability — `dining_order_sale`

A single close stores its one `saleId` on `dining_order`; a split produces N, which
won't fit one column. `dining_order_sale (id, order_id, sale_id)` records a split's
N sale ids (one row each), restoring the order→sales link parity that matters for an
audited financial system. The scalar `saleId` column and the single-close path stay
as-is; `listOrderSaleIds` unions both sources.

## Validation

**By-item:**
- Order `OPEN` and non-empty.
- `bills` non-empty; each bill's `lineIds` non-empty.
- The line ids across all bills form an **exact partition** of the order's lines:
  complete coverage (every order line assigned), no duplicates (no line on two
  bills), no unknown ids.
- Each bill's tender sum is enforced by `checkout` (Σ tenders == that bill's grand
  total), as today.

**Even:**
- Order `OPEN` and non-empty.
- `ways ≥ 2`; `methods.size() == ways`; each method non-null.
- Grand total > 0.

## Module boundaries & migrations

- `dining` `allowedDependencies` gain **`payment :: api`** — the even-split
  `EvenSplitInput` names `PaymentMethod` (which lives in `payment.api`). By-item
  needs nothing new (it just forwards `TenderInput`, already reachable via
  `sales :: api`). The addition is acyclic (`payment` has no `dining` dependency).
  `quote` / `QuoteView` live in `sales :: api`; the new split DTOs live in
  `dining :: api`. `ModularityTests` stays green.
- No new config keys.
- **Migration V33** (globally sequential; V32 is the latest existing) —
  `dining_order_sale` table in `src/main/resources/db/migration/dining/`
  (`order_id`, `sale_id` both `VARCHAR(36)`, PK `id VARCHAR(36)`, index on
  `order_id`). Store-server only; embedded picks it up via `ddl-auto`.

## Testing

- **`sales.quote`** returns the same `subtotal` / `taxTotal` / `grandTotal` that
  `checkout` computes for the same cart (no discounts), and creates no `Sale` and
  fires no `SaleCompleted`.
- **By-item** (`@SpringBootTest @ActiveProfiles("embedded")`): a 3-line order
  partitioned into 2 bills closes as 2 `SaleView`s with the correct lines and
  totals; order is `CLOSED`; `listOrderSaleIds` returns 2. Rejections: incomplete
  partition (a line unassigned), a line assigned to two bills, an unknown line id,
  an empty bill, and a bill whose tenders fall short.
- **Even**: an order with total T split 3 ways closes as one sale with 3 payments
  summing exactly to T (last share absorbs the remainder); order `CLOSED`.
  Rejections: `ways < 2`, `methods.size() != ways`.
- **Atomicity**: a failing bill (short tender) leaves zero new sales and the order
  `OPEN`.
- `ModularityTests` stays green.

## Success criteria

1. A dine-in order can be closed by-item into N itemized sales (each with its own
   receipt/tax/`SaleCompleted`) via one atomic call, with a validated whole-line
   partition.
2. A dine-in order can be closed by an even N-way split into one sale with N equal
   payments, the last share absorbing the rounding remainder.
3. Any failure during a split close rolls back completely — no partial sales, the
   order stays `OPEN`.
4. A split-closed order's sales are traceable via `dining_order_sale` /
   `listOrderSaleIds`, at parity with a single-closed order.
5. The single-bill close and every existing test are unchanged; `./mvnw verify` is
   green in both persistence modes, with V33 validated under Testcontainers
   PostgreSQL.
