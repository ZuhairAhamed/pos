# POS Terminal UI — Design Prompt (JavaFX thin client, emerald direction)

> Project-grounded UI design brief for the `pos-terminal/` module, with a
> colourful-and-professional visual direction baked in. Paste this into a
> design/build session as the source of truth for the terminal's look and flow.

**Context.** Build into the `pos-terminal/` Maven module — a standalone
**JavaFX 21** thin client (FXML + programmatic hybrid) talking to the Spring
Modulith POS backend over REST (bearer JWT, base URL `server.base-url`, default
`http://localhost:8080`). Not a web app — no HTML, no hover states. All UI is
JavaFX controls styled by one stylesheet, `src/main/resources/css/app.css`.
Extend the existing design system and screen patterns; don't fork a parallel one.

**Architecture rules.** Thin FXML controllers (UI only); observable state in
`viewmodel/`; REST via typed `api/` facades (`AuthApi`, `DiningApi`,
`ProductApi`, `MenuApi`, `SalesApi` — add `CartApi` for the retail path). Never
call `HttpClient` from a controller. Off-thread work through `FxTasks`; property
writes go through a ViewModel's injected UI dispatcher. Polling uses JavaFX
`Timeline` and stops on screen exit (`Navigator.Screen`).

## Visual direction — colourful & professional

Evolve `app.css` from single-blue-on-grey into a **semantic** colour system: hue
encodes meaning, professionalism comes from restraint around it. Colour appears
only where it informs; everything else stays neutral. No gradients on chrome, no
decorative colour.

**Palette (define as root tokens):**

- `canvas #F6F4EF` app background (warm off-white) · `card #FFFFFF` · `ink #16202E` text + top nav
- **`primary #0E7C66` (deep emerald)** — Charge/Pay + all primary actions; **`primary-press #0A5E4D`**
- **`accent #E8A32C` (amber)** — highlights, badges, item-added flash
- Status aligned to the jewel family: `success #1E7A46` · `danger #C0362C`

**Colour that carries information (the "colourful" part):**

- **Category tabs / tiles** — each category gets a 4px edge/underline colour,
  tiles stay white cards: Drinks teal `#1B8A9E`, Food terracotta `#C0653A`,
  Merch/Retail violet `#7A4FB5`, Sides `#6B8E23`.
- **Tender buttons** — Cash `#1E7A46` (green) · Card `#2340C4` (indigo) ·
  Wallet `#B0338C` (magenta).
- **Course tags** (`STARTER/MAIN/DESSERT/DRINK`) — small coloured chips reusing
  the category family.
- **Status always colour + word** (table free/occupied, fired-line badges) —
  never colour alone.

**Typography** — bundle two `.ttf` faces (JavaFX needs local fonts, not web
fonts): a geometric display (**Space Grotesk** or **Archivo**) for titles,
prices, and the grand total; **Inter** for body/labels. **Tabular figures** for
all money so columns align. Scale 13 caption / 16 body / 20 subtitle / 34 title /
44 grand-total, with 400↔700 weight contrast.

**Signature element** — an `ink` **total bar pinned to the cart bottom**:
oversized tabular grand total that pulses `accent` amber when an item is added.
This is the one bold moment; keep tiles, tabs, and forms quiet around it.

**Quality floor** — ≥48px tap targets, generous 8-multiple spacing, no hover
reliance (pressed/focused states only), visible keyboard focus,
`prefers-reduced-motion` honoured (pulse degrades to a static highlight).

## Reuse what exists (don't rebuild)

- **Login** (`login.fxml`/`LoginController`) — username/password + numeric PIN
  pad → `POST /auth/login` · `/auth/pin-login`, then `GET /auth/me` for roles.
- **Table Map** (`TableMapController`) — polling tile grid (`GET /dining/tables`);
  tap opens/resumes an order.
- **Order** (`OrderController`) — two-zone dine-in screen: left = live order lines
  (qty × name, modifiers, notes, fired badge, pre-tax **est.** subtotal); right =
  category-tabbed menu grid.
- **Payment** (`PaymentController`) — estimated total, tender entry with live
  change-due, then authoritative `SaleView` receipt (subtotal, tax, service
  charge, grand total, change) + Reprint/Done.
- **Modifier Picker** (`ModifierPickerDialog`) — modal of required/optional groups
  as toggle buttons with price deltas, live min/max validation.

## Build in this task

### 1. Retail / Quick-Service sales screen

Bring the two-zone layout to the cart path (backend supports it; terminal only
has dine-in today). Left ~65%: category-tabbed **menu grid** (tiles from
`GET /products`, grouped by `categoryName`, category-coloured edge). Right ~35%:
**live cart** on `CartApi` (`POST /carts` → `POST /carts/{id}/lines`
`{sku, quantity, modifierOptionIds}`) with **quantity steppers**, per-item price,
and the pinned **total bar**. Honest labelling: cart shows pre-tax **"est."**;
tax/service-charge/grand-total are authoritative only from the checkout
`SaleView`.

### 2. Actions row

Prominent **Charge / Pay** (emerald), plus:

- **Discounts** — line or transaction, `PERCENT`/`AMOUNT` + required
  `reasonCode`; enforce cashier cap (`DISCOUNT_CASHIER_MAX_PERCENT`/`_AMOUNT`)
  in-UI and surface a **manager-override** affordance when exceeded
  (`SessionManager.isManager()`; backend 400s otherwise).
- **Hold / Resume** — `PUT /carts/{id}/hold` · `/resume`; held list via
  `GET /carts/held?terminalId=`.
- **Void / remove line** — `DELETE /carts/{id}/lines/{lineId}`.
- **Split payment** (dine-in) — `POST /dining/orders/{id}/close-split`,
  **BY_ITEM** (assign lines per bill) or **EVEN** (N-way, one `PaymentMethod`
  per share).

### 3. Payment

Full tender set with tender colours: **Cash** (numeric keypad + change-due),
**Card** (masked PAN), **Wallet** (`WALLET` tender — this project's "mobile/QR"
equivalent; model as a Wallet button, there is no QR-camera flow). Support
**multiple tenders on one sale**. Checkout: `POST /sales` (retail) or
`POST /dining/orders/{id}/close` (dine-in) → `SaleView`; then
`POST /sales/{saleId}/reprint`.

### 4. Fast entry

Quick **search bar** (`GET /products?q=`) and **barcode field**
(`ProductView.barcode`); a hit drops straight into the cart. Keep the happy path
minimal: add → Charge → complete.

### 5. Required states

- **Empty-cart** placeholder (calm, not an error).
- **Item-added confirmation** (tile press-state + total-bar amber pulse,
  sound-ready but silent).
- **Completed-transaction / receipt** screen showing receipt number
  `{storeId}-{terminalId}-{seq}`, lines with modifiers, subtotal, discount total,
  tax, **service charge** (dine-in only, waivable), grand total, per-tender
  change due.

## Constraints

Honest totals (estimate in cart, authoritative from `SaleView`); combined tenders
+ short-cash rejection surfaced clearly; discounts gated by role; service charge
`DINE_IN` only. Optimise for the shortest-tap sale: **add items → Charge →
complete.**
