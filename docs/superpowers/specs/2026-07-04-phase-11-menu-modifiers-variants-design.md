# Phase 11 — Menu Modifiers & Variants (Design)

**Date:** 2026-07-04
**Status:** Approved (brainstorming), pending implementation plan
**Phase:** Phase 11 — second slice of the restaurant-floor track
(dining tables/orders [done] → **menu modifiers/variants** → kitchen routing → split billing)

## Goal

Make the menu richer: a product can offer **size variants** (Small/Large draft
beer) and **modifier groups** — both *forced* choices (a steak must pick a
temperature) and *optional* priced add-ons (extra cheese +2.00). A cashier or
server selects modifiers when adding a line; the add-on prices fold into the
line and flow through the existing pricing → tax → sale → receipt pipeline, and
the modifier detail is itemized on the bill. Works for both quick-service carts
and dine-in orders.

## Scope

**In scope (phase-11 core):**

- **New `menu` module** owning, authored via local admin CRUD (MANAGER/ADMIN):
  - **Modifier groups** (name, `minSelections`, `maxSelections`) and **options**
    (name, `priceDelta` — may be 0). *Forced* = `minSelections >= 1`; *optional*
    = `minSelections == 0`. A group is assigned to one or more product SKUs.
  - **Variant groups** (a display name + member SKUs, each with a label). A
    variant is a distinct sellable SKU; the group is presentation only.
- **Cart line carries modifier selections.** `cart.addLine(sku, qty,
  modifierOptionIds)` validates the selection against `menu::api` (forced groups
  must be satisfied; optional groups respect max), stores the resolved modifiers
  on the line, and sets **effective unit price = base + Σ price deltas**. The
  effective price flows through the *unchanged* pricing/tax/sales pipeline.
- **Cart lines become line-id-identified** (necessary consequence — see
  Architecture). `updateLine`/`removeLine` move from sku-keyed to `lineId`-keyed.
- **Sale line + receipt itemize modifiers.** The `SaleLine` persists the modifier
  breakdown; the receipt prints modifiers as indented sub-items under the parent
  line. Line total/tax already reflect the folded price.
- **Dine-in orders support modifiers.** The `dining` `OrderLine` carries modifier
  selections (resolved at add-time via `menu::api` so the ticket shows names +
  running price); at close each order line becomes **one cart line** — replacing
  Phase-10's aggregate-by-sku close, which is now incorrect (a burger "+cheese"
  ≠ a plain burger).

**Explicitly deferred (not this phase):**

- **Kitchen station routing / printing** (phase 12). Modifier + course data is
  *captured*; no routing.
- **Split billing / service charges** (phase 13).
- **Nested / dependent modifiers** (a modifier that reveals sub-modifiers),
  quantity-per-modifier (e.g. "×2 extra shots"), and per-modifier tax overrides —
  a modifier is a single boolean selection taxed with its parent line.
- **ERP-sourced menu definitions.** Modifiers/variants are POS-local; the ERP
  still owns base products/prices. No `ErpProduct`/sync changes.
- **Variant-specific modifier inheritance.** Modifier groups attach per sellable
  SKU; a variant group does not cascade groups to its members.

## Architecture

`menu` is a new Tier-2 module in the house hexagonal layout
(`api`/`web`/`application`/`domain`/`infrastructure`). It is a **POS-side overlay
keyed by product `sku`** — the ERP-synced `product` catalog is untouched.

### Why Approach A (menu overlay + fold price into the cart line)

Rejected alternatives: **modifiers as separate cart/sale lines** (ugly receipts,
awkward for zero-price forced modifiers, still needs parent/child linking);
**modifiers inside `dining`** (contradicts "both paths" and would force a
`cart → dining` cycle). Chosen: `menu` owns the definitions; the **cart line**
carries selections and the **one existing price seam** (`CartLineView.unitPrice`,
which sales checkout already feeds into pricing) absorbs the delta, so
pricing/tax need no change. Modifier *detail* rides alongside for the receipt and
the future kitchen phase.

### The cart line-identity change (necessary ripple)

Today a cart line is identified by `sku` (`Cart.findLine(sku)`,
`updateLine(cartId, sku, qty)`, `removeLine(cartId, sku)`), i.e. one line per
sku. Modifiers make two lines share a sku yet differ (burger "+cheese" vs plain).
So **cart lines gain a `lineId` (UUID) and are identified by it**:

- `addLine` merges into an existing line only when **sku AND the selected
  modifier set match exactly** (empty set = a plain line); otherwise it creates a
  new line. This preserves the "two plain burgers → qty 2" behavior while keeping
  differently-modified burgers separate.
- `updateLine(cartId, lineId, qty)` and `removeLine(cartId, lineId)` replace the
  sku-keyed signatures.
- **Affected callers to migrate:** the cart `web` controller's update/remove
  line endpoints (now take `lineId`), and their tests. `sales` checkout only
  iterates `cart.lines()` (unaffected). `dining` close only calls
  `createCart`/`addLine`/`close` (unaffected by the update/remove change; it
  gains the modifier-ids overload). `customer` attach is unaffected.

### Dependency direction (must stay acyclic)

- `menu → product::api` (validate a sku exists when assigning a group/variant
  member), `common`, `database`.
- `cart → menu::api` (validate + resolve selections at add-line). `cart` already
  depends on `product::api`, `configuration::api`. Adding `menu::api`.
- `dining → menu::api` (resolve modifiers at add-time for the ticket + running
  price). `dining` already depends on `cart::api`, `sales::api`, `product::api`,
  `configuration::api`.
- `sales`, `receipt`: no new module dependency — they carry modifier detail as
  their own value data copied from the cart line at checkout.

Nothing depends on `menu`; `menu` does not depend on `cart`/`dining`/`sales`.
Acyclic. `ModularityTests` gates it.

## Components

### `menu` module

**`domain`** (package-private entities):
- `ModifierGroup` — `id`, `name`, `minSelections` (int), `maxSelections` (int),
  `active`.
- `ModifierOption` — `id`, `groupId`, `name`, `priceDelta` (`BigDecimal`),
  `active`.
- `ModifierGroupAssignment` — `id`, `groupId`, `sku` (attaches a group to a
  product; many-to-many via rows so "Temperature" can serve many steaks).
- `VariantGroup` — `id`, `name`, `active`.
- `VariantMember` — `id`, `variantGroupId`, `sku`, `displayLabel`.

**`api`:**
- DTOs: `ModifierGroupView(UUID id, String name, int minSelections,
  int maxSelections, List<ModifierOptionView> options)`,
  `ModifierOptionView(UUID id, String name, BigDecimal priceDelta)`,
  `VariantGroupView(UUID id, String name, List<VariantMemberView> members)`,
  `VariantMemberView(String sku, String displayLabel)`,
  `ResolvedModifier(UUID optionId, String name, BigDecimal priceDelta)`,
  `ModifierResolution(List<ResolvedModifier> modifiers, BigDecimal totalDelta)`.
- Commands: `CreateModifierGroupCommand`, `AddOptionCommand`,
  `AssignGroupToSkuCommand`, `CreateVariantGroupCommand`, `AddVariantMemberCommand`.
- `MenuService` facade:
  - Admin (MANAGER/ADMIN): `createModifierGroup`, `addOption`, `assignGroupToSku`,
    `unassignGroupFromSku`, `createVariantGroup`, `addVariantMember`,
    `deactivateModifierGroup`, `deactivateVariantGroup`.
  - Query: `List<ModifierGroupView> groupsForSku(String sku)`;
    `ModifierResolution resolveSelections(String sku, List<UUID> selectedOptionIds)`
    — validates each option belongs to a group assigned to `sku`, enforces every
    assigned group's `min/max`, returns names + deltas + summed `totalDelta`;
    throws `DomainException.validation` on any rule violation;
    `List<VariantGroupView> listVariantGroups()`;
    `List<VariantGroupView> variantGroupsForSku(String sku)`.

**`web`** — `MenuController`: admin CRUD endpoints (`@PreAuthorize` MANAGER/ADMIN);
read endpoints `GET /menu/products/{sku}/modifier-groups` and
`GET /menu/variant-groups` (any authenticated user — the ordering UI needs them).

**`application`** — `DefaultMenuService` (`@Transactional`). **`infrastructure`** —
JPA repositories.

### `cart` changes

- `Cart`/`CartLine` domain: line gains `lineId` (UUID), `basePrice`, a collection
  of selected modifiers (`optionId`, `name`, `priceDelta`), and `effectiveUnitPrice
  = basePrice + Σ deltas`. Line equality for merge = `sku` + set of `optionId`s.
- `CartService`:
  - keep `addLine(cartId, sku, qty)` (plain line, unchanged behavior);
  - add `addLine(cartId, sku, qty, List<UUID> modifierOptionIds)` — calls
    `menu.resolveSelections(sku, ids)`, stores modifiers, sets effective price;
  - change `updateLine(cartId, lineId, qty)` and `removeLine(cartId, lineId)` to
    line-id-keyed.
- `CartLineView` gains `UUID lineId`, `BigDecimal basePrice`, and
  `List<CartLineModifierView>(UUID optionId, String name, BigDecimal priceDelta)`;
  `unitPrice` is the effective price (so sales/pricing are unchanged).

### `sales` + `receipt` changes

- `SaleLine` persists a modifier breakdown (child rows: `optionId`, `name`,
  `priceDelta`), copied from the cart line at checkout. `SaleLineView` gains
  `List<SaleLineModifierView>`. `unitPrice`/`netAmount`/`taxAmount` are already
  computed from the effective price — no math change.
- `ReceiptLineData` gains a modifier list; the text receipt prints each modifier
  indented under its parent line (name + non-zero `priceDelta`).
- `DefaultSalesService`: when mapping cart lines → sale lines, carry the modifier
  detail; `PricingInput`/`TaxLineInput` continue to use the (effective)
  `unitPrice` unchanged.

### `dining` changes (Phase-10 integration)

- `OrderLine` gains modifier selections (resolved detail: `optionId`, `name`,
  `priceDelta`). `AddLineCommand` gains `List<UUID> modifierOptionIds`;
  `DefaultDiningService.addLine` calls `menu.resolveSelections` to validate +
  resolve so the ticket shows names and a running line price. `OrderLineView`
  gains the modifier list.
- **`closeOrder` no longer aggregates by sku.** It adds **one cart line per order
  line**, carrying the modifier detail already stored on the order line. This
  replaces the Phase-10 `LinkedHashMap` sku-merge (documented there as correct
  only pre-modifiers).
- **Modifier prices are snapshot-at-order-time (the billing contract).** The
  order line resolves modifiers **once**, at add-time, via `menu.resolveSelections`
  — this validates the selection and captures each option's `name` + `priceDelta`
  onto the `OrderLine`. Those captured deltas are the **authoritative** modifier
  prices on the bill: **the guest pays what was quoted when the item was ordered.**
  At close the order line's stored modifiers are handed to the cart *pre-resolved*
  (via `carts.addLinePreResolved(cartId, sku, qty, modifiers)`), which folds them
  into the effective unit price **without** calling `resolveSelections` again. So:
  a mid-service price change or an option deactivation **never** re-prices an open
  check and **never** blocks closing a table. (Rationale: matches restaurant
  billing norms and the system's offline-first "always able to close" principle;
  a re-resolve-at-close alternative was rejected because deactivating a modifier
  would make `resolveSelections` throw and leave the table unclosable.)
  - *Scope note:* only the **modifier deltas** are snapshotted. The **base**
    product price is still taken from the current `product` catalog at close (the
    unchanged Phase-10 behavior — plain dine-in lines already re-price the base at
    close). Snapshotting the base price is out of scope.
- **New cart seam.** `cart` gains `addLinePreResolved(cartId, sku, qty,
  List<CartLineModifierInput>)` alongside the 11a `addLine(…, List<UUID>
  optionIds)`. `CartLineModifierInput(UUID optionId, String name, BigDecimal
  priceDelta)` is a new `cart::api` record. As part of adding it, `cart`'s domain
  `addLineWithModifiers` is refactored to take `CartLineModifierInput` instead of
  `menu::api`'s `ResolvedModifier`, so the cart **domain** no longer references
  `menu` at all (the `menu` resolve stays in `cart`'s application layer on the
  `optionIds` path only). `dining → menu::api` is retained (add-time resolve);
  `dining` uses `cart::api`'s new pre-resolved seam at close.

## Data model & migrations

Flyway per-module dirs, globally sequential from the next free number **V26**
(current max V25). `store-server` runs Flyway; `embedded` uses `ddl-auto`.

- `V26` (`db/migration/menu/`): `modifier_group`, `modifier_option`,
  `modifier_group_assignment` (unique `(group_id, sku)`), `variant_group`,
  `variant_member`. Register `classpath:db/migration/menu` in
  `application-store-server.yml`.
- `V27` (`db/migration/cart/`): add `cart_line.line_id` (new PK) and
  `cart_line_modifier` (`line_id` FK, `option_id`, `name`, `price_delta`).
- `V28` (`db/migration/sales/`): `sale_line_modifier` (`sale_line` FK,
  `option_id`, `name`, `price_delta`).
- `V29` (`db/migration/dining/`): `order_line_modifier` (`order_line` FK,
  `option_id`, `name`, `price_delta`).

UUID PKs stored as `VARCHAR(36)` via `@JdbcTypeCode(SqlTypes.VARCHAR)`; money is
`BigDecimal` / `NUMERIC(19,3-4)` per existing columns.

## Configuration

No new tunables required. (Modifier/variant vocabularies are data rows, not
settings.)

## Error handling

- `resolveSelections`: an option not belonging to a group assigned to the sku, or
  a group whose min/max is violated (e.g. no temperature chosen, or two
  temperatures when max=1), → `DomainException.validation` with a message naming
  the group.
- Assigning a group/variant member to an unknown sku → validation error (via
  `ProductCatalog`).
- Deactivating a group leaves existing sale/order history intact (modifier detail
  is copied by value onto lines, not referenced live).
- Selecting an inactive option is rejected at resolve-time.

## Testing

`@SpringBootTest @ActiveProfiles("embedded")` (in-memory SQLite):

- **menu:** create a group with options; assign to a sku; `groupsForSku` returns
  it; `resolveSelections` sums deltas and returns names; forced group with no
  selection rejected; exceeding `maxSelections` rejected; option from an
  unassigned group rejected; variant group create + member add + list.
- **cart:** `addLine` with modifiers sets effective price (base + deltas) and
  stores the modifier list; two adds with *different* modifiers create two lines;
  two adds with the *same* sku + same modifiers merge to qty 2; `updateLine`/
  `removeLine` by `lineId`; admin CRUD manager-gating.
- **sales/receipt:** checkout a cart with a modified line → the `Sale` line total
  and tax reflect the effective price, the sale line persists the modifiers, and
  the receipt renders them as sub-items.
- **dining:** add an order line with modifiers (running line price correct);
  close → the produced `Sale` has one line per order line (no sku merge) with
  modifier detail and correct grand total.
- **`ModularityTests`**: `menu` recognized; `cart → menu::api` and
  `dining → menu::api` within `allowedDependencies`; only `::api` named interfaces
  crossed.
- Full `./mvnw verify` green incl. store-server Testcontainers (migrations
  V26–V29 validate against the entities).

## Out-of-scope reminders (guardrails for implementation)

Do **not**, in this phase: route or print to any station; implement split
billing or service charges; add nested/dependent modifiers, per-modifier
quantities, or per-modifier tax rules; source menu data from the ERP; cascade
modifier groups across variant members. Each is a named later phase or a
deliberate exclusion.
