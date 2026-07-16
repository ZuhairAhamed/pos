# Terminal Slice 10 — Void order + Shift close — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a manager-gated "void order" action to the dine-in order screen and a cashier "close shift" action (blind cash count → variance reveal) to the home screen, both against existing backend endpoints.

**Architecture:** UI-only slice, two independent tracks. Track A: widen `DiningApi` with a bearer-authenticated void call, add `OrderViewModel.voidOrder`, wire a danger button + confirm dialog + the existing one-shot manager-token flow on the order screen. Track B: add terminal `ShiftSummary`/`DrawerReconciliation` DTOs + `ShiftApi.closeShift`, a `CloseShiftViewModel`, a blind-count dialog and a reconciliation result dialog on the home screen, extracting the shared denomination counter so it isn't duplicated.

**Tech Stack:** Java 21, JavaFX (FXML + controllers + ViewModels + JavaFX `Dialog`s), JUnit 5, Maven (`pos-terminal/pom.xml` — a separate build from the root reactor).

## Global Constraints

- JDK 21 required: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` before any mvn.
- Build/test the terminal only via its own POM: `./mvnw -f pos-terminal/pom.xml ... test`. Root `./mvnw verify` does NOT touch `pos-terminal/`.
- Money is `BigDecimal` at scale 2. Never `double`.
- **FX-threading convention (the recurring bug class):** ViewModel methods are synchronous on the calling thread and return plain values; the controller runs them off the FX thread via `FxTasks.run(work, onDone, onError)`, reading results in `onDone` through a `holder` array. The only observable a VM writes off-thread is `errorMessage` (and, for `CloseShiftViewModel`, the `summary` property), only inside `ui.accept(...)`. `onError` callbacks MUST NOT `setText` a bound label — log only. Each new VM gets an async-dispatcher regression test (a deferred, undrained `ui` dispatcher).
- **Void is manager-gated** — reuse the one-shot manager token: `ManagerPinDialog.promptForApproval` → `AuthApi.pinLoginForToken(code, pin)` → `ManagerAuth.token()` attached to exactly one call via the `ApiClient` bearer override; a 401 on that call never clears the cashier session.
- **Shift close is NOT gated** (any authenticated user; backend sets `closedBy` from the principal).
- New DTOs are annotated `@JsonIgnoreProperties(ignoreUnknown = true)` and mirror the server records field-for-field.
- No backend changes. No new Maven dependencies.

---

## File Structure

| File | Track | Responsibility | Task |
|------|-------|----------------|------|
| `api/DiningApi.java` | A | add `voidOrder(orderId, reason, bearerToken)` | 1 |
| `api/DiningApiTest.java` | A | assert void POST + reason encoding + bearer | 1 |
| `viewmodel/OrderViewModel.java` | A | add `voidOrder(...) → boolean` + `setError(String)` | 2 |
| `viewmodel/OrderViewModelTest.java` | A | void success/fail + async regression | 2 |
| `view/VoidConfirmDialog.java` | A | new optional-reason confirm dialog | 3 |
| `view/OrderController.java` | A | Void button + confirm→PIN→void→navigate | 3 |
| `resources/fxml/order.fxml` | A | `voidButton` | 3 |
| `resources/css/app.css` | A,B | `.btn-danger` (T3); variance/result (T6) | 3,6 |
| `AppCssTest.java` | A,B | slice-10 CSS assertions | 3,6 |
| `api/dto/DrawerReconciliation.java`, `ShiftSummary.java`, `CloseShiftRequest.java` | B | new DTOs | 4 |
| `api/ShiftApi.java` | B | add `closeShift(shiftId, countedCash)` | 4 |
| `api/ShiftApiTest.java` | B | assert close POST + parse summary | 4 |
| `viewmodel/CloseShiftViewModel.java` | B | new VM | 5 |
| `viewmodel/CloseShiftViewModelTest.java` | B | success/fail + async regression | 5 |
| `view/DenominationCounter.java` | B | extracted shared counter | 6 |
| `view/CloseShiftDialog.java`, `ShiftResultDialog.java` | B | new dialogs | 6 |
| `view/CloseShiftDialogParseTest.java`, `ShiftResultDialogTest.java` | B | headless parse/variance tests | 6 |
| `view/StartShiftDialog.java` | B | refactor to use `DenominationCounter` | 6 |
| `view/HomeController.java` | B | retain shift, Close button, close flow | 6 |
| `resources/fxml/home.fxml` | B | `closeShiftButton` | 6 |
| `README.md` | A,B | slice-10 manual E2E | 6 |

Dependency order within each track: **1 → 2 → 3** (Track A) and **4 → 5 → 6** (Track B). The tracks are independent; do A then B (or interleave), but keep each task's `clean test` green.

---

## Task 1: `DiningApi.voidOrder` (bearer-authenticated)

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java` (add a method near `fire`, `:70`)
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/DiningApiTest.java`

**Interfaces:**
- Produces: `void DiningApi.voidOrder(UUID orderId, String reason, String bearerToken)` — `POST /dining/orders/{orderId}/void?reason=<url-encoded>` with the `Authorization: Bearer <bearerToken>` override. Uses the 204-No-Content POST form (`TypeReference<Void>`), like `SalesApi.reprint`.

- [ ] **Step 1: Write the failing test**

Add to `DiningApiTest.java` (before the class closing brace). `URLEncoder` encodes a space as `+`; `stub.lastAuth` captures the `Authorization` header (see `AuthApiTest:40`):

```java
    @Test
    void voidOrderPostsToVoidEndpointWithReasonAndBearer() throws Exception {
        try (StubServer stub = new StubServer(204, null, null)) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            api.voidOrder(ORDER_ID, "walk out", "mgr-token-123");
            assertEquals("POST", stub.lastMethod);
            assertEquals("/dining/orders/33333333-3333-3333-3333-333333333333/void", stub.lastPath);
            assertTrue(stub.lastQuery.contains("reason=walk+out"), stub.lastQuery);
            assertEquals("Bearer mgr-token-123", stub.lastAuth);
        }
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml -Dtest=DiningApiTest test`
Expected: compilation failure — `voidOrder(UUID,String,String)` does not exist.

- [ ] **Step 3: Implement the method**

In `DiningApi.java`, add after the `fire` method (`:72`). `URLEncoder`/`StandardCharsets` are already imported (added in slice 9):

```java
    /**
     * Voids the whole order (MANAGER-gated on the server). {@code reason} is optional (may be
     * blank) and URL-encoded. Authenticated with a one-shot manager {@code bearerToken}; a 401
     * on this overridden call never clears the cashier session. 204 No Content.
     */
    public void voidOrder(UUID orderId, String reason, String bearerToken) {
        String path = "/dining/orders/" + orderId + "/void?reason="
                + URLEncoder.encode(reason == null ? "" : reason, StandardCharsets.UTF_8);
        client.post(path, null, new TypeReference<Void>() {}, bearerToken);
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=DiningApiTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/DiningApiTest.java
git commit -m "feat(terminal): DiningApi.voidOrder — bearer-authenticated whole-order void

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `OrderViewModel.voidOrder` + `setError`

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/OrderViewModel.java` (add methods after `fire`, `:116`)
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/OrderViewModelTest.java`

**Interfaces:**
- Consumes: `DiningApi.voidOrder(UUID, String, String)` from Task 1.
- Produces: `boolean OrderViewModel.voidOrder(String reason, String bearerToken)` (true on success; false + `errorMessage` on `ApiException`); `void OrderViewModel.setError(String message)` (lets the controller surface a manager-approval failure on the bound error label).

- [ ] **Step 1: Write the failing tests**

Add to `OrderViewModelTest.java` (before the class closing brace):

```java
    @Test
    void voidOrderReturnsTrueOnSuccess() {
        OrderLineView line =
                new OrderLineView(firedLineId, "BURGER", new BigDecimal("1"), null, "MAIN", null, List.of());
        boolean[] called = {false};
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(line));
                    }

                    @Override
                    public void voidOrder(UUID orderId, String reason, String bearerToken) {
                        called[0] = true;
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        assertTrue(vm.voidOrder("walkout", "tok"));
        assertTrue(called[0]);
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void voidOrderSurfacesErrorAndReturnsFalse() {
        OrderLineView line =
                new OrderLineView(firedLineId, "BURGER", new BigDecimal("1"), null, "MAIN", null, List.of());
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(line));
                    }

                    @Override
                    public void voidOrder(UUID orderId, String reason, String bearerToken) {
                        throw new ApiException(
                                403, new ProblemDetail("Forbidden", 403, "Manager role required"), "HTTP 403");
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        assertFalse(vm.voidOrder("x", "tok"));
        assertEquals("Manager role required", vm.errorMessage().get());
    }

    @Test
    void deferredDispatcherHoldsVoidErrorUntilDrained() {
        OrderLineView line =
                new OrderLineView(firedLineId, "BURGER", new BigDecimal("1"), null, "MAIN", null, List.of());
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(line));
                    }

                    @Override
                    public void voidOrder(UUID orderId, String reason, String bearerToken) {
                        throw new ApiException(
                                403, new ProblemDetail("Forbidden", 403, "Manager role required"), "HTTP 403");
                    }
                };
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        OrderViewModel vm = new OrderViewModel(dining, cache(), queue::add);
        vm.load(orderId);
        while (!queue.isEmpty()) queue.poll().run();       // drain load → baseline
        boolean result = vm.voidOrder("x", "tok");
        assertFalse(result);                               // synchronous return
        assertEquals("", vm.errorMessage().get());         // deferred: error not applied yet
        while (!queue.isEmpty()) queue.poll().run();       // drain the error write
        assertEquals("Manager role required", vm.errorMessage().get());
    }

    @Test
    void setErrorSurfacesMessage() {
        OrderViewModel vm = new OrderViewModel(new DiningApi(null), cache());
        vm.setError("Manager approval failed");
        assertEquals("Manager approval failed", vm.errorMessage().get());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=OrderViewModelTest test`
Expected: compilation failure — `voidOrder` / `setError` do not exist.

- [ ] **Step 3: Implement the methods**

In `OrderViewModel.java`, add after the `fire()` method (after `:116`):

```java
    /**
     * Voids the whole order using a one-shot manager token. Returns true on success; on
     * ApiException surfaces the message via errorMessage and returns false. No line refresh —
     * the caller navigates away on success.
     */
    public boolean voidOrder(String reason, String bearerToken) {
        try {
            dining.voidOrder(order.id(), reason, bearerToken);
            ui.accept(() -> errorMessage.set(""));
            return true;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return false;
        }
    }

    /** Lets the controller surface a manager-approval failure on the bound error label. */
    public void setError(String message) {
        ui.accept(() -> errorMessage.set(message == null ? "" : message));
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=OrderViewModelTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/OrderViewModel.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/OrderViewModelTest.java
git commit -m "feat(terminal): OrderViewModel.voidOrder + setError

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Void confirm dialog + order-screen button

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/VoidConfirmDialog.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/OrderController.java`
- Modify: `pos-terminal/src/main/resources/fxml/order.fxml`
- Modify: `pos-terminal/src/main/resources/css/app.css`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java`

**Interfaces:**
- Consumes: `OrderViewModel.voidOrder`/`setError` (Task 2); `ManagerPinDialog.promptForApproval(String) → Optional<Credentials(cashierCode, pin)>`; `AuthApi.pinLoginForToken(code, pin) → ManagerAuth` with `.isManager()`/`.token()`.
- Produces: `Optional<String> VoidConfirmDialog.promptForReason()` (reason possibly empty when confirmed; empty Optional when cancelled). `order.fxml` declares `fx:id="voidButton"`. CSS defines `.btn-danger`.

- [ ] **Step 1: Write the failing test**

Add to `AppCssTest.java` (before the class closing brace):

```java
    @Test
    void definesSliceTenVoidClass() throws Exception {
        assertTrue(css().contains(".btn-danger"), "missing style class: .btn-danger");
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=AppCssTest test`
Expected: FAIL — `.btn-danger` missing.

- [ ] **Step 3: Add the CSS**

Append to `pos-terminal/src/main/resources/css/app.css` (the `-fx-danger` token already exists at `:43`):

```css
/* Slice 10 — void (danger button) */
.btn-danger {
    -fx-background-color: -fx-danger;
    -fx-text-fill: white;
    -fx-font-size: 15px;
    -fx-font-weight: bold;
    -fx-padding: 12 20 12 20;
    -fx-background-radius: 8;
    -fx-cursor: hand;
}
.btn-danger:disabled { -fx-opacity: 0.55; }
```

- [ ] **Step 4: Run the CSS test to verify it passes**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=AppCssTest test`
Expected: PASS.

- [ ] **Step 5: Create the VoidConfirmDialog**

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/VoidConfirmDialog.java`:

```java
package com.company.pos.terminal.view;

import java.util.Optional;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

/**
 * Modal confirmation for voiding the whole order. Collects an OPTIONAL free-text reason (may be
 * blank). Pure view; the caller obtains manager approval and performs the void. Display-dependent
 * — exercised by the manual E2E.
 *
 * @return the reason string (possibly empty) when confirmed; empty Optional when cancelled.
 */
public final class VoidConfirmDialog {

    private VoidConfirmDialog() {}

    public static Optional<String> promptForReason() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Void order");
        dialog.setHeaderText("Void this entire order?");
        ButtonType voidIt = new ButtonType("Void order", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(voidIt, cancel);
        dialog.getDialogPane().getStyleClass().add("void-dialog");

        Label hint = new Label("This cannot be undone. A manager PIN is required next.");
        hint.setWrapText(true);
        Label reasonLabel = new Label("Reason (optional)");
        reasonLabel.getStyleClass().add("field-label");
        TextArea reasonField = new TextArea();
        reasonField.setPromptText("e.g. walkout, wrong table");
        reasonField.setPrefRowCount(2);
        reasonField.setWrapText(true);
        VBox box = new VBox(12, hint, reasonLabel, reasonField);
        dialog.getDialogPane().setContent(box);

        dialog.setResultConverter(bt -> bt == voidIt
                ? (reasonField.getText() == null ? "" : reasonField.getText().trim())
                : null);
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }
}
```

- [ ] **Step 6: Add `voidButton` to `order.fxml`**

In `pos-terminal/src/main/resources/fxml/order.fxml`, find the fire-button HBox:

```xml
      <HBox spacing="12">
        <Button fx:id="fireButton" text="Fire to kitchen" styleClass="btn-primary" HBox.hgrow="ALWAYS" maxWidth="Infinity"/>
      </HBox>
```

and replace it with (adds the void button after fire):

```xml
      <HBox spacing="12">
        <Button fx:id="fireButton" text="Fire to kitchen" styleClass="btn-primary" HBox.hgrow="ALWAYS" maxWidth="Infinity"/>
        <Button fx:id="voidButton" text="Void order" styleClass="btn-danger"/>
      </HBox>
```

- [ ] **Step 7: Wire the void flow in `OrderController`**

In `OrderController.java`:

(a) Add imports (with the other `com.company.pos.terminal.api` / `dto` imports):

```java
import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.dto.ManagerAuth;
```

(b) Add the field (with the other `@FXML` buttons):

```java
    @FXML private Button voidButton;
```

(c) In `initialize()`, alongside the other button handlers and the initial-disable block, add:

```java
        voidButton.setOnAction(e -> voidOrder());
        voidButton.setDisable(true);
```

(d) In `afterCatalogLoaded()`, where `payButton`/`fireButton` are enabled (`payButton.setDisable(false); fireButton.setDisable(false);`), add:

```java
        voidButton.setDisable(false);
```

(e) Add the flow method (near `fire()`):

```java
    /** Void the whole order: confirm (+optional reason) → manager PIN → one-shot void → tables. */
    private void voidOrder() {
        Optional<String> reason = VoidConfirmDialog.promptForReason();
        if (reason.isEmpty()) {
            return;
        }
        Optional<ManagerPinDialog.Credentials> creds =
                ManagerPinDialog.promptForApproval("Manager approval required to void this order");
        if (creds.isEmpty()) {
            return;
        }
        String r = reason.get();
        ManagerPinDialog.Credentials c = creds.get();
        boolean[] holder = {false};
        FxTasks.run(
                () -> {
                    ManagerAuth auth = services.authApi.pinLoginForToken(c.cashierCode(), c.pin());
                    if (!auth.isManager()) {
                        throw new ApiException(403, null, "This account is not a manager");
                    }
                    holder[0] = vm.voidOrder(r, auth.token());
                },
                () -> {
                    if (holder[0]) {
                        navigator.toTableMap();
                    }
                },
                err -> {
                    String msg = err.getMessage();
                    vm.setError(msg == null || msg.isBlank() ? "Manager approval failed" : msg);
                    LOG.log(System.Logger.Level.ERROR, "Void approval failed", err);
                });
    }
```

- [ ] **Step 8: Run the full terminal suite**

Run: `./mvnw -f pos-terminal/pom.xml clean test`
Expected: BUILD SUCCESS, all tests pass (Track A adds 4 VM tests + 1 API test + 1 CSS test to the 187 baseline).

- [ ] **Step 9: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/VoidConfirmDialog.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/OrderController.java \
        pos-terminal/src/main/resources/fxml/order.fxml \
        pos-terminal/src/main/resources/css/app.css \
        pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java
git commit -m "feat(terminal): void order — danger button, confirm dialog, manager-PIN gate

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Shift DTOs + `ShiftApi.closeShift`

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/DrawerReconciliation.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ShiftSummary.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/CloseShiftRequest.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/ShiftApi.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/ShiftApiTest.java`

**Interfaces:**
- Produces: `ShiftSummary ShiftApi.closeShift(UUID shiftId, BigDecimal countedCash)` — `POST /shifts/{shiftId}/close {countedCash}`. `ShiftSummary(shiftId, terminalId, openedBy, closedBy, status, openedAt, closedAt, DrawerReconciliation cash)`; `DrawerReconciliation(sessionId, openingFloat, cashSales, cashSalesCount, payIns, payOuts, expectedCash, countedCash, variance, currencyCode)`.

- [ ] **Step 1: Write the failing test**

Add to `ShiftApiTest.java` (add `import com.company.pos.terminal.api.dto.ShiftSummary;` at the top, then this test before the class closing brace):

```java
    @Test
    void closeShiftPostsCountedCashAndParsesSummary() throws Exception {
        String json = "{\"shiftId\":\"88888888-8888-8888-8888-888888888888\",\"terminalId\":\"T01\","
            + "\"openedBy\":\"manager\",\"closedBy\":\"manager\",\"status\":\"CLOSED\","
            + "\"openedAt\":\"2026-07-12T06:02:00Z\",\"closedAt\":\"2026-07-12T14:00:00Z\","
            + "\"cash\":{\"sessionId\":\"99999999-9999-9999-9999-999999999999\",\"openingFloat\":500.00,"
            + "\"cashSales\":1200.00,\"cashSalesCount\":37,\"payIns\":0.00,\"payOuts\":50.00,"
            + "\"expectedCash\":1650.00,\"countedCash\":1640.00,\"variance\":-10.00,\"currencyCode\":\"SAR\"}}";
        try (StubServer stub = new StubServer(200, json, "application/json")) {
            ShiftApi api = new ShiftApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            ShiftSummary s = api.closeShift(
                java.util.UUID.fromString("88888888-8888-8888-8888-888888888888"),
                new BigDecimal("1640.00"));
            assertEquals("CLOSED", s.status());
            assertEquals("manager", s.closedBy());
            assertNotNull(s.cash());
            assertEquals(0, new BigDecimal("1650.00").compareTo(s.cash().expectedCash()));
            assertEquals(0, new BigDecimal("-10.00").compareTo(s.cash().variance()));
            assertEquals(37, s.cash().cashSalesCount());
            assertEquals("POST", stub.lastMethod);
            assertEquals("/shifts/88888888-8888-8888-8888-888888888888/close", stub.lastPath);
            assertTrue(stub.lastBody.contains("countedCash"));
            assertTrue(stub.lastBody.contains("1640.00"));
        }
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=ShiftApiTest test`
Expected: compilation failure — `ShiftSummary` / `closeShift` do not exist.

- [ ] **Step 3: Create the DTOs**

`DrawerReconciliation.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

/** Cash-drawer reconciliation returned inside {@link ShiftSummary} at close. Mirrors the
 *  server {@code cashdrawer.api.DrawerReconciliation}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DrawerReconciliation(UUID sessionId, BigDecimal openingFloat, BigDecimal cashSales,
        int cashSalesCount, BigDecimal payIns, BigDecimal payOuts, BigDecimal expectedCash,
        BigDecimal countedCash, BigDecimal variance, String currencyCode) {
}
```

`ShiftSummary.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

/** Result of {@code POST /shifts/{id}/close}. Mirrors the server {@code shift.api.ShiftSummary}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShiftSummary(UUID shiftId, String terminalId, String openedBy, String closedBy,
        String status, Instant openedAt, Instant closedAt, DrawerReconciliation cash) {
}
```

`CloseShiftRequest.java`:

```java
package com.company.pos.terminal.api.dto;

import java.math.BigDecimal;

/** Body for {@code POST /shifts/{id}/close}. */
public record CloseShiftRequest(BigDecimal countedCash) {
}
```

- [ ] **Step 4: Add `closeShift` to `ShiftApi`**

In `ShiftApi.java`, add imports `import com.company.pos.terminal.api.dto.ShiftSummary;`, `import com.company.pos.terminal.api.dto.CloseShiftRequest;`, `import java.util.UUID;`, then add the method:

```java
    /** POST /shifts/{shiftId}/close — closes the shift and returns the drawer reconciliation. */
    public ShiftSummary closeShift(UUID shiftId, BigDecimal countedCash) {
        return client.post("/shifts/" + shiftId + "/close", new CloseShiftRequest(countedCash),
                new TypeReference<ShiftSummary>() {});
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=ShiftApiTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/dto/DrawerReconciliation.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ShiftSummary.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/CloseShiftRequest.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/ShiftApi.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/ShiftApiTest.java
git commit -m "feat(terminal): ShiftApi.closeShift + ShiftSummary/DrawerReconciliation DTOs

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: `CloseShiftViewModel`

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/CloseShiftViewModel.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/CloseShiftViewModelTest.java`

**Interfaces:**
- Consumes: `ShiftApi.closeShift(UUID, BigDecimal)` (Task 4).
- Produces: `boolean CloseShiftViewModel.closeShift(UUID shiftId, BigDecimal countedCash)` (true + stored `summary` on success; false + `errorMessage` on `ApiException`); read-only `summary()` (`ShiftSummary`) and `errorMessage()` properties.

- [ ] **Step 1: Write the failing test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/CloseShiftViewModelTest.java`:

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.ShiftApi;
import com.company.pos.terminal.api.dto.DrawerReconciliation;
import com.company.pos.terminal.api.dto.ShiftSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CloseShiftViewModelTest {

    private final UUID shiftId = UUID.randomUUID();

    private ShiftSummary sampleSummary() {
        DrawerReconciliation cash = new DrawerReconciliation(UUID.randomUUID(),
                new BigDecimal("500.00"), new BigDecimal("1200.00"), 37, BigDecimal.ZERO,
                new BigDecimal("50.00"), new BigDecimal("1650.00"), new BigDecimal("1640.00"),
                new BigDecimal("-10.00"), "SAR");
        return new ShiftSummary(shiftId, "T01", "manager", "manager", "CLOSED",
                Instant.now(), Instant.now(), cash);
    }

    @Test
    void closeShiftStoresSummaryAndReturnsTrue() {
        ShiftApi api = new ShiftApi(null) {
            @Override
            public ShiftSummary closeShift(UUID id, BigDecimal countedCash) {
                return sampleSummary();
            }
        };
        CloseShiftViewModel vm = new CloseShiftViewModel(api, Runnable::run);
        assertTrue(vm.closeShift(shiftId, new BigDecimal("1640.00")));
        assertNotNull(vm.summary().get());
        assertEquals(0, new BigDecimal("-10.00").compareTo(vm.summary().get().cash().variance()));
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void closeShiftSurfacesErrorAndReturnsFalse() {
        ShiftApi api = new ShiftApi(null) {
            @Override
            public ShiftSummary closeShift(UUID id, BigDecimal countedCash) {
                throw new ApiException(409, new ProblemDetail("Conflict", 409, "Shift is not open"),
                        "HTTP 409");
            }
        };
        CloseShiftViewModel vm = new CloseShiftViewModel(api, Runnable::run);
        assertFalse(vm.closeShift(shiftId, new BigDecimal("100.00")));
        assertNull(vm.summary().get());
        assertEquals("Shift is not open", vm.errorMessage().get());
    }

    @Test
    void deferredDispatcherHoldsSummaryUntilDrained() {
        ShiftApi api = new ShiftApi(null) {
            @Override
            public ShiftSummary closeShift(UUID id, BigDecimal countedCash) {
                return sampleSummary();
            }
        };
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        CloseShiftViewModel vm = new CloseShiftViewModel(api, queue::add);
        boolean result = vm.closeShift(shiftId, new BigDecimal("1640.00"));
        assertTrue(result);                    // synchronous return
        assertNull(vm.summary().get());        // deferred: summary not applied yet
        while (!queue.isEmpty()) queue.poll().run();
        assertNotNull(vm.summary().get());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=CloseShiftViewModelTest test`
Expected: compilation failure — `CloseShiftViewModel` does not exist.

- [ ] **Step 3: Create the ViewModel**

Create `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/CloseShiftViewModel.java`:

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.ShiftApi;
import com.company.pos.terminal.api.dto.ShiftSummary;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for closing the terminal's shift. Synchronous like the other VMs (the controller runs
 * it off the FX thread via FxTasks); the only observables written off-thread are {@code summary}
 * and {@code errorMessage}, inside the {@code ui} dispatcher. On success the resulting
 * {@link ShiftSummary} is stored for the reconciliation result screen.
 */
public class CloseShiftViewModel {

    private final ShiftApi api;
    private final Consumer<Runnable> ui;
    private final ReadOnlyObjectWrapper<ShiftSummary> summary = new ReadOnlyObjectWrapper<>(null);
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public CloseShiftViewModel(ShiftApi api, Consumer<Runnable> ui) {
        this.api = api;
        this.ui = ui;
    }

    public ReadOnlyObjectProperty<ShiftSummary> summary() { return summary.getReadOnlyProperty(); }
    public ReadOnlyStringProperty errorMessage() { return errorMessage.getReadOnlyProperty(); }

    /**
     * Closes the shift with the counted cash. Returns true on success (and stores the summary);
     * on ApiException surfaces the message via errorMessage and returns false.
     */
    public boolean closeShift(UUID shiftId, BigDecimal countedCash) {
        try {
            ShiftSummary closed = api.closeShift(shiftId, countedCash);
            ui.accept(() -> {
                summary.set(closed);
                errorMessage.set("");
            });
            return true;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return false;
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

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=CloseShiftViewModelTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/CloseShiftViewModel.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/CloseShiftViewModelTest.java
git commit -m "feat(terminal): CloseShiftViewModel

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Close-shift dialogs + home-screen wiring (with shared denomination counter)

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/DenominationCounter.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/CloseShiftDialog.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/ShiftResultDialog.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/StartShiftDialog.java` (use the shared counter)
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/HomeController.java`
- Modify: `pos-terminal/src/main/resources/fxml/home.fxml`
- Modify: `pos-terminal/src/main/resources/css/app.css`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/view/CloseShiftDialogParseTest.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/view/ShiftResultDialogTest.java`
- Modify: `pos-terminal/README.md`

**Interfaces:**
- Consumes: `CloseShiftViewModel` (Task 5); `StartShiftViewModel.DENOMINATIONS`/`total` (static).
- Produces: `DenominationCounter.pane(TextField) → TitledPane`; `Optional<BigDecimal> CloseShiftDialog.promptForCount(String terminalId)` + static `parse`; `ShiftResultDialog.show(DrawerReconciliation)` + static `varianceText`/`varianceStyle`; `home.fxml` `fx:id="closeShiftButton"`; CSS `.variance-over`/`.variance-short`/`.variance-balanced`/`.shift-result`.

- [ ] **Step 1: Write the failing headless tests**

Add to `AppCssTest.java`:

```java
    @Test
    void definesSliceTenShiftClasses() throws Exception {
        String css = css();
        for (String cls : new String[] {
            ".variance-over", ".variance-short", ".variance-balanced", ".shift-result"
        }) {
            assertTrue(css.contains(cls), "missing style class: " + cls);
        }
    }
```

Create `pos-terminal/src/test/java/com/company/pos/terminal/view/CloseShiftDialogParseTest.java`:

```java
package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CloseShiftDialogParseTest {

    @Test
    void blankParsesToZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(CloseShiftDialog.parse("")));
        assertEquals(0, BigDecimal.ZERO.compareTo(CloseShiftDialog.parse(null)));
    }

    @Test
    void validAmountParses() {
        assertEquals(0, new BigDecimal("1640.00").compareTo(CloseShiftDialog.parse(" 1640.00 ")));
    }

    @Test
    void negativeIsRejected() {
        assertNull(CloseShiftDialog.parse("-5"));
    }

    @Test
    void nonNumericIsRejected() {
        assertNull(CloseShiftDialog.parse("abc"));
    }
}
```

Create `pos-terminal/src/test/java/com/company/pos/terminal/view/ShiftResultDialogTest.java`:

```java
package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ShiftResultDialogTest {

    @Test
    void balancedWhenZero() {
        assertEquals("Balanced", ShiftResultDialog.varianceText(new BigDecimal("0.00"), "SAR"));
        assertEquals("variance-balanced", ShiftResultDialog.varianceStyle(new BigDecimal("0.00")));
    }

    @Test
    void overWhenPositive() {
        assertEquals("Over 10.00 SAR", ShiftResultDialog.varianceText(new BigDecimal("10.00"), "SAR"));
        assertEquals("variance-over", ShiftResultDialog.varianceStyle(new BigDecimal("10.00")));
    }

    @Test
    void shortWhenNegative() {
        assertEquals("Short 10.00 SAR", ShiftResultDialog.varianceText(new BigDecimal("-10.00"), "SAR"));
        assertEquals("variance-short", ShiftResultDialog.varianceStyle(new BigDecimal("-10.00")));
    }

    @Test
    void nullVarianceIsBalanced() {
        assertEquals("Balanced", ShiftResultDialog.varianceText(null, "SAR"));
        assertEquals("variance-balanced", ShiftResultDialog.varianceStyle(null));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=AppCssTest,CloseShiftDialogParseTest,ShiftResultDialogTest test`
Expected: FAIL/compilation error — CSS classes and the two dialog classes do not exist.

- [ ] **Step 3: Create `DenominationCounter` (shared counter)**

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/DenominationCounter.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.viewmodel.StartShiftViewModel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Shared optional count-by-denomination helper: a stepper row per SAR note whose running total
 * overwrites the given money field. Used by both the start-shift and close-shift dialogs so the
 * counting UI/behaviour lives in one place. Pure view; display-dependent (manual E2E).
 */
public final class DenominationCounter {

    private DenominationCounter() {}

    /** A collapsed TitledPane of denomination steppers; the running total writes {@code target}. */
    public static TitledPane pane(TextField target) {
        Map<BigDecimal, Integer> counts = new HashMap<>();
        VBox rows = new VBox(8);
        for (BigDecimal denom : StartShiftViewModel.DENOMINATIONS) {
            counts.put(denom, 0);
            rows.getChildren().add(row(denom, counts, target));
        }
        TitledPane pane = new TitledPane("Count by denomination (optional)", rows);
        pane.setExpanded(false);
        return pane;
    }

    private static HBox row(BigDecimal denom, Map<BigDecimal, Integer> counts, TextField target) {
        Label name = new Label(denom.toPlainString());
        name.getStyleClass().addAll("field-label", "money");
        name.setMinWidth(48);
        Label count = new Label("0");
        count.getStyleClass().add("money");
        count.setMinWidth(40);
        count.setAlignment(Pos.CENTER);
        Label lineTotal = new Label("0.00");
        lineTotal.getStyleClass().addAll("denom-line-total", "money");
        Button minus = new Button("−");
        Button plus = new Button("+");
        minus.getStyleClass().add("qty-stepper");
        plus.getStyleClass().add("qty-stepper");
        Runnable refresh = () -> {
            int n = counts.get(denom);
            count.setText(Integer.toString(n));
            lineTotal.setText(denom.multiply(BigDecimal.valueOf(n))
                    .setScale(2, RoundingMode.HALF_UP).toPlainString());
            target.setText(StartShiftViewModel.total(counts).toPlainString());
        };
        minus.setOnAction(e -> {
            counts.computeIfPresent(denom, (d, n) -> Math.max(0, n - 1));
            refresh.run();
        });
        plus.setOnAction(e -> {
            counts.merge(denom, 1, Integer::sum);
            refresh.run();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(12, name, minus, count, plus, spacer, lineTotal);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("denom-row");
        return row;
    }
}
```

- [ ] **Step 4: Refactor `StartShiftDialog` to use the shared counter**

In `StartShiftDialog.java`: replace the `denominationPane(floatField)` call in `promptForFloat` with `DenominationCounter.pane(floatField)`, and DELETE the now-unused private `denominationPane` and `denomRow` methods (the whole block from `/** Optional counting helper:` to the end of `denomRow`). Remove now-unused imports (`Button`, `HBox`, `Priority`, `Region`, `TitledPane`, `RoundingMode`, `HashMap`, `Map` — keep any still referenced by the rest of the file; the compiler flags leftovers). The line becomes:

```java
        VBox box = new VBox(16, signedIn, floatBox, Keypads.numericPad(floatField),
                DenominationCounter.pane(floatField));
```

- [ ] **Step 5: Create `CloseShiftDialog`**

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/CloseShiftDialog.java`:

```java
package com.company.pos.terminal.view;

import java.math.BigDecimal;
import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Modal blind cash count for closing the shift. Collects the counted cash via keyboard or the
 * shared count-by-denomination helper. The expected amount is deliberately NOT shown (blind
 * count). Pure view; the caller performs the close and shows the reconciliation result.
 * Display-dependent — exercised by the manual E2E; only {@link #parse} is unit-tested.
 */
public final class CloseShiftDialog {

    private CloseShiftDialog() {}

    public static Optional<BigDecimal> promptForCount(String terminalId) {
        Dialog<BigDecimal> dialog = new Dialog<>();
        dialog.setTitle("Close shift");
        dialog.setHeaderText("Close shift · Terminal " + terminalId);
        ButtonType close = new ButtonType("Count & close", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(close, cancel);
        dialog.getDialogPane().getStyleClass().add("shift-modal");

        Label hint = new Label("Count the drawer and enter the total. The expected amount is hidden.");
        hint.setWrapText(true);
        Label countLabel = new Label("Counted cash");
        countLabel.getStyleClass().add("field-label");
        TextField countField = new TextField();
        countField.setPromptText("0.00");
        countField.getStyleClass().add("money");
        VBox countBox = new VBox(6, countLabel, countField);
        countBox.getStyleClass().add("field");

        VBox box = new VBox(16, hint, countBox, Keypads.numericPad(countField),
                DenominationCounter.pane(countField));
        box.setAlignment(Pos.TOP_CENTER);
        dialog.getDialogPane().setContent(box);

        javafx.scene.Node closeNode = dialog.getDialogPane().lookupButton(close);
        Runnable revalidate = () -> closeNode.setDisable(parse(countField.getText()) == null);
        countField.textProperty().addListener((o, was, now) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> bt == close ? parse(countField.getText()) : null);
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    /** Non-negative decimal, blank counting as zero; invalid → null. */
    static BigDecimal parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal v = new BigDecimal(raw.trim());
            return v.signum() < 0 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
```

- [ ] **Step 6: Create `ShiftResultDialog`**

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/ShiftResultDialog.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.DrawerReconciliation;
import java.math.BigDecimal;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/**
 * Modal reconciliation result shown after a shift closes: opening float, cash sales, pay-ins/outs,
 * expected, counted, and the variance labelled Over / Short / Balanced (word + colour, never colour
 * alone). Pure view. Display-dependent — exercised by the manual E2E; only {@link #varianceText}
 * and {@link #varianceStyle} are unit-tested.
 */
public final class ShiftResultDialog {

    private ShiftResultDialog() {}

    public static void show(DrawerReconciliation cash) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Shift closed");
        dialog.setHeaderText("Shift closed · drawer reconciliation");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.OK);
        dialog.getDialogPane().getStyleClass().add("shift-result");

        GridPane g = new GridPane();
        g.setHgap(24);
        g.setVgap(8);
        int r = 0;
        r = addRow(g, r, "Opening float", money(cash.openingFloat(), cash.currencyCode()));
        r = addRow(g, r, "Cash sales (" + cash.cashSalesCount() + ")",
                money(cash.cashSales(), cash.currencyCode()));
        r = addRow(g, r, "Pay-ins", money(cash.payIns(), cash.currencyCode()));
        r = addRow(g, r, "Pay-outs", money(cash.payOuts(), cash.currencyCode()));
        r = addRow(g, r, "Expected", money(cash.expectedCash(), cash.currencyCode()));
        r = addRow(g, r, "Counted", money(cash.countedCash(), cash.currencyCode()));

        Label varianceKey = new Label("Variance");
        varianceKey.getStyleClass().add("field-label");
        Label varianceValue = new Label(varianceText(cash.variance(), cash.currencyCode()));
        varianceValue.getStyleClass().addAll("money", varianceStyle(cash.variance()));
        g.add(varianceKey, 0, r);
        g.add(varianceValue, 1, r);

        dialog.getDialogPane().setContent(new VBox(12, g));
        dialog.showAndWait();
    }

    private static int addRow(GridPane g, int r, String key, String value) {
        Label k = new Label(key);
        k.getStyleClass().add("field-label");
        Label v = new Label(value);
        v.getStyleClass().add("money");
        g.add(k, 0, r);
        g.add(v, 1, r);
        return r + 1;
    }

    /** "Balanced" at zero, else "Over <amt>" / "Short <amt>" (word + amount; colour is a 2nd cue). */
    static String varianceText(BigDecimal variance, String currency) {
        if (variance == null || variance.signum() == 0) {
            return "Balanced";
        }
        String word = variance.signum() > 0 ? "Over" : "Short";
        return word + " " + money(variance.abs(), currency);
    }

    static String varianceStyle(BigDecimal variance) {
        if (variance == null || variance.signum() == 0) {
            return "variance-balanced";
        }
        return variance.signum() > 0 ? "variance-over" : "variance-short";
    }

    private static String money(BigDecimal v, String currency) {
        BigDecimal amt = v == null ? BigDecimal.ZERO : v;
        return amt.toPlainString() + " " + (currency == null ? "" : currency);
    }
}
```

- [ ] **Step 7: Add the CSS**

Append to `pos-terminal/src/main/resources/css/app.css`:

```css
/* Slice 10 — shift close (variance + reconciliation result) */
.shift-result .field-label { -fx-font-size: 14px; }
.variance-balanced { -fx-text-fill: -fx-ink; -fx-font-weight: bold; }
.variance-over { -fx-text-fill: -fx-primary; -fx-font-weight: bold; }
.variance-short { -fx-text-fill: -fx-danger; -fx-font-weight: bold; }
```

- [ ] **Step 8: Add `closeShiftButton` to `home.fxml`**

In `home.fxml`, replace the sign-out button line:

```xml
      <Button fx:id="signOutButton" text="Sign out" styleClass="btn-secondary"/>
```

with:

```xml
      <Button fx:id="closeShiftButton" text="Close shift" styleClass="btn-secondary"/>
      <Button fx:id="signOutButton" text="Sign out" styleClass="btn-secondary"/>
```

- [ ] **Step 9: Wire the close flow in `HomeController`**

In `HomeController.java`:

(a) Add imports:

```java
import com.company.pos.terminal.api.dto.ShiftSummary;
import com.company.pos.terminal.viewmodel.CloseShiftViewModel;
import java.util.Optional;
import java.util.UUID;
```

(b) Add fields (near `shiftVm`):

```java
    private final CloseShiftViewModel closeVm;
    private ShiftView openShift;
    @FXML private Button closeShiftButton;
```

(c) In the constructor, after `this.shiftVm = ...`:

```java
        this.closeVm = new CloseShiftViewModel(services.shiftApi, Platform::runLater);
```

(d) In `initialize()`, after the other button handlers:

```java
        closeShiftButton.setOnAction(e -> closeShift());
        closeShiftButton.setVisible(false);
        closeShiftButton.setManaged(false);
```

(e) Replace `showShift` and `showNoShift` with:

```java
    private void showShift(ShiftView shift) {
        this.openShift = shift;
        shiftLabel.setText("Shift open since " + OPENED_AT.format(shift.openedAt()));
        closeShiftButton.setVisible(true);
        closeShiftButton.setManaged(true);
    }

    private void showNoShift() {
        this.openShift = null;
        shiftLabel.setText("No shift open — cash reports unavailable");
        closeShiftButton.setVisible(false);
        closeShiftButton.setManaged(false);
    }
```

(f) Add the close flow method:

```java
    /** Close the shift: blind count → close → reconciliation result → no-shift state. */
    private void closeShift() {
        if (openShift == null) {
            return;
        }
        Optional<BigDecimal> counted = CloseShiftDialog.promptForCount(services.config.terminalId());
        if (counted.isEmpty()) {
            return;
        }
        UUID id = openShift.shiftId();
        boolean[] holder = {false};
        FxTasks.run(
                () -> holder[0] = closeVm.closeShift(id, counted.get()),
                () -> {
                    if (holder[0]) {
                        ShiftSummary s = closeVm.summary().get();
                        if (s != null && s.cash() != null) {
                            ShiftResultDialog.show(s.cash());
                        }
                        showNoShift();
                    } else {
                        shiftLabel.setText(closeVm.errorMessage().get());
                    }
                },
                err -> {
                    shiftLabel.setText("Couldn't close the shift");
                    LOG.log(System.Logger.Level.ERROR, "Close shift failed", err);
                });
    }
```

- [ ] **Step 10: Run the full terminal suite**

Run: `./mvnw -f pos-terminal/pom.xml clean test`
Expected: BUILD SUCCESS, all tests pass (Track B adds the API test, 3 VM tests, 1 CSS test, 4 parse tests, 4 variance tests).

- [ ] **Step 11: Add the Slice 10 manual-E2E section to the README**

Append to `pos-terminal/README.md` (after the Slice 9 section):

```markdown
## Slice 10 — Void order + Shift close (manual E2E)

**Prereq:** backend on `embedded,dev`; log in `manager`/`manager`.

**Void order (order screen):**
1. Open a table, add lines (optionally fire some). Tap **Void order** (red).
2. In the confirm dialog, optionally type a reason; tap **Void order**.
3. Enter a manager code + PIN in the approval dialog (`manager`/`manager`).
4. On success the screen returns to the table map and the order is gone. A wrong PIN or a
   non-manager account shows an error on the order screen and does not void.

**Close shift (home screen):**
1. With a shift open, the home screen shows a **Close shift** button.
2. Tap it → the blind-count dialog (no expected amount shown). Enter the counted cash by
   keyboard or the denomination helper; tap **Count & close**.
3. The reconciliation result shows opening float, cash sales, pay-ins/outs, expected, counted,
   and the variance labelled **Over / Short / Balanced** (word + colour).
4. Home updates to "No shift open" and the Close shift button disappears.
```

- [ ] **Step 12: Final full-suite run and commit**

Run: `./mvnw -f pos-terminal/pom.xml clean test`
Expected: BUILD SUCCESS, all green.

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/DenominationCounter.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/CloseShiftDialog.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/ShiftResultDialog.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/StartShiftDialog.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/HomeController.java \
        pos-terminal/src/main/resources/fxml/home.fxml \
        pos-terminal/src/main/resources/css/app.css \
        pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/view/CloseShiftDialogParseTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/view/ShiftResultDialogTest.java \
        pos-terminal/README.md
git commit -m "feat(terminal): close shift — blind count + reconciliation result on home

Extracts the shared DenominationCounter (used by start- and close-shift dialogs), adds the
blind-count CloseShiftDialog and the variance ShiftResultDialog, and wires the home-screen
Close shift button. Closes terminal slice 10.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Track A: `DiningApi.voidOrder` bearer+encoded reason (T1); `OrderViewModel.voidOrder`+`setError` with async test (T2); danger button + `VoidConfirmDialog` + manager-PIN one-shot flow + navigate (T3). ✓
- Track B: `ShiftSummary`/`DrawerReconciliation`/`CloseShiftRequest` DTOs + `ShiftApi.closeShift` (T4); `CloseShiftViewModel` with async test (T5); blind-count `CloseShiftDialog`, `ShiftResultDialog` (variance Over/Short/Balanced word+colour), home `closeShiftButton` + flow, shared `DenominationCounter` extraction (T6). ✓
- Blind count (no expected shown) → variance reveal: `CloseShiftDialog` shows no expected; `ShiftResultDialog` reveals expected+variance post-close. ✓
- Manager gate on void / none on close: void uses `ManagerPinDialog`+bearer; close uses the session. ✓
- FX-threading: both VMs synchronous, observables only in `ui.accept`, controllers use `holder` in `onDone`, `onError` logs only (home close `onError` sets a plain non-bound `shiftLabel`, which is allowed — it is not bound). Async regression tests in T2 and T5. ✓
- No backend changes; new DTOs `@JsonIgnoreProperties`. ✓

**Placeholder scan:** No TBD/TODO; every code step shows full code; commands have expected output. ✓

**Type consistency:** `voidOrder(UUID,String,String)` identical in T1 (impl+test) and T2 (VM caller+tests) and T3 (controller). `closeShift(UUID,BigDecimal)` identical in T4/T5/T6. `ShiftSummary`/`DrawerReconciliation` field lists identical across T4 DTOs, T5 test builder, and T6 `ShiftResultDialog`/`HomeController` usage. `DenominationCounter.pane(TextField)` defined T6, used by both dialogs. `varianceText`/`varianceStyle` defined and tested in T6. ✓

**Note on `errorLabel` vs `shiftLabel`:** on the order screen `errorLabel` is bound to `vm.errorMessage()`, so the void `onError` uses `vm.setError(...)` (never `setText`). On the home screen `shiftLabel` is NOT bound, so the close `onError`/error branch may `setText` it directly — consistent with the existing `checkShift`/`openShift` handlers.

**Note on glyphs:** `DenominationCounter` uses the literal `−` (U+2212) / `+` stepper glyphs carried over verbatim from `StartShiftDialog`; keep the files UTF-8.
