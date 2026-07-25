# Item Availability / 86ing — Design Spec (Slice 20)

**Date:** 2026-07-25
**Branch:** `feat/terminal-ui-restaurant-slice`
**Type:** Full-stack (backend `product` / `cart` / `dining` / `audit` + `pos-terminal`)

## Problem

A restaurant routinely needs to mark a menu item "sold out for tonight" — *86ing* it — so
order-takers stop selling it and the kitchen isn't handed tickets it can't make. The system has
**no availability concept today**:

- `inventory` is a pure numeric `quantityOnHand` (decremented on sale) with no availability
  boolean — it drives low-stock reporting only, never gates ordering.
- `product` has an `active` flag, but that is a **permanent soft-delete** (catalogue membership),
  and the ordering path (`cart.addLine` → `catalogue.findBySku`) does not even check it.
- There is no manual, reversible "out right now" state anywhere (menu, product, dining, inventory).

So 86ing is genuinely new and full-stack, in the shape of prior full-stack slices (11 transfer,
17 KDS, 19 returns).

## Decisions (locked during brainstorming)

1. **Model:** a dedicated manual `available` boolean on the product SKU, **separate from `active`**.
   Manual, reversible, independent of stock counts. `active` = "on the menu at all"; `available` =
   "can be ordered right now."
2. **Authorization:** any signed-in cashier/server may toggle (no manager PIN). Every toggle is
   audited via the existing outbox → `audit` path.
3. **Terminal UI:** a dedicated **"86 board"** screen (list + toggle) reachable from **Home**, plus
   ordering pickers that grey out + block 86'd items.
4. **Propagation:** re-fetch on open (no new WebSocket topic). Correctness comes from
   **server-side rejection at add-line**, so a stale open screen fails atomically rather than
   ordering a 86'd item.

## Scope

**In scope**

- `available` flag on the product SKU (covers plain items and, because each variant member is its
  own SKU, specific variant sizes).
- Cashier-level toggle endpoint + audit.
- Server-side add-line rejection in both ordering tracks (`cart`, `dining`).
- Terminal 86 board screen + Home tile + greyed/blocked ordering pickers.

**Out of scope (deferred, YAGNI)**

- 86ing a **modifier option** (e.g. "extra bacon") — SKU-level only for this slice.
- Auto-reset of availability (e.g. nightly / at shift close) — flag persists until manually
  restored.
- Any coupling to inventory stock counts (no auto-86 at quantity 0).
- Live WebSocket push (`/ws/floor` AVAILABILITY topic) — re-fetch-on-open only.

## Design

### 1. Data & domain (`product` module)

- **Migration `V37`** adds `available BOOLEAN NOT NULL DEFAULT true` to the product table.
  (Store-server runs Flyway; embedded gets the column via Hibernate `ddl-auto`. V36 is the current
  ceiling — this is the next global sequential number.)
- `Product` entity gains the `available` field and two domain methods: `markUnavailable()` /
  `markAvailable()`.
- `ProductView` (the `product :: api` DTO) exposes `available` so ordering services and the terminal
  can read it. Adding a field to the record is backward-compatible.

### 2. Write path & audit (`product` + `audit`)

- New service method `ProductAdminService.setAvailability(String sku, boolean available, String actor)`:
  flips the flag on the entity and publishes `ProductChanged(sku, type, actor, null, null)` **inside
  the same transaction** — reusing the established outbox → audit path (never a synchronous
  `audit.record`).
- Add two `ProductChangeType` values: `MARKED_UNAVAILABLE` and `MARKED_AVAILABLE`. Because
  `ProductChangedAuditListener` switches over `ProductChangeType` with **no `default`**, adding these
  values is a compile error until the listener handles them — forcing matching `AuditAction`
  constants and switch cases in the same commit. This is the documented one-way `audit → product::api`
  spillover, not scope creep.
- **Endpoint:** `PUT /products/{sku}/availability` with body `{"available": false}`.
  - **Authenticated, no role gate** (cashier-level). It therefore lives on an **ungated controller
    method**, not the ADMIN-gated `ProductAdminController` write methods.
  - `actor` is taken from the JWT principal (as other audited writes do).
  - Returns the updated `ProductView` (or 204). Unknown SKU → 404.

### 3. Server-side enforcement (`cart` + `dining`)

Both `cart.addLine` (retail) and `dining.addLine` (restaurant) already resolve the SKU through
`catalogue.findBySku`. After resolving, if `available == false`:

- throw a dedicated `ItemUnavailableException` (per module, or a shared one under the ordering
  path), mapped to **HTTP 409 Conflict** with a problem-detail body identifying the SKU.

This mirrors the existing "server is the source of truth; a stale client causes a clean atomic
failure, never a wrong result" convention (close/checkout stale-safety). No client-side stale-guard
is required beyond showing the error.

### 4. Terminal — 86 board (`pos-terminal`)

- **`AvailabilityViewModel`** — synchronous methods returning plain values:
  - `load()` → fetches the product list (via `GET /products`, now carrying `available`), filtered to
    **active** items, returns rows `(sku, name, available)`.
  - `setAvailability(sku, available)` → calls the new endpoint, returns the updated row/state.
  - Only `errorMessage` is written off-thread (inside `ui.accept(...)`); plain fields are the
    synchronous truth. No inter-thread time source needed (no clock).
- **`AvailabilityController`** + FXML: a searchable list; each row shows the item name, an
  `Available` / `86'D` pill, and a toggle control. The toggle runs its HTTP **off the FX thread**
  inside the `FxTasks.run` `work` lambda; the result is applied in `onDone` via a holder array. No
  blocking VM call inside `onDone`.
- **`AvailabilityApi`** (terminal API client): `list()` (reuse the product-list DTO) and
  `setAvailability(sku, boolean)` → `PUT /products/{sku}/availability`.
- **Navigation:** `Navigator.toAvailability()` + a new **Home tile "86 / Availability"** (visible to
  any signed-in user). Screen implements `Navigator.Screen` if it owns any resource requiring
  `onLeave()` (none expected here — no socket/timer).

### 5. Terminal — ordering pickers

- The dine-in item picker (`SkuPickerDialog`) and the retail item picker read the `available` field
  (added to the terminal's product DTO subset — a `@JsonIgnoreProperties(ignoreUnknown = true)`
  record, so the new field must be added explicitly to deserialize). Rows with `available == false`
  render **greyed, tagged "86'D", with add disabled**.
- Pickers fetch fresh on open (existing behavior), so an item 86'd elsewhere appears unavailable on
  the next open.
- **Stale-open-screen fallback:** if an add-line call returns **409**, the controller surfaces a
  toast "Item is 86'd" and does not add the line.

## Testing

**Backend**

- `ProductAdminService.setAvailability` flips the flag and publishes `ProductChanged` with the new
  type (unit / slice test).
- `cart.addLine` rejects a 86'd SKU with 409; `dining.addLine` rejects a 86'd SKU with 409.
- The availability endpoint is reachable by a **plain cashier** token (no role gate) and updates the
  flag; unknown SKU → 404.
- **Audit E2E** (Awaitility): hitting the real committing endpoint produces an audit row for the new
  `AuditAction` (a bare `publish()` in a non-transactional test would never fire the listener).
- `ModularityTests` — no new disallowed cross-module dependency (`cart`/`dining` already depend on
  `product :: api`; `audit` already depends on `product :: api`).

**Terminal**

- `AvailabilityViewModel` unit tests: load returns active rows; toggle updates state; error path sets
  `errorMessage`.
- **Async-dispatcher regression test** (deferred, undrained `ui` dispatcher) — proves control flow
  uses plain fields, not deferred observables.
- `AvailabilityApi` `StubServer` test: asserts method `PUT`, path `/products/{sku}/availability`,
  request body, and reads `stub.lastPath` / `stub.lastBody`.
- Picker logic test: a product with `available == false` is rendered disabled / tagged and cannot be
  added.

## Modules touched

`product` (flag, entity methods, `ProductView`, service, endpoint, migration V37),
`cart` + `dining` (add-line availability gate + 409 mapping),
`audit` (new `AuditAction` constants + exhaustive switch cases),
`pos-terminal` (AvailabilityViewModel/Controller/Api, Home tile, Navigator, picker grey-out, product
DTO field).

## Open questions / notes for the plan

- Exact placement of the ungated availability endpoint (a small `ProductAvailabilityController` vs. an
  ungated method on an existing product controller) is an implementation detail for the plan; the
  constraint is **authenticated, not role-gated**.
- The pre-existing gap that the ordering path does not filter by `active` is **not** addressed here;
  this slice adds only the `available` gate. The 86 board itself lists active products.
