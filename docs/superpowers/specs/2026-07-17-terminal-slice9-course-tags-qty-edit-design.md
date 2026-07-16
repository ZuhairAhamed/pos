# Terminal Slice 9 — Dine-in course tags + line quantity edit (design)

**Date:** 2026-07-17
**Branch:** `feat/terminal-ui-restaurant-slice` (continues the existing terminal slice series)
**Status:** Approved for planning

## Summary

On the JavaFX terminal's **dine-in order screen**, let staff edit an open order's lines
directly: change a line's **quantity**, change its **course tag**
(`STARTER` / `MAIN` / `DESSERT` / `DRINK`), and **remove** a line — all via **inline
per-row controls** that mirror the retail cart. Course defaults to `MAIN` on add
(unchanged) and is adjusted per line before the line is fired.

This is a **UI-only** slice. The backend already supports everything:

- `POST /dining/orders/{id}/lines` accepts `course` (terminal already sends `"MAIN"`).
- `PUT /dining/orders/{id}/lines/{lineId}?qty=&note=&course=` updates qty, note, and course.
- Fired lines are already locked server-side and client-side.

## Motivation

The dine-in order screen (`OrderController`) is currently read-mostly: you can add a
line (with modifiers) and fire, but you cannot change a quantity or set a course from
the UI. `OrderViewModel.updateQty` exists but no control calls it, and every added line
is hardcoded to course `MAIN` (`OrderController.java:181`). The backend has supported
qty/note/course edits since phase 10–11; this slice closes the UI gap. It also aligns
the dine-in line editor with the retail cart, which already offers inline `−`/`+`
steppers.

## The correctness rule that shapes the design

`DefaultDiningService.updateLine` (`:202-204`) is an **unconditional full replace**:

```java
line.setQty(qty);
line.setNote(note);
line.setCourse(course);
```

So an `updateLine` call that omits `note` or `course` **wipes** them. The terminal's
current `DiningApi.updateLine(orderId, lineId, qty)` sends only `?qty=` — a naive qty
stepper built on it would blank the line's note and course.

**Rule:** every edit resends the current qty **+ note + course** triple, changing only
the one field the user touched. This is the crux behaviour the tests must lock down.

## Decisions (resolved during brainstorming)

1. **Edit affordance:** inline per-row controls (mirror the retail cart), replacing the
   current selection-based `ListView` + bottom `removeButton`. Chosen over a
   selection-based action bar for touch consistency with retail.
2. **Course timing:** per-line **after** add. Lines default to `MAIN` on add (as today);
   the course is changed via a per-row control that calls `updateLine`. Keeps the
   add/modifier flow fast. Editable only on un-fired lines.

## Design

### Components & changes (all under `pos-terminal/`)

**1. `api/DiningApi.updateLine`** — widen the signature to carry the full triple:

```java
OrderView updateLine(UUID orderId, UUID lineId, BigDecimal qty, String note, String course)
```

Builds `PUT /dining/orders/{orderId}/lines/{lineId}?qty=<q>[&note=<n>][&course=<c>]`.
`note` is free text and MUST be URL-encoded; `course` is an enum name and `qty` a
`BigDecimal` (both safe, but encode defensively). Omit the `note`/`course` query
params when the argument is `null` (callers that want to preserve a field pass its
current value — they never rely on omission to preserve).

**2. `viewmodel/OrderViewModel`**

- `updateQty(line, qty)` — now calls
  `dining.updateLine(order.id(), line.id(), qty, line.note(), line.course())`
  (preserves note + course). Keeps the existing fired-line guard (no-op +
  `errorMessage` set via `ui.accept`).
- **New** `updateCourse(line, course)` — calls
  `dining.updateLine(order.id(), line.id(), line.qty(), line.note(), course)`.
  Same fired-line guard.
- Both stay **synchronous**, return `void`, and mutate observables only inside
  `ui.accept(...)` — the terminal FX-threading convention. The controller runs them
  off the FX thread via `FxTasks`.

**3. `view/OrderController`** — replace the selection-based `ListView<OrderLineView>`
(`lineList` + `OrderLineCell`) and the bottom `removeButton` with a `VBox` of rows
rebuilt by a `renderLines()` method, mirroring retail's `renderCart()`/`cartRow()`:

- **Un-fired row:** `[−] <qty> [+]` steppers · product name + modifier/note sub-text ·
  a course dropdown (`STARTER` / `MAIN` / `DESSERT` / `DRINK`) · a `×` remove button.
  Each control dispatches through `FxTasks.run(...)` to the matching VM method
  (`updateQty` / `updateCourse` / `removeLine`).
- **Fired row:** product name + `[fired]` muted badge; **no** edit controls (preserves
  the existing lock).
- `−` is disabled at qty 1 (decrement floor); removing a line is the explicit `×`.
- Remove the `removeButton` field and its selection listener from `initialize()`. Keep
  `fireButton`, `splitButton`, `payButton`, `backButton` and their existing behaviour.
- The existing `lines()` change-listener re-invokes `renderLines()` (same wiring shape
  as today's `lineList.getItems().setAll(...)`), and still toggles `splitButton`
  disabled on empty.

**4. `resources/fxml/order.fxml`** — swap `ListView fx:id="lineList"` and the
`removeButton` for `ScrollPane > VBox fx:id="lineBox"`, plus an empty-state label
(`fx:id="emptyLabel"`) shown when the order has no lines. `splitButton`, `fireButton`,
`payButton`, `backButton`, `subtotalLabel`, `errorLabel`, `categoryTabs` are unchanged.

**5. `resources/css/app.css`** — add a `.course-chip` style for the course dropdown;
reuse the existing `.qty-stepper` and `.cart-line` classes from the retail cart for
visual consistency.

### Data flow

Row control → `OrderController` handler → `FxTasks.run` (off FX thread) →
`OrderViewModel.updateQty` / `updateCourse` / `removeLine` (synchronous: calls
`DiningApi`, re-reads the returned `OrderView`, rebuilds `lines()` + `subtotalText()`
inside `ui.accept`) → `lines()` change listener → `renderLines()` rebuilds the rows.
Identical in shape to the existing add/remove/fire flow and the retail cart.

### Error handling

- Fired-line edits are guarded in the VM (no-op + `errorMessage`); the controller also
  omits edit controls on fired rows so the calls are never dispatched.
- `errorLabel.text` stays bound to `vm.errorMessage()`. `FxTasks` `onError` callbacks
  MUST NOT `setText` the bound label (throws on a bound property) — they log only, per
  the existing convention.
- Server-side validation (e.g. qty ≤ 0) surfaces through `errorMessage`.

### FX-threading (the recurring bug class)

VM methods are synchronous truth on the calling thread; the only observable written off
the FX thread is `errorMessage`, and only inside `ui.accept(...)`. `OrderViewModelTest`
currently has **no** async-dispatcher test — this slice adds one (a deferred, undrained
`ui` dispatcher) using the `ArrayDeque` / `deferred = queue::add` / drain pattern already
used in `PaymentViewModelTest` (`:144`, `:244-253`).

## Testing

- **`DiningApiTest`** — assert `updateLine` PUTs `qty` + `note` + `course`; add a case
  proving a `note` containing spaces/`&` is URL-encoded in the query string.
- **`OrderViewModelTest`** (add async coverage it currently lacks):
  - `updateQty preserves note + course` — the crux regression against the full-replace
    wipe: a fake `DiningApi.updateLine` captures the `note`/`course` it receives and the
    test asserts they equal the line's current values.
  - `updateCourse changes course, preserves qty + note`.
  - `updateCourse on a fired line is a no-op and sets errorMessage`.
  - **Async-dispatcher regression** — deferred `ui`; assert observable writes appear
    only after the queue is drained, for `updateCourse` (and the new `updateQty`).
- Row rendering is display-dependent (constructs JavaFX nodes) → covered by a **README
  manual-E2E** step, the same treatment `ModifierPickerDialog` gets. Add a "Slice 9"
  section to `pos-terminal/README.md`.
- `FxmlContractTest` — no existing assertion references `lineList`/`removeButton`
  (only `splitButton`), so no breakage; optionally add an assertion that `order.fxml`
  declares `lineBox`.

## Out of scope

- **Backend changes** — none; all endpoints and the `CourseTag` enum already exist.
- **Note editing UI** — the note is *preserved* on every edit but is not made editable
  in this slice (a separate concern).
- **Void / unfire / re-fire** of fired lines.
- The retail screen (already has inline steppers) and everything else in the slice audit
  (shift close, service-charge waiver, reports, table transfer/merge).

## Files touched

| File | Change |
|------|--------|
| `pos-terminal/.../api/DiningApi.java` | widen `updateLine` to `(qty, note, course)`, URL-encode |
| `pos-terminal/.../viewmodel/OrderViewModel.java` | `updateQty` preserves note+course; new `updateCourse` |
| `pos-terminal/.../view/OrderController.java` | inline per-row rows (steppers, course, ×); drop `removeButton` |
| `pos-terminal/.../resources/fxml/order.fxml` | `lineList`/`removeButton` → `VBox lineBox` + empty label |
| `pos-terminal/.../resources/css/app.css` | `.course-chip`; reuse `.qty-stepper`/`.cart-line` |
| `pos-terminal/.../api/DiningApiTest.java` | assert qty+note+course PUT, note encoding |
| `pos-terminal/.../viewmodel/OrderViewModelTest.java` | preserve/updateCourse/fired/async tests |
| `pos-terminal/.../FxmlContractTest.java` | (optional) assert `lineBox` |
| `pos-terminal/README.md` | Slice 9 manual-E2E section |

## Verification

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -f pos-terminal/pom.xml clean test
```

All terminal tests green (currently 180; this slice adds cases). Manual E2E per the new
README section against a running `embedded,dev` backend.
