# Terminal Slice 4 — Zero-Backend Wins Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add four terminal-only features to the JavaFX POS terminal — a Start Shift cash-float modal on first sign-in, denomination fast-cash chips on the payment screen, an explicit estimate-vs-server-quote presentation, and a receipt success state — using only backend endpoints that already exist.

**Architecture:** All work is in `pos-terminal/` (standalone Maven module, REST thin client, MVVM). New server calls go through a new typed `ShiftApi` on the shared `ApiClient`. The Start Shift modal follows the existing `ModifierPickerDialog` pattern (modal JavaFX `Dialog`, pure view, no server access — the controller does I/O off the FX thread via `FxTasks`). Testable logic lives in a synchronous ViewModel (`StartShiftViewModel`) per the existing convention; display-only code is guarded by headless resource-contract tests (the `AppCssTest` style) and a final manual E2E.

**Tech Stack:** Java 21, JavaFX 21 (FXML + code-built dialogs), Jackson, JUnit 5, `com.sun.net.httpserver` StubServer for API tests. No TestFX — all tests run headless.

## Global Constraints

- **JDK 21 required; system default is 17.** Before ANY Maven command: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`.
- **Build only the terminal module:** `./mvnw -f pos-terminal/pom.xml test` from the repo root. Never add `pos-terminal` to the root reactor. `./mvnw verify` at the root does not touch this module.
- **No new palette.** Only the existing `app.css` tokens: canvas `#F6F4EF`, card `#FFFFFF`, ink `#16202E`, primary `#0E7C66` (press `#0A5E4D`), accent `#E8A32C`, success `#1E7A46`, danger `#C0362C`, muted `#5F6B7A`, border `#E2DED5`. Reference them via looked-up colors (`-fx-primary` etc.), never hardcoded hex in new rules.
- **Spacing in multiples of 8; minimum interactive size 48px** (secondary buttons 56px, primary 64px).
- **Money is `BigDecimal`, scale 2, `RoundingMode.HALF_UP` — never `double`.** Money labels get the `.money` style class.
- **Never imply a client-side total is final.** Estimates are labelled and muted; only the server quote/`SaleView` renders in the prominent total styles. Tender/denomination controls stay disabled until the quote loads.
- **No TestFX / no display in tests.** Tests are: StubServer API tests, synchronous ViewModel tests, and resource-contract tests reading CSS/FXML as text. Dialog/controller rendering is covered by the final manual E2E only.
- **In Java, RED often = compile error.** A test referencing a class that doesn't exist yet fails compilation — that counts as the failing-test step; the expected output notes say so.
- **Commits:** conventional style matching recent history (`feat(terminal): …`), one per task, and end every commit message with the line `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Work happens on the current branch `feat/terminal-ui-restaurant-slice` (or an isolated worktree created at execution time via superpowers:using-git-worktrees).

## File Structure

| File | Responsibility |
|---|---|
| `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ShiftView.java` (new) | Terminal-side mirror of the server's shift view |
| `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/OpenShiftRequest.java` (new) | `POST /shifts` request body |
| `pos-terminal/src/main/java/com/company/pos/terminal/api/ShiftApi.java` (new) | Typed client for `/shifts` (`findOpenShift` 404→null, `openShift`) |
| `pos-terminal/src/main/java/com/company/pos/terminal/api/SessionManager.java` (modify) | Adds session-scoped "shift prompt dismissed" flag |
| `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java` (modify) | Wires `ShiftApi` into the composition root |
| `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/StartShiftViewModel.java` (new) | Open-shift validation/error surfacing + denomination math (headless-testable) |
| `pos-terminal/src/main/java/com/company/pos/terminal/view/StartShiftDialog.java` (new) | Modal cash-float prompt (pure view, `ModifierPickerDialog` pattern) |
| `pos-terminal/src/main/java/com/company/pos/terminal/view/HomeController.java` (modify) | Shift check on arrival, dialog trigger, shift status line |
| `pos-terminal/src/main/resources/fxml/home.fxml` (modify) | Adds `shiftLabel` |
| `pos-terminal/src/main/java/com/company/pos/terminal/view/PaymentController.java` (modify) | Estimate/quote presentation, denomination chips, success state, Done labels |
| `pos-terminal/src/main/resources/fxml/payment.fxml` (modify) | `estimateLabel`/`quoteBadge`, denomination bar, success banner |
| `pos-terminal/src/main/resources/css/app.css` (modify) | New classes: `.estimate-line`, `.quote-badge`, `.denom-chip`, `.denom-row`, `.denom-line-total`, `.success-banner`, `.success-check` |
| `pos-terminal/src/test/java/com/company/pos/terminal/api/ShiftApiTest.java` (new) | StubServer tests for ShiftApi |
| `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/StartShiftViewModelTest.java` (new) | VM validation/denomination tests |
| `pos-terminal/src/test/java/com/company/pos/terminal/view/StartShiftDialogParseTest.java` (new) | Float-parse rule tests (static, no FX) |
| `pos-terminal/src/test/java/com/company/pos/terminal/api/SessionManagerShiftPromptTest.java` (new) | Dismissed-flag lifecycle |
| `pos-terminal/src/test/java/com/company/pos/terminal/FxmlContractTest.java` (new) | Guards fx:ids in home.fxml / payment.fxml |
| `pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java` (modify) | Guards the new style classes |
| `pos-terminal/README.md` (modify) | Documents the slice-4 features |

---

### Task 1: ShiftApi + DTOs + Services wiring

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ShiftView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/OpenShiftRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/ShiftApi.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/ShiftApiTest.java`

**Interfaces:**
- Consumes: existing `ApiClient` (`get`/`post` with `TypeReference`), `ApiException.status()`, test helper `StubServer`.
- Produces: `ShiftView(UUID shiftId, String terminalId, String openedBy, String status, String currencyCode, Instant openedAt, Instant closedAt)`; `ShiftApi.findOpenShift()` returning `ShiftView` **or null when the server 404s**; `ShiftApi.openShift(BigDecimal openingFloat)` returning `ShiftView`; `Services.shiftApi` public final field. Tasks 2–3 rely on exactly these signatures.

The server side (already deployed, `src/main/java/com/company/pos/shift/web/ShiftController.java`): `GET /shifts/open` returns the open shift for the terminal or 404; `POST /shifts` takes `{"openingFloat": <decimal>}` and returns 201 with a `ShiftView`.

- [ ] **Step 1: Write the failing tests**

Create `pos-terminal/src/test/java/com/company/pos/terminal/api/ShiftApiTest.java`:

```java
package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.ShiftView;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ShiftApiTest {

    private static final String SHIFT_JSON =
            "{\"shiftId\":\"88888888-8888-8888-8888-888888888888\",\"terminalId\":\"T01\","
            + "\"openedBy\":\"manager\",\"status\":\"OPEN\",\"currencyCode\":\"SAR\","
            + "\"openedAt\":\"2026-07-12T06:02:00Z\",\"closedAt\":null}";

    @Test
    void findOpenShiftParsesShift() throws Exception {
        try (StubServer stub = new StubServer(200, SHIFT_JSON, "application/json")) {
            ShiftApi api = new ShiftApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            ShiftView v = api.findOpenShift();
            assertNotNull(v);
            assertEquals("T01", v.terminalId());
            assertEquals("OPEN", v.status());
            assertEquals("SAR", v.currencyCode());
            assertEquals("GET", stub.lastMethod);
            assertEquals("/shifts/open", stub.lastPath);
        }
    }

    @Test
    void findOpenShiftReturnsNullWhenNoShiftIsOpen() throws Exception {
        // The server 404s when the terminal has no open shift — that is a normal state, not an error.
        try (StubServer stub = new StubServer(404,
                "{\"title\":\"Not Found\",\"detail\":\"No open shift for this terminal\"}",
                "application/json")) {
            ShiftApi api = new ShiftApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            assertNull(api.findOpenShift());
        }
    }

    @Test
    void findOpenShiftRethrowsNon404Failures() throws Exception {
        try (StubServer stub = new StubServer(500, null, null)) {
            ShiftApi api = new ShiftApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            ApiException ex = assertThrows(ApiException.class, api::findOpenShift);
            assertEquals(500, ex.status());
        }
    }

    @Test
    void openShiftPostsOpeningFloatAndParsesShift() throws Exception {
        try (StubServer stub = new StubServer(201, SHIFT_JSON, "application/json")) {
            ShiftApi api = new ShiftApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            ShiftView v = api.openShift(new BigDecimal("500.00"));
            assertEquals("OPEN", v.status());
            assertEquals("POST", stub.lastMethod);
            assertEquals("/shifts", stub.lastPath);
            assertTrue(stub.lastBody.contains("openingFloat"));
            assertTrue(stub.lastBody.contains("500.00"));
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -f pos-terminal/pom.xml test -Dtest=ShiftApiTest
```
Expected: **BUILD FAILURE** — compilation error, `cannot find symbol: class ShiftApi` (and `ShiftView`).

- [ ] **Step 3: Write the implementation**

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ShiftView.java`:

```java
package com.company.pos.terminal.api.dto;

import java.time.Instant;
import java.util.UUID;

/** A terminal shift as returned by {@code POST /shifts} and {@code GET /shifts/open}. */
public record ShiftView(UUID shiftId, String terminalId, String openedBy, String status,
        String currencyCode, Instant openedAt, Instant closedAt) {
}
```

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/OpenShiftRequest.java`:

```java
package com.company.pos.terminal.api.dto;

import java.math.BigDecimal;

/** Body for {@code POST /shifts}: the opening cash float counted into the drawer. */
public record OpenShiftRequest(BigDecimal openingFloat) {
}
```

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/ShiftApi.java`:

```java
package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.OpenShiftRequest;
import com.company.pos.terminal.api.dto.ShiftView;
import com.fasterxml.jackson.core.type.TypeReference;
import java.math.BigDecimal;

/** Typed client for the store server's {@code /shifts} endpoints. */
public class ShiftApi {

    private final ApiClient client;

    public ShiftApi(ApiClient client) {
        this.client = client;
    }

    /**
     * GET /shifts/open — this terminal's open shift, or {@code null} when none exists.
     * The server models "no open shift" as a 404; that is an expected state here, so it
     * maps to null rather than an exception. Any other failure is rethrown.
     */
    public ShiftView findOpenShift() {
        try {
            return client.get("/shifts/open", new TypeReference<ShiftView>() {});
        } catch (ApiException e) {
            if (e.status() == 404) {
                return null;
            }
            throw e;
        }
    }

    /** POST /shifts — opens a shift for this terminal with the given opening cash float. */
    public ShiftView openShift(BigDecimal openingFloat) {
        return client.post("/shifts", new OpenShiftRequest(openingFloat),
                new TypeReference<ShiftView>() {});
    }
}
```

Modify `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java` — add the import, field, and constructor line alongside the existing `*Api` wiring:

```java
import com.company.pos.terminal.api.ShiftApi;
```
```java
    public final ShiftApi shiftApi;
```
and inside the constructor, after `this.cartApi = new CartApi(apiClient);`:
```java
        this.shiftApi = new ShiftApi(apiClient);
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=ShiftApiTest`
Expected: **Tests run: 4, Failures: 0, Errors: 0** — BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ShiftView.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/OpenShiftRequest.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/ShiftApi.java \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/ShiftApiTest.java
git commit -m "feat(terminal): typed ShiftApi for /shifts open-shift check and open"
```

---

### Task 2: StartShiftViewModel — validation + denomination math

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/StartShiftViewModel.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/StartShiftViewModelTest.java`

**Interfaces:**
- Consumes: `ShiftApi.openShift(BigDecimal)` and `ShiftView` from Task 1; `ApiException` message pattern from `PaymentViewModel`.
- Produces (Task 3 relies on these): `StartShiftViewModel(ShiftApi api, Consumer<Runnable> ui)`; `static final List<BigDecimal> DENOMINATIONS` (SAR notes 500, 200, 100, 50, 10, 5, largest first); `static BigDecimal total(Map<BigDecimal, Integer> counts)`; `boolean openShift(BigDecimal openingFloat)`; `ReadOnlyObjectProperty<ShiftView> shift()`; `ReadOnlyStringProperty errorMessage()`.

Convention note: like `PaymentViewModel`, this VM is synchronous — the controller runs it off the FX thread; every observable write goes through the `ui` dispatcher so tests can use `Runnable::run` and production uses `Platform::runLater`.

- [ ] **Step 1: Write the failing tests**

Create `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/StartShiftViewModelTest.java`:

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.ShiftApi;
import com.company.pos.terminal.api.dto.ShiftView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StartShiftViewModelTest {

    private static ShiftView openShiftView() {
        return new ShiftView(UUID.randomUUID(), "T01", "manager", "OPEN", "SAR",
                Instant.parse("2026-07-12T06:02:00Z"), null);
    }

    /** Records the float it was asked to open with and returns a fixed shift. */
    private static final class RecordingShiftApi extends ShiftApi {
        BigDecimal received;
        int calls;
        RecordingShiftApi() { super(null); }
        @Override public ShiftView openShift(BigDecimal openingFloat) {
            received = openingFloat;
            calls++;
            return openShiftView();
        }
    }

    @Test
    void denominationTotalSumsNotesTimesCounts() {
        Map<BigDecimal, Integer> counts = Map.of(
                new BigDecimal("500"), 1,
                new BigDecimal("50"), 2,
                new BigDecimal("5"), 0);
        assertEquals(new BigDecimal("600.00"), StartShiftViewModel.total(counts));
    }

    @Test
    void denominationTotalTreatsNullAndNegativeCountsAsZero() {
        Map<BigDecimal, Integer> counts = new java.util.HashMap<>();
        counts.put(new BigDecimal("100"), null);
        counts.put(new BigDecimal("10"), -3);
        counts.put(new BigDecimal("5"), 2);
        assertEquals(new BigDecimal("10.00"), StartShiftViewModel.total(counts));
    }

    @Test
    void negativeFloatRejectedWithoutApiCall() {
        RecordingShiftApi api = new RecordingShiftApi();
        StartShiftViewModel vm = new StartShiftViewModel(api, Runnable::run);
        assertFalse(vm.openShift(new BigDecimal("-1")));
        assertEquals(0, api.calls, "must not call the server with a negative float");
        assertTrue(vm.errorMessage().get().toLowerCase().contains("zero or more"));
        assertNull(vm.shift().get());
    }

    @Test
    void nullFloatRejectedWithoutApiCall() {
        RecordingShiftApi api = new RecordingShiftApi();
        StartShiftViewModel vm = new StartShiftViewModel(api, Runnable::run);
        assertFalse(vm.openShift(null));
        assertEquals(0, api.calls);
    }

    @Test
    void openShiftScalesTheFloatAndExposesTheShift() {
        RecordingShiftApi api = new RecordingShiftApi();
        StartShiftViewModel vm = new StartShiftViewModel(api, Runnable::run);
        assertTrue(vm.openShift(new BigDecimal("500")));
        assertEquals(new BigDecimal("500.00"), api.received);
        assertNotNull(vm.shift().get());
        assertEquals("OPEN", vm.shift().get().status());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void zeroFloatIsLegal() {
        RecordingShiftApi api = new RecordingShiftApi();
        StartShiftViewModel vm = new StartShiftViewModel(api, Runnable::run);
        assertTrue(vm.openShift(BigDecimal.ZERO), "an empty till is a valid opening float");
        assertEquals(1, api.calls);
    }

    @Test
    void apiFailureSurfacesErrorAndReturnsFalse() {
        ShiftApi failing = new ShiftApi(null) {
            @Override public ShiftView openShift(BigDecimal openingFloat) {
                throw new ApiException(409, null, "Shift already open");
            }
        };
        StartShiftViewModel vm = new StartShiftViewModel(failing, Runnable::run);
        assertFalse(vm.openShift(new BigDecimal("100")));
        assertEquals("Shift already open", vm.errorMessage().get());
        assertNull(vm.shift().get());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=StartShiftViewModelTest`
Expected: **BUILD FAILURE** — compilation error, `cannot find symbol: class StartShiftViewModel`.

- [ ] **Step 3: Write the implementation**

Create `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/StartShiftViewModel.java`:

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.ShiftApi;
import com.company.pos.terminal.api.dto.ShiftView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the once-a-day start-shift prompt. Synchronous like the other VMs
 * (the controller runs it off the FX thread via FxTasks); observable writes go through
 * the {@code ui} dispatcher. Also hosts the pure denomination-count math used by the
 * dialog's optional counting helper, so that arithmetic is unit-tested headlessly.
 */
public class StartShiftViewModel {

    /** SAR notes offered by the counting helper, largest first. */
    public static final List<BigDecimal> DENOMINATIONS = List.of(
            new BigDecimal("500"), new BigDecimal("200"), new BigDecimal("100"),
            new BigDecimal("50"), new BigDecimal("10"), new BigDecimal("5"));

    private final ShiftApi api;
    private final Consumer<Runnable> ui;

    private final ReadOnlyObjectWrapper<ShiftView> shift = new ReadOnlyObjectWrapper<>(null);
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public StartShiftViewModel(ShiftApi api, Consumer<Runnable> ui) {
        this.api = api;
        this.ui = ui;
    }

    public ReadOnlyObjectProperty<ShiftView> shift() { return shift.getReadOnlyProperty(); }
    public ReadOnlyStringProperty errorMessage() { return errorMessage.getReadOnlyProperty(); }

    /** Σ(denomination × count) at money scale; null or negative counts contribute zero. */
    public static BigDecimal total(Map<BigDecimal, Integer> counts) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<BigDecimal, Integer> e : counts.entrySet()) {
            Integer n = e.getValue();
            if (n != null && n > 0) {
                sum = sum.add(e.getKey().multiply(BigDecimal.valueOf(n)));
            }
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Opens a shift with the given float. An empty till (zero) is legal; a negative or
     * missing float is rejected before any server call. Returns true on success; on
     * failure the reason is surfaced via {@link #errorMessage()}.
     */
    public boolean openShift(BigDecimal openingFloat) {
        if (openingFloat == null || openingFloat.signum() < 0) {
            ui.accept(() -> errorMessage.set("Opening float must be zero or more"));
            return false;
        }
        try {
            ShiftView opened = api.openShift(openingFloat.setScale(2, RoundingMode.HALF_UP));
            ui.accept(() -> {
                shift.set(opened);
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

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=StartShiftViewModelTest`
Expected: **Tests run: 7, Failures: 0, Errors: 0** — BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/StartShiftViewModel.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/StartShiftViewModelTest.java
git commit -m "feat(terminal): StartShiftViewModel with float validation and denomination math"
```

---

### Task 3: Start Shift dialog + Home integration

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/StartShiftDialog.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/HomeController.java`
- Modify: `pos-terminal/src/main/resources/fxml/home.fxml`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/SessionManager.java`
- Modify: `pos-terminal/src/main/resources/css/app.css`
- Modify: `pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/view/StartShiftDialogParseTest.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/SessionManagerShiftPromptTest.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/FxmlContractTest.java`

**Interfaces:**
- Consumes: `Services.shiftApi` + `ShiftApi.findOpenShift()` (Task 1), `StartShiftViewModel` (Task 2), existing `FxTasks.run(work, onDone, onError)`, `TerminalConfig.terminalId()`, `SessionManager.username()`.
- Produces: `StartShiftDialog.promptForFloat(String terminalId, String username)` returning `Optional<BigDecimal>` (empty = skipped); package-private `static BigDecimal parse(String raw)` (blank → `ZERO`, negative/garbage → `null`); `SessionManager.shiftPromptDismissed()` / `dismissShiftPrompt()`.

Flow being built: Home `initialize()` → `GET /shifts/open` off the FX thread → shift exists → show "Shift open since HH:mm" in the toolbar; no shift → modal dialog (blocking `showAndWait`, same pattern as `ModifierPickerDialog`) → "Start shift" posts the float via the VM; "Skip for now" records the dismissal in the session (so returning to Home after every retail sale doesn't re-nag) and shows a persistent "No shift open" note.

- [ ] **Step 1: Write the failing tests**

Create `pos-terminal/src/test/java/com/company/pos/terminal/view/StartShiftDialogParseTest.java` (the parse rule is a static method with no FX dependency — the only headless-testable part of the dialog):

```java
package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class StartShiftDialogParseTest {

    @Test
    void blankParsesToZero() {
        assertEquals(BigDecimal.ZERO, StartShiftDialog.parse(""));
        assertEquals(BigDecimal.ZERO, StartShiftDialog.parse(null));
        assertEquals(BigDecimal.ZERO, StartShiftDialog.parse("   "));
    }

    @Test
    void plainDecimalParses() {
        assertEquals(new BigDecimal("500"), StartShiftDialog.parse("500"));
        assertEquals(new BigDecimal("12.50"), StartShiftDialog.parse(" 12.50 "));
    }

    @Test
    void negativeIsInvalid() {
        assertNull(StartShiftDialog.parse("-1"));
    }

    @Test
    void garbageIsInvalid() {
        assertNull(StartShiftDialog.parse("abc"));
        assertNull(StartShiftDialog.parse("12..5"));
    }
}
```

Create `pos-terminal/src/test/java/com/company/pos/terminal/api/SessionManagerShiftPromptTest.java`:

```java
package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SessionManagerShiftPromptTest {

    @Test
    void dismissalIsSessionScopedAndClearedOnSignOut() {
        SessionManager session = new SessionManager();
        assertFalse(session.shiftPromptDismissed());
        session.dismissShiftPrompt();
        assertTrue(session.shiftPromptDismissed());
        session.clear();
        assertFalse(session.shiftPromptDismissed(), "sign-out must reset the dismissal");
    }
}
```

Create `pos-terminal/src/test/java/com/company/pos/terminal/FxmlContractTest.java`:

```java
package com.company.pos.terminal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for FXML resources: guards that fx:ids injected by controllers stay
 * present. FXML is plain XML on the classpath, so this stays headless like AppCssTest.
 */
class FxmlContractTest {

    static String resource(String path) throws Exception {
        try (InputStream in = FxmlContractTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " must be on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void homeDeclaresShiftStatusLabel() throws Exception {
        assertTrue(resource("/fxml/home.fxml").contains("fx:id=\"shiftLabel\""),
                "home.fxml must declare the shift status label");
    }
}
```

Add to `pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java` (new test method inside the existing class):

```java
    @Test
    void definesSliceFourClasses() throws Exception {
        String css = css();
        for (String cls : new String[] {
            ".denom-row", ".denom-line-total"
        }) {
            assertTrue(css.contains(cls), "missing style class: " + cls);
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest='StartShiftDialogParseTest,SessionManagerShiftPromptTest,FxmlContractTest,AppCssTest'`
Expected: **BUILD FAILURE** — compilation errors (`cannot find symbol: StartShiftDialog`, `shiftPromptDismissed`). If compilation is fixed piecemeal, the FXML/CSS assertions fail with "must declare the shift status label" / "missing style class".

- [ ] **Step 3: Implement SessionManager flag**

In `pos-terminal/src/main/java/com/company/pos/terminal/api/SessionManager.java`, add a field after `roles`:

```java
    private volatile boolean shiftPromptDismissed;
```

add accessors after `isManager()`:

```java
    /** Session-scoped: the cashier skipped the start-shift prompt; don't re-nag until sign-out. */
    public boolean shiftPromptDismissed() { return shiftPromptDismissed; }
    public void dismissShiftPrompt() { this.shiftPromptDismissed = true; }
```

and inside `clear()` add:

```java
        this.shiftPromptDismissed = false;
```

- [ ] **Step 4: Implement StartShiftDialog**

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/StartShiftDialog.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.viewmodel.StartShiftViewModel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Modal start-shift prompt shown on the first sign-in of the day (no open shift on this
 * terminal). Collects the opening cash float via keyboard, the shared pin-pad form
 * language, or an optional count-by-denomination helper whose running total writes the
 * float field. Pure view with no server access, mirroring ModifierPickerDialog: returns
 * the confirmed float, or empty when the cashier skips. The caller performs the POST.
 *
 * <p>Display-dependent (constructs a JavaFX Dialog) — exercised by the manual E2E, never
 * a headless unit test; only the static {@link #parse} rule is unit-tested.
 */
public final class StartShiftDialog {

    private StartShiftDialog() {}

    public static Optional<BigDecimal> promptForFloat(String terminalId, String username) {
        Dialog<BigDecimal> dialog = new Dialog<>();
        dialog.setTitle("Start shift");
        dialog.setHeaderText("Start shift · Terminal " + terminalId);
        ButtonType start = new ButtonType("Start shift", ButtonBar.ButtonData.OK_DONE);
        ButtonType skip = new ButtonType("Skip for now", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(start, skip);
        dialog.getDialogPane().getStyleClass().add("shift-modal");

        Label signedIn = new Label("Signed in as " + username);
        signedIn.getStyleClass().add("subtitle");

        Label floatLabel = new Label("Opening cash float");
        floatLabel.getStyleClass().add("field-label");
        TextField floatField = new TextField();
        floatField.setPromptText("0.00");
        floatField.getStyleClass().add("money");
        VBox floatBox = new VBox(6, floatLabel, floatField);
        floatBox.getStyleClass().add("field");

        VBox box = new VBox(16, signedIn, floatBox, pinPad(floatField), denominationPane(floatField));
        box.setAlignment(Pos.TOP_CENTER);
        dialog.getDialogPane().setContent(box);

        // "Start shift" stays disabled while the field does not parse to a non-negative amount.
        javafx.scene.Node startNode = dialog.getDialogPane().lookupButton(start);
        Runnable revalidate = () -> startNode.setDisable(parse(floatField.getText()) == null);
        floatField.textProperty().addListener((o, was, now) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> bt == start ? parse(floatField.getText()) : null);
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    /** Non-negative decimal, blank counting as zero (an empty till is legal); invalid → null. */
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

    /** 3×4 numeric pad appending to the target field — same form language as the login pad. */
    private static GridPane pinPad(TextField target) {
        GridPane pad = new GridPane();
        pad.getStyleClass().add("pin-pad");
        pad.setAlignment(Pos.CENTER);
        String[][] keys = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {".", "0", "⌫"}};
        for (int r = 0; r < keys.length; r++) {
            for (int c = 0; c < keys[r].length; c++) {
                String key = keys[r][c];
                Button b = new Button(key);
                b.getStyleClass().add("pin-key");
                if (".".equals(key) || "⌫".equals(key)) {
                    b.getStyleClass().add("pin-key-alt");
                }
                b.setOnAction(e -> {
                    String t = target.getText() == null ? "" : target.getText();
                    if ("⌫".equals(key)) {
                        if (!t.isEmpty()) {
                            target.setText(t.substring(0, t.length() - 1));
                        }
                    } else {
                        target.setText(t + key);
                    }
                });
                pad.add(b, c, r);
            }
        }
        return pad;
    }

    /** Optional counting helper: a stepper row per SAR note; the total overwrites the float field. */
    private static TitledPane denominationPane(TextField floatField) {
        Map<BigDecimal, Integer> counts = new HashMap<>();
        VBox rows = new VBox(8);
        for (BigDecimal denom : StartShiftViewModel.DENOMINATIONS) {
            counts.put(denom, 0);
            rows.getChildren().add(denomRow(denom, counts, floatField));
        }
        TitledPane pane = new TitledPane("Count by denomination (optional)", rows);
        pane.setExpanded(false);
        return pane;
    }

    private static HBox denomRow(BigDecimal denom, Map<BigDecimal, Integer> counts,
            TextField floatField) {
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
            floatField.setText(StartShiftViewModel.total(counts).toPlainString());
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

- [ ] **Step 5: Wire HomeController and home.fxml**

Replace the toolbar block in `pos-terminal/src/main/resources/fxml/home.fxml` (currently `<Label fx:id="userLabel" …/>` sits directly in the HBox) so the user and shift status stack:

```xml
    <HBox spacing="16" alignment="CENTER" maxWidth="Infinity">
      <Label text="Choose a mode" styleClass="title"/>
      <Pane HBox.hgrow="ALWAYS"/>
      <VBox alignment="CENTER_RIGHT" spacing="2">
        <Label fx:id="userLabel" styleClass="subtitle"/>
        <Label fx:id="shiftLabel" styleClass="subtitle"/>
      </VBox>
      <Button fx:id="signOutButton" text="Sign out" styleClass="btn-secondary"/>
    </HBox>
```

(`VBox` is already imported in home.fxml's `<?import?>` list; `Pane`, `HBox`, `Button`, `Label` unchanged.)

Replace `pos-terminal/src/main/java/com/company/pos/terminal/view/HomeController.java` entirely with:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.ShiftView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.StartShiftViewModel;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

/**
 * Thin controller for the post-login mode picker. Two tiles route to the dine-in table map or a
 * fresh retail sale. Shows the signed-in user and a sign-out affordance. On arrival it checks the
 * terminal's shift: an open shift shows as a status line; none prompts the once-a-day Start Shift
 * modal (skippable — the dismissal is session-scoped so the prompt doesn't re-nag on every return
 * to Home). No other business logic.
 */
public class HomeController {

    private static final System.Logger LOG = System.getLogger(HomeController.class.getName());
    private static final DateTimeFormatter OPENED_AT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final Services services;
    private final Navigator navigator;
    private final StartShiftViewModel shiftVm;

    @FXML private Label userLabel;
    @FXML private Label shiftLabel;
    @FXML private Button signOutButton;
    @FXML private Button dineInButton;
    @FXML private Button retailButton;

    public HomeController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.shiftVm = new StartShiftViewModel(services.shiftApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        String user = services.session.username();
        userLabel.setText(user == null ? "" : "Signed in: " + user);
        shiftLabel.setText("");
        dineInButton.setOnAction(e -> navigator.toTableMap());
        retailButton.setOnAction(e -> navigator.toRetail());
        signOutButton.setOnAction(e -> {
            services.session.clear();
            navigator.toLogin();
        });
        checkShift();
    }

    /** First arrival of the day: if the terminal has no open shift, prompt for a cash float. */
    private void checkShift() {
        final ShiftView[] holder = new ShiftView[1];
        FxTasks.run(() -> holder[0] = services.shiftApi.findOpenShift(),
                () -> onShiftChecked(holder[0]),
                err -> {
                    shiftLabel.setText("Shift status unavailable");
                    LOG.log(System.Logger.Level.ERROR, "Open-shift check failed", err);
                });
    }

    private void onShiftChecked(ShiftView open) {
        if (open != null) {
            showShift(open);
            return;
        }
        if (services.session.shiftPromptDismissed()) {
            showNoShift();
            return;
        }
        StartShiftDialog.promptForFloat(services.config.terminalId(), services.session.username())
                .ifPresentOrElse(this::openShift, () -> {
                    services.session.dismissShiftPrompt();
                    showNoShift();
                });
    }

    private void openShift(BigDecimal openingFloat) {
        FxTasks.run(() -> shiftVm.openShift(openingFloat),
                () -> {
                    ShiftView opened = shiftVm.shift().get();
                    if (opened != null) {
                        showShift(opened);
                    } else {
                        shiftLabel.setText(shiftVm.errorMessage().get());
                    }
                },
                err -> {
                    shiftLabel.setText("Couldn't start the shift");
                    LOG.log(System.Logger.Level.ERROR, "Open shift failed", err);
                });
    }

    private void showShift(ShiftView shift) {
        shiftLabel.setText("Shift open since " + OPENED_AT.format(shift.openedAt()));
    }

    private void showNoShift() {
        shiftLabel.setText("No shift open — cash reports unavailable");
    }
}
```

- [ ] **Step 6: Add the CSS**

Append to `pos-terminal/src/main/resources/css/app.css`:

```css
/* ---- Start shift modal ---------------------------------------------------- */

/* One stepper row per SAR note in the optional counting helper. */
.denom-row { -fx-padding: 0 0 0 0; }
.denom-line-total { -fx-font-size: 16px; -fx-text-fill: -fx-muted; }
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest='StartShiftDialogParseTest,SessionManagerShiftPromptTest,FxmlContractTest,AppCssTest'`
Expected: **all green** (4 + 1 + 1 + 3 tests). Then run the whole suite to catch regressions: `./mvnw -f pos-terminal/pom.xml test` — expected BUILD SUCCESS, no failures.

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/StartShiftDialog.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/HomeController.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/SessionManager.java \
        pos-terminal/src/main/resources/fxml/home.fxml \
        pos-terminal/src/main/resources/css/app.css \
        pos-terminal/src/test/java/com/company/pos/terminal/view/StartShiftDialogParseTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/SessionManagerShiftPromptTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/FxmlContractTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java
git commit -m "feat(terminal): start-shift cash-float modal on first sign-in of the day"
```

---

### Task 4: Estimate-vs-server-quote presentation on the payment screen

**Files:**
- Modify: `pos-terminal/src/main/resources/fxml/payment.fxml` (the two total labels, lines 20–21)
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/PaymentController.java` (`initialize()` ~line 102/125, `onQuoteLoaded()` ~line 151)
- Modify: `pos-terminal/src/main/resources/css/app.css`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/FxmlContractTest.java` (add method)
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java` (extend `definesSliceFourClasses`)

**Interfaces:**
- Consumes: existing `PaymentController` fields/flow — `estimatedTotal`, `loadQuote()`, `onQuoteLoaded(QuoteView)`, `money(BigDecimal, String)`.
- Produces: new FXML nodes `estimateLabel` (`.estimate-line`) and `quoteBadge` (`.quote-badge`, hidden until the quote loads). Task 5/6 edit the same files; apply this task first.

Design rule being implemented: the estimate is muted and explicitly labelled; the big `.pay-total` number is only ever the server quote, marked with a `✓ server` badge; while loading it reads "Fetching total…" with tenders disabled (existing gate).

- [ ] **Step 1: Write the failing tests**

Add to `FxmlContractTest`:

```java
    @Test
    void paymentDeclaresQuotePresentationNodes() throws Exception {
        String fxml = resource("/fxml/payment.fxml");
        assertTrue(fxml.contains("fx:id=\"estimateLabel\""), "muted estimate line");
        assertTrue(fxml.contains("fx:id=\"quoteBadge\""), "server-quote badge");
    }
```

Extend the array in `AppCssTest.definesSliceFourClasses` to:

```java
        for (String cls : new String[] {
            ".denom-row", ".denom-line-total",
            ".estimate-line", ".quote-badge"
        }) {
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest='FxmlContractTest,AppCssTest'`
Expected: FAIL — `muted estimate line` and `missing style class: .estimate-line`.

- [ ] **Step 3: Edit payment.fxml**

Replace lines 20–21 of `pos-terminal/src/main/resources/fxml/payment.fxml`:

```xml
    <Label fx:id="totalLabel" styleClass="pay-total,money"/>
    <Label fx:id="remainingLabel" styleClass="pay-total,money"/>
```

with:

```xml
    <Label fx:id="estimateLabel" styleClass="estimate-line,money"/>
    <HBox spacing="8" alignment="CENTER" maxWidth="Infinity">
      <Label fx:id="totalLabel" styleClass="pay-total,money"/>
      <Label fx:id="quoteBadge" text="✓ server" styleClass="quote-badge" visible="false" managed="false"/>
    </HBox>
    <Label fx:id="remainingLabel" styleClass="pay-total,money"/>
```

- [ ] **Step 4: Edit PaymentController**

Add the two fields next to the existing `totalLabel` declaration:

```java
    @FXML private Label estimateLabel;
    @FXML private Label quoteBadge;
```

In `initialize()`, replace the first line of the method body:

```java
        totalLabel.setText("Total due (est.): " + estimatedTotal.toPlainString());
```

with:

```java
        estimateLabel.setText("Estimate at order: " + estimatedTotal.toPlainString());
```

and replace the pre-quote placeholder near the end of `initialize()`:

```java
        totalLabel.setText("Total due: loading…");
```

with:

```java
        totalLabel.setText("Fetching total…");
```

In `onQuoteLoaded(QuoteView q)`, after `totalLabel.setText("Total due: " + money(q.grandTotal(), cur));` add:

```java
        quoteBadge.setVisible(true);
        quoteBadge.setManaged(true);
```

- [ ] **Step 5: Add the CSS**

Append to `pos-terminal/src/main/resources/css/app.css`:

```css
/* ---- Estimate vs server quote --------------------------------------------- */

/* The client-side estimate is always muted and explicitly labelled — the big
   .pay-total number is only ever the server quote. */
.estimate-line { -fx-font-size: 16px; -fx-text-fill: -fx-muted; }

/* Small pill marking the total as server-quoted (appears only after the quote loads). */
.quote-badge {
    -fx-background-color: derive(-fx-primary, 88%);
    -fx-text-fill: -fx-primary;
    -fx-font-size: 13px;
    -fx-font-weight: bold;
    -fx-padding: 4 10 4 10;
    -fx-background-radius: 12;
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -f pos-terminal/pom.xml test`
Expected: BUILD SUCCESS, whole suite green (FXML loads are not exercised headlessly; the contract tests plus compilation cover this task).

- [ ] **Step 7: Commit**

```bash
git add pos-terminal/src/main/resources/fxml/payment.fxml \
        pos-terminal/src/main/java/com/company/pos/terminal/view/PaymentController.java \
        pos-terminal/src/main/resources/css/app.css \
        pos-terminal/src/test/java/com/company/pos/terminal/FxmlContractTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java
git commit -m "feat(terminal): payment screen separates muted estimate from badged server quote"
```

---

### Task 5: Denomination fast-cash chips

**Files:**
- Modify: `pos-terminal/src/main/resources/fxml/payment.fxml` (inside `tenderBox`, after `changePreviewLabel`)
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/PaymentController.java` (`initialize()`, `setTendersEnabled()`, `setBusy()`)
- Modify: `pos-terminal/src/main/resources/css/app.css`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/FxmlContractTest.java` (add method)
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java` (extend `definesSliceFourClasses`)

**Interfaces:**
- Consumes: `PaymentViewModel.remaining()` (already public — synchronous read of committed state), existing `tenderedField` + change-preview listener, the quote gate (`setTendersEnabled`, `quoteLoaded`).
- Produces: buttons `denomExactButton`, `denom50Button`, `denom100Button`, `denom200Button`, `denom500Button` (`.denom-chip`, 56px). Behaviour: one tap fills **Cash tendered** — "Exact" with the live remaining amount, the notes with their face value — which drives the existing change preview; CASH then commits. Chips are disabled until the quote loads (Exact before the quote would fill an estimate) and while busy.

- [ ] **Step 1: Write the failing tests**

Add to `FxmlContractTest`:

```java
    @Test
    void paymentDeclaresDenominationChips() throws Exception {
        String fxml = resource("/fxml/payment.fxml");
        for (String id : new String[] {
            "denomExactButton", "denom50Button", "denom100Button",
            "denom200Button", "denom500Button"
        }) {
            assertTrue(fxml.contains("fx:id=\"" + id + "\""), "missing denomination chip: " + id);
        }
    }
```

Extend the array in `AppCssTest.definesSliceFourClasses` to:

```java
        for (String cls : new String[] {
            ".denom-row", ".denom-line-total",
            ".estimate-line", ".quote-badge",
            ".denom-chip"
        }) {
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest='FxmlContractTest,AppCssTest'`
Expected: FAIL — `missing denomination chip: denomExactButton`, `missing style class: .denom-chip`.

- [ ] **Step 3: Edit payment.fxml**

Insert directly after the `<Label fx:id="changePreviewLabel" …/>` line (inside `tenderBox`, before the Cash/Card/Wallet HBox):

```xml
      <!-- Denomination fast-cash: one tap fills "Cash tendered" (Exact = the live remaining
           amount, notes = face value); the change preview reacts, then Cash commits. -->
      <HBox fx:id="denomBar" spacing="8" alignment="CENTER" maxWidth="Infinity">
        <Button fx:id="denomExactButton" text="Exact" styleClass="denom-chip" maxWidth="Infinity" HBox.hgrow="ALWAYS"/>
        <Button fx:id="denom50Button" text="50" styleClass="denom-chip,money" maxWidth="Infinity" HBox.hgrow="ALWAYS"/>
        <Button fx:id="denom100Button" text="100" styleClass="denom-chip,money" maxWidth="Infinity" HBox.hgrow="ALWAYS"/>
        <Button fx:id="denom200Button" text="200" styleClass="denom-chip,money" maxWidth="Infinity" HBox.hgrow="ALWAYS"/>
        <Button fx:id="denom500Button" text="500" styleClass="denom-chip,money" maxWidth="Infinity" HBox.hgrow="ALWAYS"/>
      </HBox>
```

- [ ] **Step 4: Edit PaymentController**

Add fields next to the other `@FXML` buttons:

```java
    @FXML private Button denomExactButton;
    @FXML private Button denom50Button;
    @FXML private Button denom100Button;
    @FXML private Button denom200Button;
    @FXML private Button denom500Button;
    private java.util.List<Button> denomButtons;
```

In `initialize()`, after the `doneButton.setOnAction(…)` line and **before** the `setTendersEnabled(false)` call, add:

```java
        denomExactButton.setOnAction(e -> tenderedField.setText(vm.remaining().toPlainString()));
        denom50Button.setOnAction(e -> tenderedField.setText("50"));
        denom100Button.setOnAction(e -> tenderedField.setText("100"));
        denom200Button.setOnAction(e -> tenderedField.setText("200"));
        denom500Button.setOnAction(e -> tenderedField.setText("500"));
        denomButtons = java.util.List.of(denomExactButton, denom50Button, denom100Button,
                denom200Button, denom500Button);
```

In `setTendersEnabled(boolean enabled)`, add at the end (guarded because the method also runs from `initialize()` ordering-sensitive paths):

```java
        if (denomButtons != null) {
            denomButtons.forEach(b -> b.setDisable(!enabled));
        }
```

In `setBusy(boolean busy)`, add at the end:

```java
        if (denomButtons != null) {
            denomButtons.forEach(b -> b.setDisable(busy || !quoteLoaded));
        }
```

- [ ] **Step 5: Add the CSS**

Append to `pos-terminal/src/main/resources/css/app.css`:

```css
/* ---- Denomination fast-cash chips ----------------------------------------- */

/* 56px quick-fill chips for common SAR notes plus "Exact". They only fill the
   cash-tendered field — the coloured tender button still commits the payment. */
.denom-chip {
    -fx-min-height: 56px;
    -fx-pref-height: 56px;
    -fx-font-size: 16px;
    -fx-font-weight: bold;
    -fx-background-color: -fx-canvas;
    -fx-text-fill: -fx-ink;
    -fx-border-color: -fx-border;
    -fx-border-radius: 8;
    -fx-background-radius: 8;
    -fx-cursor: hand;
}
.denom-chip:hover    { -fx-border-color: -fx-primary; }
.denom-chip:pressed  { -fx-background-color: derive(-fx-canvas, -12%); }
.denom-chip:focused  { -fx-border-color: -fx-primary; -fx-border-width: 2; }
.denom-chip:disabled { -fx-opacity: 0.55; }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -f pos-terminal/pom.xml test`
Expected: BUILD SUCCESS, whole suite green.

- [ ] **Step 7: Commit**

```bash
git add pos-terminal/src/main/resources/fxml/payment.fxml \
        pos-terminal/src/main/java/com/company/pos/terminal/view/PaymentController.java \
        pos-terminal/src/main/resources/css/app.css \
        pos-terminal/src/test/java/com/company/pos/terminal/FxmlContractTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java
git commit -m "feat(terminal): denomination fast-cash chips fill cash tendered in one tap"
```

---

### Task 6: Receipt success state + destination-labelled Done

**Files:**
- Modify: `pos-terminal/src/main/resources/fxml/payment.fxml` (top of `resultBox`)
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/PaymentController.java` (`showResult()`)
- Modify: `pos-terminal/src/main/resources/css/app.css`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/FxmlContractTest.java` (add method)
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java` (extend `definesSliceFourClasses`)

**Interfaces:**
- Consumes: `showResult(SaleView)` and its `money(…)` helper, `mode` field, `services.config.reducedMotion()` (existing `TerminalConfig` flag — same one the retail pulse honours).
- Produces: `successBanner` (VBox `.success-banner`) with `successCheck` (`✓`, `.success-check`) and `paidLabel` ("Paid · 110.40 SAR"); Done button text becomes "Done ▸ Tables" (dine-in) / "Done ▸ Home" (retail) so the scene swap never surprises. The checkmark gets a 200ms scale-in unless reduced motion is configured.

- [ ] **Step 1: Write the failing tests**

Add to `FxmlContractTest`:

```java
    @Test
    void paymentDeclaresSuccessBanner() throws Exception {
        String fxml = resource("/fxml/payment.fxml");
        assertTrue(fxml.contains("fx:id=\"successBanner\""));
        assertTrue(fxml.contains("fx:id=\"successCheck\""));
        assertTrue(fxml.contains("fx:id=\"paidLabel\""));
    }
```

Extend the array in `AppCssTest.definesSliceFourClasses` to its final form:

```java
        for (String cls : new String[] {
            ".denom-row", ".denom-line-total",
            ".estimate-line", ".quote-badge",
            ".denom-chip",
            ".success-banner", ".success-check"
        }) {
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest='FxmlContractTest,AppCssTest'`
Expected: FAIL — missing `successBanner` fx:id and `.success-banner` class.

- [ ] **Step 3: Edit payment.fxml**

Inside `resultBox`, insert **before** the `<Label fx:id="receiptLabel" …/>` line:

```xml
      <VBox fx:id="successBanner" alignment="CENTER" spacing="4" styleClass="success-banner" maxWidth="Infinity">
        <Label fx:id="successCheck" text="✓" styleClass="success-check"/>
        <Label fx:id="paidLabel" styleClass="pay-grand-label,money"/>
      </VBox>
```

- [ ] **Step 4: Edit PaymentController**

Add imports:

```java
import javafx.animation.ScaleTransition;
import javafx.util.Duration;
```

Add fields next to `receiptLabel`:

```java
    @FXML private VBox successBanner;
    @FXML private Label successCheck;
    @FXML private Label paidLabel;
```

In `showResult(SaleView sale)`, directly after `receiptLabel.setText("Receipt " + sale.receiptNumber());` add:

```java
        paidLabel.setText("Paid · " + money(sale.grandTotal(), cur));
        doneButton.setText(mode == Mode.RETAIL ? "Done ▸ Home" : "Done ▸ Tables");
```

At the end of `showResult`, after `resultBox.setManaged(true);` add:

```java
        if (!services.config.reducedMotion()) {
            ScaleTransition pop = new ScaleTransition(Duration.millis(200), successCheck);
            pop.setFromX(0.6);
            pop.setFromY(0.6);
            pop.setToX(1.0);
            pop.setToY(1.0);
            pop.play();
        }
```

- [ ] **Step 5: Add the CSS**

Append to `pos-terminal/src/main/resources/css/app.css`:

```css
/* ---- Payment success state ------------------------------------------------ */

/* Confirmation banner atop the receipt: success-tinted, check + "Paid · amount".
   A state, not a step — Done remains the only action that navigates away. */
.success-banner {
    -fx-background-color: derive(-fx-success, 90%);
    -fx-background-radius: 8;
    -fx-padding: 16;
}
.success-check {
    -fx-font-size: 44px;
    -fx-font-weight: bold;
    -fx-text-fill: -fx-success;
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -f pos-terminal/pom.xml test`
Expected: BUILD SUCCESS, whole suite green.

- [ ] **Step 7: Commit**

```bash
git add pos-terminal/src/main/resources/fxml/payment.fxml \
        pos-terminal/src/main/java/com/company/pos/terminal/view/PaymentController.java \
        pos-terminal/src/main/resources/css/app.css \
        pos-terminal/src/test/java/com/company/pos/terminal/FxmlContractTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java
git commit -m "feat(terminal): receipt success banner and destination-labelled Done"
```

---

### Task 7: Full verification, docs, manual E2E

**Files:**
- Modify: `pos-terminal/README.md`

- [ ] **Step 1: Run the entire terminal suite**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -f pos-terminal/pom.xml clean test
```
Expected: BUILD SUCCESS; previous count was 93 tests, now ~93 + 17 new — zero failures/errors.

- [ ] **Step 2: Document the slice in the terminal README**

Append to `pos-terminal/README.md` (adjust placement to fit the file's existing structure — add under the features/overview section, keeping its heading style):

```markdown
### Slice 4 — shift, denominations, quote clarity, success state

- **Start shift**: on the first arrival at Home with no open shift on this terminal
  (`GET /shifts/open` → 404), a modal prompts for the opening cash float (numeric pad
  + optional count-by-denomination helper) and opens the shift via `POST /shifts`.
  Skipping is remembered for the session and shown as "No shift open" in the toolbar.
- **Denomination fast-cash**: Exact / 50 / 100 / 200 / 500 chips on the payment screen
  fill "Cash tendered" in one tap; the change preview reacts and Cash commits. Chips
  stay disabled until the server quote loads.
- **Estimate vs quote**: the client estimate renders as a muted "Estimate at order"
  line; the prominent total is only ever the server quote, marked with a "✓ server"
  badge ("Fetching total…" while it loads).
- **Success state**: the receipt opens with a success-tinted "Paid · <amount>" banner
  (checkmark scale-in honours `ui.reduced-motion`), and Done is labelled with its
  destination ("Done ▸ Tables" / "Done ▸ Home").
```

- [ ] **Step 3: Manual E2E (needs a display + running backend)**

Terminal 1 — backend with dev seed data (root module):
```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw spring-boot:run -Dspring-boot.run.profiles=embedded,dev
```
Terminal 2 — the JavaFX terminal:
```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -f pos-terminal/pom.xml javafx:run
```

Verify, in order (login `manager` / `manager`):
1. **Start shift modal** appears over Home on first sign-in; expand "Count by denomination", tap `+` on 500 once and `+` on 50 twice → float field reads `600.00`; tap **Start shift** → toolbar shows "Shift open since HH:mm". Sign out, sign in again → no modal (shift already open), status line shows immediately.
2. To check **Skip**: only if a fresh DB is used (delete the embedded SQLite file) — skip → "No shift open — cash reports unavailable"; navigate Retail → Home → prompt does **not** reappear this session.
3. **Retail payment**: add an item → Charge → observe muted "Estimate at order: …", "Fetching total…", then "Total due: … SAR" with the **✓ server** badge; denomination chips enable only after the badge appears.
4. Tap **Exact** → Cash tendered fills with the remaining amount, change preview shows `Change: 0.00`; tap **100** instead → preview shows the difference; tap **Cash** → receipt appears with the green **✓ Paid · <amount>** banner (checkmark pops in), and the button reads **Done ▸ Home**.
5. **Dine-in**: seat a table, add a line, Pay → same quote presentation; complete with Card → button reads **Done ▸ Tables** and returns to the table map.
6. Set `ui.reduced-motion=true` (JVM flag `-Dui.reduced-motion=true`) and re-run step 4 → banner appears without the scale-in.

- [ ] **Step 4: Commit the docs**

```bash
git add pos-terminal/README.md
git commit -m "docs(terminal): document slice-4 shift/denomination/quote/success features"
```
