# Terminal Order Stale-Detection Push Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When another terminal voids/closes/merges the dine-in order a cashier is editing, detect it within ~1s over the existing `/ws/floor` WebSocket, lock the order screen, and prompt a return to tables — and fix the slice-14 shared-client lifecycle bug so both screens get live push on every visit.

**Architecture:** Per-screen `RealtimeClient` instances via a new `Services.newRealtimeClient()` factory (with a `NoopRealtimeClient` when push is disabled). The order screen connects its own client and, on any floor ping, re-checks `GET /dining/orders/{id}` (coalesced, off the FX thread); a non-OPEN status locks the screen, an OPEN status refreshes the lines. Terminal-only — no backend change.

**Tech Stack:** JavaFX 21, JDK `java.net.http` WebSocket (already wired in slice 14), JUnit 5.

## Global Constraints

- **JDK 21.** Before any Maven command: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`.
- **Terminal is a SEPARATE build.** Build/test ONLY with `./mvnw -f pos-terminal/pom.xml clean test` (headless). The root `./mvnw` does not touch it. Do NOT run backend tests for this slice — there is no backend change.
- **This slice is TERMINAL-ONLY.** No backend file, no migration, no config key, no Maven change, no module-boundary change.
- **FX-threading (the codebase's recurring bug class).** The push callback fires on a WebSocket/HttpClient thread. Any blocking call (`vm.recheck()` does a GET) goes in the `FxTasks.run(...)` **work** lambda; UI changes (locking controls, showing the banner) happen in `onDone` on the FX thread via a holder; observable mutations inside the VM go through `ui.accept(...)` only. Nothing writes an observable or scene-graph node off the FX thread. `onError` must never `setText` a **bound** label (`errorLabel.text` is bound) — log instead.
- **Reuse the existing `RealtimeClient.connect(Runnable)` seam** — do not change its signature (the floor map depends on it).
- Every VM method with inter-thread behaviour gets a deferred-dispatcher regression test.
- Commit trailer on every commit:
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

---

## Task 1: Per-screen RealtimeClient factory (+ fix the floor-map re-visit bug)

**Why:** Slice 14's single shared `Services.realtimeClient` is killed permanently by the first `close()` (its `closed` flag + `shutdownNow()` are one-shot), so the floor map loses live push after the first navigation away. A per-screen factory fixes that and lets the order screen (Task 2) own its own socket.

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/NoopRealtimeClient.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/RealtimeClients.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/TableMapController.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/RealtimeClientsTest.java`

**Interfaces:**
- Consumes: existing `RealtimeClient { void connect(Runnable); void close(); }`, `WebSocketRealtimeClient(String httpBaseUrl, String path, SessionManager)`, `TerminalConfig.realtimeEnabled()` / `realtimePath()` / `serverBaseUrl()`, `SessionManager`.
- Produces: `NoopRealtimeClient` (no-op `RealtimeClient`); `RealtimeClients.create(TerminalConfig, SessionManager) -> RealtimeClient`; `Services.newRealtimeClient() -> RealtimeClient` (consumed by TableMapController now and OrderController in Task 2).

- [ ] **Step 1: Write the failing factory test**

`pos-terminal/src/test/java/com/company/pos/terminal/api/RealtimeClientsTest.java`:

```java
package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import com.company.pos.terminal.config.TerminalConfig;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class RealtimeClientsTest {

    private TerminalConfig config(boolean enabled) {
        Properties p = new Properties();
        p.setProperty("realtime.enabled", Boolean.toString(enabled));
        return TerminalConfig.from(p);
    }

    @Test
    void enabledConfigYieldsAFreshWebSocketClientEachCall() {
        TerminalConfig c = config(true);
        SessionManager session = new SessionManager();
        RealtimeClient a = RealtimeClients.create(c, session);
        RealtimeClient b = RealtimeClients.create(c, session);
        assertInstanceOf(WebSocketRealtimeClient.class, a);
        assertNotSame(a, b, "each screen must get its own client instance");
    }

    @Test
    void disabledConfigYieldsANoopClient() {
        RealtimeClient client = RealtimeClients.create(config(false), new SessionManager());
        assertInstanceOf(NoopRealtimeClient.class, client);
        // Safe no-ops: neither call throws.
        client.connect(() -> {});
        client.close();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml test -Dtest=RealtimeClientsTest`
Expected: FAIL — `RealtimeClients` / `NoopRealtimeClient` do not exist.

- [ ] **Step 3: Create `NoopRealtimeClient`**

`pos-terminal/src/main/java/com/company/pos/terminal/api/NoopRealtimeClient.java`:

```java
package com.company.pos.terminal.api;

/** A {@link RealtimeClient} that does nothing — returned when {@code realtime.enabled=false} so
 *  consumers can call {@code connect}/{@code close} unconditionally without a feature-flag check. */
public final class NoopRealtimeClient implements RealtimeClient {

    @Override
    public void connect(Runnable onMessage) {
        // no live push when disabled
    }

    @Override
    public void close() {
        // nothing to release
    }
}
```

- [ ] **Step 4: Create `RealtimeClients`**

`pos-terminal/src/main/java/com/company/pos/terminal/api/RealtimeClients.java`:

```java
package com.company.pos.terminal.api;

import com.company.pos.terminal.config.TerminalConfig;

/**
 * Builds a {@link RealtimeClient} per call. Each screen owns its own instance (connect on enter,
 * close on leave), because {@link WebSocketRealtimeClient#close()} is one-shot — a shared instance
 * dies after the first screen leaves. Returns a {@link NoopRealtimeClient} when push is disabled.
 */
public final class RealtimeClients {

    private RealtimeClients() {
    }

    public static RealtimeClient create(TerminalConfig config, SessionManager session) {
        if (!config.realtimeEnabled()) {
            return new NoopRealtimeClient();
        }
        return new WebSocketRealtimeClient(config.serverBaseUrl(), config.realtimePath(), session);
    }
}
```

- [ ] **Step 5: Run the factory test — verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml test -Dtest=RealtimeClientsTest`
Expected: PASS.

- [ ] **Step 6: Switch `Services` from a shared field to a factory method**

In `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`:

- Add import: `import com.company.pos.terminal.api.RealtimeClients;` (keep the existing `import com.company.pos.terminal.api.RealtimeClient;`). Remove `import com.company.pos.terminal.api.WebSocketRealtimeClient;` if present (no longer referenced here).
- Delete the field declaration `public final RealtimeClient realtimeClient;`.
- Delete its construction line in the constructor:
  ```java
        this.realtimeClient = new WebSocketRealtimeClient(
                config.serverBaseUrl(), config.realtimePath(), session);
  ```
- Add this method to the class (after the constructor):
  ```java
    /** A fresh realtime push client for one screen's lifecycle (connect on enter, close on leave).
     *  Returns a no-op client when {@code realtime.enabled=false}. */
    public RealtimeClient newRealtimeClient() {
        return RealtimeClients.create(config, session);
    }
  ```

- [ ] **Step 7: Point `TableMapController` at a per-controller client**

In `pos-terminal/src/main/java/com/company/pos/terminal/view/TableMapController.java`:

- Add import: `import com.company.pos.terminal.api.RealtimeClient;`
- Add a field beside `private Timeline poller;`:
  ```java
    private RealtimeClient realtime;
  ```
- In `initialize()`, replace the existing block:
  ```java
        if (services.config.realtimeEnabled()) {
            services.realtimeClient.connect(this::refresh);
        }
  ```
  with:
  ```java
        realtime = services.newRealtimeClient();
        realtime.connect(this::refresh);
  ```
- In `onLeave()`, replace `services.realtimeClient.close();` with:
  ```java
        if (realtime != null) {
            realtime.close();
        }
  ```

- [ ] **Step 8: Run the full terminal suite**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml clean test`
Expected: PASS — `RealtimeClientsTest` green; existing `TableMapViewModelTest`, `WebSocketRealtimeClientTest`, `TerminalConfigTest`, and the rest still green. (No `Services.realtimeClient` references remain — the compile confirms it.)

- [ ] **Step 9: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/NoopRealtimeClient.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/RealtimeClients.java \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/TableMapController.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/RealtimeClientsTest.java
git commit -m "feat(terminal): per-screen RealtimeClient factory; fix floor-map re-visit push

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Order-screen stale detection (re-check on ping, lock + banner)

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/OrderViewModel.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/OrderController.java`
- Modify: `pos-terminal/src/main/resources/fxml/order.fxml`
- Modify: `pos-terminal/src/main/resources/css/app.css`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/OrderViewModelTest.java` (append)
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java` (append)

**Interfaces:**
- Consumes: `Services.newRealtimeClient()` (Task 1), `DiningApi.order(UUID)`, `OrderView.status()` (String `OPEN`/`CLOSED`/`VOIDED`), `SubtotalCalculator.estimate`, `FxTasks.run`.
- Produces: `OrderViewModel.RecheckResult` (enum `OPEN`/`STALE`/`UNKNOWN`) and `OrderViewModel.recheck() -> RecheckResult`; `OrderController implements Navigator.Screen` with a stale-lock.

- [ ] **Step 1: Write the failing VM tests**

Append to `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/OrderViewModelTest.java`. Add these imports at the top if not already present: `import java.util.ArrayDeque;` and `import java.util.Deque;`. Add a helper and the tests inside the class:

```java
    /** An order in a given lifecycle status with no lines. */
    private OrderView orderStatus(String status) {
        return new OrderView(
                orderId, tableId, "DINE_IN", status, "clerk", Instant.now(), null, null, List.of());
    }

    /** DiningApi whose order() returns `load` on the first call, then `recheck` on every call after. */
    private DiningApi diningReturning(OrderView load, OrderView recheck) {
        return new DiningApi(null) {
            private boolean loaded;
            @Override
            public OrderView order(UUID id) {
                if (!loaded) {
                    loaded = true;
                    return load;
                }
                return recheck;
            }
        };
    }

    @Test
    void recheckOpenRefreshesLinesFromServer() {
        OrderLineView added =
                new OrderLineView(
                        UUID.randomUUID(), "BURGER", new BigDecimal("1"), null, "MAIN", null,
                        List.of());
        OrderViewModel vm =
                new OrderViewModel(diningReturning(orderWith(List.of()), orderWith(List.of(added))),
                        cache());
        vm.load(orderId);
        assertEquals(0, vm.lines().size());
        assertEquals(OrderViewModel.RecheckResult.OPEN, vm.recheck());
        assertEquals(1, vm.lines().size());
    }

    @Test
    void recheckVoidedReturnsStale() {
        OrderViewModel vm =
                new OrderViewModel(diningReturning(orderWith(List.of()), orderStatus("VOIDED")),
                        cache());
        vm.load(orderId);
        assertEquals(OrderViewModel.RecheckResult.STALE, vm.recheck());
        assertEquals("VOIDED", vm.currentOrder().status());
    }

    @Test
    void recheckClosedReturnsStale() {
        OrderViewModel vm =
                new OrderViewModel(diningReturning(orderWith(List.of()), orderStatus("CLOSED")),
                        cache());
        vm.load(orderId);
        assertEquals(OrderViewModel.RecheckResult.STALE, vm.recheck());
    }

    @Test
    void recheckApiExceptionReturnsUnknownAndKeepsState() {
        DiningApi dining =
                new DiningApi(null) {
                    private boolean loaded;
                    @Override
                    public OrderView order(UUID id) {
                        if (!loaded) {
                            loaded = true;
                            return orderWith(List.of());
                        }
                        throw new ApiException(0, null, "Cannot reach store server");
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        assertEquals(OrderViewModel.RecheckResult.UNKNOWN, vm.recheck());
        assertEquals("OPEN", vm.currentOrder().status());
    }

    @Test
    void recheckHoldsLineRefreshUntilDispatcherDrains() {
        OrderLineView added =
                new OrderLineView(
                        UUID.randomUUID(), "BURGER", new BigDecimal("1"), null, "MAIN", null,
                        List.of());
        Deque<Runnable> queue = new ArrayDeque<>();
        OrderViewModel vm =
                new OrderViewModel(diningReturning(orderWith(List.of()), orderWith(List.of(added))),
                        cache(), queue::add);
        vm.load(orderId);
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
        assertEquals(0, vm.lines().size());
        // Return value is correct immediately; the observable update is still queued.
        assertEquals(OrderViewModel.RecheckResult.OPEN, vm.recheck());
        assertEquals(0, vm.lines().size());
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
        assertEquals(1, vm.lines().size());
    }
```

> `orderWith(...)`, `orderId`, `tableId`, `cache()` already exist in this test class (verified). If any of these helper names differ on disk, adapt to the real ones.

- [ ] **Step 2: Run them to verify they fail**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml test -Dtest=OrderViewModelTest`
Expected: FAIL — `RecheckResult` / `recheck()` do not exist.

- [ ] **Step 3: Add `recheck()` to `OrderViewModel`**

In `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/OrderViewModel.java`, add the nested enum and method (place after `load(...)`):

```java
    /** Outcome of a post-ping re-check: the order is still OPEN (lines refreshed), it has gone to a
     *  terminal state (VOIDED/CLOSED → STALE), or the check could not be completed (UNKNOWN). */
    public enum RecheckResult { OPEN, STALE, UNKNOWN }

    /**
     * Re-reads the order after a floor-change ping. OPEN → refreshes {@link #lines()} +
     * {@link #subtotalText()} and returns OPEN. A non-OPEN status → returns STALE (the current
     * order is updated so the caller can read its status; displayed lines are left intact for
     * locking). An {@link ApiException} (network or 404) → returns UNKNOWN without locking.
     * Synchronous on the calling thread — the controller runs it off the FX thread.
     */
    public RecheckResult recheck() {
        OrderView refreshed;
        try {
            refreshed = dining.order(order.id());
        } catch (ApiException e) {
            return RecheckResult.UNKNOWN;
        }
        order = refreshed;
        if (!"OPEN".equals(refreshed.status())) {
            return RecheckResult.STALE;
        }
        List<OrderLineView> nextLines = refreshed.lines() == null ? List.of() : refreshed.lines();
        String subtotal = SubtotalCalculator.estimate(refreshed, cache).toPlainString();
        ui.accept(() -> {
            lines.setAll(nextLines);
            subtotalText.set(subtotal);
        });
        return RecheckResult.OPEN;
    }
```

- [ ] **Step 4: Run the VM tests — verify they pass**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml test -Dtest=OrderViewModelTest`
Expected: PASS.

- [ ] **Step 5: Add the stale banner to `order.fxml`**

In `pos-terminal/src/main/resources/fxml/order.fxml`, add this line immediately AFTER the existing `errorLabel` line (line ~30):

```xml
      <!-- Stale banner: shown when the order was changed on another terminal; the screen locks. -->
      <Label fx:id="staleBanner" styleClass="stale-banner" wrapText="true" maxWidth="Infinity" managed="false" visible="false"/>
```

- [ ] **Step 6: Add the `.stale-banner` style to `app.css`**

Append to `pos-terminal/src/main/resources/css/app.css` (near the other banner styles):

```css
/* Slice 15 — order stale-detection banner (amber-emphasis, uses existing tokens). */
.stale-banner {
    -fx-background-color: derive(-fx-accent, 60%);
    -fx-text-fill: -fx-ink;
    -fx-border-color: -fx-accent;
    -fx-border-width: 1;
    -fx-background-radius: 8;
    -fx-border-radius: 8;
    -fx-padding: 10 14;
    -fx-font-weight: bold;
}
```

- [ ] **Step 7: Add the CSS contract assertion**

Append a test method inside `pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java`:

```java
    @Test
    void definesSliceFifteenStaleClass() throws Exception {
        assertTrue(css().contains(".stale-banner"), "missing style class: .stale-banner");
    }
```

- [ ] **Step 8: Wire the order screen to re-check on every ping and lock when stale**

In `pos-terminal/src/main/java/com/company/pos/terminal/view/OrderController.java`:

- Add imports:
  ```java
  import com.company.pos.terminal.api.RealtimeClient;
  import java.util.concurrent.atomic.AtomicBoolean;
  ```
- Change the class declaration to implement the screen lifecycle:
  ```java
  public class OrderController implements Navigator.Screen {
  ```
- Add fields (beside the other private fields):
  ```java
    private RealtimeClient realtime;
    private final AtomicBoolean checkInFlight = new AtomicBoolean(false);
    private boolean stale;
  ```
- Add the `@FXML` field beside `errorLabel`:
  ```java
    @FXML private Label staleBanner;
  ```
- At the END of `afterCatalogLoaded()` (after the `FxTasks.run(() -> vm.load(orderId), ...)` block), connect a per-screen client:
  ```java
        realtime = services.newRealtimeClient();
        realtime.connect(this::onFloorPing);
  ```
- Add these methods:
  ```java
    /** A floor ping arrived (on the WebSocket thread): re-check THIS order's status off the FX
     *  thread. Coalesced — at most one check in flight, and none once the screen is stale. */
    private void onFloorPing() {
        if (stale || !checkInFlight.compareAndSet(false, true)) {
            return;
        }
        OrderViewModel.RecheckResult[] holder = { OrderViewModel.RecheckResult.UNKNOWN };
        FxTasks.run(
                () -> holder[0] = vm.recheck(),
                () -> {
                    checkInFlight.set(false);
                    if (holder[0] == OrderViewModel.RecheckResult.STALE) {
                        lockStale();
                    }
                },
                err -> {
                    checkInFlight.set(false);
                    LOG.log(System.Logger.Level.ERROR, "Order re-check failed", err);
                });
    }

    /** Lock every editing control; keep "Back to tables" live; show the stale banner. */
    private void lockStale() {
        stale = true;
        lineBox.setDisable(true);
        categoryTabs.setDisable(true);
        fireButton.setDisable(true);
        voidButton.setDisable(true);
        moveButton.setDisable(true);
        mergeButton.setDisable(true);
        splitButton.setDisable(true);
        payButton.setDisable(true);
        String status = vm.currentOrder() == null ? null : vm.currentOrder().status();
        staleBanner.setText(staleMessage(status));
        staleBanner.setManaged(true);
        staleBanner.setVisible(true);
    }

    private static String staleMessage(String status) {
        String what = "changed";
        if ("VOIDED".equals(status)) {
            what = "voided";
        } else if ("CLOSED".equals(status)) {
            what = "paid/closed";
        }
        return "This order was " + what + " on another terminal. Return to tables.";
    }

    @Override
    public void onLeave() {
        if (realtime != null) {
            realtime.close();
        }
    }
  ```

> Note: `errorLabel.text` is bound to `vm.errorMessage()`, but `staleBanner` is driven directly here (not bound), so `setText`/`setVisible`/`setManaged` on it are safe. `backButton` is intentionally left enabled — it is the return path.

- [ ] **Step 9: Run the full terminal suite**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml clean test`
Expected: PASS — the 5 new `OrderViewModelTest` recheck tests, the new `AppCssTest` assertion, and all existing tests green.

- [ ] **Step 10: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/OrderViewModel.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/OrderController.java \
        pos-terminal/src/main/resources/fxml/order.fxml \
        pos-terminal/src/main/resources/css/app.css \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/OrderViewModelTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java
git commit -m "feat(terminal): order-screen stale detection via /ws/floor (lock + banner)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Final verification (after both tasks)

- [ ] `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml clean test` (full terminal suite green).
- [ ] Manual E2E (documented, not automated): backend `--spring.profiles.active=embedded,dev`; two terminals (`./mvnw -f pos-terminal/pom.xml javafx:run`), log in `manager`/`manager`. (1) Terminal A opens table T1's order and adds a line; Terminal B voids T1's order (or merges it away) → within ~1s A's screen locks with the amber banner and only "Back to tables" responds. (2) Terminal A: floor map → open an order → back to floor map → confirm the floor map STILL receives live updates when another terminal changes a table (the slice-14 re-visit bug is fixed). (3) With `-Drealtime.enabled=false`, both screens work with polling only and neither throws.

## Self-review notes (author)

- **Spec coverage:** routing = re-check own order on any ping (Task 2 `onFloorPing`/`recheck`, no backend); stale UX = lock + banner + manual return (Task 2 `lockStale`, backButton kept live); on-OPEN = refresh lines (Task 2 `recheck` OPEN branch); lifecycle = per-screen factory + Noop (Task 1), fixing the floor-map re-visit bug (Task 1 Step 7). Terminal-only, no boundary/migration/config/Maven change.
- **Type consistency:** `RecheckResult { OPEN, STALE, UNKNOWN }` used identically in the VM and the controller holder; `OrderView.status()` compared against the literal `"OPEN"`/`"VOIDED"`/`"CLOSED"` (matches the DTO's documented values); `RealtimeClients.create(TerminalConfig, SessionManager)` signature matches its test and `Services.newRealtimeClient()`; `WebSocketRealtimeClient(serverBaseUrl, realtimePath, session)` matches the slice-14 constructor.
- **Confirm-before-coding anchors (flagged in-task, not placeholders):** `OrderViewModelTest` helper names `orderWith`/`orderId`/`tableId`/`cache()` (Task 2 Step 1 — verified present, adapt if renamed); `order.fxml` `errorLabel` insertion point (Task 2 Step 5); `app.css` token names `-fx-accent`/`-fx-ink` (verified in `AppCssTest`).
- **FX-threading:** blocking `recheck()` runs in the `FxTasks` work lambda; lock applied in `onDone` via holder; OPEN line refresh via `ui.accept`; `checkInFlight` cleared in both `onDone` and `onError`; deferred-dispatcher regression test present.
