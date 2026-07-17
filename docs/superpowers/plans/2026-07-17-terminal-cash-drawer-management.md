# Terminal Cash-Drawer Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a terminal cash-drawer surface — record pay-in/pay-out movements and view a blind-safe activity peek — against the already-existing backend endpoints, without un-blinding the shift close.

**Architecture:** Terminal-only (JavaFX thin REST client). A new `CashDrawerApi` + `CashDrawerViewModel` drive two I/O-free modals (`DrawerActivityDialog`, `CashMovementDialog`) launched from a new **Drawer** button on the Home screen. All HTTP runs in `FxTasks` work lambdas; results are read in `onDone` via holder arrays; the VM writes only `errorMessage` off-thread inside `ui.accept`. No backend, migration, config, or Maven change — `POST /cash-drawer/pay-in`, `POST /cash-drawer/pay-out`, and `GET /cash-drawer/reconciliation` already exist and are cashier-level.

**Tech Stack:** Java 21, JavaFX, Jackson (`ApiClient` + `TypeReference`), JUnit 5. Build/test: `./mvnw -f pos-terminal/pom.xml clean test` (headless).

## Global Constraints

- **Build/test command:** `./mvnw -f pos-terminal/pom.xml clean test` (set `JAVA_HOME` to JDK 21 first: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`). The terminal is a **separate build**, not in the root reactor.
- **Blind-count integrity (the crux):** the activity peek must NEVER display `expectedCash`, `variance`, or the cash-sales **amount**. It shows opening float, cash-sales **count**, pay-ins total, pay-outs total — exactly those four. This is enforced by `DrawerActivityDialogTest`.
- **FX-threading convention:** VM methods are synchronous on the calling thread and return plain values (`DrawerReconciliation` / `CashMovementView` / `null`). The controller runs them off-thread via `FxTasks.run(work, onDone, onError)` and reads results in the FX-thread `onDone` via a holder array. The ONLY observable a VM writes off-thread is `errorMessage`, and only inside `ui.accept(...)`. Never call a blocking VM/HTTP method inside `onDone` — kick a new `FxTasks` task instead (`showAndWait()` is fine in `onDone`).
- **Cashier-level:** no manager-PIN token anywhere in this slice.
- **Money:** `BigDecimal`; amounts sent to the server are `setScale(2, RoundingMode.HALF_UP)`.
- **Dialogs are I/O-free:** they collect input or display a passed-in value; all I/O lives in `HomeController` via `FxTasks` + the VM (matches the existing `closeShift` seam).
- **Error-message precedence** (copy verbatim from the existing shift VMs): `problem.detail()` → `problem.title()` → `getMessage()` → `"Request failed"`.

---

### Task 1: Data + service + view-model core

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/CashMovementView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/CashMovementRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/CashDrawerApi.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/CashDrawerViewModel.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/CashDrawerViewModelTest.java`

**Interfaces:**
- Consumes: `ApiClient` (`get`/`post` with `TypeReference`), existing `api/dto/DrawerReconciliation`, `ApiException`, `ProblemDetail`.
- Produces:
  - `CashDrawerApi(ApiClient)` with `DrawerReconciliation reconciliation()`, `CashMovementView payIn(BigDecimal, String)`, `CashMovementView payOut(BigDecimal, String)`.
  - `CashDrawerViewModel(CashDrawerApi, Consumer<Runnable>)` with `DrawerReconciliation loadActivity()`, `CashMovementView payIn(BigDecimal, String)`, `CashMovementView payOut(BigDecimal, String)`, `ReadOnlyStringProperty errorMessage()`.
  - `Services.cashDrawerApi` field.

- [ ] **Step 1: Write the failing VM test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/CashDrawerViewModelTest.java`:

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.CashDrawerApi;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.dto.CashMovementView;
import com.company.pos.terminal.api.dto.DrawerReconciliation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CashDrawerViewModelTest {

    private DrawerReconciliation sampleActivity() {
        return new DrawerReconciliation(UUID.randomUUID(), new BigDecimal("500.00"),
                new BigDecimal("1200.00"), 37, new BigDecimal("100.00"), new BigDecimal("50.00"),
                new BigDecimal("1750.00"), null, null, "SAR");
    }

    private CashMovementView sampleMovement(String type, BigDecimal amount) {
        return new CashMovementView(UUID.randomUUID(), UUID.randomUUID(), type, amount,
                "till drop", Instant.now());
    }

    @Test
    void loadActivityReturnsReconciliation() {
        CashDrawerApi api = new CashDrawerApi(null) {
            @Override public DrawerReconciliation reconciliation() { return sampleActivity(); }
        };
        CashDrawerViewModel vm = new CashDrawerViewModel(api, Runnable::run);
        DrawerReconciliation a = vm.loadActivity();
        assertNotNull(a);
        assertEquals(37, a.cashSalesCount());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void loadActivitySurfacesErrorAndReturnsNull() {
        CashDrawerApi api = new CashDrawerApi(null) {
            @Override public DrawerReconciliation reconciliation() {
                throw new ApiException(409, new ProblemDetail("Conflict", 409, "No open drawer"),
                        "HTTP 409");
            }
        };
        CashDrawerViewModel vm = new CashDrawerViewModel(api, Runnable::run);
        assertNull(vm.loadActivity());
        assertEquals("No open drawer", vm.errorMessage().get());
    }

    @Test
    void payInValidReturnsMovementAndClearsError() {
        CashDrawerApi api = new CashDrawerApi(null) {
            @Override public CashMovementView payIn(BigDecimal amount, String reason) {
                assertEquals(0, new BigDecimal("25.00").compareTo(amount));
                assertEquals("float top-up", reason);
                return sampleMovement("PAY_IN", amount);
            }
        };
        CashDrawerViewModel vm = new CashDrawerViewModel(api, Runnable::run);
        CashMovementView m = vm.payIn(new BigDecimal("25"), "  float top-up  ");
        assertNotNull(m);
        assertEquals("PAY_IN", m.type());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void payInRejectsNonPositiveAmountWithoutCallingApi() {
        CashDrawerApi api = new CashDrawerApi(null) {
            @Override public CashMovementView payIn(BigDecimal amount, String reason) {
                throw new AssertionError("API must not be called for invalid amount");
            }
        };
        CashDrawerViewModel vm = new CashDrawerViewModel(api, Runnable::run);
        assertNull(vm.payIn(BigDecimal.ZERO, "reason"));
        assertEquals("Amount must be greater than zero", vm.errorMessage().get());
        assertNull(vm.payIn(new BigDecimal("-5"), "reason"));
    }

    @Test
    void payInRejectsBlankReasonWithoutCallingApi() {
        CashDrawerApi api = new CashDrawerApi(null) {
            @Override public CashMovementView payIn(BigDecimal amount, String reason) {
                throw new AssertionError("API must not be called for blank reason");
            }
        };
        CashDrawerViewModel vm = new CashDrawerViewModel(api, Runnable::run);
        assertNull(vm.payIn(new BigDecimal("10"), "   "));
        assertEquals("Enter a reason", vm.errorMessage().get());
    }

    @Test
    void payInSurfacesApiError() {
        CashDrawerApi api = new CashDrawerApi(null) {
            @Override public CashMovementView payIn(BigDecimal amount, String reason) {
                throw new ApiException(400, new ProblemDetail("Bad", 400, "Amount too large"),
                        "HTTP 400");
            }
        };
        CashDrawerViewModel vm = new CashDrawerViewModel(api, Runnable::run);
        assertNull(vm.payIn(new BigDecimal("10"), "reason"));
        assertEquals("Amount too large", vm.errorMessage().get());
    }

    @Test
    void payOutValidReturnsMovement() {
        CashDrawerApi api = new CashDrawerApi(null) {
            @Override public CashMovementView payOut(BigDecimal amount, String reason) {
                return sampleMovement("PAY_OUT", amount);
            }
        };
        CashDrawerViewModel vm = new CashDrawerViewModel(api, Runnable::run);
        CashMovementView m = vm.payOut(new BigDecimal("15"), "supplier cash");
        assertNotNull(m);
        assertEquals("PAY_OUT", m.type());
    }

    @Test
    void deferredDispatcherHoldsErrorUntilDrained() {
        CashDrawerApi api = new CashDrawerApi(null) {
            @Override public CashMovementView payIn(BigDecimal amount, String reason) {
                throw new AssertionError("must not call API for invalid amount");
            }
        };
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        CashDrawerViewModel vm = new CashDrawerViewModel(api, queue::add);
        CashMovementView result = vm.payIn(BigDecimal.ZERO, "reason");
        assertNull(result);                          // synchronous return
        assertEquals("", vm.errorMessage().get());   // deferred: error not applied yet
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals("Amount must be greater than zero", vm.errorMessage().get());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml test -Dtest=CashDrawerViewModelTest`
Expected: FAIL — compilation error (`CashDrawerApi`, `CashMovementView`, `CashDrawerViewModel` do not exist).

- [ ] **Step 3: Create the DTOs**

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/CashMovementView.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A single cash-drawer movement returned by the pay-in / pay-out endpoints. Mirrors the server
 *  {@code cashdrawer.api.CashMovementView}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CashMovementView(UUID id, UUID sessionId, String type, BigDecimal amount,
        String reference, Instant createdAt) {
}
```

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/CashMovementRequest.java`:

```java
package com.company.pos.terminal.api.dto;

import java.math.BigDecimal;

/** POST body for {@code /cash-drawer/pay-in} and {@code /cash-drawer/pay-out}. Mirrors the server
 *  {@code CashMovementRequest{amount, reason}}. */
public record CashMovementRequest(BigDecimal amount, String reason) {
}
```

- [ ] **Step 4: Create `CashDrawerApi`**

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/CashDrawerApi.java`:

```java
package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.CashMovementRequest;
import com.company.pos.terminal.api.dto.CashMovementView;
import com.company.pos.terminal.api.dto.DrawerReconciliation;
import com.fasterxml.jackson.core.type.TypeReference;
import java.math.BigDecimal;

/** Typed client for the store server's {@code /cash-drawer} endpoints (cashier-level). */
public class CashDrawerApi {

    private final ApiClient client;

    public CashDrawerApi(ApiClient client) {
        this.client = client;
    }

    /** GET /cash-drawer/reconciliation — the open drawer session's current reconciliation. */
    public DrawerReconciliation reconciliation() {
        return client.get("/cash-drawer/reconciliation", new TypeReference<DrawerReconciliation>() {});
    }

    /** POST /cash-drawer/pay-in — records cash added to the drawer. */
    public CashMovementView payIn(BigDecimal amount, String reason) {
        return client.post("/cash-drawer/pay-in", new CashMovementRequest(amount, reason),
                new TypeReference<CashMovementView>() {});
    }

    /** POST /cash-drawer/pay-out — records cash removed from the drawer. */
    public CashMovementView payOut(BigDecimal amount, String reason) {
        return client.post("/cash-drawer/pay-out", new CashMovementRequest(amount, reason),
                new TypeReference<CashMovementView>() {});
    }
}
```

- [ ] **Step 5: Create `CashDrawerViewModel`**

Create `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/CashDrawerViewModel.java`:

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.CashDrawerApi;
import com.company.pos.terminal.api.dto.CashMovementView;
import com.company.pos.terminal.api.dto.DrawerReconciliation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for mid-shift cash-drawer management. Synchronous like the other VMs (the controller
 * runs it off the FX thread via FxTasks); the only observable written off-thread is
 * {@code errorMessage}, inside the {@code ui} dispatcher. Read methods return plain values (or
 * {@code null} on failure) so the controller reads control-flow truth from the return value.
 */
public class CashDrawerViewModel {

    private final CashDrawerApi api;
    private final Consumer<Runnable> ui;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public CashDrawerViewModel(CashDrawerApi api, Consumer<Runnable> ui) {
        this.api = api;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() { return errorMessage.getReadOnlyProperty(); }

    /** Fetches the current drawer activity; on failure surfaces the reason and returns null. */
    public DrawerReconciliation loadActivity() {
        try {
            DrawerReconciliation a = api.reconciliation();
            ui.accept(() -> errorMessage.set(""));
            return a;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return null;
        }
    }

    /** Records a pay-in. Rejects a non-positive amount or blank reason before any server call;
     *  returns the movement on success, or null (with errorMessage set) on validation/API failure. */
    public CashMovementView payIn(BigDecimal amount, String reason) {
        return move(amount, reason, true);
    }

    /** Records a pay-out. Same validation and failure semantics as {@link #payIn}. */
    public CashMovementView payOut(BigDecimal amount, String reason) {
        return move(amount, reason, false);
    }

    private CashMovementView move(BigDecimal amount, String reason, boolean payIn) {
        if (amount == null || amount.signum() <= 0) {
            ui.accept(() -> errorMessage.set("Amount must be greater than zero"));
            return null;
        }
        if (reason == null || reason.isBlank()) {
            ui.accept(() -> errorMessage.set("Enter a reason"));
            return null;
        }
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        String trimmed = reason.trim();
        try {
            CashMovementView m = payIn ? api.payIn(scaled, trimmed) : api.payOut(scaled, trimmed);
            ui.accept(() -> errorMessage.set(""));
            return m;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return null;
        }
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

- [ ] **Step 6: Wire `cashDrawerApi` into `Services`**

In `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`, add the import
`import com.company.pos.terminal.api.CashDrawerApi;`, add the field after `shiftApi`:

```java
    public final ShiftApi shiftApi;
    public final CashDrawerApi cashDrawerApi;
```

and the wiring after `this.shiftApi = new ShiftApi(apiClient);`:

```java
        this.shiftApi = new ShiftApi(apiClient);
        this.cashDrawerApi = new CashDrawerApi(apiClient);
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml test -Dtest=CashDrawerViewModelTest`
Expected: PASS (8 tests).

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/dto/CashMovementView.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/CashMovementRequest.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/CashDrawerApi.java \
        pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/CashDrawerViewModel.java \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/CashDrawerViewModelTest.java
git commit -m "feat(terminal): CashDrawerApi + CashDrawerViewModel (pay-in/pay-out + activity)"
```

---

### Task 2: The two I/O-free modals (blind-safe activity + movement entry)

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/DrawerActivityDialog.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/CashMovementDialog.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/view/DrawerActivityDialogTest.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/view/CashMovementDialogTest.java`

**Interfaces:**
- Consumes: `api/dto/DrawerReconciliation`, `Keypads.numericPad(TextField)`.
- Produces:
  - `DrawerActivityDialog.DrawerAction` (enum `PAY_IN`, `PAY_OUT`); `DrawerActivityDialog.Row` (record `label`, `value`); `static List<Row> activityRows(DrawerReconciliation)`; `static Optional<DrawerAction> promptForAction(DrawerReconciliation, String)`.
  - `CashMovementDialog.CashMovementInput` (record `BigDecimal amount`, `String reason`); `static BigDecimal parseAmount(String)`; `static Optional<CashMovementInput> prompt(DrawerActivityDialog.DrawerAction, String)`.

- [ ] **Step 1: Write the failing helper tests**

Create `pos-terminal/src/test/java/com/company/pos/terminal/view/DrawerActivityDialogTest.java`:

```java
package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.DrawerReconciliation;
import com.company.pos.terminal.view.DrawerActivityDialog.Row;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DrawerActivityDialogTest {

    /** Distinct known values so the blind-safety assertions are meaningful. */
    private DrawerReconciliation activity() {
        return new DrawerReconciliation(UUID.randomUUID(),
                new BigDecimal("500.00"),   // opening float  (shown)
                new BigDecimal("1234.00"),  // cash sales AMOUNT (must NOT be shown)
                37,                          // cash sales count (shown)
                new BigDecimal("100.00"),   // pay-ins (shown)
                new BigDecimal("50.00"),    // pay-outs (shown)
                new BigDecimal("1784.00"),  // expectedCash (must NOT be shown)
                null, null, "SAR");
    }

    @Test
    void showsExactlyTheFourBlindSafeRows() {
        List<Row> rows = DrawerActivityDialog.activityRows(activity());
        assertEquals(4, rows.size());
        assertEquals(List.of("Opening float", "Cash sales", "Pay-ins", "Pay-outs"),
                rows.stream().map(Row::label).toList());
    }

    @Test
    void cashSalesRowShowsCountNotAmount() {
        List<Row> rows = DrawerActivityDialog.activityRows(activity());
        Row cashSales = rows.stream().filter(r -> r.label().equals("Cash sales")).findFirst().get();
        assertTrue(cashSales.value().contains("37"), "cash-sales row shows the count");
        assertFalse(cashSales.value().contains("1234"), "cash-sales AMOUNT must not appear");
    }

    @Test
    void neverExposesExpectedOrCashSalesAmountOrVariance() {
        List<Row> rows = DrawerActivityDialog.activityRows(activity());
        for (Row r : rows) {
            assertFalse(r.value().contains("1234"), "cash-sales amount leaked in " + r.label());
            assertFalse(r.value().contains("1784"), "expected cash leaked in " + r.label());
        }
    }
}
```

Create `pos-terminal/src/test/java/com/company/pos/terminal/view/CashMovementDialogTest.java`:

```java
package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CashMovementDialogTest {

    @Test
    void parsesPositiveDecimal() {
        assertEquals(0, new BigDecimal("25.50").compareTo(CashMovementDialog.parseAmount("25.50")));
    }

    @Test
    void rejectsZeroNegativeBlankAndNonNumeric() {
        assertNull(CashMovementDialog.parseAmount("0"));
        assertNull(CashMovementDialog.parseAmount("-5"));
        assertNull(CashMovementDialog.parseAmount(""));
        assertNull(CashMovementDialog.parseAmount("   "));
        assertNull(CashMovementDialog.parseAmount(null));
        assertNull(CashMovementDialog.parseAmount("abc"));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml test -Dtest='DrawerActivityDialogTest,CashMovementDialogTest'`
Expected: FAIL — compilation error (`DrawerActivityDialog`, `CashMovementDialog` do not exist).

- [ ] **Step 3: Create `DrawerActivityDialog`**

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/DrawerActivityDialog.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.DrawerReconciliation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/**
 * Modal mid-shift drawer peek. Shows only blind-safe activity — opening float, cash-sales COUNT,
 * pay-ins and pay-outs totals — and offers Pay in / Pay out / Done. It deliberately never shows the
 * cash-sales amount, expected cash, or variance, so it cannot un-blind the shift close. Pure view;
 * only {@link #activityRows} is unit-tested.
 */
public final class DrawerActivityDialog {

    /** Which movement the cashier chose; empty result = Done. */
    public enum DrawerAction { PAY_IN, PAY_OUT }

    /** One key/value line of the activity grid. */
    public record Row(String label, String value) {}

    private DrawerActivityDialog() {}

    /** Blind-safe rows: opening float, cash-sales COUNT (not amount), pay-ins, pay-outs. */
    public static List<Row> activityRows(DrawerReconciliation r) {
        return List.of(
                new Row("Opening float", money(r.openingFloat(), r.currencyCode())),
                new Row("Cash sales", String.valueOf(r.cashSalesCount())),
                new Row("Pay-ins", money(r.payIns(), r.currencyCode())),
                new Row("Pay-outs", money(r.payOuts(), r.currencyCode())));
    }

    public static Optional<DrawerAction> promptForAction(DrawerReconciliation activity,
            String terminalId) {
        Dialog<DrawerAction> dialog = new Dialog<>();
        dialog.setTitle("Cash drawer");
        dialog.setHeaderText("Cash drawer · Terminal " + terminalId);
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType payIn = new ButtonType("Pay in", ButtonBar.ButtonData.OTHER);
        ButtonType payOut = new ButtonType("Pay out", ButtonBar.ButtonData.OTHER);
        ButtonType done = new ButtonType("Done", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(payIn, payOut, done);

        GridPane g = new GridPane();
        g.setHgap(24);
        g.setVgap(8);
        int r = 0;
        for (Row row : activityRows(activity)) {
            Label k = new Label(row.label());
            k.getStyleClass().add("field-label");
            Label v = new Label(row.value());
            v.getStyleClass().add("money");
            g.add(k, 0, r);
            g.add(v, 1, r);
            r++;
        }
        dialog.getDialogPane().setContent(new VBox(12, g));

        dialog.setResultConverter(bt -> {
            if (bt == payIn) return DrawerAction.PAY_IN;
            if (bt == payOut) return DrawerAction.PAY_OUT;
            return null;
        });
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    private static String money(BigDecimal v, String currency) {
        BigDecimal amt = v == null ? BigDecimal.ZERO : v;
        return amt.toPlainString() + " " + (currency == null ? "" : currency);
    }
}
```

- [ ] **Step 4: Create `CashMovementDialog`**

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/CashMovementDialog.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.view.DrawerActivityDialog.DrawerAction;
import java.math.BigDecimal;
import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Modal amount + reason entry for a single cash-drawer movement. Pure view; the caller performs the
 * pay-in/pay-out. Submit is disabled until the amount is a positive decimal AND the reason is
 * non-blank. Only {@link #parseAmount} is unit-tested.
 */
public final class CashMovementDialog {

    /** The collected movement input. */
    public record CashMovementInput(BigDecimal amount, String reason) {}

    private CashMovementDialog() {}

    public static Optional<CashMovementInput> prompt(DrawerAction action, String terminalId) {
        boolean payIn = action == DrawerAction.PAY_IN;
        String verb = payIn ? "Pay in" : "Pay out";
        Dialog<CashMovementInput> dialog = new Dialog<>();
        dialog.setTitle(verb);
        dialog.setHeaderText(verb + " · Terminal " + terminalId);
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = new ButtonType(verb, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);

        Label amountLabel = new Label("Amount");
        amountLabel.getStyleClass().add("field-label");
        TextField amountField = new TextField();
        amountField.setPromptText("0.00");
        amountField.getStyleClass().add("money");
        VBox amountBox = new VBox(6, amountLabel, amountField);
        amountBox.getStyleClass().add("field");

        Label reasonLabel = new Label("Reason");
        reasonLabel.getStyleClass().add("field-label");
        TextField reasonField = new TextField();
        reasonField.setPromptText(payIn ? "e.g. change fund top-up" : "e.g. supplier cash");
        VBox reasonBox = new VBox(6, reasonLabel, reasonField);
        reasonBox.getStyleClass().add("field");

        VBox box = new VBox(16, amountBox, reasonBox, Keypads.numericPad(amountField));
        box.setAlignment(Pos.TOP_CENTER);
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(
                parseAmount(amountField.getText()) == null || reasonField.getText().isBlank());
        amountField.textProperty().addListener((o, was, now) -> revalidate.run());
        reasonField.textProperty().addListener((o, was, now) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> {
            if (bt != submit) return null;
            BigDecimal amount = parseAmount(amountField.getText());
            if (amount == null || reasonField.getText().isBlank()) return null;
            return new CashMovementInput(amount, reasonField.getText().trim());
        });
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    /** Positive non-zero decimal → value; blank, non-numeric, zero, or negative → null. */
    static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(raw.trim());
            return v.signum() <= 0 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml test -Dtest='DrawerActivityDialogTest,CashMovementDialogTest'`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/DrawerActivityDialog.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/CashMovementDialog.java \
        pos-terminal/src/test/java/com/company/pos/terminal/view/DrawerActivityDialogTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/view/CashMovementDialogTest.java
git commit -m "feat(terminal): blind-safe DrawerActivityDialog + CashMovementDialog"
```

---

### Task 3: Home-screen wiring (Drawer button end to end)

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/HomeController.java`
- Modify: `pos-terminal/src/main/resources/fxml/home.fxml`
- Modify: `pos-terminal/src/main/resources/css/app.css`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java`

**Interfaces:**
- Consumes: `Services.cashDrawerApi`, `CashDrawerViewModel`, `DrawerActivityDialog` (+ `DrawerAction`), `CashMovementDialog` (+ `CashMovementInput`).
- Produces: a `Drawer` button on Home that fetches activity, shows the peek, and records movements via `FxTasks` + the VM.

- [ ] **Step 1: Add the `.drawer-modal` CSS guard test**

In `pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java`, add after
`definesSliceFifteenStaleClass`:

```java
    @Test
    void definesSliceSixteenDrawerClass() throws Exception {
        assertTrue(css().contains(".drawer-modal"), "missing style class: .drawer-modal");
    }
```

- [ ] **Step 2: Run the CSS test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml test -Dtest=AppCssTest#definesSliceSixteenDrawerClass`
Expected: FAIL — `.drawer-modal` not present.

- [ ] **Step 3: Add the `.drawer-modal` rule to `app.css`**

Append to `pos-terminal/src/main/resources/css/app.css` (mirrors the existing `.shift-modal` /
`.shift-result` modal styling; use the semantic tokens already in the file):

```css
/* Slice 16 — cash-drawer management modals */
.drawer-modal {
    -fx-background-color: -fx-surface;
}
.drawer-modal .header-panel {
    -fx-background-color: -fx-primary;
}
.drawer-modal .header-panel .label {
    -fx-text-fill: white;
}
```

- [ ] **Step 4: Add the Drawer button to `home.fxml`**

In `pos-terminal/src/main/resources/fxml/home.fxml`, add the Drawer button immediately before
`closeShiftButton`:

```xml
      <Button fx:id="drawerButton" text="Drawer" styleClass="btn-secondary"/>
      <Button fx:id="closeShiftButton" text="Close shift" styleClass="btn-secondary"/>
```

- [ ] **Step 5: Wire `HomeController`**

In `pos-terminal/src/main/java/com/company/pos/terminal/view/HomeController.java`:

Add imports:

```java
import com.company.pos.terminal.api.dto.CashMovementView;
import com.company.pos.terminal.api.dto.DrawerReconciliation;
import com.company.pos.terminal.viewmodel.CashDrawerViewModel;
import com.company.pos.terminal.view.CashMovementDialog.CashMovementInput;
import com.company.pos.terminal.view.DrawerActivityDialog.DrawerAction;
```

Add the VM field and the button field:

```java
    private final CloseShiftViewModel closeVm;
    private final CashDrawerViewModel drawerVm;
    private ShiftView openShift;
```
```java
    @FXML private Button closeShiftButton;
    @FXML private Button drawerButton;
```

Initialise the VM in the constructor (after `closeVm`):

```java
        this.closeVm = new CloseShiftViewModel(services.shiftApi, Platform::runLater);
        this.drawerVm = new CashDrawerViewModel(services.cashDrawerApi, Platform::runLater);
```

In `initialize()`, wire and hide the button (alongside the existing `closeShiftButton` setup):

```java
        closeShiftButton.setOnAction(e -> closeShift());
        closeShiftButton.setVisible(false);
        closeShiftButton.setManaged(false);
        drawerButton.setOnAction(e -> openDrawer());
        drawerButton.setVisible(false);
        drawerButton.setManaged(false);
        checkShift();
```

In `showShift(...)` show the drawer button:

```java
    private void showShift(ShiftView shift) {
        this.openShift = shift;
        shiftLabel.setText("Shift open since " + OPENED_AT.format(shift.openedAt()));
        closeShiftButton.setVisible(true);
        closeShiftButton.setManaged(true);
        drawerButton.setVisible(true);
        drawerButton.setManaged(true);
    }
```

In `showNoShift()` hide it:

```java
    private void showNoShift() {
        this.openShift = null;
        shiftLabel.setText("No shift open — cash reports unavailable");
        closeShiftButton.setVisible(false);
        closeShiftButton.setManaged(false);
        drawerButton.setVisible(false);
        drawerButton.setManaged(false);
    }
```

Add the drawer flow methods (after `closeShift()`):

```java
    /** Drawer peek: fetch activity → show blind-safe view → optionally record a movement → loop. */
    private void openDrawer() {
        if (openShift == null) {
            return;
        }
        final DrawerReconciliation[] holder = new DrawerReconciliation[1];
        FxTasks.run(
                () -> holder[0] = drawerVm.loadActivity(),
                () -> {
                    if (holder[0] == null) {
                        shiftLabel.setText(drawerVm.errorMessage().get());
                        return;
                    }
                    DrawerActivityDialog.promptForAction(holder[0], services.config.terminalId())
                            .flatMap(action -> CashMovementDialog
                                    .prompt(action, services.config.terminalId())
                                    .map(input -> Map.entry(action, input)))
                            .ifPresent(e -> submitMovement(e.getKey(), e.getValue()));
                },
                err -> {
                    shiftLabel.setText("Drawer unavailable");
                    LOG.log(System.Logger.Level.ERROR, "Load drawer activity failed", err);
                });
    }

    private void submitMovement(DrawerAction action, CashMovementInput input) {
        final CashMovementView[] holder = new CashMovementView[1];
        FxTasks.run(
                () -> holder[0] = action == DrawerAction.PAY_IN
                        ? drawerVm.payIn(input.amount(), input.reason())
                        : drawerVm.payOut(input.amount(), input.reason()),
                () -> {
                    if (holder[0] != null) {
                        openDrawer();   // re-fetch → show refreshed activity → allow another
                    } else {
                        shiftLabel.setText(drawerVm.errorMessage().get());
                    }
                },
                err -> {
                    shiftLabel.setText("Couldn't record the movement");
                    LOG.log(System.Logger.Level.ERROR, "Cash movement failed", err);
                });
    }
```

Add the `Map` import for `Map.entry`:

```java
import java.util.Map;
```

> Note the FX-threading contract: `openDrawer`'s `onDone` only calls `showAndWait()`-based dialogs (UI) and then `submitMovement`, which starts a **new** `FxTasks` task — no HTTP runs inside any `onDone`. `submitMovement`'s success `onDone` calls `openDrawer()`, which likewise starts a fresh task before any I/O.

- [ ] **Step 6: Run the full terminal test suite**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml clean test`
Expected: PASS (all existing tests + the new `CashDrawerViewModelTest`, `DrawerActivityDialogTest`, `CashMovementDialogTest`, and the extended `AppCssTest`).

- [ ] **Step 7: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/HomeController.java \
        pos-terminal/src/main/resources/fxml/home.fxml \
        pos-terminal/src/main/resources/css/app.css \
        pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java
git commit -m "feat(terminal): Drawer button on Home — pay-in/pay-out + activity peek"
```

---

## Manual E2E (documented, run once by a human)

1. Start the backend: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw spring-boot:run -Dspring-boot.run.profiles=embedded,dev` (log in `manager`/`manager`).
2. Launch the terminal: `./mvnw -f pos-terminal/pom.xml javafx:run`; sign in; open a shift with a float.
3. On Home, tap **Drawer** → confirm the activity view shows Opening float, Cash sales (a **count**), Pay-ins, Pay-outs — and **no** expected/variance/cash-sales-amount figure.
4. Tap **Pay in**, enter an amount + reason, submit → the view re-opens with the Pay-ins total increased.
5. Tap **Pay out**, enter an amount + reason, submit → Pay-outs total increased. Tap **Done**.
6. Close the shift → the reconciliation reflects both movements.

## Self-Review

- **Spec coverage:** pay-in ✔ (Task 1 VM + Task 2 dialog + Task 3 wiring), pay-out ✔ (same), blind-safe peek ✔ (Task 2 `activityRows` + test), cashier-level ✔ (no token anywhere), terminal-only ✔ (no backend files touched), `.drawer-modal` guard ✔ (Task 3). Accepted limitation (wire carries expected) is documented in the spec, not a task.
- **Placeholder scan:** none — every step has complete code and exact commands.
- **Type consistency:** `DrawerAction` and `CashMovementInput` defined in Task 2, consumed in Task 3; `CashDrawerApi`/`CashDrawerViewModel`/`Services.cashDrawerApi` defined in Task 1, consumed in Task 3; VM method signatures (`loadActivity`, `payIn`, `payOut`) match across tasks and tests.
