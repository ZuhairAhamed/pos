# Terminal Slice 9 — Dine-in course tags + line quantity edit — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On the JavaFX terminal's dine-in order screen, let staff change a line's quantity, change its course tag, and remove a line via inline per-row controls that mirror the retail cart.

**Architecture:** UI-only slice against an unchanged backend. Widen the terminal's `DiningApi.updateLine` to carry the full `qty`+`note`+`course` triple the server's full-replace endpoint requires, add an `OrderViewModel.updateCourse` and make `updateQty` preserve note/course, then rebuild the order screen's line list from a selection-based `ListView` into a `VBox` of inline rows (steppers + course dropdown + remove), following the existing retail-cart pattern.

**Tech Stack:** Java 21, JavaFX (FXML + controllers + ViewModels), JUnit 5, Maven (`pos-terminal/pom.xml`, a separate build from the root reactor).

## Global Constraints

- JDK 21 required. Set `JAVA_HOME` first: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`.
- Build/test the terminal only via its own POM: `./mvnw -f pos-terminal/pom.xml ... test`. The root `./mvnw verify` does NOT touch `pos-terminal/`.
- Money is `BigDecimal` at scale 2. Never `double`.
- **FX-threading convention (the recurring bug class):** ViewModel methods are synchronous on the calling thread and return plain values; the controller runs them off the FX thread via `FxTasks.run(work, onDone, onError)`. The only observable a VM writes off-thread is `errorMessage`, and only inside `ui.accept(...)`. `FxTasks` `onError` callbacks MUST NOT `setText` a bound label — log only.
- **Backend `updateLine` is a full replace** (`setQty`/`setNote`/`setCourse` all fire every call). Every edit MUST resend the current `qty`+`note`+`course`, changing only the touched field. Omitting a field wipes it.
- Valid course tags mirror the server `CourseTag` enum: `STARTER`, `MAIN`, `DESSERT`, `DRINK`.
- No backend changes in this slice. No new Maven dependencies.

---

## File Structure

| File | Responsibility | Task |
|------|----------------|------|
| `pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java` | Widen `updateLine` to `(qty, note, course)`, URL-encode query | 1 |
| `pos-terminal/src/test/java/com/company/pos/terminal/api/DiningApiTest.java` | Assert updated query params + encoding | 1 |
| `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/OrderViewModel.java` | `updateQty` preserves note+course; new `updateCourse` | 2 |
| `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/OrderViewModelTest.java` | Preserve/updateCourse/fired/async tests | 2 |
| `pos-terminal/src/main/resources/fxml/order.fxml` | `lineList`/`removeButton` → `VBox lineBox` + empty label | 3 |
| `pos-terminal/src/main/resources/css/app.css` | `.course-chip`, `.line-remove` | 3 |
| `pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java` | Slice-9 CSS class contract | 3 |
| `pos-terminal/src/test/java/com/company/pos/terminal/FxmlContractTest.java` | Assert `lineBox` declared | 3 |
| `pos-terminal/src/main/java/com/company/pos/terminal/view/OrderController.java` | Inline per-row line editor | 4 |
| `pos-terminal/README.md` | Slice 9 manual-E2E section | 4 |

Dependency order: **1 → 2 → 4**, with **3** independent (do it before 4, since Task 4's controller expects the `lineBox` fx:id and the new CSS classes).

---

## Task 1: Widen `DiningApi.updateLine` to carry qty + note + course

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java` (replace the method at `:59-62`)
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/DiningApiTest.java` (replace `updateLineSendsQtyAsQueryParam` at `:126-135`)

**Interfaces:**
- Produces: `OrderView DiningApi.updateLine(UUID orderId, UUID lineId, BigDecimal qty, String note, String course)` — issues `PUT /dining/orders/{orderId}/lines/{lineId}?qty=<q>[&note=<enc>][&course=<enc>]`. `note`/`course` params are omitted when null; `note` is URL-encoded (space → `+`).
- Note: the previous 3-arg `updateLine(UUID, UUID, BigDecimal)` is **removed**. Callers in Task 2 use the new signature.

- [ ] **Step 1: Rewrite the failing test**

In `DiningApiTest.java`, replace the whole `updateLineSendsQtyAsQueryParam` method (`:126-135`) with these two tests:

```java
    @Test
    void updateLineSendsQtyNoteAndCourseAsQueryParams() throws Exception {
        try (StubServer stub = new StubServer(200, ORDER_JSON, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            api.updateLine(ORDER_ID, LINE_ID, new BigDecimal("3"), "no onion", "STARTER");
            assertEquals("PUT", stub.lastMethod);
            assertEquals("/dining/orders/33333333-3333-3333-3333-333333333333/lines/"
                    + "66666666-6666-6666-6666-666666666666", stub.lastPath);
            assertTrue(stub.lastQuery.contains("qty=3"), stub.lastQuery);
            assertTrue(stub.lastQuery.contains("note=no+onion"), stub.lastQuery);
            assertTrue(stub.lastQuery.contains("course=STARTER"), stub.lastQuery);
        }
    }

    @Test
    void updateLineOmitsNullNoteAndCourse() throws Exception {
        try (StubServer stub = new StubServer(200, ORDER_JSON, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            api.updateLine(ORDER_ID, LINE_ID, new BigDecimal("3"), null, null);
            assertEquals("PUT", stub.lastMethod);
            assertEquals("qty=3", stub.lastQuery);
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail to compile / fail**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml -Dtest=DiningApiTest test`
Expected: compilation failure — `updateLine(UUID,UUID,BigDecimal,String,String)` does not exist yet.

- [ ] **Step 3: Implement the widened method**

In `DiningApi.java`, replace the existing method:

```java
    /** The server takes {@code qty} as a query parameter (not a body). */
    public OrderView updateLine(UUID orderId, UUID lineId, BigDecimal qty) {
        return client.put("/dining/orders/" + orderId + "/lines/" + lineId + "?qty=" + qty, null,
                new TypeReference<OrderView>() {});
    }
```

with:

```java
    /**
     * Updates a line. The server's endpoint is a FULL REPLACE — it overwrites qty, note, and
     * course on every call — so callers must pass the line's current note/course to preserve them.
     * qty/note/course are query params ({@code note} is URL-encoded; null note/course are omitted).
     */
    public OrderView updateLine(UUID orderId, UUID lineId, BigDecimal qty, String note, String course) {
        StringBuilder path = new StringBuilder("/dining/orders/").append(orderId)
                .append("/lines/").append(lineId).append("?qty=").append(qty);
        if (note != null) {
            path.append("&note=").append(URLEncoder.encode(note, StandardCharsets.UTF_8));
        }
        if (course != null) {
            path.append("&course=").append(URLEncoder.encode(course, StandardCharsets.UTF_8));
        }
        return client.put(path.toString(), null, new TypeReference<OrderView>() {});
    }
```

Add these imports to `DiningApi.java` (alongside the existing `java.*` imports):

```java
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=DiningApiTest test`
Expected: PASS (all `DiningApiTest` cases green).

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/DiningApiTest.java
git commit -m "feat(terminal): DiningApi.updateLine carries qty+note+course (full-replace safe)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `OrderViewModel` — preserve note/course on qty edit, add `updateCourse`

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/OrderViewModel.java` (`updateQty` at `:89-95`; add `updateCourse`)
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/OrderViewModelTest.java`

**Interfaces:**
- Consumes: `DiningApi.updateLine(UUID, UUID, BigDecimal, String, String)` from Task 1.
- Produces: `void OrderViewModel.updateCourse(OrderLineView line, String course)` — fired-line-guarded; preserves qty+note. `updateQty` now preserves note+course.

- [ ] **Step 1: Fix the existing test that overrides the old signature, and add the new failing tests**

In `OrderViewModelTest.java`, the existing `updateQtyRefreshesState` test (`:110-136`) overrides the removed 3-arg `updateLine`. Replace its override so it matches the new 5-arg signature — change the method inside the anonymous `DiningApi` (`:125-128`):

```java
                    @Override
                    public OrderView updateLine(UUID oid, UUID lineId, BigDecimal qty, String note,
                            String course) {
                        return orderWith(List.of(after));
                    }
```

Then add these four tests to the class (before the closing brace at `:190`):

```java
    @Test
    void updateQtyPreservesNoteAndCourse() {
        OrderLineView line =
                new OrderLineView(
                        firedLineId, "BURGER", new BigDecimal("1"), "no onion", "STARTER", null,
                        List.of());
        String[] captured = new String[2];
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(line));
                    }

                    @Override
                    public OrderView updateLine(UUID oid, UUID lineId, BigDecimal qty, String note,
                            String course) {
                        captured[0] = note;
                        captured[1] = course;
                        return orderWith(List.of(new OrderLineView(
                                lineId, "BURGER", qty, note, course, null, List.of())));
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        vm.updateQty(line, new BigDecimal("4"));
        assertEquals("no onion", captured[0]);
        assertEquals("STARTER", captured[1]);
    }

    @Test
    void updateCourseChangesCoursePreservingQtyAndNote() {
        OrderLineView line =
                new OrderLineView(
                        firedLineId, "BURGER", new BigDecimal("2"), "no onion", "MAIN", null,
                        List.of());
        BigDecimal[] capturedQty = new BigDecimal[1];
        String[] capturedNote = new String[1];
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(line));
                    }

                    @Override
                    public OrderView updateLine(UUID oid, UUID lineId, BigDecimal qty, String note,
                            String course) {
                        capturedQty[0] = qty;
                        capturedNote[0] = note;
                        return orderWith(List.of(new OrderLineView(
                                lineId, "BURGER", qty, note, course, null, List.of())));
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        vm.updateCourse(line, "DESSERT");
        assertEquals(new BigDecimal("2"), capturedQty[0]);
        assertEquals("no onion", capturedNote[0]);
        assertEquals("DESSERT", vm.lines().get(0).course());
    }

    @Test
    void updateCourseOnFiredLineIsNoOp() {
        OrderLineView fired =
                new OrderLineView(
                        firedLineId, "BURGER", new BigDecimal("1"), null, "MAIN", Instant.now(),
                        List.of());
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(fired));
                    }

                    @Override
                    public OrderView updateLine(UUID oid, UUID lineId, BigDecimal qty, String note,
                            String course) {
                        throw new AssertionError("updateLine must not be called for a fired line");
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        vm.updateCourse(fired, "DESSERT");
        assertEquals("Fired lines cannot be changed", vm.errorMessage().get());
        assertEquals("MAIN", vm.lines().get(0).course());
    }

    @Test
    void deferredDispatcherHoldsCourseWriteUntilDrained() {
        OrderLineView before =
                new OrderLineView(
                        firedLineId, "BURGER", new BigDecimal("1"), null, "MAIN", null, List.of());
        OrderLineView after =
                new OrderLineView(
                        firedLineId, "BURGER", new BigDecimal("1"), null, "DESSERT", null, List.of());
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(before));
                    }

                    @Override
                    public OrderView updateLine(UUID oid, UUID lineId, BigDecimal qty, String note,
                            String course) {
                        return orderWith(List.of(after));
                    }
                };
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        OrderViewModel vm = new OrderViewModel(dining, cache(), queue::add);
        vm.load(orderId);
        while (!queue.isEmpty()) queue.poll().run();       // drain load → baseline
        assertEquals("MAIN", vm.lines().get(0).course());
        vm.updateCourse(before, "DESSERT");
        assertEquals("MAIN", vm.lines().get(0).course());  // deferred: not yet applied
        while (!queue.isEmpty()) queue.poll().run();       // drain the course write
        assertEquals("DESSERT", vm.lines().get(0).course());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=OrderViewModelTest test`
Expected: compilation failure — `updateCourse` does not exist yet (and the class won't compile until it's added).

- [ ] **Step 3: Implement `updateQty` preservation + `updateCourse`**

In `OrderViewModel.java`, replace the existing `updateQty` (`:89-95`):

```java
    public void updateQty(OrderLineView line, BigDecimal qty) {
        if (!canEdit(line)) {
            ui.accept(() -> errorMessage.set("Fired lines cannot be changed"));
            return;
        }
        apply(() -> dining.updateLine(order.id(), line.id(), qty));
    }
```

with (preserve note+course, plus the new `updateCourse` right after):

```java
    public void updateQty(OrderLineView line, BigDecimal qty) {
        if (!canEdit(line)) {
            ui.accept(() -> errorMessage.set("Fired lines cannot be changed"));
            return;
        }
        apply(() -> dining.updateLine(order.id(), line.id(), qty, line.note(), line.course()));
    }

    /** Changes a line's course tag, preserving its qty and note. Fired lines are locked. */
    public void updateCourse(OrderLineView line, String course) {
        if (!canEdit(line)) {
            ui.accept(() -> errorMessage.set("Fired lines cannot be changed"));
            return;
        }
        apply(() -> dining.updateLine(order.id(), line.id(), line.qty(), line.note(), course));
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=OrderViewModelTest test`
Expected: PASS (all `OrderViewModelTest` cases green, including the new async and preservation tests).

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/OrderViewModel.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/OrderViewModelTest.java
git commit -m "feat(terminal): OrderViewModel.updateCourse + qty edit preserves note/course

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: FXML + CSS for inline rows (with contract tests)

**Files:**
- Modify: `pos-terminal/src/main/resources/fxml/order.fxml`
- Modify: `pos-terminal/src/main/resources/css/app.css` (append a slice-9 block)
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java` (add a slice-9 method)
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/FxmlContractTest.java` (add a `lineBox` assertion)

**Interfaces:**
- Produces: `order.fxml` declares `fx:id="lineBox"` (a `VBox`) and `fx:id="emptyLabel"` (a `Label`); it no longer declares `lineList` or `removeButton`. CSS defines `.course-chip` and `.line-remove`. Task 4's controller injects `lineBox` + `emptyLabel`.

- [ ] **Step 1: Write the failing contract tests**

In `AppCssTest.java`, add this method before the class closing brace:

```java
    @Test
    void definesSliceNineClasses() throws Exception {
        String css = css();
        for (String cls : new String[] { ".course-chip", ".line-remove" }) {
            assertTrue(css.contains(cls), "missing style class: " + cls);
        }
    }
```

In `FxmlContractTest.java`, add this method (mirror the existing `orderDeclaresSplitButton` shape at `:65`):

```java
    @Test
    void orderDeclaresLineBox() throws Exception {
        assertTrue(resource("/fxml/order.fxml").contains("fx:id=\"lineBox\""),
                "order.fxml must declare the inline line-box container");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=AppCssTest,FxmlContractTest test`
Expected: FAIL — `.course-chip`/`.line-remove` missing from CSS; `lineBox` missing from FXML.

- [ ] **Step 3: Update the FXML**

Replace the entire contents of `pos-terminal/src/main/resources/fxml/order.fxml` with:

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.geometry.Insets?>
<?import javafx.scene.control.Button?>
<?import javafx.scene.control.Label?>
<?import javafx.scene.control.ScrollPane?>
<?import javafx.scene.control.TabPane?>
<?import javafx.scene.layout.BorderPane?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.layout.Pane?>
<?import javafx.scene.layout.VBox?>

<!-- Two-pane order screen: LEFT = current order (inline-editable lines + estimated subtotal +
     actions); CENTER = touch menu grid, one tab per category. -->
<BorderPane styleClass="screen" xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml">
  <left>
    <VBox spacing="12" prefWidth="440" styleClass="order-pane">
      <padding><Insets top="0" right="24" bottom="0" left="0"/></padding>

      <Label text="Order" styleClass="title"/>

      <ScrollPane fitToWidth="true" VBox.vgrow="ALWAYS" styleClass="order-lines">
        <VBox fx:id="lineBox" spacing="8"/>
      </ScrollPane>
      <Label fx:id="emptyLabel" text="No items yet" styleClass="empty-cart"/>

      <Label fx:id="subtotalLabel" styleClass="subtotal"/>

      <!-- Error banner: danger style, hidden (and not laid out) when empty. -->
      <Label fx:id="errorLabel" styleClass="error-banner" wrapText="true" maxWidth="Infinity"/>

      <HBox spacing="12">
        <Button fx:id="fireButton" text="Fire to kitchen" styleClass="btn-primary" HBox.hgrow="ALWAYS" maxWidth="Infinity"/>
      </HBox>

      <HBox spacing="12">
        <Button fx:id="backButton" text="Back to tables" styleClass="btn-secondary"/>
        <Pane HBox.hgrow="ALWAYS"/>
        <Button fx:id="splitButton" text="Split bill" styleClass="btn-secondary"/>
        <Button fx:id="payButton" text="Pay" defaultButton="true" styleClass="btn-primary"/>
      </HBox>
    </VBox>
  </left>

  <center>
    <VBox spacing="12">
      <Label text="Menu" styleClass="subtitle"/>
      <TabPane fx:id="categoryTabs" VBox.vgrow="ALWAYS" styleClass="menu-tabs"/>
    </VBox>
  </center>
</BorderPane>
```

- [ ] **Step 4: Append the CSS block**

Append to the end of `pos-terminal/src/main/resources/css/app.css`:

```css
/* Slice 9 — inline dine-in line editor (course chip + remove) */
.course-chip { -fx-font-size: 12px; -fx-padding: 4 10 4 10; -fx-background-radius: 8; }
.line-remove { -fx-background-color: transparent; -fx-text-fill: -fx-ink; -fx-font-size: 18px; -fx-cursor: hand; }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=AppCssTest,FxmlContractTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add pos-terminal/src/main/resources/fxml/order.fxml \
        pos-terminal/src/main/resources/css/app.css \
        pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/FxmlContractTest.java
git commit -m "feat(terminal): order screen FXML/CSS for inline line rows

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `OrderController` — inline per-row line editor

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/OrderController.java` (full rewrite)
- Modify: `pos-terminal/README.md` (add Slice 9 manual-E2E section)

**Interfaces:**
- Consumes: `OrderViewModel.updateQty`, `OrderViewModel.updateCourse`, `OrderViewModel.removeLine`, `OrderViewModel.fire` (Task 2); `lineBox`/`emptyLabel` fx:ids (Task 3).
- Produces: the working dine-in order screen. Row rendering is display-dependent → verified by the full test suite (compile + existing contract tests) plus the README manual E2E, not a headless unit test (same treatment as `ModifierPickerDialog`).

- [ ] **Step 1: Rewrite `OrderController.java`**

Replace the entire contents of `pos-terminal/src/main/java/com/company/pos/terminal/view/OrderController.java` with:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.ModifierGroupView;
import com.company.pos.terminal.api.dto.OrderLineModifierView;
import com.company.pos.terminal.api.dto.OrderLineView;
import com.company.pos.terminal.api.dto.ProductView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.order.MenuCache;
import com.company.pos.terminal.order.SubtotalCalculator;
import com.company.pos.terminal.viewmodel.OrderViewModel;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Thin controller for the dine-in order screen. Binds the FXML controls to an {@link OrderViewModel}
 * (built from {@link Services#diningApi} + a {@link MenuCache}), renders the order lines as inline
 * editable rows and the client-side estimated subtotal on the left, and a touch menu grid (one tab
 * per {@code MenuCache.categories()} category) on the right. All (synchronous) VM/API calls are
 * dispatched off the FX thread via {@link FxTasks}; no business logic lives here.
 *
 * <p><b>Inline rows:</b> each un-fired line row has {@code −}/{@code +} quantity steppers (minus
 * disabled at qty 1 — use {@code ×} to remove), a course dropdown (STARTER/MAIN/DESSERT/DRINK), and
 * a {@code ×} remove button, mirroring the retail cart. A fired line (fired() == true) renders as a
 * muted, locked row with a {@code [fired]} badge and no edit controls; the VM refuses edits on fired
 * lines regardless, but the UI never even dispatches the call.
 *
 * <p><b>Estimate honesty:</b> the subtotal is {@code SubtotalCalculator} (pre-tax,
 * pre-service-charge) and is labelled "(est.)"; the authoritative total appears at payment.
 *
 * <p><b>Error handling:</b> {@code errorLabel.text} is bound to {@code vm.errorMessage()}, so
 * {@code FxTasks} {@code onError} callbacks must never {@code setText} it (that throws on a bound
 * property). Unexpected task failures are logged via {@link System.Logger}; VM-surfaced business
 * errors flow through the bound {@code errorMessage()} property.
 */
public class OrderController {

    private static final System.Logger LOG = System.getLogger(OrderController.class.getName());

    /** Course vocabulary — mirrors the server {@code CourseTag} enum. */
    private static final List<String> COURSES = List.of("STARTER", "MAIN", "DESSERT", "DRINK");

    private final Services services;
    private final Navigator navigator;
    private final UUID orderId;
    private OrderViewModel vm;
    private MenuCache cache;

    @FXML private VBox lineBox;
    @FXML private Label emptyLabel;
    @FXML private Label subtotalLabel;
    @FXML private Label errorLabel;
    @FXML private Button fireButton;
    @FXML private Button splitButton;
    @FXML private Button payButton;
    @FXML private Button backButton;
    @FXML private TabPane categoryTabs;

    public OrderController(Services services, Navigator navigator, UUID orderId) {
        this.services = services;
        this.navigator = navigator;
        this.orderId = orderId;
    }

    @FXML
    public void initialize() {
        // Error banner: hidden (and not laid out) when empty; text is bound in afterCatalogLoaded.
        errorLabel.visibleProperty().bind(errorLabel.textProperty().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        fireButton.setOnAction(e -> fire());
        splitButton.setOnAction(e -> navigator.toSplit(orderId));
        payButton.setOnAction(e -> pay());
        backButton.setOnAction(e -> navigator.toTableMap());

        // Nothing is actionable until the catalog + order have loaded.
        payButton.setDisable(true);
        fireButton.setDisable(true);
        splitButton.setDisable(true);

        // Load the product catalog into a MenuCache, off the FX thread; then wire the VM.
        FxTasks.run(
                () -> cache = new MenuCache(services.productApi.list()),
                this::afterCatalogLoaded,
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to load catalog", err));
    }

    private void afterCatalogLoaded() {
        vm = new OrderViewModel(services.diningApi, cache, Platform::runLater);

        subtotalLabel
                .textProperty()
                .bind(Bindings.concat("Subtotal (est.): ", vm.subtotalText()));
        errorLabel.textProperty().bind(vm.errorMessage());
        vm.lines()
                .addListener((ListChangeListener<OrderLineView>) c -> {
                    renderLines();
                    splitButton.setDisable(vm.lines().isEmpty());
                });

        buildMenu();

        payButton.setDisable(false);
        fireButton.setDisable(false);

        FxTasks.run(
                () -> vm.load(orderId),
                () -> {
                    renderLines();
                    splitButton.setDisable(vm.lines().isEmpty());
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to load order " + orderId, err));
    }

    /** One tab per category (MenuCache already folds null/blank categories into "Other"). */
    private void buildMenu() {
        categoryTabs.getTabs().clear();
        for (String category : cache.categories()) {
            FlowPane grid = new FlowPane(12, 12);
            grid.getStyleClass().add("menu-grid");
            for (ProductView p : cache.productsInCategory(category)) {
                Button b = new Button(p.name() + "\n" + priceText(p));
                b.getStyleClass().add("menu-button");
                b.setWrapText(true);
                b.setOnAction(e -> addProduct(p));
                grid.getChildren().add(b);
            }
            ScrollPane scroll = new ScrollPane(grid);
            scroll.setFitToWidth(true);
            scroll.getStyleClass().add("menu-scroll");
            Tab tab = new Tab(category, scroll);
            tab.setClosable(false);
            categoryTabs.getTabs().add(tab);
        }
    }

    /** Tap a product: fetch its modifier groups off-thread, then open the picker or add directly. */
    private void addProduct(ProductView p) {
        FxTasks.run(
                () -> {
                    List<ModifierGroupView> groups = services.menuApi.modifierGroupsForSku(p.sku());
                    Platform.runLater(() -> addWithGroups(p, groups));
                },
                () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to load modifiers for " + p.sku(), err));
    }

    /** On the FX thread: open the picker if the product has groups, else add with no modifiers. */
    private void addWithGroups(ProductView p, List<ModifierGroupView> groups) {
        List<UUID> optionIds = List.of();
        if (groups != null && !groups.isEmpty()) {
            Optional<List<UUID>> chosen = ModifierPickerDialog.pickFor(p.name(), groups);
            if (chosen.isEmpty()) {
                return; // cancelled — do not add
            }
            optionIds = chosen.get();
        }
        final List<UUID> ids = optionIds;
        FxTasks.run(
                () -> vm.addLine(p.sku(), BigDecimal.ONE, null, "MAIN", ids),
                () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to add line " + p.sku(), err));
    }

    /** Rebuilds the line rows from the VM. Un-fired lines are editable; fired lines are locked. */
    private void renderLines() {
        lineBox.getChildren().clear();
        boolean empty = vm == null || vm.lines().isEmpty();
        emptyLabel.setVisible(empty);
        emptyLabel.setManaged(empty);
        if (empty) {
            return;
        }
        for (OrderLineView line : vm.lines()) {
            lineBox.getChildren().add(lineRow(line));
        }
    }

    private HBox lineRow(OrderLineView line) {
        Label name = new Label(cache.nameFor(line.sku()) + subText(line));
        name.setWrapText(true);
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);

        if (line.fired()) {
            Label badge = new Label("[fired]");
            badge.getStyleClass().add("order-line-fired");
            HBox row = new HBox(8, name, badge);
            row.getStyleClass().addAll("cart-line", "order-line-fired");
            row.setMaxWidth(Double.MAX_VALUE);
            return row;
        }

        Button minus = new Button("−"); // − minus sign
        minus.getStyleClass().add("qty-stepper");
        minus.setDisable(line.qty() != null && line.qty().compareTo(BigDecimal.ONE) <= 0);
        minus.setOnAction(e -> step(line, -1));

        Label qty = new Label(qtyText(line.qty()));
        qty.getStyleClass().add("money");

        Button plus = new Button("+");
        plus.getStyleClass().add("qty-stepper");
        plus.setOnAction(e -> step(line, +1));

        // Course dropdown. Set the value BEFORE the action handler so the initial setValue does not
        // fire a spurious server call; a re-render rebuilds a fresh combo, so no update loop.
        ComboBox<String> course = new ComboBox<>(FXCollections.observableArrayList(COURSES));
        course.setValue(line.course() == null ? "MAIN" : line.course());
        course.getStyleClass().add("course-chip");
        course.setOnAction(e -> changeCourse(line, course.getValue()));

        Button remove = new Button("×"); // × multiplication sign
        remove.getStyleClass().add("line-remove");
        remove.setOnAction(e -> remove(line));

        HBox row = new HBox(8, minus, qty, plus, name, course, remove);
        row.getStyleClass().add("cart-line");
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private void step(OrderLineView line, int delta) {
        BigDecimal current = line.qty() == null ? BigDecimal.ZERO : line.qty();
        BigDecimal next = current.add(BigDecimal.valueOf(delta));
        if (next.compareTo(BigDecimal.ONE) < 0) {
            return; // floor at 1; removing a line is the explicit × button
        }
        FxTasks.run(
                () -> vm.updateQty(line, next),
                () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to change quantity", err));
    }

    private void changeCourse(OrderLineView line, String course) {
        FxTasks.run(
                () -> vm.updateCourse(line, course),
                () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to change course", err));
    }

    private void remove(OrderLineView line) {
        FxTasks.run(
                () -> vm.removeLine(line),
                () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to remove line", err));
    }

    private void fire() {
        FxTasks.run(
                vm::fire,
                () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to fire order", err));
    }

    private void pay() {
        BigDecimal estimatedTotal = SubtotalCalculator.estimate(vm.currentOrder(), cache);
        navigator.toPayment(orderId, estimatedTotal);
    }

    private static String priceText(ProductView p) {
        BigDecimal price = p.unitPrice() == null ? BigDecimal.ZERO : p.unitPrice();
        return price.toPlainString();
    }

    private static String qtyText(BigDecimal qty) {
        if (qty == null) {
            return "0";
        }
        return qty.stripTrailingZeros().toPlainString();
    }

    /** Modifier names and an optional note, each on its own indented sub-line under the product. */
    private static String subText(OrderLineView line) {
        StringBuilder sb = new StringBuilder();
        String mods = modifierText(line);
        if (!mods.isEmpty()) {
            sb.append("\n   ").append(mods);
        }
        if (line.note() != null && !line.note().isBlank()) {
            sb.append("\n   note: ").append(line.note());
        }
        return sb.toString();
    }

    private static String modifierText(OrderLineView line) {
        if (line.modifiers() == null || line.modifiers().isEmpty()) {
            return "";
        }
        return line.modifiers().stream()
                .filter(m -> m != null && m.name() != null)
                .map(OrderLineModifierView::name)
                .collect(Collectors.joining(", "));
    }
}
```

- [ ] **Step 2: Compile and run the full terminal suite**

Run: `./mvnw -f pos-terminal/pom.xml clean test`
Expected: BUILD SUCCESS — the controller compiles against the new fx:ids and VM methods, and all existing tests (now 186: 180 prior + 6 added across Tasks 1–3) pass. If compilation fails on a removed import or fx:id mismatch, fix it against Task 3's FXML before proceeding.

- [ ] **Step 3: Add the Slice 9 manual-E2E section to the README**

Append to `pos-terminal/README.md` (after the Slice 8 section):

```markdown
## Slice 9 — Inline course tags + quantity edit (manual E2E)

The dine-in **order** screen now edits lines inline (like the retail cart):

1. **Prereq** — run the backend with `embedded,dev` and log in (`manager`/`manager`).
   Open a table and add a couple of products (at least one with a modifier group).
2. **Quantity** — each un-fired row shows `[−] qty [+]`. Tap `+`/`−`; the estimated
   subtotal updates. `−` is disabled at qty 1 (use `×` to remove the line).
3. **Course** — each un-fired row has a course dropdown (Starter/Main/Dessert/Drink),
   defaulting to Main. Change it; the server persists it (visible after re-open).
4. **Note is preserved** — changing qty or course on a line with a note keeps the note
   (the server endpoint is a full replace; the terminal resends qty+note+course).
5. **Remove** — the `×` button removes an un-fired line.
6. **Fired lock** — after **Fire to kitchen**, fired lines render muted with a `[fired]`
   badge and NO edit controls (no steppers, course dropdown, or remove).
```

- [ ] **Step 4: Final full-suite run**

Run: `./mvnw -f pos-terminal/pom.xml clean test`
Expected: BUILD SUCCESS, all tests green.

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/OrderController.java \
        pos-terminal/README.md
git commit -m "feat(terminal): inline dine-in line editor — qty steppers, course, remove

Course tags and quantity are now editable per-line on the dine-in order screen,
mirroring the retail cart. Fired lines stay locked. Closes terminal slice 9.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Widen `DiningApi.updateLine` to the qty+note+course triple, URL-encode → Task 1. ✓
- `updateQty` preserves note+course; new `updateCourse`; fired guard; FX-threading async test → Task 2. ✓
- Inline per-row controls (steppers, course dropdown, ×) replacing ListView+removeButton; fired rows locked; `−` floor at 1 → Tasks 3 (FXML/CSS) + 4 (controller). ✓
- DiningApiTest encoding assertion; OrderViewModelTest preserve/updateCourse/fired/async; AppCssTest slice-9; FxmlContractTest lineBox; README manual E2E → Tasks 1–4. ✓
- Backend unchanged; course vocabulary STARTER/MAIN/DESSERT/DRINK → constant `COURSES` in Task 4. ✓
- Out of scope (note-editing UI, void/unfire, retail) — not touched. ✓

**Placeholder scan:** No TBD/TODO; every code step shows full code; commands have expected output. ✓

**Type consistency:** `updateLine(UUID, UUID, BigDecimal, String, String)` used identically in Tasks 1 (impl + tests) and 2 (VM callers + test overrides). `updateCourse(OrderLineView, String)` defined in Task 2, called in Task 4. `lineBox`/`emptyLabel` declared in Task 3 FXML, injected in Task 4 controller. `.course-chip`/`.line-remove` defined in Task 3 CSS, applied in Task 4 controller. ✓

**Note on `−`/`×` glyphs:** the controller uses the literal `−` (U+2212 minus) and `×` (U+00D7 multiplication) glyphs, matching the retail cart's `−`/`+` steppers. Keep them literal (UTF-8 source) rather than swapping to `-`/`x`.
