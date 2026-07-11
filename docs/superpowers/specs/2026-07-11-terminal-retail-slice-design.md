# Terminal UI — Retail Happy-Path Slice + Emerald Visual System

**Date:** 2026-07-11
**Module:** `pos-terminal/` (standalone JavaFX 21 thin client)
**Branch:** `feat/terminal-ui-restaurant-slice`
**Source brief:** `docs/terminal-ui-design-prompt.md`

## Goal

Bring the retail / quick-service sales path to the terminal (which today only
does dine-in), and rewrite the shared stylesheet from single-blue-on-grey into a
**semantic emerald** design system applied across every screen. Optimise for the
shortest-tap sale: **add items → Charge → complete**.

This is the **happy-path slice**. Discounts, hold/resume, void-line, and split
payment are explicitly **deferred** to a named follow-up (see *Out of scope*).

## Constraints & ground truth (verified against the backend)

The terminal is a **thin REST client** — no DB, no business rules, no backend
changes. All authoritative money comes from the server; the terminal only
computes a client-side *estimated* pre-tax subtotal for preview.

Verified backend surface this slice depends on:

- **Cart** (`CartController`): `POST /carts` (returns `{cartId}`, no body),
  `GET /carts/{id}`, `POST /carts/{id}/lines {sku, quantity, modifierOptionIds}`,
  `PUT /carts/{id}/lines/{lineId} {quantity}`, `DELETE /carts/{id}/lines/{lineId}`.
  **`CartView` carries NO totals** — only `lines[]` with `unitPrice`/`basePrice`.
  The terminal therefore computes the estimated subtotal itself.
- **Sales** (`SalesController`): `POST /sales` takes
  `CheckoutCommand(cartId, tenders[], lineDiscounts, transactionDiscount, applyServiceCharge)`
  → `SaleView`. Retail forces `applyServiceCharge=false` server-side regardless
  of the request. `GET /sales/{saleId}`, `POST /sales/{saleId}/reprint` (204).
- **`SaleView`** (authoritative receipt): `receiptNumber, currencyCode, subtotal,
  taxTotal, grandTotal, discountTotal, txnDiscount*, serviceChargeAmount, lines[],
  payments[]`. `SaleLineView` carries `modifiers[]`, `lineDiscount*`.
- **Tenders**: `PaymentMethod` = `CASH | CARD | WALLET`. `TenderInput(method,
  amount, tendered)`. Multiple tenders per sale are supported.
- **Products** (`ProductController`): `GET /products` and `GET /products?q=`.
  **`?q=` matches name + SKU only, NOT barcode** (`findByName…OrSku…`). The
  backend `ProductView` does carry a `barcode` field.
- **Menu** (`MenuApi`): modifier groups per product, already consumed by the
  existing `ModifierPickerDialog`.

**Key derived constraint — barcode lookup is client-side.** Because `?q=` cannot
match a barcode, the barcode field resolves against the **cached catalog's
`barcode` field** (exact match), falling back to treating the input as a SKU.
The terminal `ProductView` DTO gains a `barcode` field (currently dropped).

## Architecture (extends the existing patterns — do not fork)

Layering stays as-is: typed `api/` facades → observable `viewmodel/` → thin FXML
controllers in `view/`; off-thread work via `FxTasks`; property writes via the
ViewModel's injected UI dispatcher; polling via `Timeline` stopped on
`Navigator.Screen#onLeave`. One shared stylesheet `css/app.css`. Never call
`HttpClient`/`ApiClient` from a controller.

### New / changed units

| Unit | Kind | Responsibility |
| --- | --- | --- |
| `api/CartApi` | new facade | Cart REST ops (create, get, addLine, updateQty, removeLine). Mirrors existing `DiningApi` style. |
| `api/dto/CartView`, `CartLineView`, `CartLineModifierView` | new DTOs | Terminal mirrors of the cart read model (money = `BigDecimal`, `@JsonIgnoreProperties(ignoreUnknown=true)`). |
| `api/dto/ProductView` | change | Add `barcode` field (for client-side barcode match). |
| `viewmodel/RetailViewModel` | new | Observable cart state: lines, est. subtotal, item-added pulse signal, search/barcode resolution. No FX types leak into logic that is unit-tested. |
| `view/RetailController` + `fxml/retail.fxml` | new | Two-zone retail screen (menu grid left ~65%, live cart right ~35%, pinned total bar). Reuses `ModifierPickerDialog`. |
| `view/HomeController` + `fxml/home.fxml` | new | Post-login mode picker: **Dine-in** / **Retail sale**. |
| `viewmodel/PaymentViewModel` + `view/PaymentController` | change | Add retail checkout branch (`POST /sales`) and **multi-tender** entry; keep dine-in `close`. |
| `app/Navigator` | change | Add `toHome()`, `toRetail()`; login lands on Home. |
| `order/SubtotalCalculator` | reuse | Client-side est. subtotal over cart lines. |
| `css/app.css` | rewrite | Semantic emerald token system (below). |
| `resources/fonts/` | new (empty) | Placeholder + `Font.loadFont()` hook for later `.ttf`. |

### Navigation flow

```
login → HOME ─┬─ Dine-in  → table map → order → payment(dine-in close) → receipt
              └─ Retail   → retail sale → payment(retail /sales)      → receipt
```

Existing dine-in path is otherwise untouched (Home is inserted before the table
map). `Navigator.Screen#onLeave` tears down the retail screen's search debounce /
any timers on exit.

## Visual system (`app.css` rewrite)

Semantic — hue encodes meaning; professionalism from restraint. No gradients on
chrome, no decorative colour. Root tokens:

- `canvas #F6F4EF` · `card #FFFFFF` · `ink #16202E` (text + top nav)
- `primary #0E7C66` (emerald, all primary actions) · `primary-press #0A5E4D`
- `accent #E8A32C` (amber highlight / item-added flash)
- `success #1E7A46` · `danger #C0362C`
- **Category edges** (4px edge on white tiles): Drinks `#1B8A9E`, Food `#C0653A`,
  Merch/Retail `#7A4FB5`, Sides `#6B8E23` → `.cat-drinks/.cat-food/.cat-merch/.cat-sides`.
- **Tender colours**: Cash `#1E7A46`, Card `#2340C4`, Wallet `#B0338C`.

**Typography** — type scale 13 caption / 16 body / 20 subtitle / 34 title / 44
grand-total, 400↔700 weight contrast, **tabular figures on all money**.
Implemented via a system-font family stack now; `resources/fonts/` + a documented
`Font.loadFont()` call site let real Space Grotesk/Archivo + Inter `.ttf` files
drop in later with no code change.

**Signature element** — an `ink` **total bar pinned to the cart bottom**:
oversized tabular grand total that **pulses `accent` amber** when an item is
added. The one bold moment; tiles/tabs/forms stay quiet. Pulse is gated by a
config flag (`ui.reduced-motion`, default off/animated) since JavaFX cannot read
the OS `prefers-reduced-motion`; when reduced-motion is on it degrades to a
static amber highlight.

**Quality floor** — ≥48px tap targets, 8-multiple spacing, no hover reliance
(pressed/focused states only, matching the existing convention), visible keyboard
focus. **Status always colour + word** (reusing the existing table-map "Open /
In use" rule — never colour alone).

## Screen behaviour

### Home
Two large tiles (Dine-in, Retail sale). Shows the signed-in user + a sign-out
affordance. Reached after successful login instead of jumping straight to the
table map.

### Retail sale (`retail.fxml`)
- **Left (~65%)** — category-tabbed menu grid from `GET /products` grouped by
  `categoryName`; white tiles with the category edge colour; ≥96px tiles showing
  name + tabular price. A product with modifier groups opens the existing
  `ModifierPickerDialog` before adding; otherwise it adds directly.
- **Right (~35%)** — live cart:
  - `POST /carts` on screen entry to get a `cartId` (held in the VM).
  - Add: `POST /carts/{id}/lines {sku, quantity, modifierOptionIds}`.
  - Quantity steppers: `PUT /carts/{id}/lines/{lineId} {quantity}`; stepping to 0
    removes the line.
  - Remove: `DELETE /carts/{id}/lines/{lineId}`.
  - Each line: qty × name, modifier summary, est. line price (tabular).
  - **Pinned total bar** (ink): oversized tabular **"est."** grand total from
    `SubtotalCalculator`; amber pulse on add.
- **Fast entry** — search field (`GET /products?q=`, debounced) filters/among the
  grid; barcode field resolves **client-side** against cached `ProductView.barcode`
  (exact), falling back to SKU; a hit adds straight to the cart.
- **Actions row** — **Charge / Pay** (emerald primary) → payment; **remove line**
  is inline on each cart line. (Discounts / hold / split deferred — not rendered.)
- **States**:
  - **Empty cart** — calm placeholder ("Scan or tap an item to start"), not an error.
  - **Item added** — tile press-state + total-bar amber pulse (silent, sound-ready).
  - Backend unreachable — the existing "Cannot reach store server" error banner.

### Payment (retail branch)
- Estimated total shown while tendering; authoritative only from `SaleView`.
- **Multi-tender**: a running list of `TenderInput`s with live "tendered vs
  remaining due". Tender buttons carry tender colours:
  - **Cash** — numeric keypad, `tendered` may exceed `amount` → live change-due.
  - **Card** — masked PAN entry (display-only; no real auth in this slice).
  - **Wallet** — `WALLET` tender (this project's mobile/QR equivalent; a button,
    no camera flow).
- **Short-cash / under-tender rejected clearly** before allowing checkout
  (sum of tender amounts must cover the due total).
- Checkout: retail → `POST /sales` with the collected tenders; dine-in keeps
  `POST /dining/orders/{id}/close`. Response `SaleView` renders the authoritative
  receipt.
- **Receipt / completed** — receipt number `{storeId}-{terminalId}-{seq}`, lines
  with modifiers, subtotal, discount total, tax, service charge (dine-in only —
  absent for retail), grand total, per-tender change due. **Reprint**
  (`POST /sales/{saleId}/reprint`) and **Done** (→ Home).

## Data flow

1. Retail entry: VM `POST /carts` → holds `cartId`.
2. Tile/search/barcode → resolve `ProductView` → (modifiers?) `ModifierPickerDialog`
   → `POST /carts/{id}/lines` → server returns updated `CartView` → VM recomputes
   est. subtotal → total bar pulses.
3. Charge → Navigator `toPayment(cartId, estTotal, RETAIL)`.
4. Payment VM collects tenders → `POST /sales {cartId, tenders}` → `SaleView`.
5. Receipt renders `SaleView`; Reprint / Done → Home.

All REST calls run off the FX thread via `FxTasks`; results marshalled back to
observable properties through the VM's UI dispatcher (the pattern fixed in commit
`a156edc`). Errors surface via the shared error banner; a failed line-add leaves
the cart in its last server-confirmed state.

## Testing (headless, existing pattern — no TestFX)

- `RetailViewModelTest` — add/update/remove line against `StubServer`; est.
  subtotal correctness; item-added pulse signal fires; barcode resolves against
  cached catalog; empty-cart state.
- `CartApiTest` — cart REST round-trips against the in-JVM `StubServer`.
- `PaymentViewModelTest` (extend) — multi-tender accumulation, remaining-due,
  short-cash rejection, retail vs dine-in checkout branch.
- DTO deserialization tolerant of unknown fields (`@JsonIgnoreProperties`).
- `ModularityTests` is backend-only and untouched by terminal changes.

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` then
`./mvnw -f pos-terminal/pom.xml clean test`.

## Out of scope (named follow-up: "Terminal retail — advanced actions")

- **Discounts** (line/txn, `PERCENT`/`AMOUNT` + `reasonCode`), cashier-cap
  (`DISCOUNT_CASHIER_MAX_PERCENT`/`_AMOUNT`) enforcement, manager-override
  affordance (`SessionManager.isManager()`).
- **Hold / Resume** (`PUT /carts/{id}/hold|resume`, held list
  `GET /carts/held?terminalId=`).
- **Void line** as a distinct manager-gated action (`DELETE` line stays for
  normal cart editing).
- **Split payment** (dine-in `POST /dining/orders/{id}/close-split`, BY_ITEM / EVEN).

These are designed-around (nothing here blocks them) but not built in this slice.

## Success criteria

1. From Home → Retail, a cashier can add items (tap, search, or barcode), see a
   live pulsing est. total, Charge, tender (single or multiple methods incl.
   Cash change), and land on an authoritative `SaleView` receipt — the
   shortest-tap sale works end to end against a running backend.
2. The emerald semantic system is applied to **all** screens (login, home, table
   map, order, retail, payment); money is tabular; status stays colour + word.
3. `./mvnw -f pos-terminal/pom.xml clean test` is green (existing 67 tests + new).
4. No backend change; dine-in path still works.
