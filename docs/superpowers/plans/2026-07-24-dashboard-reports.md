# Manager Dashboard + Reports Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface the existing MANAGER/ADMIN analytics APIs on the JavaFX terminal as two read-only screens — a Dashboard (Home tile) and a Reports screen (Admin hub) with preset/custom date ranges and CSV export.

**Architecture:** Terminal-only (no backend changes — `/dashboard` and `/reports/*` already exist and are MANAGER/ADMIN-gated). New terminal DTOs mirror the backend records; new `DashboardApi`/`ReportingApi` wrap `ApiClient`; two screens follow the Controller + synchronous-ViewModel + `FxTasks` pattern. Two new terminal primitives: a raw-text GET (`ApiClient.getText`) for CSV, and a `FileChooser` save for export.

**Tech Stack:** Java 21, JavaFX (standalone `pos-terminal` build), Jackson, JUnit 5, the terminal's `ApiClient`/`FxTasks`/`Navigator`/`Services`.

## Global Constraints

- **JDK 21 required.** Before any Maven command: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`.
- **Build/test only the terminal:** `./mvnw -f pos-terminal/pom.xml test [-Dtest=<Class>]` (headless, no display/TestFX). There are NO backend changes in this slice — never run the root reactor build for it.
- **No backend changes.** Both `GET /dashboard` and `GET /reports/*` already exist, `@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")`. Do not touch `src/main/java`.
- **Money is `BigDecimal`** + a sibling `currencyCode` String from the DTO; never `double`. Render `amount + " " + currencyCode`.
- **FX-threading convention:** ViewModels are synchronous and return plain values; the controller runs them off-thread via `FxTasks.run(work, onDone, onError)` and reads results only in the FX-thread `onDone` via a holder array. The only observable a VM writes off-thread is `errorMessage`, inside `ui.accept(...)`. `onDone` never calls a blocking (HTTP) VM method — chain I/O in `work`. `showAndWait()`/`FileChooser` ARE allowed in `onDone` (UI, not blocking I/O). Every VM gets an async-dispatcher (deferred, undrained `ui`) regression test.
- **Inter-thread time is an injected `Supplier<Instant> clock`** — never inline `Instant.now()`. Resolve "today" via `LocalDate.ofInstant(clock.get(), ZoneId.systemDefault())`.
- **Api classes** are `public` (non-final, so VM tests subclass with fakes), hold one `ApiClient`, delegate via `client.get(...)` / `client.getText(...)`. Register as `public final` fields in `Services`.
- **New DTOs** live in `com.company.pos.terminal.api.dto` and carry `@JsonIgnoreProperties(ignoreUnknown = true)`. **Reuse the existing `dto/ShiftView`** for dashboard open-shifts — do not create a duplicate.
- **Entry tiles are role-gated** via `services.session.isManager()` (MANAGER or ADMIN); toggle both `setVisible(...)` and `setManaged(...)`.
- **Status/emphasis is colour + text**, never colour alone. Append new CSS under a `/* ---- Slice 18 — Dashboard + Reports ---- */` block reusing existing tokens.
- **Blind-close safety:** the dashboard renders only `ShiftView` fields (`terminalId, openedBy, openedAt, status`) — it must never render `expectedCash`/`variance`/cash-sales amount (`ShiftView` carries none, so this holds by construction; do not reach for reconciliation data).

---

## File structure

**New terminal DTOs** (`pos-terminal/src/main/java/com/company/pos/terminal/api/dto/`)
- `SalesSummaryReport.java`, `ProductPerformanceReport.java` (nested `ProductLine`) — shared by dashboard + reports.
- `RevenueSummary.java`, `LowStockTile.java`, `DashboardSnapshot.java` — dashboard.
- `PaymentBreakdownReport.java` (nested `PaymentLine`), `TaxSummaryReport.java`, `CashierReport.java` (nested `CashierLine`) — reports.
- (Reuse existing `dto/ShiftView.java`.)

**New terminal API + enum** (`pos-terminal/src/main/java/com/company/pos/terminal/api/`)
- `DashboardApi.java`, `ReportingApi.java`, `ReportType.java`.
- `ApiClient.java` — add `getText(String)`.

**New ViewModels** (`.../viewmodel/`): `DashboardViewModel.java`, `ReportsViewModel.java`.
**New Controllers + FXML** (`.../view/` + `resources/fxml/`): `DashboardController.java`+`dashboard.fxml`, `ReportsController.java`+`reports.fxml`.
**Modified:** `Services.java` (+2 fields), `Navigator.java` (+2 methods), `HomeController.java`+`home.fxml` (Dashboard tile), `AdminController.java`+`admin.fxml` (Reports tile), `css/app.css`.
**Tests:** `DashboardViewModelTest`, `ReportsViewModelTest`, `ReportingApiTest`, `ApiClientGetTextTest`.

---

## Task 1: Dashboard DTOs + DashboardApi + DashboardViewModel

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/SalesSummaryReport.java`, `ProductPerformanceReport.java`, `RevenueSummary.java`, `LowStockTile.java`, `DashboardSnapshot.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/DashboardApi.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/DashboardViewModel.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/DashboardViewModelTest.java`

**Interfaces:**
- Produces DTOs (fields mirror backend exactly): `SalesSummaryReport(LocalDate from, LocalDate to, String currencyCode, long saleCount, BigDecimal subtotal, BigDecimal lineDiscounts, BigDecimal txnDiscounts, BigDecimal taxTotal, BigDecimal grossSales, long returnCount, BigDecimal refundTotal, BigDecimal netSales)`; `ProductPerformanceReport(LocalDate from, LocalDate to, String currencyCode, List<ProductLine> lines)` with nested `ProductLine(String sku, String name, BigDecimal quantitySold, BigDecimal revenue, BigDecimal discounts)`; `RevenueSummary(BigDecimal today, int windowDays, BigDecimal window)`; `LowStockTile(String sku, String name, BigDecimal onHand, BigDecimal reorderLevel)`; `DashboardSnapshot(LocalDate asOfDate, String currencyCode, SalesSummaryReport todaysSales, RevenueSummary revenue, List<ProductPerformanceReport.ProductLine> bestSellers, List<LowStockTile> lowStock, List<ShiftView> openShifts, List<String> activeCashiers)`.
- Produces: `DashboardApi(ApiClient client)` with `DashboardSnapshot snapshot()`.
- Produces: `DashboardViewModel(DashboardApi api)` and `(DashboardApi api, Consumer<Runnable> ui)`; `ReadOnlyStringProperty errorMessage()`; `DashboardSnapshot load()`.
- Produces: `Services.dashboardApi`.

- [ ] **Step 1: Write the failing test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/DashboardViewModelTest.java`:

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.DashboardApi;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.dto.DashboardSnapshot;
import com.company.pos.terminal.api.dto.RevenueSummary;
import com.company.pos.terminal.api.dto.SalesSummaryReport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class DashboardViewModelTest {

    private DashboardSnapshot snapshot() {
        SalesSummaryReport sales = new SalesSummaryReport(LocalDate.parse("2026-07-24"),
                LocalDate.parse("2026-07-24"), "SAR", 34, new BigDecimal("1240.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("186.00"), new BigDecimal("1240.00"),
                0, BigDecimal.ZERO, new BigDecimal("1240.00"));
        return new DashboardSnapshot(LocalDate.parse("2026-07-24"), "SAR", sales,
                new RevenueSummary(new BigDecimal("1240.00"), 7, new BigDecimal("8910.00")),
                List.of(), List.of(), List.of(), List.of("alice"));
    }

    @Test
    void loadReturnsSnapshot() {
        DashboardApi api = new DashboardApi(null) {
            @Override public DashboardSnapshot snapshot() { return snapshot(); }
        };
        DashboardViewModel vm = new DashboardViewModel(api);
        DashboardSnapshot s = vm.load();
        assertEquals(34, s.todaysSales().saleCount());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void loadSurfacesErrorUnderDeferredDispatcher() {
        DashboardApi api = new DashboardApi(null) {
            @Override public DashboardSnapshot snapshot() {
                throw new ApiException(503, new ProblemDetail("Unavailable", 503, "server down"),
                        "HTTP 503");
            }
        };
        Deque<Runnable> queue = new ArrayDeque<>();
        Consumer<Runnable> deferred = queue::add;
        DashboardViewModel vm = new DashboardViewModel(api, deferred);

        DashboardSnapshot s = vm.load();

        assertNull(s);                              // synchronous truth: failed
        assertEquals("", vm.errorMessage().get());  // deferred: not applied yet
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals("server down", vm.errorMessage().get());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml test -Dtest=DashboardViewModelTest`
Expected: FAIL — DTOs, `DashboardApi`, `DashboardViewModel` do not exist (compile error).

- [ ] **Step 3: Create the shared + dashboard DTOs**

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/SalesSummaryReport.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SalesSummaryReport(LocalDate from, LocalDate to, String currencyCode,
        long saleCount, BigDecimal subtotal, BigDecimal lineDiscounts, BigDecimal txnDiscounts,
        BigDecimal taxTotal, BigDecimal grossSales, long returnCount, BigDecimal refundTotal,
        BigDecimal netSales) {
}
```

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ProductPerformanceReport.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductPerformanceReport(LocalDate from, LocalDate to, String currencyCode,
        List<ProductLine> lines) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductLine(String sku, String name, BigDecimal quantitySold, BigDecimal revenue,
            BigDecimal discounts) {
    }
}
```

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/RevenueSummary.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RevenueSummary(BigDecimal today, int windowDays, BigDecimal window) {
}
```

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/LowStockTile.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LowStockTile(String sku, String name, BigDecimal onHand, BigDecimal reorderLevel) {
}
```

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/DashboardSnapshot.java` (reuses the existing `dto/ShiftView`):

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DashboardSnapshot(LocalDate asOfDate, String currencyCode,
        SalesSummaryReport todaysSales, RevenueSummary revenue,
        List<ProductPerformanceReport.ProductLine> bestSellers, List<LowStockTile> lowStock,
        List<ShiftView> openShifts, List<String> activeCashiers) {
}
```

(Verify `pos-terminal/.../api/dto/ShiftView.java` exists with fields `shiftId, terminalId, openedBy, status, currencyCode, openedAt, closedAt`; it does — reuse it.)

- [ ] **Step 4: Create `DashboardApi`**

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/DashboardApi.java`:

```java
package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.DashboardSnapshot;
import com.fasterxml.jackson.core.type.TypeReference;

/** Typed client for the manager dashboard snapshot. Non-final so view-model tests subclass it. */
public class DashboardApi {

    private final ApiClient client;

    public DashboardApi(ApiClient client) {
        this.client = client;
    }

    /** GET /dashboard — full snapshot (today's sales, revenue, best-sellers, low-stock, shifts). */
    public DashboardSnapshot snapshot() {
        return client.get("/dashboard", new TypeReference<DashboardSnapshot>() {});
    }
}
```

- [ ] **Step 5: Create `DashboardViewModel`**

Create `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/DashboardViewModel.java`:

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.DashboardApi;
import com.company.pos.terminal.api.dto.DashboardSnapshot;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the manager dashboard. Synchronous (the controller runs it off the FX thread via
 * FxTasks); the only off-thread observable write is {@code errorMessage} inside {@code ui}.
 */
public class DashboardViewModel {

    private final DashboardApi api;
    private final Consumer<Runnable> ui;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public DashboardViewModel(DashboardApi api) {
        this(api, Runnable::run);
    }

    public DashboardViewModel(DashboardApi api, Consumer<Runnable> ui) {
        this.api = api;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    /** Fetches the snapshot; on failure surfaces the reason and returns null. */
    public DashboardSnapshot load() {
        try {
            DashboardSnapshot snapshot = api.snapshot();
            ui.accept(() -> errorMessage.set(""));
            return snapshot;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return null;
        }
    }

    /** Prefer the server's ProblemDetail (detail, then title), else the exception message. */
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

- [ ] **Step 6: Register `dashboardApi` in `Services`**

In `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`: add import `import com.company.pos.terminal.api.DashboardApi;`, add field `public final DashboardApi dashboardApi;` (next to `kitchenTicketApi`), and in the constructor add `this.dashboardApi = new DashboardApi(apiClient);`.

- [ ] **Step 7: Run test to verify it passes**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=DashboardViewModelTest`
Expected: PASS (2 tests).

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/dto/SalesSummaryReport.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ProductPerformanceReport.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/RevenueSummary.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/LowStockTile.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/DashboardSnapshot.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/DashboardApi.java \
        pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/DashboardViewModel.java \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/DashboardViewModelTest.java
git commit -m "feat(terminal): DashboardApi + DashboardViewModel + dashboard DTOs"
```

---

## Task 2: Dashboard screen + Home tile

No headless controller test (FXML/controller). Gate: clean compile + full terminal suite (incl. `FxmlContractTest`/`AppCssTest`).

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/DashboardController.java`
- Create: `pos-terminal/src/main/resources/fxml/dashboard.fxml`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/HomeController.java`
- Modify: `pos-terminal/src/main/resources/fxml/home.fxml`
- Modify: `pos-terminal/src/main/resources/css/app.css`

**Interfaces:**
- Consumes: `Services.dashboardApi`, `DashboardViewModel`, `FxTasks.run`, `Navigator`.
- Produces: `Navigator.toDashboard()`.

- [ ] **Step 1: Add `toDashboard()` to Navigator**

In `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java`, add (mirroring `toStaff()`):

```java
    public void toDashboard() {
        com.company.pos.terminal.view.DashboardController controller =
                new com.company.pos.terminal.view.DashboardController(services, this);
        setScene("/fxml/dashboard.fxml", controller);
    }
```

- [ ] **Step 2: Create `dashboard.fxml`**

Create `pos-terminal/src/main/resources/fxml/dashboard.fxml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?import javafx.scene.control.Button?>
<?import javafx.scene.control.Label?>
<?import javafx.scene.control.TableColumn?>
<?import javafx.scene.control.TableView?>
<?import javafx.scene.layout.BorderPane?>
<?import javafx.scene.layout.FlowPane?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.layout.Pane?>
<?import javafx.scene.layout.VBox?>

<BorderPane styleClass="screen" xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml">
    <top>
        <HBox spacing="16" alignment="CENTER_LEFT" styleClass="screen-header">
            <Label text="Dashboard" styleClass="title"/>
            <Label fx:id="errorLabel" styleClass="error-text"/>
            <Pane HBox.hgrow="ALWAYS"/>
            <Button fx:id="refreshButton" text="Refresh" styleClass="btn-secondary"/>
            <Button fx:id="backButton" text="Back" styleClass="btn-secondary"/>
        </HBox>
    </top>
    <center>
        <VBox spacing="16" styleClass="dashboard-body">
            <FlowPane fx:id="kpiRow" hgap="16" vgap="16"/>
            <HBox spacing="16" VBox.vgrow="ALWAYS">
                <VBox spacing="6" HBox.hgrow="ALWAYS">
                    <Label text="Best sellers" styleClass="section-label"/>
                    <TableView fx:id="bestSellersTable" VBox.vgrow="ALWAYS">
                        <columns>
                            <TableColumn fx:id="bsNameCol" text="Item"/>
                            <TableColumn fx:id="bsQtyCol" text="Qty"/>
                            <TableColumn fx:id="bsRevenueCol" text="Revenue"/>
                        </columns>
                    </TableView>
                </VBox>
                <VBox spacing="6" HBox.hgrow="ALWAYS">
                    <Label text="Low stock" styleClass="section-label"/>
                    <TableView fx:id="lowStockTable" VBox.vgrow="ALWAYS">
                        <columns>
                            <TableColumn fx:id="lsNameCol" text="Item"/>
                            <TableColumn fx:id="lsOnHandCol" text="On hand"/>
                            <TableColumn fx:id="lsReorderCol" text="Reorder"/>
                        </columns>
                    </TableView>
                </VBox>
                <VBox spacing="6" HBox.hgrow="ALWAYS">
                    <Label text="Open shifts" styleClass="section-label"/>
                    <TableView fx:id="shiftsTable" VBox.vgrow="ALWAYS">
                        <columns>
                            <TableColumn fx:id="shTerminalCol" text="Terminal"/>
                            <TableColumn fx:id="shOpenedByCol" text="Opened by"/>
                            <TableColumn fx:id="shOpenedAtCol" text="Opened at"/>
                        </columns>
                    </TableView>
                    <Label fx:id="activeCashiersLabel" styleClass="subtitle"/>
                </VBox>
            </HBox>
        </VBox>
    </center>
</BorderPane>
```

- [ ] **Step 3: Create `DashboardController`**

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/DashboardController.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.DashboardSnapshot;
import com.company.pos.terminal.api.dto.LowStockTile;
import com.company.pos.terminal.api.dto.ProductPerformanceReport.ProductLine;
import com.company.pos.terminal.api.dto.ShiftView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.DashboardViewModel;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

/** Manager dashboard: KPI tiles + best-sellers / low-stock / open-shifts tables. Read-only. */
public class DashboardController implements Navigator.Screen {

    private static final System.Logger LOG = System.getLogger(DashboardController.class.getName());
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());

    private final Services services;
    private final Navigator navigator;
    private final DashboardViewModel vm;

    @FXML private Label errorLabel;
    @FXML private Button refreshButton;
    @FXML private Button backButton;
    @FXML private FlowPane kpiRow;
    @FXML private TableView<ProductLine> bestSellersTable;
    @FXML private TableColumn<ProductLine, String> bsNameCol;
    @FXML private TableColumn<ProductLine, String> bsQtyCol;
    @FXML private TableColumn<ProductLine, String> bsRevenueCol;
    @FXML private TableView<LowStockTile> lowStockTable;
    @FXML private TableColumn<LowStockTile, String> lsNameCol;
    @FXML private TableColumn<LowStockTile, String> lsOnHandCol;
    @FXML private TableColumn<LowStockTile, String> lsReorderCol;
    @FXML private TableView<ShiftView> shiftsTable;
    @FXML private TableColumn<ShiftView, String> shTerminalCol;
    @FXML private TableColumn<ShiftView, String> shOpenedByCol;
    @FXML private TableColumn<ShiftView, String> shOpenedAtCol;
    @FXML private Label activeCashiersLabel;

    public DashboardController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new DashboardViewModel(services.dashboardApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        errorLabel.textProperty().bind(vm.errorMessage());
        errorLabel.visibleProperty().bind(vm.errorMessage().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        bsNameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        bsQtyCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().quantitySold().stripTrailingZeros().toPlainString()));
        bsRevenueCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().revenue().toPlainString()));
        lsNameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        lsOnHandCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().onHand().stripTrailingZeros().toPlainString()));
        lsReorderCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().reorderLevel().stripTrailingZeros().toPlainString()));
        shTerminalCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().terminalId()));
        shOpenedByCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().openedBy()));
        shOpenedAtCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().openedAt() == null ? "" : TS.format(c.getValue().openedAt())));

        refreshButton.setOnAction(e -> load());
        backButton.setOnAction(e -> navigator.toHome());
        load();
    }

    private void load() {
        DashboardSnapshot[] holder = new DashboardSnapshot[1];
        FxTasks.run(
                () -> holder[0] = vm.load(),
                () -> { if (holder[0] != null) render(holder[0]); },
                err -> LOG.log(System.Logger.Level.ERROR, "Dashboard load failed", err));
    }

    private void render(DashboardSnapshot s) {
        kpiRow.getChildren().setAll(
                kpi("Today's sales", money(s.todaysSales().grossSales(), s.currencyCode()),
                        s.todaysSales().saleCount() + " sales"),
                kpi("Revenue today", money(s.revenue().today(), s.currencyCode()), ""),
                kpi("Revenue " + s.revenue().windowDays() + "d",
                        money(s.revenue().window(), s.currencyCode()), ""));
        bestSellersTable.setItems(FXCollections.observableArrayList(s.bestSellers()));
        lowStockTable.setItems(FXCollections.observableArrayList(s.lowStock()));
        shiftsTable.setItems(FXCollections.observableArrayList(s.openShifts()));
        activeCashiersLabel.setText(s.activeCashiers().isEmpty()
                ? "No active cashiers"
                : "Active: " + String.join(", ", s.activeCashiers()));
    }

    private VBox kpi(String label, String value, String sub) {
        VBox tile = new VBox(4);
        tile.getStyleClass().add("kpi-tile");
        Label v = new Label(value);
        v.getStyleClass().add("kpi-value");
        Label l = new Label(label);
        l.getStyleClass().add("kpi-label");
        tile.getChildren().addAll(v, l);
        if (sub != null && !sub.isBlank()) {
            Label sb = new Label(sub);
            sb.getStyleClass().add("kpi-sub");
            tile.getChildren().add(sb);
        }
        return tile;
    }

    private static String money(java.math.BigDecimal amount, String currency) {
        return (amount == null ? "0" : amount.toPlainString()) + " " + currency;
    }
}
```

- [ ] **Step 4: Add the Dashboard tile to Home**

In `pos-terminal/src/main/resources/fxml/home.fxml`, add a tile to the second `HBox` (the `home-tile` row):

```xml
      <Button fx:id="dashboardButton" text="Dashboard" styleClass="home-tile"/>
```

In `pos-terminal/src/main/java/com/company/pos/terminal/view/HomeController.java`: add the field `@FXML private Button dashboardButton;` and wire it manager-gated in `initialize()`:

```java
        boolean showDashboard = services.session.isManager();
        dashboardButton.setVisible(showDashboard);
        dashboardButton.setManaged(showDashboard);
        dashboardButton.setOnAction(e -> navigator.toDashboard());
```

- [ ] **Step 5: Add dashboard styles**

Append to `pos-terminal/src/main/resources/css/app.css`:

```css
/* ---- Slice 18 — Dashboard + Reports ---- */
.screen-header { -fx-padding: 12 16 12 16; }
.dashboard-body { -fx-padding: 16; }
.kpi-tile {
    -fx-min-width: 200px; -fx-padding: 16;
    -fx-background-color: -fx-card; -fx-background-radius: 12;
    -fx-border-color: -fx-border; -fx-border-radius: 12;
}
.kpi-value { -fx-font-size: 34px; -fx-font-weight: bold; -fx-text-fill: -fx-ink; }
.kpi-label { -fx-font-size: 16px; -fx-text-fill: -fx-muted; }
.kpi-sub   { -fx-font-size: 13px; -fx-text-fill: -fx-primary; }
.section-label { -fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: -fx-ink; }
```

- [ ] **Step 6: Compile + full terminal suite**

Run: `./mvnw -f pos-terminal/pom.xml test`
Expected: PASS (all existing + new). Resolve any FXML `fx:id`↔`@FXML` mismatch (`FxmlContractTest`) or CSS issue (`AppCssTest`).

- [ ] **Step 7: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/DashboardController.java \
        pos-terminal/src/main/resources/fxml/dashboard.fxml \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/HomeController.java \
        pos-terminal/src/main/resources/fxml/home.fxml \
        pos-terminal/src/main/resources/css/app.css
git commit -m "feat(terminal): Dashboard screen + Home Dashboard tile (manager-gated)"
```

---

## Task 3: `ApiClient.getText` (raw-text GET for CSV)

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/ApiClient.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/ApiClientGetTextTest.java`

**Interfaces:**
- Produces: `ApiClient.getText(String path)` → the response body as a `String` (no JSON deserialization); non-2xx throws `ApiException`; a 401 clears the session.

- [ ] **Step 1: Write the failing test**

The terminal already has a `StubServer` test harness used by `QuoteApiTest` (`pos-terminal/src/test/java/com/company/pos/terminal/api/QuoteApiTest.java`). READ it first to mirror the exact `StubServer` construction and `ApiClient` wiring (base URL + a `SessionManager`). Create `pos-terminal/src/test/java/com/company/pos/terminal/api/ApiClientGetTextTest.java` following that harness:

```java
package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiClientGetTextTest {

    // Mirror QuoteApiTest's StubServer setup: start a stub, point an ApiClient at it with a
    // SessionManager holding a token. Replace the harness lines below with the exact pattern
    // used in QuoteApiTest (StubServer field, @BeforeEach start, @AfterEach stop, baseUrl()).
    private StubServer server;
    private ApiClient client;

    @BeforeEach
    void setUp() {
        server = new StubServer();          // match QuoteApiTest construction
        server.start();
        SessionManager session = new SessionManager();
        session.set(/* token + roles per QuoteApiTest's helper */ "tok", java.util.Set.of("MANAGER"), "mgr");
        client = new ApiClient(server.baseUrl(), session);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void getTextReturnsRawBody() {
        server.enqueue(200, "text/csv", "from,to\n2026-07-01,2026-07-24\n");
        String body = client.getText("/reports/sales?from=2026-07-01&to=2026-07-24&format=csv");
        assertEquals("from,to\n2026-07-01,2026-07-24\n", body);
    }

    @Test
    void getTextThrowsOnServerError() {
        server.enqueue(500, "text/plain", "boom");
        assertThrows(ApiException.class,
                () -> client.getText("/reports/sales?from=2026-07-01&to=2026-07-24&format=csv"));
    }
}
```

Adjust the `StubServer`/`SessionManager` calls to match `QuoteApiTest` exactly (method names for enqueue/response, and how `SessionManager` is seeded — if `SessionManager` has no `set(...)`, seed it the same way `QuoteApiTest` does). The two assertions (raw body returned; non-2xx throws) are the contract.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=ApiClientGetTextTest`
Expected: FAIL — `getText` does not exist.

- [ ] **Step 3: Implement `getText`**

In `pos-terminal/src/main/java/com/company/pos/terminal/api/ApiClient.java`, add this public method (it reuses the private `toApiException(...)` already in the class; it sends `Accept: text/csv` rather than JSON and skips deserialization):

```java
    /** Raw-text GET (no JSON deserialization) — for CSV report export. Same auth/error semantics
     *  as the JSON methods: non-2xx throws ApiException; a session 401 clears the session. */
    public String getText(String path) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "text/csv")
                    .GET();
            if (session.token() != null) {
                b.header("Authorization", "Bearer " + session.token());
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            if (sc == 401) {
                session.clear();
            }
            if (sc < 200 || sc >= 300) {
                throw toApiException(sc, resp.body());
            }
            return resp.body() == null ? "" : resp.body();
        } catch (ApiException e) {
            throw e;
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ApiException(0, null, "Cannot reach store server: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new ApiException(-1, null, "Client error: " + e.getMessage());
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=ApiClientGetTextTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/ApiClient.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/ApiClientGetTextTest.java
git commit -m "feat(terminal): ApiClient.getText raw-text GET for CSV export"
```

---

## Task 4: Reporting DTOs + ReportingApi + ReportType

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/PaymentBreakdownReport.java`, `TaxSummaryReport.java`, `CashierReport.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/ReportType.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/ReportingApi.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/ReportingApiTest.java`

**Interfaces:**
- Consumes: `ApiClient.get(...)` + `ApiClient.getText(...)` (Task 3); shared DTOs `SalesSummaryReport`, `ProductPerformanceReport` (Task 1).
- Produces DTOs: `PaymentBreakdownReport(LocalDate from, LocalDate to, String currencyCode, List<PaymentLine> lines, BigDecimal totalCollected, BigDecimal totalRefunded)` + nested `PaymentLine(String method, long count, BigDecimal collected, BigDecimal refunded)`; `TaxSummaryReport(LocalDate from, LocalDate to, String currencyCode, BigDecimal taxableAmount, BigDecimal taxCollected, BigDecimal refundTax, BigDecimal netTax)`; `CashierReport(LocalDate from, LocalDate to, String currencyCode, List<CashierLine> lines)` + nested `CashierLine(String cashierUsername, long saleCount, BigDecimal totalSales, BigDecimal totalDiscounts)`.
- Produces: `enum ReportType { SALES, PAYMENTS, TAX, CASHIERS, PRODUCTS }` with `String path()`.
- Produces: `ReportingApi(ApiClient client)` with `salesReport(LocalDate,LocalDate)`, `paymentsReport(LocalDate,LocalDate)`, `taxReport(LocalDate,LocalDate)`, `cashiersReport(LocalDate,LocalDate)`, `productsReport(LocalDate,LocalDate,int)`, `String exportCsv(ReportType,LocalDate,LocalDate,Integer)`.
- Produces: `Services.reportingApi`.

- [ ] **Step 1: Write the failing test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/api/ReportingApiTest.java` (mirror `QuoteApiTest`'s `StubServer` harness, as in Task 3):

```java
package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.dto.SalesSummaryReport;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReportingApiTest {

    private StubServer server;
    private ReportingApi api;

    @BeforeEach
    void setUp() {
        server = new StubServer();
        server.start();
        SessionManager session = new SessionManager();
        session.set("tok", java.util.Set.of("MANAGER"), "mgr");  // match QuoteApiTest seeding
        api = new ReportingApi(new ApiClient(server.baseUrl(), session));
    }

    @AfterEach
    void tearDown() { server.stop(); }

    @Test
    void salesReportRequestsCorrectPathAndParses() {
        server.enqueue(200, "application/json",
                "{\"from\":\"2026-07-01\",\"to\":\"2026-07-24\",\"currencyCode\":\"SAR\","
                + "\"saleCount\":34,\"subtotal\":1000,\"lineDiscounts\":0,\"txnDiscounts\":0,"
                + "\"taxTotal\":150,\"grossSales\":1240,\"returnCount\":0,\"refundTotal\":0,"
                + "\"netSales\":1240}");
        SalesSummaryReport r = api.salesReport(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-24"));
        assertEquals(34, r.saleCount());
        assertTrue(server.lastPath().startsWith("/reports/sales"));
        assertTrue(server.lastPath().contains("from=2026-07-01"));
        assertTrue(server.lastPath().contains("to=2026-07-24"));
    }

    @Test
    void exportCsvHitsCsvPathAndReturnsRawBody() {
        server.enqueue(200, "text/csv", "a,b\n1,2\n");
        String csv = api.exportCsv(ReportType.PRODUCTS, LocalDate.parse("2026-07-01"),
                LocalDate.parse("2026-07-24"), 50);
        assertEquals("a,b\n1,2\n", csv);
        assertTrue(server.lastPath().contains("/reports/products"));
        assertTrue(server.lastPath().contains("format=csv"));
        assertTrue(server.lastPath().contains("limit=50"));
    }
}
```

Adjust `StubServer` accessor names (`lastPath()`, `enqueue(...)`, `baseUrl()`) and `SessionManager` seeding to match `QuoteApiTest` exactly. The contract: JSON reports parse + hit the right path/params; `exportCsv` hits `format=csv` (and `limit` for products) and returns the raw body.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=ReportingApiTest`
Expected: FAIL — DTOs, `ReportType`, `ReportingApi` do not exist.

- [ ] **Step 3: Create the reporting DTOs**

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/PaymentBreakdownReport.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentBreakdownReport(LocalDate from, LocalDate to, String currencyCode,
        List<PaymentLine> lines, BigDecimal totalCollected, BigDecimal totalRefunded) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentLine(String method, long count, BigDecimal collected, BigDecimal refunded) {
    }
}
```

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/TaxSummaryReport.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TaxSummaryReport(LocalDate from, LocalDate to, String currencyCode,
        BigDecimal taxableAmount, BigDecimal taxCollected, BigDecimal refundTax, BigDecimal netTax) {
}
```

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/CashierReport.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CashierReport(LocalDate from, LocalDate to, String currencyCode,
        List<CashierLine> lines) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CashierLine(String cashierUsername, long saleCount, BigDecimal totalSales,
            BigDecimal totalDiscounts) {
    }
}
```

- [ ] **Step 4: Create `ReportType`**

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/ReportType.java`:

```java
package com.company.pos.terminal.api;

/** The five report endpoints, each carrying its /reports/<path> segment. */
public enum ReportType {
    SALES("sales"), PAYMENTS("payments"), TAX("tax"), CASHIERS("cashiers"), PRODUCTS("products");

    private final String path;

    ReportType(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }
}
```

- [ ] **Step 5: Create `ReportingApi`**

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/ReportingApi.java` (`LocalDate.toString()` is ISO `yyyy-MM-dd`, matching the backend `@DateTimeFormat(iso = DATE)`):

```java
package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.CashierReport;
import com.company.pos.terminal.api.dto.PaymentBreakdownReport;
import com.company.pos.terminal.api.dto.ProductPerformanceReport;
import com.company.pos.terminal.api.dto.SalesSummaryReport;
import com.company.pos.terminal.api.dto.TaxSummaryReport;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.LocalDate;

/** Typed client for the reporting endpoints. Non-final so view-model tests subclass it. */
public class ReportingApi {

    private final ApiClient client;

    public ReportingApi(ApiClient client) {
        this.client = client;
    }

    public SalesSummaryReport salesReport(LocalDate from, LocalDate to) {
        return client.get(range("/reports/sales", from, to), new TypeReference<SalesSummaryReport>() {});
    }

    public PaymentBreakdownReport paymentsReport(LocalDate from, LocalDate to) {
        return client.get(range("/reports/payments", from, to),
                new TypeReference<PaymentBreakdownReport>() {});
    }

    public TaxSummaryReport taxReport(LocalDate from, LocalDate to) {
        return client.get(range("/reports/tax", from, to), new TypeReference<TaxSummaryReport>() {});
    }

    public CashierReport cashiersReport(LocalDate from, LocalDate to) {
        return client.get(range("/reports/cashiers", from, to), new TypeReference<CashierReport>() {});
    }

    public ProductPerformanceReport productsReport(LocalDate from, LocalDate to, int limit) {
        return client.get(range("/reports/products", from, to) + "&limit=" + limit,
                new TypeReference<ProductPerformanceReport>() {});
    }

    /** Fetch a report as CSV text (backend text/csv). {@code limit} applies to PRODUCTS only. */
    public String exportCsv(ReportType type, LocalDate from, LocalDate to, Integer limit) {
        String path = range("/reports/" + type.path(), from, to) + "&format=csv";
        if (limit != null) {
            path += "&limit=" + limit;
        }
        return client.getText(path);
    }

    private static String range(String base, LocalDate from, LocalDate to) {
        return base + "?from=" + from + "&to=" + to;
    }
}
```

- [ ] **Step 6: Register `reportingApi` in `Services`**

In `Services.java`: add import `import com.company.pos.terminal.api.ReportingApi;`, field `public final ReportingApi reportingApi;`, and constructor line `this.reportingApi = new ReportingApi(apiClient);`.

- [ ] **Step 7: Run test to verify it passes**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=ReportingApiTest`
Expected: PASS (2 tests).

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/dto/PaymentBreakdownReport.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/TaxSummaryReport.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/CashierReport.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/ReportType.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/ReportingApi.java \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/ReportingApiTest.java
git commit -m "feat(terminal): ReportingApi + report DTOs + ReportType + CSV export"
```

---

## Task 5: ReportsViewModel (date presets + per-type fetch)

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/ReportsViewModel.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/ReportsViewModelTest.java`

**Interfaces:**
- Consumes: `ReportingApi`, `ReportType`, all report DTOs.
- Produces: `enum Preset { TODAY, YESTERDAY, LAST_7_DAYS, THIS_MONTH }`; ctors `ReportsViewModel(ReportingApi api)` and `(ReportingApi api, Consumer<Runnable> ui, Supplier<Instant> clock)`; `ReadOnlyStringProperty errorMessage()`; `LocalDate[] resolve(Preset preset)` returning `{from, to}`; `SalesSummaryReport sales(LocalDate,LocalDate)`, `PaymentBreakdownReport payments(LocalDate,LocalDate)`, `TaxSummaryReport tax(LocalDate,LocalDate)`, `CashierReport cashiers(LocalDate,LocalDate)`, `ProductPerformanceReport products(LocalDate,LocalDate,int)`, `String exportCsv(ReportType,LocalDate,LocalDate,Integer)`. Each fetch returns `null` on `ApiException` and sets `errorMessage`.

- [ ] **Step 1: Write the failing test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/ReportsViewModelTest.java`:

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.ReportType;
import com.company.pos.terminal.api.ReportingApi;
import com.company.pos.terminal.api.dto.SalesSummaryReport;
import com.company.pos.terminal.viewmodel.ReportsViewModel.Preset;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ReportsViewModelTest {

    // 2026-07-24 is a Friday. Clock fixed at noon UTC; presets resolve against system zone.
    private final Supplier<Instant> clock = () -> Instant.parse("2026-07-24T12:00:00Z");
    private LocalDate today() { return LocalDate.ofInstant(clock.get(), ZoneId.systemDefault()); }

    private ReportsViewModel vm(ReportingApi api, Consumer<Runnable> ui) {
        return new ReportsViewModel(api, ui, clock);
    }

    @Test
    void presetsResolveToDateRanges() {
        ReportsViewModel vm = vm(new ReportingApi(null), Runnable::run);
        LocalDate t = today();
        assertEquals(t, vm.resolve(Preset.TODAY)[0]);
        assertEquals(t, vm.resolve(Preset.TODAY)[1]);
        assertEquals(t.minusDays(1), vm.resolve(Preset.YESTERDAY)[0]);
        assertEquals(t.minusDays(1), vm.resolve(Preset.YESTERDAY)[1]);
        assertEquals(t.minusDays(6), vm.resolve(Preset.LAST_7_DAYS)[0]);   // 7 days incl. today
        assertEquals(t, vm.resolve(Preset.LAST_7_DAYS)[1]);
        assertEquals(t.withDayOfMonth(1), vm.resolve(Preset.THIS_MONTH)[0]);
        assertEquals(t, vm.resolve(Preset.THIS_MONTH)[1]);
    }

    @Test
    void salesDispatchesToApiAndReturnsReport() {
        SalesSummaryReport report = new SalesSummaryReport(LocalDate.parse("2026-07-01"),
                LocalDate.parse("2026-07-24"), "SAR", 5, BigDecimal.TEN, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.TEN, 0, BigDecimal.ZERO, BigDecimal.TEN);
        ReportingApi api = new ReportingApi(null) {
            @Override public SalesSummaryReport salesReport(LocalDate from, LocalDate to) { return report; }
        };
        ReportsViewModel vm = vm(api, Runnable::run);
        assertEquals(5, vm.sales(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-24")).saleCount());
    }

    @Test
    void exportCsvReturnsRawString() {
        ReportingApi api = new ReportingApi(null) {
            @Override public String exportCsv(ReportType t, LocalDate f, LocalDate to, Integer lim) {
                return "csv-body";
            }
        };
        ReportsViewModel vm = vm(api, Runnable::run);
        assertEquals("csv-body",
                vm.exportCsv(ReportType.SALES, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-24"), null));
    }

    @Test
    void fetchSurfacesErrorUnderDeferredDispatcher() {
        ReportingApi api = new ReportingApi(null) {
            @Override public SalesSummaryReport salesReport(LocalDate from, LocalDate to) {
                throw new ApiException(500, new ProblemDetail("Error", 500, "boom"), "HTTP 500");
            }
        };
        Deque<Runnable> queue = new ArrayDeque<>();
        ReportsViewModel vm = vm(api, queue::add);

        SalesSummaryReport r = vm.sales(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-24"));

        assertNull(r);
        assertEquals("", vm.errorMessage().get());
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals("boom", vm.errorMessage().get());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=ReportsViewModelTest`
Expected: FAIL — `ReportsViewModel` does not exist.

- [ ] **Step 3: Create `ReportsViewModel`**

Create `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/ReportsViewModel.java`:

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.ReportType;
import com.company.pos.terminal.api.ReportingApi;
import com.company.pos.terminal.api.dto.CashierReport;
import com.company.pos.terminal.api.dto.PaymentBreakdownReport;
import com.company.pos.terminal.api.dto.ProductPerformanceReport;
import com.company.pos.terminal.api.dto.SalesSummaryReport;
import com.company.pos.terminal.api.dto.TaxSummaryReport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the reports screen. Synchronous (the controller runs it off the FX thread via
 * FxTasks); the only off-thread observable write is {@code errorMessage} inside {@code ui}. Date
 * presets resolve against the injected {@code clock} (never inline Instant.now()).
 */
public class ReportsViewModel {

    /** Quick-pick ranges. CUSTOM is not here — the controller supplies custom dates directly. */
    public enum Preset { TODAY, YESTERDAY, LAST_7_DAYS, THIS_MONTH }

    private final ReportingApi api;
    private final Consumer<Runnable> ui;
    private final Supplier<Instant> clock;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public ReportsViewModel(ReportingApi api) {
        this(api, Runnable::run, Instant::now);
    }

    public ReportsViewModel(ReportingApi api, Consumer<Runnable> ui, Supplier<Instant> clock) {
        this.api = api;
        this.ui = ui;
        this.clock = clock;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    /** Resolve a preset to {from, to} inclusive, against today (system zone) from the clock. */
    public LocalDate[] resolve(Preset preset) {
        LocalDate today = LocalDate.ofInstant(clock.get(), ZoneId.systemDefault());
        return switch (preset) {
            case TODAY -> new LocalDate[] {today, today};
            case YESTERDAY -> new LocalDate[] {today.minusDays(1), today.minusDays(1)};
            case LAST_7_DAYS -> new LocalDate[] {today.minusDays(6), today};
            case THIS_MONTH -> new LocalDate[] {today.withDayOfMonth(1), today};
        };
    }

    public SalesSummaryReport sales(LocalDate from, LocalDate to) {
        return fetch(() -> api.salesReport(from, to));
    }

    public PaymentBreakdownReport payments(LocalDate from, LocalDate to) {
        return fetch(() -> api.paymentsReport(from, to));
    }

    public TaxSummaryReport tax(LocalDate from, LocalDate to) {
        return fetch(() -> api.taxReport(from, to));
    }

    public CashierReport cashiers(LocalDate from, LocalDate to) {
        return fetch(() -> api.cashiersReport(from, to));
    }

    public ProductPerformanceReport products(LocalDate from, LocalDate to, int limit) {
        return fetch(() -> api.productsReport(from, to, limit));
    }

    /** Fetch a report as CSV; null on failure (error surfaced via errorMessage). */
    public String exportCsv(ReportType type, LocalDate from, LocalDate to, Integer limit) {
        return fetch(() -> api.exportCsv(type, from, to, limit));
    }

    private <T> T fetch(Supplier<T> call) {
        try {
            T result = call.get();
            ui.accept(() -> errorMessage.set(""));
            return result;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return null;
        }
    }

    /** Prefer the server's ProblemDetail (detail, then title), else the exception message. */
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

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=ReportsViewModelTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/ReportsViewModel.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/ReportsViewModelTest.java
git commit -m "feat(terminal): ReportsViewModel with date presets + per-type fetch"
```

---

## Task 6: Reports screen + Admin tile + CSV save

No headless controller test (FXML/`FileChooser` glue). Gate: full terminal suite (incl. `FxmlContractTest`/`AppCssTest`).

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/ReportsController.java`
- Create: `pos-terminal/src/main/resources/fxml/reports.fxml`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/AdminController.java`
- Modify: `pos-terminal/src/main/resources/fxml/admin.fxml`
- Modify: `pos-terminal/src/main/resources/css/app.css`

**Interfaces:**
- Consumes: `Services.reportingApi`, `ReportsViewModel`, `ReportType`, `FxTasks.run`, `Navigator`.
- Produces: `Navigator.toReports()`.

- [ ] **Step 1: Add `toReports()` to Navigator**

In `Navigator.java`:

```java
    public void toReports() {
        com.company.pos.terminal.view.ReportsController controller =
                new com.company.pos.terminal.view.ReportsController(services, this);
        setScene("/fxml/reports.fxml", controller);
    }
```

- [ ] **Step 2: Create `reports.fxml`**

Create `pos-terminal/src/main/resources/fxml/reports.fxml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?import javafx.scene.control.Button?>
<?import javafx.scene.control.DatePicker?>
<?import javafx.scene.control.Label?>
<?import javafx.scene.control.ToggleButton?>
<?import javafx.scene.layout.BorderPane?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.layout.Pane?>
<?import javafx.scene.layout.StackPane?>
<?import javafx.scene.layout.VBox?>

<BorderPane styleClass="screen" xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml">
    <top>
        <VBox spacing="8" styleClass="screen-header">
            <HBox spacing="16" alignment="CENTER_LEFT">
                <Label text="Reports" styleClass="title"/>
                <Label fx:id="errorLabel" styleClass="error-text"/>
                <Pane HBox.hgrow="ALWAYS"/>
                <Button fx:id="exportButton" text="Export CSV" styleClass="btn-secondary"/>
                <Button fx:id="backButton" text="Back" styleClass="btn-secondary"/>
            </HBox>
            <HBox fx:id="typeRow" spacing="8"/>
            <HBox spacing="8" alignment="CENTER_LEFT">
                <ToggleButton fx:id="todayChip" text="Today" styleClass="chip"/>
                <ToggleButton fx:id="yesterdayChip" text="Yesterday" styleClass="chip"/>
                <ToggleButton fx:id="last7Chip" text="Last 7 days" styleClass="chip"/>
                <ToggleButton fx:id="monthChip" text="This month" styleClass="chip"/>
                <ToggleButton fx:id="customChip" text="Custom" styleClass="chip"/>
                <HBox fx:id="customRow" spacing="8" alignment="CENTER_LEFT">
                    <Label text="From"/>
                    <DatePicker fx:id="fromPicker"/>
                    <Label text="To"/>
                    <DatePicker fx:id="toPicker"/>
                    <Button fx:id="runButton" text="Run" styleClass="btn-primary"/>
                </HBox>
            </HBox>
        </VBox>
    </top>
    <center>
        <StackPane fx:id="resultArea" styleClass="report-result"/>
    </center>
</BorderPane>
```

- [ ] **Step 3: Create `ReportsController`**

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/ReportsController.java`. It renders scalar reports (Sales, Tax) as a tile grid and list reports (Payments, Cashiers, Products) as a `TableView`, exports via `FileChooser`, and resolves presets through the VM. The render switch is exhaustive over `ReportType`.

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.ReportType;
import com.company.pos.terminal.api.dto.CashierReport;
import com.company.pos.terminal.api.dto.PaymentBreakdownReport;
import com.company.pos.terminal.api.dto.ProductPerformanceReport;
import com.company.pos.terminal.api.dto.SalesSummaryReport;
import com.company.pos.terminal.api.dto.TaxSummaryReport;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.ReportsViewModel;
import com.company.pos.terminal.viewmodel.ReportsViewModel.Preset;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/** Reports screen: report-type selector + preset/custom date range + results + CSV export. */
public class ReportsController implements Navigator.Screen {

    private static final System.Logger LOG = System.getLogger(ReportsController.class.getName());
    private static final int PRODUCTS_LIMIT = 50;

    private final Services services;
    private final Navigator navigator;
    private final ReportsViewModel vm;
    private final ToggleGroup typeGroup = new ToggleGroup();
    private final ToggleGroup rangeGroup = new ToggleGroup();

    private ReportType selectedType = ReportType.SALES;
    private LocalDate from;
    private LocalDate to;

    @FXML private Label errorLabel;
    @FXML private Button exportButton;
    @FXML private Button backButton;
    @FXML private HBox typeRow;
    @FXML private ToggleButton todayChip;
    @FXML private ToggleButton yesterdayChip;
    @FXML private ToggleButton last7Chip;
    @FXML private ToggleButton monthChip;
    @FXML private ToggleButton customChip;
    @FXML private HBox customRow;
    @FXML private DatePicker fromPicker;
    @FXML private DatePicker toPicker;
    @FXML private Button runButton;
    @FXML private StackPane resultArea;

    public ReportsController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new ReportsViewModel(services.reportingApi, Platform::runLater, java.time.Instant::now);
    }

    @FXML
    public void initialize() {
        errorLabel.textProperty().bind(vm.errorMessage());
        errorLabel.visibleProperty().bind(vm.errorMessage().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        for (ReportType type : ReportType.values()) {
            ToggleButton b = new ToggleButton(label(type));
            b.getStyleClass().add("chip");
            b.setToggleGroup(typeGroup);
            b.setUserData(type);
            b.setOnAction(e -> { selectedType = type; fetch(); });
            if (type == ReportType.SALES) b.setSelected(true);
            typeRow.getChildren().add(b);
        }

        todayChip.setToggleGroup(rangeGroup);
        yesterdayChip.setToggleGroup(rangeGroup);
        last7Chip.setToggleGroup(rangeGroup);
        monthChip.setToggleGroup(rangeGroup);
        customChip.setToggleGroup(rangeGroup);
        todayChip.setOnAction(e -> applyPreset(Preset.TODAY));
        yesterdayChip.setOnAction(e -> applyPreset(Preset.YESTERDAY));
        last7Chip.setOnAction(e -> applyPreset(Preset.LAST_7_DAYS));
        monthChip.setOnAction(e -> applyPreset(Preset.THIS_MONTH));
        customChip.setOnAction(e -> showCustom(true));
        runButton.setOnAction(e -> applyCustom());

        exportButton.setOnAction(e -> export());
        backButton.setOnAction(e -> navigator.toAdmin());

        showCustom(false);
        todayChip.setSelected(true);
        applyPreset(Preset.TODAY);
    }

    private void showCustom(boolean show) {
        customRow.setVisible(show);
        customRow.setManaged(show);
    }

    private void applyPreset(Preset preset) {
        showCustom(false);
        LocalDate[] r = vm.resolve(preset);
        from = r[0];
        to = r[1];
        fetch();
    }

    private void applyCustom() {
        if (fromPicker.getValue() == null || toPicker.getValue() == null) {
            return;
        }
        from = fromPicker.getValue();
        to = toPicker.getValue();
        fetch();
    }

    private void fetch() {
        if (from == null || to == null) {
            return;
        }
        LocalDate f = from;
        LocalDate t = to;
        ReportType type = selectedType;
        Object[] holder = new Object[1];
        FxTasks.run(
                () -> holder[0] = fetchFor(type, f, t),
                () -> { if (holder[0] != null) render(type, holder[0]); },
                err -> LOG.log(System.Logger.Level.ERROR, "Report fetch failed", err));
    }

    private Object fetchFor(ReportType type, LocalDate f, LocalDate t) {
        return switch (type) {
            case SALES -> vm.sales(f, t);
            case PAYMENTS -> vm.payments(f, t);
            case TAX -> vm.tax(f, t);
            case CASHIERS -> vm.cashiers(f, t);
            case PRODUCTS -> vm.products(f, t, PRODUCTS_LIMIT);
        };
    }

    private void render(ReportType type, Object report) {
        switch (type) {
            case SALES -> renderSales((SalesSummaryReport) report);
            case TAX -> renderTax((TaxSummaryReport) report);
            case PAYMENTS -> renderPayments((PaymentBreakdownReport) report);
            case CASHIERS -> renderCashiers((CashierReport) report);
            case PRODUCTS -> renderProducts((ProductPerformanceReport) report);
        }
    }

    private void renderSales(SalesSummaryReport r) {
        FlowPane tiles = tileGrid();
        tiles.getChildren().addAll(
                tile("Sales", r.saleCount() + ""),
                tile("Gross", money(r.grossSales(), r.currencyCode())),
                tile("Discounts", money(r.lineDiscounts().add(r.txnDiscounts()), r.currencyCode())),
                tile("Tax", money(r.taxTotal(), r.currencyCode())),
                tile("Returns", r.returnCount() + ""),
                tile("Refunds", money(r.refundTotal(), r.currencyCode())),
                tile("Net", money(r.netSales(), r.currencyCode())));
        resultArea.getChildren().setAll(tiles);
    }

    private void renderTax(TaxSummaryReport r) {
        FlowPane tiles = tileGrid();
        tiles.getChildren().addAll(
                tile("Taxable", money(r.taxableAmount(), r.currencyCode())),
                tile("Tax collected", money(r.taxCollected(), r.currencyCode())),
                tile("Refund tax", money(r.refundTax(), r.currencyCode())),
                tile("Net tax", money(r.netTax(), r.currencyCode())));
        resultArea.getChildren().setAll(tiles);
    }

    private void renderPayments(PaymentBreakdownReport r) {
        TableView<PaymentBreakdownReport.PaymentLine> t = new TableView<>();
        addCol(t, "Method", l -> l.method());
        addCol(t, "Count", l -> l.count() + "");
        addCol(t, "Collected", l -> l.collected().toPlainString());
        addCol(t, "Refunded", l -> l.refunded().toPlainString());
        t.setItems(FXCollections.observableArrayList(r.lines()));
        resultArea.getChildren().setAll(t);
    }

    private void renderCashiers(CashierReport r) {
        TableView<CashierReport.CashierLine> t = new TableView<>();
        addCol(t, "Cashier", l -> l.cashierUsername());
        addCol(t, "Sales", l -> l.saleCount() + "");
        addCol(t, "Total", l -> l.totalSales().toPlainString());
        addCol(t, "Discounts", l -> l.totalDiscounts().toPlainString());
        t.setItems(FXCollections.observableArrayList(r.lines()));
        resultArea.getChildren().setAll(t);
    }

    private void renderProducts(ProductPerformanceReport r) {
        TableView<ProductPerformanceReport.ProductLine> t = new TableView<>();
        addCol(t, "SKU", l -> l.sku());
        addCol(t, "Item", l -> l.name());
        addCol(t, "Qty", l -> l.quantitySold().stripTrailingZeros().toPlainString());
        addCol(t, "Revenue", l -> l.revenue().toPlainString());
        addCol(t, "Discounts", l -> l.discounts().toPlainString());
        t.setItems(FXCollections.observableArrayList(r.lines()));
        resultArea.getChildren().setAll(t);
    }

    private void export() {
        if (from == null || to == null) {
            return;
        }
        LocalDate f = from;
        LocalDate t = to;
        ReportType type = selectedType;
        Integer limit = type == ReportType.PRODUCTS ? PRODUCTS_LIMIT : null;
        String[] holder = new String[1];
        FxTasks.run(
                () -> holder[0] = vm.exportCsv(type, f, t, limit),
                () -> { if (holder[0] != null) saveCsv(type, f, t, holder[0]); },
                err -> LOG.log(System.Logger.Level.ERROR, "CSV export failed", err));
    }

    private void saveCsv(ReportType type, LocalDate f, LocalDate t, String csv) {
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName(type.path() + "-" + f + "_" + t + ".csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        java.io.File file = chooser.showSaveDialog(resultArea.getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file.toPath(), csv);
        } catch (java.io.IOException ex) {
            LOG.log(System.Logger.Level.ERROR, "Cannot write CSV", ex);
        }
    }

    private FlowPane tileGrid() {
        FlowPane p = new FlowPane();
        p.setHgap(16);
        p.setVgap(16);
        p.getStyleClass().add("report-tiles");
        return p;
    }

    private VBox tile(String label, String value) {
        VBox v = new VBox(4);
        v.getStyleClass().add("kpi-tile");
        Label val = new Label(value);
        val.getStyleClass().add("kpi-value");
        Label lab = new Label(label);
        lab.getStyleClass().add("kpi-label");
        v.getChildren().addAll(val, lab);
        return v;
    }

    private <S> void addCol(TableView<S> table, String title, java.util.function.Function<S, String> get) {
        TableColumn<S, String> col = new TableColumn<>(title);
        col.setCellValueFactory(c -> new SimpleStringProperty(get.apply(c.getValue())));
        table.getColumns().add(col);
    }

    private static String label(ReportType type) {
        return switch (type) {
            case SALES -> "Sales";
            case PAYMENTS -> "Payments";
            case TAX -> "Tax";
            case CASHIERS -> "Cashiers";
            case PRODUCTS -> "Products";
        };
    }

    private static String money(java.math.BigDecimal amount, String currency) {
        return (amount == null ? "0" : amount.toPlainString()) + " " + currency;
    }
}
```

Note: the FXML declares `ToggleButton`s in `typeRow` via code (the row is populated in `initialize()`), so `typeRow` starts empty in FXML — that's intentional. The five preset chips + custom are declared in FXML with `fx:id`s matching the `@FXML` fields.

- [ ] **Step 4: Add the Reports tile to Admin**

In `pos-terminal/src/main/resources/fxml/admin.fxml`, add to the tile-row `HBox`:

```xml
      <Button fx:id="reportsButton" text="Reports" styleClass="home-tile"/>
```

In `pos-terminal/src/main/java/com/company/pos/terminal/view/AdminController.java`: add field `@FXML private Button reportsButton;` and wire it manager-gated in `initialize()`:

```java
        reportsButton.setVisible(manager);
        reportsButton.setManaged(manager);
        reportsButton.setOnAction(e -> navigator.toReports());
```

(`boolean manager = services.session.isManager();` is already computed earlier in `initialize()`.)

- [ ] **Step 5: Add reports styles**

Append to the Slice-18 block in `pos-terminal/src/main/resources/css/app.css`:

```css
.chip {
    -fx-background-color: -fx-card; -fx-background-radius: 20;
    -fx-border-color: -fx-border; -fx-border-radius: 20; -fx-padding: 8 16 8 16;
    -fx-cursor: hand;
}
.chip:selected { -fx-background-color: derive(-fx-primary, 80%); -fx-border-color: -fx-primary; }
.report-result { -fx-padding: 16; }
.report-tiles { -fx-padding: 8; }
```

- [ ] **Step 6: Compile + full terminal suite**

Run: `./mvnw -f pos-terminal/pom.xml test`
Expected: PASS (all existing + new). Fix any FXML `fx:id`↔`@FXML` mismatch. Every `@FXML` field in `ReportsController` must have a matching `fx:id` in `reports.fxml` (except the code-populated `ToggleButton`s inside `typeRow`).

- [ ] **Step 7: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/ReportsController.java \
        pos-terminal/src/main/resources/fxml/reports.fxml \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/AdminController.java \
        pos-terminal/src/main/resources/fxml/admin.fxml \
        pos-terminal/src/main/resources/css/app.css
git commit -m "feat(terminal): Reports screen + Admin Reports tile + CSV export"
```

---

## Task 7: Final integration gate + docs

**Files:**
- Modify: `docs/run-modes.md` (note the terminal now surfaces dashboard + reports).

- [ ] **Step 1: Full terminal build**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml clean test`
Expected: PASS (all terminal tests). If `QuoteApiTest` alone flakes with an HTTP 403, rerun it in isolation (`./mvnw -f pos-terminal/pom.xml test -Dtest=QuoteApiTest`) to confirm it's the known StubServer flake, not this change.

- [ ] **Step 2: Document the terminal surfacing**

In `docs/run-modes.md`, under the terminal/UI notes, add a short line: the JavaFX terminal now surfaces the manager **Dashboard** (Home tile, MANAGER/ADMIN) over `GET /dashboard`, and a **Reports** screen (Admin hub, MANAGER/ADMIN) over `GET /reports/{sales,payments,tax,cashiers,products}` with preset/custom date ranges and CSV export (via the new `ApiClient.getText`). No new backend endpoints. Match the document's existing style.

- [ ] **Step 3: Commit**

```bash
git add docs/run-modes.md
git commit -m "docs: terminal surfaces manager dashboard + reports (slice 18)"
```

---

## Self-review notes (author)

- **Spec coverage:** Dashboard DTOs/Api/VM (T1), Dashboard screen + Home tile + blind-close-safe render (T2), raw-text GET (T3), reporting DTOs/Api/CSV (T4), ReportsViewModel presets + fetch (T5), Reports screen + Admin tile + FileChooser export (T6), gate + docs (T7). Every spec section maps to a task.
- **No backend changes** — all tasks are `-f pos-terminal/pom.xml`.
- **Type consistency:** terminal DTO fields copied verbatim from the backend records; `ReportType.path()` segments match the controller `@GetMapping` paths; `salesReport/paymentsReport/taxReport/cashiersReport/productsReport/exportCsv` names identical across `ReportingApi` (T4), `ReportsViewModel` (T5), and `ReportsController` (T6); `Preset` enum values consistent T5↔T6; `resolve(Preset)` returns `LocalDate[]{from,to}` used identically.
- **Verify-before-code hooks:** the `StubServer`/`SessionManager` seeding in T3/T4 tests must be matched to `QuoteApiTest`'s exact harness (accessor names may differ) — called out inline. Existing `dto/ShiftView` reused (not duplicated). `ApiClient.getText` reuses the private `toApiException` in the same class.
- **Blind-close:** dashboard renders only `ShiftView` fields (no `expectedCash`) — enforced by the DTO shape and the T2 render code.
