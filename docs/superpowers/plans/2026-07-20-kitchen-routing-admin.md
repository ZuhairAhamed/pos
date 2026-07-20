# Kitchen Routing Admin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A MANAGER/ADMIN terminal screen to route products (SKUs) to kitchen stations, so fired order lines print at the right station.

**Architecture:** Terminal-only (`pos-terminal/`) — the `kitchen` backend (assign/unassign/list, MANAGER+ADMIN) is already complete. New `KitchenApi` thin client + `KitchenRoutingViewModel` (synchronous, FX-safe) + a pure `RoutingRows` join helper + a searchable products×stations table + an I/O-free `StationPickerDialog` + a Kitchen tile in the existing Admin area. Mirrors the sub-project #2 Products screen 1:1.

**Tech Stack:** JavaFX, `java.net.http` + Jackson (existing `ApiClient`), JUnit 5, headless (no TestFX).

## Global Constraints

_Every task's requirements implicitly include this section._

- **Terminal build only.** `pos-terminal/` is a separate Maven build NOT in the root reactor. ALL Maven commands use `-f pos-terminal/pom.xml`. Set JDK 21 first: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`. No backend change, no migration, no new module dependency.
- **FX-threading (the recurring bug class):** the ViewModel is **synchronous**, returns plain values; the controller runs it off the FX thread via `FxTasks.run(work, onDone, onError)` and reads results in the FX-thread `onDone` via a `holder[]` array. The only observable written off-thread is `errorMessage`, inside `ui.accept(...)`. NEVER call a blocking VM/HTTP method inside `onDone` — mutations re-kick a fresh `FxTasks` task via `reload()` (a `showAndWait()` in the FX button handler is fine — it's UI, not I/O). Every VM needs an async-dispatcher regression test (a deferred, undrained `ui` dispatcher).
- **Dialogs are I/O-free:** `StationPickerDialog` collects input only and returns a value; the controller does all HTTP.
- **Access:** the Kitchen tile is visible to **MANAGER or ADMIN** (`services.session.isManager()`) — a different gate from the ADMIN-only Staff/Products tiles. The endpoints are MANAGER+ADMIN; the manager's session token carries the role, so no bearer-override/one-shot-token machinery is needed.
- **Stations are free-text** (no Station entity); route **per-SKU**; unrouted rows render a muted **"Default"** (the terminal cannot read `KITCHEN_DEFAULT_STATION` yet).
- fx:id ⇄ `@FXML` bijection must be exact in every FXML/controller pair; changes to `AdminController`/`admin.fxml`/`Navigator`/`Services` are **additive only** (Staff/Products/Home wiring untouched).

---

## File Structure

**Terminal (`pos-terminal/src/main/java/com/company/pos/terminal/`)**
- Create `api/StationAssignmentView.java` — inbound DTO record.
- Create `api/AssignStationRequest.java` — outbound DTO record.
- Create `api/KitchenApi.java` — thin REST client.
- Create `viewmodel/KitchenRoutingViewModel.java` — synchronous VM.
- Create `viewmodel/RoutingRows.java` — pure join helper + `RoutingRow` record.
- Modify `app/Services.java` — add `kitchenApi`.
- Create `view/StationPickerDialog.java` — I/O-free modal.
- Create `view/KitchenRoutingController.java` — screen controller.
- Modify `app/Navigator.java` — add `toKitchenRouting()`.
- Modify `view/AdminController.java` — add Kitchen tile wiring.
- Create `resources/fxml/kitchen-routing.fxml`; modify `resources/fxml/admin.fxml`.

**Terminal tests (`pos-terminal/src/test/java/com/company/pos/terminal/`)**
- Create `viewmodel/KitchenRoutingViewModelTest.java`, `viewmodel/RoutingRowsTest.java`, `view/StationPickerDialogTest.java`.

---

## Task 1: `KitchenApi` + DTOs + `KitchenRoutingViewModel` + `RoutingRows`

**Files:**
- Create: `api/StationAssignmentView.java`, `api/AssignStationRequest.java`, `api/KitchenApi.java`, `viewmodel/KitchenRoutingViewModel.java`, `viewmodel/RoutingRows.java`
- Modify: `app/Services.java`
- Test: `viewmodel/KitchenRoutingViewModelTest.java`, `viewmodel/RoutingRowsTest.java`

**Interfaces:**
- Consumes: existing `ApiClient` (`<T> T get(String, TypeReference<T>)`, `<T> T post(String, Object, TypeReference<T>)`, `void delete(String)`); `ApiException` (`problem()`, `getMessage()`), `ProblemDetail` (`title()`, `status()`, `detail()`); existing `api/ProductApi` (`List<dto.ProductView> list()`); `api/dto/ProductView` (record `sku, name, categoryName, barcode, unitPrice`).
- Produces: `KitchenApi` (`listAssignments`, `assign`, `unassign` — non-final for test subclassing), `StationAssignmentView(sku, stationName)`, `KitchenRoutingViewModel(KitchenApi, ProductApi, Consumer<Runnable>)` with `loadProducts/loadAssignments/assign/unassign/errorMessage`, `RoutingRows.build(...)` + `RoutingRows.RoutingRow(sku, name, station, routed)` + `RoutingRows.distinctStations(...)`, and `Services.kitchenApi`.

- [ ] **Step 1: Create the DTOs**

`api/StationAssignmentView.java`:

```java
package com.company.pos.terminal.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StationAssignmentView(String sku, String stationName) {
}
```

`api/AssignStationRequest.java`:

```java
package com.company.pos.terminal.api;

public record AssignStationRequest(String sku, String stationName) {
}
```

- [ ] **Step 2: Create `KitchenApi`**

`api/KitchenApi.java`:

```java
package com.company.pos.terminal.api;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;

/** Typed client for the store server's kitchen-routing endpoints (writes MANAGER/ADMIN-gated).
 *  Non-final so view-model tests can subclass with fakes. */
public class KitchenApi {

    private final ApiClient client;

    public KitchenApi(ApiClient client) {
        this.client = client;
    }

    /** GET /kitchen/stations/assignments — all explicit SKU→station assignments. */
    public List<StationAssignmentView> listAssignments() {
        return client.get("/kitchen/stations/assignments",
                new TypeReference<List<StationAssignmentView>>() {});
    }

    /** POST /kitchen/stations/assignments — assign or change a SKU's station (server upserts). */
    public StationAssignmentView assign(String sku, String stationName) {
        return client.post("/kitchen/stations/assignments",
                new AssignStationRequest(sku, stationName),
                new TypeReference<StationAssignmentView>() {});
    }

    /** DELETE /kitchen/stations/assignments/{sku} — clear a SKU's routing. */
    public void unassign(String sku) {
        client.delete("/kitchen/stations/assignments/" + sku);
    }
}
```

- [ ] **Step 3: Create the `RoutingRows` join helper**

`viewmodel/RoutingRows.java`:

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.StationAssignmentView;
import com.company.pos.terminal.api.dto.ProductView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure join of products × station assignments into display rows, plus the distinct station names. */
public final class RoutingRows {

    private RoutingRows() {
    }

    /** A product's routing. {@code station} is the explicit assignment, or null when {@code routed==false}
     *  (the row falls back to the default station at fire time). */
    public record RoutingRow(String sku, String name, String station, boolean routed) {
    }

    public static List<RoutingRow> build(List<ProductView> products,
            List<StationAssignmentView> assignments) {
        Map<String, String> bySku = new LinkedHashMap<>();
        if (assignments != null) {
            for (StationAssignmentView a : assignments) {
                bySku.put(a.sku(), a.stationName());
            }
        }
        List<RoutingRow> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (products != null) {
            for (ProductView p : products) {
                String station = bySku.get(p.sku());
                rows.add(new RoutingRow(p.sku(), p.name(), station, station != null));
                seen.add(p.sku());
            }
        }
        // Orphan assignments (SKU no longer in the product list) — surface so stale routing can be cleared.
        for (Map.Entry<String, String> e : bySku.entrySet()) {
            if (!seen.contains(e.getKey())) {
                rows.add(new RoutingRow(e.getKey(), "(unknown product)", e.getValue(), true));
            }
        }
        return rows;
    }

    /** Distinct station names currently in use, insertion-ordered — feeds the picker's suggestions. */
    public static List<String> distinctStations(List<StationAssignmentView> assignments) {
        Set<String> out = new LinkedHashSet<>();
        if (assignments != null) {
            for (StationAssignmentView a : assignments) {
                if (a.stationName() != null && !a.stationName().isBlank()) {
                    out.add(a.stationName());
                }
            }
        }
        return new ArrayList<>(out);
    }
}
```

- [ ] **Step 4: Write the failing `RoutingRowsTest`**

`viewmodel/RoutingRowsTest.java`:

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.StationAssignmentView;
import com.company.pos.terminal.api.dto.ProductView;
import com.company.pos.terminal.viewmodel.RoutingRows.RoutingRow;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutingRowsTest {

    private ProductView product(String sku, String name) {
        return new ProductView(sku, name, null, null, new BigDecimal("1.00"));
    }

    @Test
    void routedAndUnroutedRows() {
        List<RoutingRow> rows = RoutingRows.build(
                List.of(product("COLA", "Cola"), product("FRIES", "Fries")),
                List.of(new StationAssignmentView("FRIES", "Fryer")));
        RoutingRow cola = rows.stream().filter(r -> r.sku().equals("COLA")).findFirst().orElseThrow();
        RoutingRow fries = rows.stream().filter(r -> r.sku().equals("FRIES")).findFirst().orElseThrow();
        assertFalse(cola.routed());
        assertNull(cola.station());
        assertTrue(fries.routed());
        assertEquals("Fryer", fries.station());
    }

    @Test
    void orphanAssignmentSurfacesAsUnknownProduct() {
        List<RoutingRow> rows = RoutingRows.build(
                List.of(product("COLA", "Cola")),
                List.of(new StationAssignmentView("GONE", "Grill")));
        RoutingRow orphan = rows.stream().filter(r -> r.sku().equals("GONE")).findFirst().orElseThrow();
        assertEquals("(unknown product)", orphan.name());
        assertTrue(orphan.routed());
        assertEquals("Grill", orphan.station());
    }

    @Test
    void distinctStationsDedupesAndSkipsBlank() {
        List<String> s = RoutingRows.distinctStations(List.of(
                new StationAssignmentView("A", "Bar"),
                new StationAssignmentView("B", "Bar"),
                new StationAssignmentView("C", "Grill")));
        assertEquals(List.of("Bar", "Grill"), s);
    }
}
```

- [ ] **Step 5: Run `RoutingRowsTest` — expect GREEN**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw -f pos-terminal/pom.xml test -Dtest=RoutingRowsTest`
Expected: PASS (3 tests). (`RoutingRows` was written in Step 3, so this is green immediately — the test locks the contract before the VM/controller depend on it.)

- [ ] **Step 6: Write the failing `KitchenRoutingViewModelTest`**

`viewmodel/KitchenRoutingViewModelTest.java`:

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.KitchenApi;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.ProductApi;
import com.company.pos.terminal.api.StationAssignmentView;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

class KitchenRoutingViewModelTest {

    private KitchenRoutingViewModel vm(KitchenApi k) {
        return new KitchenRoutingViewModel(k, new ProductApi(null), Runnable::run);
    }

    @Test
    void loadAssignmentsReturnsListAndClearsError() {
        KitchenApi k = new KitchenApi(null) {
            @Override public List<StationAssignmentView> listAssignments() {
                return List.of(new StationAssignmentView("COLA", "Bar"));
            }
        };
        KitchenRoutingViewModel vm = vm(k);
        assertEquals(1, vm.loadAssignments().size());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void assignRejectsBlankStationWithoutCallingApi() {
        boolean[] called = {false};
        KitchenApi k = new KitchenApi(null) {
            @Override public StationAssignmentView assign(String sku, String stationName) {
                called[0] = true;
                return null;
            }
        };
        KitchenRoutingViewModel vm = vm(k);
        assertNull(vm.assign("COLA", "  "));
        assertFalse(called[0]);
        assertEquals("Station name is required", vm.errorMessage().get());
    }

    @Test
    void assignTrimsAndReturnsViewOnSuccess() {
        KitchenApi k = new KitchenApi(null) {
            @Override public StationAssignmentView assign(String sku, String stationName) {
                return new StationAssignmentView(sku, stationName);
            }
        };
        KitchenRoutingViewModel vm = vm(k);
        StationAssignmentView v = vm.assign("COLA", " Bar ");
        assertEquals("COLA", v.sku());
        assertEquals("Bar", v.stationName());   // VM trims before the call
    }

    @Test
    void unassignReturnsTrueOnSuccess() {
        KitchenApi k = new KitchenApi(null) {
            @Override public void unassign(String sku) { /* ok */ }
        };
        assertTrue(vm(k).unassign("COLA"));
    }

    @Test
    void deferredDispatcherHoldsErrorUntilDrained() {
        KitchenApi k = new KitchenApi(null) {
            @Override public List<StationAssignmentView> listAssignments() {
                throw new ApiException(500, new ProblemDetail("Error", 500, "boom"), "HTTP 500");
            }
        };
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        KitchenRoutingViewModel vm = new KitchenRoutingViewModel(k, new ProductApi(null), queue::add);
        assertNull(vm.loadAssignments());
        assertEquals("", vm.errorMessage().get());   // deferred: not applied yet
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
        assertEquals("boom", vm.errorMessage().get());
    }
}
```

- [ ] **Step 7: Run it — expect RED**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw -f pos-terminal/pom.xml test -Dtest=KitchenRoutingViewModelTest`
Expected: FAIL — `KitchenRoutingViewModel` does not exist (compile error).

- [ ] **Step 8: Create `KitchenRoutingViewModel`**

`viewmodel/KitchenRoutingViewModel.java`:

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.KitchenApi;
import com.company.pos.terminal.api.ProductApi;
import com.company.pos.terminal.api.StationAssignmentView;
import com.company.pos.terminal.api.dto.ProductView;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the kitchen-routing screen. Synchronous like the other VMs — the controller runs it
 * off the FX thread via FxTasks and reads the return value; the only observable written off-thread is
 * {@code errorMessage}, inside the {@code ui} dispatcher.
 */
public class KitchenRoutingViewModel {

    private final KitchenApi kitchenApi;
    private final ProductApi productApi;
    private final Consumer<Runnable> ui;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public KitchenRoutingViewModel(KitchenApi kitchenApi, ProductApi productApi, Consumer<Runnable> ui) {
        this.kitchenApi = kitchenApi;
        this.productApi = productApi;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    public List<ProductView> loadProducts() {
        try {
            List<ProductView> list = productApi.list();
            ui.accept(() -> errorMessage.set(""));
            return list;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public List<StationAssignmentView> loadAssignments() {
        try {
            List<StationAssignmentView> list = kitchenApi.listAssignments();
            ui.accept(() -> errorMessage.set(""));
            return list;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public StationAssignmentView assign(String sku, String stationName) {
        if (stationName == null || stationName.isBlank()) {
            ui.accept(() -> errorMessage.set("Station name is required"));
            return null;
        }
        try {
            StationAssignmentView v = kitchenApi.assign(sku, stationName.trim());
            ui.accept(() -> errorMessage.set(""));
            return v;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public boolean unassign(String sku) {
        try {
            kitchenApi.unassign(sku);
            ui.accept(() -> errorMessage.set(""));
            return true;
        } catch (ApiException e) {
            fail(e);
            return false;
        }
    }

    private void fail(ApiException e) {
        String msg = messageOf(e);
        ui.accept(() -> errorMessage.set(msg));
    }

    private String messageOf(ApiException e) {
        if (e.problem() != null) {
            if (e.problem().detail() != null && !e.problem().detail().isBlank()) {
                return e.problem().detail();
            }
            if (e.problem().title() != null && !e.problem().title().isBlank()) {
                return e.problem().title();
            }
        }
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        return "Request failed";
    }
}
```

- [ ] **Step 9: Wire `kitchenApi` into `Services`**

In `app/Services.java`: add import `import com.company.pos.terminal.api.KitchenApi;`; add field `public final KitchenApi kitchenApi;` (next to `usersApi`); in the constructor add `this.kitchenApi = new KitchenApi(apiClient);` (after `this.usersApi = new UsersApi(apiClient);`).

- [ ] **Step 10: Run both tests — expect GREEN**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw -f pos-terminal/pom.xml test -Dtest=KitchenRoutingViewModelTest,RoutingRowsTest`
Expected: PASS (5 + 3 = 8 tests).

- [ ] **Step 11: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/StationAssignmentView.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/AssignStationRequest.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/KitchenApi.java \
        pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/KitchenRoutingViewModel.java \
        pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/RoutingRows.java \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/KitchenRoutingViewModelTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/RoutingRowsTest.java
git commit -m "feat(terminal): KitchenApi + KitchenRoutingViewModel + RoutingRows"
```

---

## Task 2: `StationPickerDialog` (I/O-free) + test

**Files:**
- Create: `view/StationPickerDialog.java`
- Test: `view/StationPickerDialogTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks (pure JavaFX + stdlib).
- Produces: `StationPickerDialog.promptForStation(List<String> existingStations, String current): Optional<String>` and package-visible static `normalize(String): String`.

Context: mirrors the I/O-free `ProductFormDialog`/`UserFormDialog` from earlier sub-projects — pure view, collects input only. An **editable** `ComboBox<String>` pre-populated with the station names already in use; the user picks one or types a new name. Save is disabled while the entry normalizes to null. Only `normalize` is unit-tested (headless; `showAndWait` is never invoked in tests).

- [ ] **Step 1: Write the failing test**

`view/StationPickerDialogTest.java`:

```java
package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class StationPickerDialogTest {

    @Test
    void normalizeTrims() {
        assertEquals("Grill", StationPickerDialog.normalize("  Grill "));
    }

    @Test
    void normalizeBlankIsNull() {
        assertNull(StationPickerDialog.normalize("   "));
    }

    @Test
    void normalizeNullIsNull() {
        assertNull(StationPickerDialog.normalize(null));
    }
}
```

- [ ] **Step 2: Run it — expect RED**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw -f pos-terminal/pom.xml test -Dtest=StationPickerDialogTest`
Expected: FAIL — `StationPickerDialog` does not exist (compile error).

- [ ] **Step 3: Create `StationPickerDialog`**

`view/StationPickerDialog.java`:

```java
package com.company.pos.terminal.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * Modal to choose a kitchen station for a product. Pure view — collects input only; the controller
 * performs all HTTP. An editable combo pre-populated with the station names already in use (pick an
 * existing one to avoid typo-duplicates, or type a new one). Only {@link #normalize} is unit-tested.
 */
public final class StationPickerDialog {

    private StationPickerDialog() {
    }

    public static Optional<String> promptForStation(List<String> existingStations, String current) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Kitchen station");
        dialog.setHeaderText("Route to station");
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);

        List<String> names = new ArrayList<>(existingStations == null ? List.of() : existingStations);
        ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList(names));
        combo.setEditable(true);
        combo.setPromptText("station name");
        if (current != null && !current.isBlank()) {
            combo.setValue(current);
        }

        Label label = new Label("Station");
        label.getStyleClass().add("field-label");
        VBox box = new VBox(6, label, combo);
        box.getStyleClass().add("field");
        box.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(normalize(combo.getEditor().getText()) == null);
        combo.getEditor().textProperty().addListener((o, a, b) -> revalidate.run());
        combo.valueProperty().addListener((o, a, b) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> bt == submit ? normalize(combo.getEditor().getText()) : null);
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    /** Trim the entered station; null if blank. */
    static String normalize(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
```

- [ ] **Step 4: Run it — expect GREEN**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw -f pos-terminal/pom.xml test -Dtest=StationPickerDialogTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/StationPickerDialog.java \
        pos-terminal/src/test/java/com/company/pos/terminal/view/StationPickerDialogTest.java
git commit -m "feat(terminal): I/O-free StationPickerDialog"
```

---

## Task 3: Routing screen wiring (controller + FXML + navigation + Admin tile)

**Files:**
- Create: `view/KitchenRoutingController.java`, `resources/fxml/kitchen-routing.fxml`
- Modify: `app/Navigator.java`, `view/AdminController.java`, `resources/fxml/admin.fxml`

**Interfaces:**
- Consumes: `KitchenRoutingViewModel`, `RoutingRows`/`RoutingRow`, `Services.kitchenApi`+`Services.productApi` (Task 1); `StationPickerDialog` (Task 2); `FxTasks.run`, `Navigator` (`toAdmin`, new `toKitchenRouting`), `Services`, `session.isManager()`.
- Produces: `Navigator.toKitchenRouting()`; a functional Kitchen routing screen; a MANAGER/ADMIN Kitchen tile.

Context: mirrors `ProductsController`/`products.fxml`. No dedicated unit test (controller/FXML wiring, like `ProductsController`); verified by the full terminal suite compiling + green. Products+assignments are fetched together in `reload()`'s `FxTasks` work lambda; the picker receives the cached distinct-station list synchronously; each mutation's `onDone` re-kicks `reload()`.

- [ ] **Step 1: Create `kitchen-routing.fxml`**

`resources/fxml/kitchen-routing.fxml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.scene.control.Button?>
<?import javafx.scene.control.Label?>
<?import javafx.scene.control.TableColumn?>
<?import javafx.scene.control.TableView?>
<?import javafx.scene.control.TextField?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.layout.Pane?>
<?import javafx.scene.layout.VBox?>

<VBox styleClass="screen" spacing="16" xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml">
  <HBox spacing="16" alignment="CENTER_LEFT">
    <Label text="Kitchen routing" styleClass="title"/>
    <Pane HBox.hgrow="ALWAYS"/>
    <Label fx:id="errorLabel" styleClass="error-text"/>
    <TextField fx:id="searchField" promptText="Search SKU or name"/>
    <Button fx:id="backButton" text="Back" styleClass="btn-secondary"/>
  </HBox>

  <TableView fx:id="table" VBox.vgrow="ALWAYS">
    <columns>
      <TableColumn fx:id="skuCol" text="SKU" prefWidth="160"/>
      <TableColumn fx:id="nameCol" text="Name" prefWidth="300"/>
      <TableColumn fx:id="stationCol" text="Station" prefWidth="220"/>
    </columns>
  </TableView>

  <HBox spacing="12" alignment="CENTER_LEFT">
    <Button fx:id="assignButton" text="Assign / Change" styleClass="btn-primary"/>
    <Button fx:id="clearButton" text="Clear routing"/>
  </HBox>
</VBox>
```

- [ ] **Step 2: Create `KitchenRoutingController`**

`view/KitchenRoutingController.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.StationAssignmentView;
import com.company.pos.terminal.api.dto.ProductView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.KitchenRoutingViewModel;
import com.company.pos.terminal.viewmodel.RoutingRows;
import com.company.pos.terminal.viewmodel.RoutingRows.RoutingRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;

/**
 * MANAGER/ADMIN kitchen-routing screen. Loads products + station assignments off the FX thread via
 * FxTasks, joins them into rows (RoutingRows), and lets the user assign/change/clear a SKU's station
 * through the I/O-free StationPickerDialog. Search filters the cached rows client-side.
 */
public class KitchenRoutingController {

    private static final System.Logger LOG = System.getLogger(KitchenRoutingController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final KitchenRoutingViewModel vm;

    private List<RoutingRow> allRows = new ArrayList<>();
    private List<String> stations = new ArrayList<>();

    @FXML private Label errorLabel;
    @FXML private TextField searchField;
    @FXML private Button backButton;
    @FXML private Button assignButton;
    @FXML private Button clearButton;
    @FXML private TableView<RoutingRow> table;
    @FXML private TableColumn<RoutingRow, String> skuCol;
    @FXML private TableColumn<RoutingRow, String> nameCol;
    @FXML private TableColumn<RoutingRow, String> stationCol;

    public KitchenRoutingController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new KitchenRoutingViewModel(services.kitchenApi, services.productApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        skuCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().sku()));
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        stationCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().routed() ? c.getValue().station() : "Default"));

        errorLabel.textProperty().bind(vm.errorMessage());
        searchField.textProperty().addListener((o, a, b) -> applyFilter());
        table.getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> refreshButtons(sel));

        backButton.setOnAction(e -> navigator.toAdmin());
        assignButton.setOnAction(e -> assignSelected());
        clearButton.setOnAction(e -> clearSelected());

        refreshButtons(null);
        reload();
    }

    @SuppressWarnings("unchecked")
    private void reload() {
        final List<ProductView>[] ph = new List[1];
        final List<StationAssignmentView>[] ah = new List[1];
        FxTasks.run(
                () -> {
                    ph[0] = vm.loadProducts();
                    ah[0] = vm.loadAssignments();
                },
                () -> {
                    if (ph[0] != null && ah[0] != null) {
                        allRows = RoutingRows.build(ph[0], ah[0]);
                        stations = RoutingRows.distinctStations(ah[0]);
                        applyFilter();
                    }
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Load kitchen routing failed", err));
    }

    private void applyFilter() {
        String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        List<RoutingRow> shown = allRows.stream()
                .filter(r -> q.isEmpty()
                        || r.sku().toLowerCase().contains(q)
                        || r.name().toLowerCase().contains(q))
                .toList();
        table.setItems(FXCollections.observableArrayList(shown));
    }

    private void refreshButtons(RoutingRow sel) {
        assignButton.setDisable(sel == null);
        clearButton.setDisable(sel == null || !sel.routed());
    }

    private void assignSelected() {
        RoutingRow sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        Optional<String> station = StationPickerDialog.promptForStation(stations, sel.station());
        station.ifPresent(s -> {
            final StationAssignmentView[] holder = new StationAssignmentView[1];
            FxTasks.run(() -> holder[0] = vm.assign(sel.sku(), s),
                    () -> { if (holder[0] != null) reload(); },
                    err -> LOG.log(System.Logger.Level.ERROR, "Assign station failed", err));
        });
    }

    private void clearSelected() {
        RoutingRow sel = table.getSelectionModel().getSelectedItem();
        if (sel == null || !sel.routed()) {
            return;
        }
        final boolean[] holder = {false};
        FxTasks.run(() -> holder[0] = vm.unassign(sel.sku()),
                () -> { if (holder[0]) reload(); },
                err -> LOG.log(System.Logger.Level.ERROR, "Clear routing failed", err));
    }
}
```

- [ ] **Step 3: Add `toKitchenRouting()` to `Navigator`**

In `app/Navigator.java`, add this method immediately after `toProducts()`:

```java
    public void toKitchenRouting() {
        com.company.pos.terminal.view.KitchenRoutingController controller =
                new com.company.pos.terminal.view.KitchenRoutingController(services, this);
        setScene("/fxml/kitchen-routing.fxml", controller);
    }
```

- [ ] **Step 4: Add the Kitchen tile to `AdminController`**

In `view/AdminController.java`: add the field `@FXML private Button kitchenButton;` (next to `productsButton`); at the end of `initialize()` add:

```java
        boolean manager = services.session.isManager();
        kitchenButton.setVisible(manager);
        kitchenButton.setManaged(manager);
        kitchenButton.setOnAction(e -> navigator.toKitchenRouting());
```

(`isManager()` is MANAGER||ADMIN — the Kitchen tile is intentionally broader than the ADMIN-only Staff/Products tiles. Leave the existing `admin`-gated Staff/Products wiring unchanged.)

- [ ] **Step 5: Add the Kitchen tile to `admin.fxml`**

In `resources/fxml/admin.fxml`, add `kitchenButton` to the tiles `HBox` (after `productsButton`):

```xml
    <HBox spacing="32" alignment="CENTER">
      <Button fx:id="staffButton" text="Staff" styleClass="home-tile"/>
      <Button fx:id="productsButton" text="Products" styleClass="home-tile"/>
      <Button fx:id="kitchenButton" text="Kitchen" styleClass="home-tile"/>
    </HBox>
```

- [ ] **Step 6: Run the full terminal suite**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw -f pos-terminal/pom.xml clean test`
Expected: PASS — compiles (new controller + `Navigator.toKitchenRouting` + `AdminController` field bound to `admin.fxml`, exact fx:id bijection) and the full suite stays green.

- [ ] **Step 7: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/KitchenRoutingController.java \
        pos-terminal/src/main/resources/fxml/kitchen-routing.fxml \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/AdminController.java \
        pos-terminal/src/main/resources/fxml/admin.fxml
git commit -m "feat(terminal): Kitchen routing screen + Admin-area tile"
```

---

## Final verification (after all tasks)

- [ ] **Full terminal build**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw -f pos-terminal/pom.xml clean test`
Expected: green.

- [ ] **Manual smoke (optional, needs a running backend)**

Start backend with `--spring.profiles.active=embedded,dev`, launch the terminal (`./mvnw -f pos-terminal/pom.xml javafx:run`), log in as a MANAGER or ADMIN. Verify: Admin → Kitchen lists the seeded products with "Default" stations; Assign/Change opens the picker (existing stations appear as you add more); assign a station; the row updates; Clear routing returns it to "Default"; search filters by SKU/name.
