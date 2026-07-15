# Slice 7 — Table States + Takeaway (Design)

**Date:** 2026-07-15
**Branch:** `feat/terminal-ui-restaurant-slice`
**Scope:** Richer dine-in table states on the terminal table map, plus a takeaway
(QUICK_SERVICE) flow modelled on counter pseudo-tables — with the minimal backend
change needed to classify and describe open orders.

## Goal

The table-map screen distinguishes free / seated / active tables and flags long-open
orders, and gains a `Tables | Takeaway` segmented view. Takeaway orders are ordinary
`QUICK_SERVICE` orders opened against counter-labelled tables; the cashier starts one
with a **New takeaway** button and sees open takeaway orders in a separate list. No
new persistence concept and no schema migration.

## Decisions (settled during brainstorming)

| Decision | Choice |
|---|---|
| Takeaway model | **Counter pseudo-tables, no migration.** A takeaway is a `QUICK_SERVICE` order opened against a counter-labelled `DiningTable`. (Tableless orders and a `takeaway` column were both rejected.) |
| Table states | **Free / Seated / Active + dwell attention badge**, all derived from the existing order summary (`lineCount`, `openedAt`). (Two-state and full kitchen-fired/awaiting-payment sets rejected.) |
| Takeaway UX | **`Tables | Takeaway` segmented view** on the existing table-map screen. Takeaway segment = New-takeaway button + list of open takeaway orders. (Split-screen and inline-counter-tiles rejected.) |
| Counter identification | **Label-prefix convention held in config** (`dining.takeaway.label-prefix`, default `"Counter "`). (A `takeaway` table column / migration and a single-fixed-table id were rejected.) |

## Architecture

Everything rides on the existing order/table model. A takeaway is just a
`QUICK_SERVICE` order against a counter-labelled table — no new entity, no migration.
The existing quote / close / close-split paths already treat it correctly:
`resolveApplyServiceCharge` gates the service charge on
`order.getServiceType() == ServiceType.DINE_IN`, so takeaway orders skip the service
charge automatically with **no change** to that code.

The only backend change is enriching the order **summary** so the terminal can (a)
classify an open order as dine-in vs takeaway by semantics and (b) render state. All
richer table state is derived on the terminal from data already in the summary.

## Global Constraints (inherited by every task)

- **Terminal is a separate build**, not in the root reactor:
  `./mvnw -f pos-terminal/pom.xml clean test` (headless; no TestFX/display). Backend:
  `./mvnw test`. `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` first.
- **No schema migration** in this slice. `OpenOrderView.serviceType` is a projection of
  an existing non-null column, not a DDL change.
- **Terminal holds no business rules and computes no money.** State derivation is
  presentation logic over server-supplied summary fields only.
- **MVVM sync-VM convention:** ViewModel logic is synchronous on the calling thread;
  the controller runs it off the FX thread via `FxTasks`. Plain fields are control-flow
  truth; the only observable written outside the FX thread is `errorMessage`, written
  inside `ui.accept(...)`. An async-dispatcher regression test is mandatory.
- **`now` is injected** into the ViewModel as a `Supplier<Instant>` clock (defaulting to
  `Instant::now`) so dwell-threshold logic is deterministic under test. `Instant.now()`
  is never called inline in the VM.
- **Money/enums cross as Strings** in DTOs (`"DINE_IN"` / `"QUICK_SERVICE"`), mirroring
  the server JSON field-for-field; DTOs carry `@JsonIgnoreProperties(ignoreUnknown = true)`.
- **CSS uses existing emerald tokens / `derive()` only**; new tap targets ≥ 56px.
- After any change, re-run the affected module's tests **and** `ModularityTests`.
- Commit trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. Never stage
  or commit the pre-existing `M CLAUDE.md`.

## Part 1 — Backend

### 1.1 `OpenOrderView` gains `serviceType`

`dining.api.OpenOrderView` (record) gains a trailing `String serviceType` (the enum name,
serialized as its `ServiceType` value). `DefaultDiningService.listOpenOrders()` already
loads each `DiningOrder`; add `o.getServiceType().name()` (or the enum — Jackson serializes
it to its name) to the projection. No other backend method changes.

The terminal `OpenOrderView` DTO gains the matching `String serviceType` field.

### 1.2 No changes to order lifecycle

`openOrder`, `getOrder`, `close`, `close-split`, `quote`, `quote-split`,
`resolveApplyServiceCharge` are untouched. A `QUICK_SERVICE` order opened against a counter
table is a normal order everywhere downstream; the service charge already excludes it.

### 1.3 `DevDiningSeeder` (`@Profile("dev")`, new)

There is no dining-table seeder today — a fresh dev run has an empty floor. Add a seeder
mirroring `DevCatalogueSeeder` (dev-profile `ApplicationRunner`, module-local so
`ModularityTests` is unaffected) that, if no tables exist yet, registers via
`DiningService.registerTable`:
- Dine-in tables `T1`…`T6` (varied seat counts).
- Counter tables `Counter 1`, `Counter 2`, `Counter 3` — labels using the same prefix
  literal the terminal defaults to (`"Counter "`). **Coupling note:** the prefix default
  is duplicated between this seeder and the terminal config default; both use `"Counter "`.
  A deployment that overrides the terminal prefix must register counters to match.

Idempotent: seeds only when `listTables()` is empty, so restarts don't duplicate.

## Part 2 — Terminal: state model & segmented screen

### 2.1 `TableCell` state

`TableCell` (record) is extended to carry table state:

```java
public enum TableState { FREE, SEATED, ACTIVE }

public record TableCell(UUID tableId, String label, TableState state, boolean attention,
                        UUID orderId) {
    public boolean occupied() { return state != TableState.FREE; }
}
```

`orderId` is the open order id when occupied, else `null` (unchanged meaning). `occupied()`
is retained so existing call sites (`openOrResume`) keep working.

### 2.2 `TableMapViewModel` — derivation, bucketing, clock

Constructor gains a `Supplier<Instant> clock` and reads the counter prefix + dwell
threshold from config (passed in, not read statically). `refresh()`:

1. Fetch `tables()` and `openOrders()` (unchanged calls).
2. Build `orderByTable: Map<UUID, OpenOrderView>` from open orders.
3. **Dine-in cells** (`cells`, the grid): for each **active** table whose label does **not**
   start with the counter prefix, derive:
   - `FREE` when it has no open order.
   - `SEATED` when it has an open order with `lineCount == 0`.
   - `ACTIVE` when it has an open order with `lineCount > 0`.
   - `attention = occupied && clock.get() − order.openedAt() > dwellThreshold`.
4. **Takeaway list** (`takeawayOrders`, a second `ObservableList<TakeawayRow>`): every open
   order with `serviceType.equals("QUICK_SERVICE")`, mapped to a row (order id, counter
   label, `openedAt`, `lineCount`, `attention`). Counter tables never enter `cells`.
5. `ui.accept(...)` sets both lists and clears `errorMessage`; on `ApiException`, sets
   `errorMessage` (existing `messageOf` helper).

New method **`openTakeaway()`**: find the first **counter-prefixed active table with no open
order** (deterministic order: table list order); if found, `dining.openOrder(tableId,
"QUICK_SERVICE")` and return the new order id; if none, set `errorMessage` to
`"All counters are busy"` and return `null`. Runs synchronously like `openOrResume`.

`TakeawayRow` is a small VM record: `record TakeawayRow(UUID orderId, String label,
Instant openedAt, int lineCount, boolean attention) {}`.

### 2.3 Screen: `table-map.fxml` + `TableMapController`

- Header gains a `Tables | Takeaway` **segmented toggle** (`.segmented` container, two
  `.segment` ToggleButtons in a `ToggleGroup`; null-toggle re-select fallback so a segment
  is always selected — the slice-6 mode-toggle pattern).
- Center holds **both** regions, swapped by `visible`/`managed`: the existing `tableFlow`
  `FlowPane` (Tables) and a new takeaway `VBox`/`ScrollPane` (Takeaway) with a **New
  takeaway** primary button above a rebuilt list of takeaway rows.
- Polling refresh (unchanged `Timeline`) repaints **both** regions every interval; the
  segment toggle only flips visibility, it does not stop polling.
- Tile rendering adds the state class (`.table-free` / `.table-seated` / `.table-active`)
  and an attention badge/marker when `cell.attention()`. Tile caption reflects state
  ("Open" / "Seated" / "In use"); occupied tiles show dwell.
- Takeaway row tap → `navigator.toOrder(row.orderId())`. **New takeaway** → `FxTasks.run`
  of `vm.openTakeaway()`, then `navigator.toOrder(id)` when non-null (null → error already
  surfaced, stay on screen), mirroring `open(cell)`.
- FX-threading: reuse the established pattern — run VM calls off-thread via `FxTasks`;
  rebuild list regions on the FX thread from the observable lists' change listeners.

### 2.4 `DiningApi.openOrder` overload

```java
public OrderView openOrder(UUID tableId) {                 // existing: delegates DINE_IN
    return openOrder(tableId, "DINE_IN");
}
public OrderView openOrder(UUID tableId, String serviceType) {
    return client.post("/dining/orders", new OpenOrderRequest(tableId, serviceType),
            new TypeReference<OrderView>() {});
}
```

`OpenOrderRequest` already carries `(UUID tableId, String serviceType)`.

## Part 3 — Config, CSS, testing

### 3.1 Terminal config

`TerminalConfig` + `pos-terminal.properties` gain two keys (and both are added to the
system-property overlay list in `TerminalConfig.load()`):

- `dining.takeaway.label-prefix` — default `"Counter "`.
- `dining.dwell.attention.minutes` — default `45` (parsed as int with the existing
  `NumberFormatException` guard pattern; exposed as a `Duration` accessor).

### 3.2 CSS (tokens only)

New classes, all from existing emerald tokens / `derive()`, tap targets ≥ 56px:
`.segmented`, `.segment` (+ `:selected`), `.table-seated`, `.table-active`,
`.attention-badge`, `.takeaway-row`. `.table-free` is retained. A tile always carries
**exactly one** of `.table-free` / `.table-seated` / `.table-active`; the old
`.table-occupied` class is retired (its `tileFor` usage is replaced by the three-state
mapping), and its rule is removed from `app.css` to avoid a dead selector.

### 3.3 Testing

**Backend:**
- `listOpenOrders` includes `serviceType` — service test asserts the field for a seeded
  DINE_IN and a QUICK_SERVICE order; web test asserts the JSON field on `GET /dining/orders`.
- `ModularityTests`.

**Terminal (headless):**
- `TableMapViewModelTest`: FREE/SEATED/ACTIVE derivation; attention threshold via injected
  clock (just-under and just-over the dwell window); counter tables excluded from the grid;
  QUICK_SERVICE orders bucketed into the takeaway list; `openTakeaway` picks the first free
  counter; all-counters-busy sets the error and returns null; async-dispatcher regression
  test.
- `DiningApiTest` (StubServer): `openOrder(tableId, "QUICK_SERVICE")` serializes
  `serviceType` in the body; `OpenOrderView` parses `serviceType`.
- `FxmlContractTest` / `AppCssTest`: new fx:ids (segmented toggle, takeaway region, New
  takeaway button) and CSS classes.
- Manual GUI E2E section in `pos-terminal/README.md`: (1) seat a dine-in table → Seated,
  add items → Active, leave open past the dwell window → attention badge; (2) New takeaway
  → order screen → pay, confirming no service charge on the quote; (3) open takeaway rows
  list and resume; (4) all counters busy → error. Run backend with
  `--spring.profiles.active=embedded,dev` (now seeds tables), login `manager`/`manager`.

## Out of scope

Tableless orders; table transfer / merge / moving an order between counters; kitchen-fired
and awaiting-payment signals; per-table timers beyond the dwell badge; a manager UI for
registering counters (dev seeder + existing MANAGER `POST /dining/tables` cover it).
