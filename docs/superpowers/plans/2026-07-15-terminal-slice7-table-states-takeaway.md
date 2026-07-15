# Slice 7 — Table States + Takeaway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the terminal table map richer dine-in states (Free / Seated / Active + a dwell attention flag) and a `Tables | Takeaway` segmented view, where takeaway is a `QUICK_SERVICE` order opened against a counter-labelled table — with the one backend change (serviceType on the open-order summary) that makes it work.

**Architecture:** No new persistence concept and no migration. A takeaway is an ordinary `QUICK_SERVICE` `DiningOrder` against a counter-labelled `DiningTable`; the existing quote/close/split paths already skip the service charge for non-`DINE_IN`. The only backend change is adding `serviceType` to the `listOpenOrders` projection so the terminal can classify open orders. All table-state derivation is presentation logic in the terminal ViewModel over server-supplied summary fields.

**Tech Stack:** Java 21, Spring Boot 3.3 (backend, Maven root reactor), JavaFX 21 (pos-terminal, separate Maven build), JUnit 5, AssertJ, MockMvc, an in-process `StubServer` for terminal API tests.

## Global Constraints

- **Two builds.** Backend: `./mvnw test`. Terminal (NOT in the root reactor): `./mvnw -f pos-terminal/pom.xml test`. Always `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` first (system default is 17).
- **Repo path contains spaces and `&`** — always quote it in shell.
- **No schema migration in this slice.** `serviceType` on the summary is a projection of the existing non-null `service_type` column.
- **Terminal holds no business rules and computes no money.** State derivation is presentation logic over summary fields only.
- **MVVM sync-VM convention.** ViewModel methods run synchronously on the calling thread; the controller runs them off the FX thread via `FxTasks`. Plain fields are control-flow truth; the only observable written off the FX thread is `errorMessage`, written inside `ui.accept(...)`. An async-dispatcher regression test is mandatory for the VM.
- **`now` is injected** into the ViewModel as `Supplier<Instant> clock` (default `Instant::now`). Never call `Instant.now()` inline in VM logic.
- **DTOs mirror server JSON field-for-field**; enums cross as Strings (`"DINE_IN"`, `"QUICK_SERVICE"`); DTO records carry `@JsonIgnoreProperties(ignoreUnknown = true)`.
- **CSS uses existing emerald tokens / `derive()` only**; new tap targets ≥ 56px; state is never signalled by colour alone (caption text carries the state word, matching the existing `.table-cell` comment).
- After any change: run the affected module's tests **and** `ModularityTests` (`./mvnw test -Dtest=ModularityTests`).
- Commit trailer on every commit: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- **Never stage or commit the pre-existing `M CLAUDE.md`.** Stage only the files each step names.

---

### Task 1: Backend — `serviceType` on the open-order summary

**Files:**
- Modify: `src/main/java/com/company/pos/dining/api/OpenOrderView.java`
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java:141-147`
- Test: `src/test/java/com/company/pos/dining/DiningOrderServiceTest.java` (add a method)
- Test (create): `src/test/java/com/company/pos/dining/DiningOpenOrdersControllerTest.java`

**Interfaces:**
- Produces: `dining.api.OpenOrderView` record gains a trailing field `ServiceType serviceType`. New full shape: `OpenOrderView(UUID orderId, UUID tableId, String tableLabel, Instant openedAt, int lineCount, ServiceType serviceType)`. Jackson serializes `serviceType` to its enum name (`"DINE_IN"` / `"QUICK_SERVICE"`).

- [ ] **Step 1: Write the failing service test**

Add to `src/test/java/com/company/pos/dining/DiningOrderServiceTest.java` (the class already imports `ServiceType`, `OpenOrderView`, `RegisterTableCommand`, `OpenOrderCommand`, has the `freshTable` helper and `@Autowired DiningService dining`):

```java
    @Test
    void listOpenOrdersCarriesServiceType() {
        UUID dineTable = freshTable("SVC-DINE-" + UUID.randomUUID());
        UUID counterTable = freshTable("SVC-CNT-" + UUID.randomUUID());
        UUID dineOrder = dining.openOrder(
                new OpenOrderCommand(dineTable, ServiceType.DINE_IN), "alice").id();
        UUID takeawayOrder = dining.openOrder(
                new OpenOrderCommand(counterTable, ServiceType.QUICK_SERVICE), "alice").id();

        var byId = dining.listOpenOrders().stream()
                .collect(java.util.stream.Collectors.toMap(OpenOrderView::orderId, o -> o));

        assertThat(byId.get(dineOrder).serviceType()).isEqualTo(ServiceType.DINE_IN);
        assertThat(byId.get(takeawayOrder).serviceType()).isEqualTo(ServiceType.QUICK_SERVICE);
    }
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; cd "/Users/zuhairahamed/Desktop/Research & Development/POS" && ./mvnw test -Dtest=DiningOrderServiceTest#listOpenOrdersCarriesServiceType`
Expected: FAIL — compilation error, `cannot find symbol: method serviceType()` on `OpenOrderView`.

- [ ] **Step 3: Add the field to `OpenOrderView`**

Rewrite `src/main/java/com/company/pos/dining/api/OpenOrderView.java`:

```java
package com.company.pos.dining.api;

import java.time.Instant;
import java.util.UUID;

public record OpenOrderView(UUID orderId, UUID tableId, String tableLabel, Instant openedAt,
        int lineCount, ServiceType serviceType) {
}
```

- [ ] **Step 4: Populate it in the projection**

In `src/main/java/com/company/pos/dining/application/DefaultDiningService.java`, the `listOpenOrders()` body (around line 141) currently maps to a 5-arg `OpenOrderView`. Change the mapping to pass the service type:

```java
    @Override
    @Transactional(readOnly = true)
    public List<OpenOrderView> listOpenOrders() {
        return orders.findByStatus(OrderStatus.OPEN).stream()
                .map(o -> new OpenOrderView(o.getId(), o.getTableId(),
                        tables.findById(o.getTableId()).map(DiningTable::getLabel).orElse(null),
                        o.getOpenedAt(), o.getLines().size(), o.getServiceType()))
                .toList();
    }
```

- [ ] **Step 5: Run the service test to confirm it passes**

Run: `./mvnw test -Dtest=DiningOrderServiceTest`
Expected: PASS (all methods, including the new one).

- [ ] **Step 6: Write the failing web test**

Create `src/test/java/com/company/pos/dining/DiningOpenOrdersControllerTest.java`:

```java
package com.company.pos.dining;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.ServiceType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Transactional
class DiningOpenOrdersControllerTest {

    @Autowired MockMvc mvc;
    @Autowired DiningService dining;

    @Test
    void listOpenOrdersJsonIncludesServiceType() throws Exception {
        UUID table = dining.registerTable(
                new RegisterTableCommand("WEB-" + UUID.randomUUID(), 4)).id();
        UUID orderId = dining.openOrder(
                new OpenOrderCommand(table, ServiceType.QUICK_SERVICE), "cashier1").id();

        mvc.perform(get("/dining/orders").with(jwt().jwt(j -> j.subject("cashier1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.orderId=='" + orderId + "')].serviceType")
                        .value(org.hamcrest.Matchers.hasItem("QUICK_SERVICE")));
    }
}
```

- [ ] **Step 7: Run the web test**

Run: `./mvnw test -Dtest=DiningOpenOrdersControllerTest`
Expected: PASS.

- [ ] **Step 8: Run the module boundary check**

Run: `./mvnw test -Dtest=ModularityTests`
Expected: PASS (no new cross-module dependency introduced).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/company/pos/dining/api/OpenOrderView.java \
        src/main/java/com/company/pos/dining/application/DefaultDiningService.java \
        src/test/java/com/company/pos/dining/DiningOrderServiceTest.java \
        src/test/java/com/company/pos/dining/DiningOpenOrdersControllerTest.java
git commit -m "feat(dining): expose serviceType on the open-order summary

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Backend — `DevDiningSeeder` (dine-in + counter tables)

**Files:**
- Create: `src/main/java/com/company/pos/dining/application/DevDiningSeeder.java`
- Test (create): `src/test/java/com/company/pos/dining/DevDiningSeederTest.java`

**Interfaces:**
- Consumes: `DiningService.registerTable(RegisterTableCommand)` and `DiningService.listTables()` (existing).
- Produces: under the `dev` profile only, a freshly started backend has tables `T1`–`T6` and `Counter 1`–`Counter 3`. Counter labels use the literal prefix `"Counter "` (must match the terminal's `dining.takeaway.label-prefix` default — see Task 4).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/dining/DevDiningSeederTest.java`:

```java
package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.TableView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles({"embedded", "dev"})
class DevDiningSeederTest {

    @Autowired DiningService dining;

    @Test
    void seedsDineInAndCounterTables() {
        var labels = dining.listTables().stream().map(TableView::label).toList();
        assertThat(labels).contains("T1", "T6", "Counter 1", "Counter 3");
        long counters = labels.stream().filter(l -> l.startsWith("Counter ")).count();
        assertThat(counters).isEqualTo(3);
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./mvnw test -Dtest=DevDiningSeederTest`
Expected: FAIL — assertion error, the seeded floor is empty (no `DevDiningSeeder` yet).

- [ ] **Step 3: Write the seeder**

Create `src/main/java/com/company/pos/dining/application/DevDiningSeeder.java`:

```java
package com.company.pos.dining.application;

import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.RegisterTableCommand;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev-only seeder: registers a small dining floor so a freshly started backend is demoable.
 * There is no table-registration UI in the terminal, so without this a fresh dev run has an
 * empty floor. Seeds six dine-in tables (T1..T6) and three counter tables (Counter 1..3);
 * counter labels use the same prefix the terminal defaults to ({@code "Counter "}), so the
 * terminal buckets them as takeaway stations. Active only under the {@code dev} profile;
 * idempotent (skips when any table already exists). Module-local (touches only this module's
 * api facade), so it does not affect {@code ModularityTests}.
 */
@Component
@Profile("dev")
class DevDiningSeeder implements ApplicationRunner {

    private static final System.Logger LOG = System.getLogger(DevDiningSeeder.class.getName());

    private final DiningService dining;

    DevDiningSeeder(DiningService dining) {
        this.dining = dining;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!dining.listTables().isEmpty()) {
            return;
        }
        for (int i = 1; i <= 6; i++) {
            dining.registerTable(new RegisterTableCommand("T" + i, i % 2 == 0 ? 4 : 2));
        }
        for (int i = 1; i <= 3; i++) {
            dining.registerTable(new RegisterTableCommand("Counter " + i, 1));
        }
        LOG.log(System.Logger.Level.INFO,
                "[dev-seed] registered 6 dine-in tables (T1..T6) and 3 counters (Counter 1..3)");
    }
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `./mvnw test -Dtest=DevDiningSeederTest`
Expected: PASS.

- [ ] **Step 5: Run the module boundary check**

Run: `./mvnw test -Dtest=ModularityTests`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/dining/application/DevDiningSeeder.java \
        src/test/java/com/company/pos/dining/DevDiningSeederTest.java
git commit -m "feat(dining): dev seeder for dine-in + counter tables

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Terminal — `OpenOrderView.serviceType` DTO + `openOrder(tableId, serviceType)` overload

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/OpenOrderView.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java:43-46`
- Modify: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/TableMapViewModelTest.java:35,84` (add the serviceType arg to the two existing `new OpenOrderView(...)` calls so the module compiles)
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/DiningApiTest.java`

**Interfaces:**
- Consumes: the backend JSON contract from Task 1 (`serviceType` string on each open-order summary).
- Produces:
  - Terminal DTO `OpenOrderView(UUID orderId, UUID tableId, String tableLabel, Instant openedAt, int lineCount, String serviceType)`.
  - `DiningApi.openOrder(UUID tableId)` (existing, now delegates `"DINE_IN"`) and new `DiningApi.openOrder(UUID tableId, String serviceType)` returning `OrderView`.

- [ ] **Step 1: Write the failing API tests**

In `pos-terminal/src/test/java/com/company/pos/terminal/api/DiningApiTest.java`, extend the existing `openOrdersParsesOpenOrderSummaries` test's JSON and assertions, and add a new takeaway-open test. Replace the existing `openOrdersParsesOpenOrderSummaries` method body with:

```java
    @Test
    void openOrdersParsesOpenOrderSummaries() throws Exception {
        String json = "[{\"orderId\":\"33333333-3333-3333-3333-333333333333\","
                + "\"tableId\":\"44444444-4444-4444-4444-444444444444\",\"tableLabel\":\"T1\","
                + "\"openedAt\":\"2026-07-11T10:00:00Z\",\"lineCount\":3,"
                + "\"serviceType\":\"QUICK_SERVICE\"}]";
        try (StubServer stub = new StubServer(200, json, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            List<OpenOrderView> orders = api.openOrders();
            assertEquals(1, orders.size());
            assertEquals(ORDER_ID, orders.get(0).orderId());
            assertEquals("T1", orders.get(0).tableLabel());
            assertEquals(3, orders.get(0).lineCount());
            assertEquals("QUICK_SERVICE", orders.get(0).serviceType());
            assertEquals("GET", stub.lastMethod);
            assertEquals("/dining/orders", stub.lastPath);
        }
    }

    @Test
    void openOrderPostsQuickServiceWhenRequested() throws Exception {
        try (StubServer stub = new StubServer(201, ORDER_JSON, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            api.openOrder(TABLE_ID, "QUICK_SERVICE");
            assertEquals("POST", stub.lastMethod);
            assertEquals("/dining/orders", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"serviceType\":\"QUICK_SERVICE\""));
            assertTrue(stub.lastBody.contains("tableId"));
        }
    }
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; cd "/Users/zuhairahamed/Desktop/Research & Development/POS" && ./mvnw -f pos-terminal/pom.xml test -Dtest=DiningApiTest`
Expected: FAIL — compilation error: `serviceType()` not found on `OpenOrderView`, and `openOrder(UUID, String)` not found.

- [ ] **Step 3: Add `serviceType` to the terminal DTO**

Rewrite `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/OpenOrderView.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

/**
 * Summary of an open dining order, as returned by {@code GET /dining/orders}.
 * {@code serviceType} mirrors the server's {@code ServiceType} ({@code DINE_IN} /
 * {@code QUICK_SERVICE}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenOrderView(UUID orderId, UUID tableId, String tableLabel, Instant openedAt,
        int lineCount, String serviceType) {
}
```

- [ ] **Step 4: Add the `openOrder` overload**

In `pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java`, replace the existing `openOrder` method (lines 43-46) with:

```java
    public OrderView openOrder(UUID tableId) {
        return openOrder(tableId, "DINE_IN");
    }

    /** Opens a dining order of the given service type ({@code "DINE_IN"} or {@code "QUICK_SERVICE"}). */
    public OrderView openOrder(UUID tableId, String serviceType) {
        return client.post("/dining/orders", new OpenOrderRequest(tableId, serviceType),
                new TypeReference<OrderView>() {});
    }
```

- [ ] **Step 5: Fix the two existing OpenOrderView call sites so the module compiles**

In `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/TableMapViewModelTest.java`, both occurrences (lines 35 and 84) read `new OpenOrderView(openOrderId, t1, "T1", Instant.now(), 0)`. Append the serviceType arg to each:

```java
                return List.of(new OpenOrderView(openOrderId, t1, "T1", Instant.now(), 0, "DINE_IN"));
```

(Both call sites become identical; use `replace_all`.)

- [ ] **Step 6: Run the API tests to confirm they pass**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=DiningApiTest,TableMapViewModelTest`
Expected: PASS (DiningApiTest new assertions green; TableMapViewModelTest still green — its existing behaviour is unchanged because lineCount 0 → occupied via SEATED, which Task 5 formalizes; but at THIS task `TableCell` is unchanged, so the module just needs to compile and the existing assertions to hold).

Note: `TableMapViewModelTest` compiles and passes here because `TableCell` and `TableMapViewModel` are untouched in this task — only the DTO gained a field, which its constructor calls now supply.

- [ ] **Step 7: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/dto/OpenOrderView.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/DiningApiTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/TableMapViewModelTest.java
git commit -m "feat(terminal): OpenOrderView.serviceType + openOrder(serviceType) overload

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Terminal — config keys for counter prefix and dwell threshold

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/config/TerminalConfig.java`
- Modify: `pos-terminal/src/main/resources/pos-terminal.properties`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/config/TerminalConfigTest.java`

**Interfaces:**
- Produces: `TerminalConfig.takeawayLabelPrefix()` → `String` (default `"Counter "`); `TerminalConfig.dwellAttention()` → `java.time.Duration` (default 45 minutes, from key `dining.dwell.attention.minutes`).

- [ ] **Step 1: Write the failing config tests**

Add to `pos-terminal/src/test/java/com/company/pos/terminal/config/TerminalConfigTest.java`:

```java
    @Test
    void takeawayPrefixAndDwellDefaults() {
        TerminalConfig cfg = TerminalConfig.from(new Properties());
        assertEquals("Counter ", cfg.takeawayLabelPrefix());
        assertEquals(java.time.Duration.ofMinutes(45), cfg.dwellAttention());
    }

    @Test
    void takeawayPrefixAndDwellFromProperties() {
        Properties p = new Properties();
        p.setProperty("dining.takeaway.label-prefix", "TA-");
        p.setProperty("dining.dwell.attention.minutes", "20");
        TerminalConfig cfg = TerminalConfig.from(p);
        assertEquals("TA-", cfg.takeawayLabelPrefix());
        assertEquals(java.time.Duration.ofMinutes(20), cfg.dwellAttention());
    }

    @Test
    void nonNumericDwellThrowsWithBadValue() {
        Properties p = new Properties();
        p.setProperty("dining.dwell.attention.minutes", "soon");
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> TerminalConfig.from(p));
        assertTrue(ex.getMessage().contains("soon"));
    }
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=TerminalConfigTest`
Expected: FAIL — compilation error: `takeawayLabelPrefix()` / `dwellAttention()` not found.

- [ ] **Step 3: Add the fields, parsing, and accessors**

In `pos-terminal/src/main/java/com/company/pos/terminal/config/TerminalConfig.java`:

Add the import at the top:
```java
import java.time.Duration;
```

Add two fields beside the existing ones:
```java
    private final String takeawayLabelPrefix;
    private final Duration dwellAttention;
```

In the private constructor, after the `reducedMotion` line, add:
```java
        this.takeawayLabelPrefix = p.getProperty("dining.takeaway.label-prefix", "Counter ");
        String rawDwell = p.getProperty("dining.dwell.attention.minutes", "45");
        try {
            this.dwellAttention = Duration.ofMinutes(Integer.parseInt(rawDwell));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "dining.dwell.attention.minutes must be an integer, got: " + rawDwell, e);
        }
```

In `load()`, add the two keys to the system-property overlay array:
```java
        for (String key : new String[]{"server.base-url", "terminal.id", "store.id",
                "poll.interval.seconds", "ui.reduced-motion",
                "dining.takeaway.label-prefix", "dining.dwell.attention.minutes"}) {
```

Add the accessors beside the others:
```java
    public String takeawayLabelPrefix() { return takeawayLabelPrefix; }
    public Duration dwellAttention() { return dwellAttention; }
```

- [ ] **Step 4: Add the properties defaults**

Append to `pos-terminal/src/main/resources/pos-terminal.properties`:

```properties
# Tables whose label starts with this prefix are takeaway counters (kept off the dine-in grid).
dining.takeaway.label-prefix=Counter 
# An open order past this many minutes shows a "long open" attention badge on the table map.
dining.dwell.attention.minutes=45
```

(Note the trailing space after `Counter ` in the prefix value — it must match the seeded labels `Counter 1`.)

- [ ] **Step 5: Run the config tests to confirm they pass**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=TerminalConfigTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/config/TerminalConfig.java \
        pos-terminal/src/main/resources/pos-terminal.properties \
        pos-terminal/src/test/java/com/company/pos/terminal/config/TerminalConfigTest.java
git commit -m "feat(terminal): config keys for takeaway prefix + dwell threshold

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Terminal — table-state model + takeaway bucketing in `TableMapViewModel`

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/TableCell.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/TakeawayRow.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/TableMapViewModel.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/TableMapViewModelTest.java`

**Interfaces:**
- Consumes: `OpenOrderView.serviceType()`, `DiningApi.openOrder(UUID, String)` (Task 3).
- Produces:
  - `enum TableCell.TableState { FREE, SEATED, ACTIVE }`.
  - `TableCell(UUID tableId, String label, TableState state, int openMinutes, boolean attention, UUID orderId)` with `boolean occupied()` returning `state != FREE`.
  - `TakeawayRow(UUID orderId, String label, int openMinutes, int lineCount, boolean attention)`.
  - `TableMapViewModel` gains `ObservableList<TakeawayRow> takeawayOrders()`, `UUID openTakeaway()`, and a full constructor `TableMapViewModel(DiningApi, Consumer<Runnable> ui, String counterPrefix, Duration dwellThreshold, Supplier<Instant> clock)`. Existing 1-arg and 2-arg constructors delegate with defaults `"Counter "`, `Duration.ofMinutes(45)`, `Instant::now`.

- [ ] **Step 1: Write the failing tests**

Replace the whole body of `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/TableMapViewModelTest.java` with the version below. It keeps the existing behaviours and adds state/attention/bucketing/openTakeaway/async tests. (Full file — do not merge by hand.)

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.DiningApi;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.dto.OpenOrderView;
import com.company.pos.terminal.api.dto.OrderView;
import com.company.pos.terminal.api.dto.TableView;
import com.company.pos.terminal.viewmodel.TableCell.TableState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TableMapViewModelTest {

    private final UUID t1 = UUID.randomUUID();
    private final UUID t2 = UUID.randomUUID();
    private final UUID counter1 = UUID.randomUUID();
    private final UUID openOrderId = UUID.randomUUID();
    private final Instant base = Instant.parse("2026-07-15T12:00:00Z");
    private final Supplier<Instant> fixedClock = () -> base;

    private TableCell cell(TableMapViewModel vm, UUID tableId) {
        return vm.cells().stream().filter(c -> c.tableId().equals(tableId)).findFirst().orElseThrow();
    }

    private TableMapViewModel vm(DiningApi dining) {
        return new TableMapViewModel(dining, Runnable::run, "Counter ",
                Duration.ofMinutes(45), fixedClock);
    }

    /** Two dine-in tables; t1 has a seated (0-line) DINE_IN order opened "now", t2 is free. */
    private DiningApi diningWithOneSeated() {
        return new DiningApi(null) {
            @Override
            public List<TableView> tables() {
                return List.of(new TableView(t1, "T1", 4, true), new TableView(t2, "T2", 2, true));
            }

            @Override
            public List<OpenOrderView> openOrders() {
                return List.of(new OpenOrderView(openOrderId, t1, "T1", base, 0, "DINE_IN"));
            }
        };
    }

    @Test
    void refreshDerivesFreeAndSeatedStates() {
        TableMapViewModel vm = vm(diningWithOneSeated());
        vm.refresh();
        assertEquals(2, vm.cells().size());
        assertEquals(TableState.SEATED, cell(vm, t1).state());
        assertTrue(cell(vm, t1).occupied());
        assertEquals(openOrderId, cell(vm, t1).orderId());
        assertEquals(TableState.FREE, cell(vm, t2).state());
        assertFalse(cell(vm, t2).occupied());
        assertNull(cell(vm, t2).orderId());
    }

    @Test
    void refreshDerivesActiveStateWhenOrderHasLines() {
        DiningApi dining = new DiningApi(null) {
            @Override
            public List<TableView> tables() {
                return List.of(new TableView(t1, "T1", 4, true));
            }

            @Override
            public List<OpenOrderView> openOrders() {
                return List.of(new OpenOrderView(openOrderId, t1, "T1", base, 3, "DINE_IN"));
            }
        };
        TableMapViewModel vm = vm(dining);
        vm.refresh();
        assertEquals(TableState.ACTIVE, cell(vm, t1).state());
    }

    @Test
    void attentionFlagsOrdersPastDwellThreshold() {
        // Order opened 50 minutes before the clock; threshold is 45 → attention, openMinutes 50.
        Instant opened = base.minus(Duration.ofMinutes(50));
        DiningApi dining = new DiningApi(null) {
            @Override
            public List<TableView> tables() {
                return List.of(new TableView(t1, "T1", 4, true));
            }

            @Override
            public List<OpenOrderView> openOrders() {
                return List.of(new OpenOrderView(openOrderId, t1, "T1", opened, 2, "DINE_IN"));
            }
        };
        TableMapViewModel vm = vm(dining);
        vm.refresh();
        assertTrue(cell(vm, t1).attention());
        assertEquals(50, cell(vm, t1).openMinutes());
    }

    @Test
    void noAttentionJustUnderThreshold() {
        Instant opened = base.minus(Duration.ofMinutes(44));
        DiningApi dining = new DiningApi(null) {
            @Override
            public List<TableView> tables() {
                return List.of(new TableView(t1, "T1", 4, true));
            }

            @Override
            public List<OpenOrderView> openOrders() {
                return List.of(new OpenOrderView(openOrderId, t1, "T1", opened, 2, "DINE_IN"));
            }
        };
        TableMapViewModel vm = vm(dining);
        vm.refresh();
        assertFalse(cell(vm, t1).attention());
    }

    @Test
    void counterTablesExcludedFromGridAndTakeawayBucketed() {
        UUID takeawayOrder = UUID.randomUUID();
        DiningApi dining = new DiningApi(null) {
            @Override
            public List<TableView> tables() {
                return List.of(new TableView(t1, "T1", 4, true),
                        new TableView(counter1, "Counter 1", 1, true));
            }

            @Override
            public List<OpenOrderView> openOrders() {
                return List.of(
                        new OpenOrderView(openOrderId, t1, "T1", base, 1, "DINE_IN"),
                        new OpenOrderView(takeawayOrder, counter1, "Counter 1", base, 2,
                                "QUICK_SERVICE"));
            }
        };
        TableMapViewModel vm = vm(dining);
        vm.refresh();
        // Grid: only the dine-in table.
        assertEquals(1, vm.cells().size());
        assertEquals(t1, vm.cells().get(0).tableId());
        // Takeaway list: only the QUICK_SERVICE order.
        assertEquals(1, vm.takeawayOrders().size());
        TakeawayRow row = vm.takeawayOrders().get(0);
        assertEquals(takeawayOrder, row.orderId());
        assertEquals("Counter 1", row.label());
        assertEquals(2, row.lineCount());
    }

    @Test
    void openTakeawayOpensFirstFreeCounter() {
        UUID newId = UUID.randomUUID();
        UUID counter2 = UUID.randomUUID();
        UUID[] openedAgainst = new UUID[1];
        DiningApi dining = new DiningApi(null) {
            @Override
            public List<TableView> tables() {
                return List.of(new TableView(counter1, "Counter 1", 1, true),
                        new TableView(counter2, "Counter 2", 1, true));
            }

            @Override
            public List<OpenOrderView> openOrders() {
                // Counter 1 is busy → openTakeaway must pick Counter 2.
                return List.of(new OpenOrderView(UUID.randomUUID(), counter1, "Counter 1", base, 0,
                        "QUICK_SERVICE"));
            }

            @Override
            public OrderView openOrder(UUID tableId, String serviceType) {
                openedAgainst[0] = tableId;
                assertEquals("QUICK_SERVICE", serviceType);
                return new OrderView(newId, tableId, "QUICK_SERVICE", "OPEN", "clerk", base, null,
                        null, List.of());
            }
        };
        TableMapViewModel vm = vm(dining);
        assertEquals(newId, vm.openTakeaway());
        assertEquals(counter2, openedAgainst[0]);
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void openTakeawaySurfacesErrorWhenAllCountersBusy() {
        DiningApi dining = new DiningApi(null) {
            @Override
            public List<TableView> tables() {
                return List.of(new TableView(counter1, "Counter 1", 1, true));
            }

            @Override
            public List<OpenOrderView> openOrders() {
                return List.of(new OpenOrderView(UUID.randomUUID(), counter1, "Counter 1", base, 0,
                        "QUICK_SERVICE"));
            }
        };
        TableMapViewModel vm = vm(dining);
        assertNull(vm.openTakeaway());
        assertEquals("All counters are busy", vm.errorMessage().get());
    }

    @Test
    void openOrResumeReturnsExistingOrderIdForOccupied() {
        TableMapViewModel vm = vm(diningWithOneSeated());
        vm.refresh();
        assertEquals(openOrderId, vm.openOrResume(cell(vm, t1)));
    }

    @Test
    void openOrResumeOpensNewDineInOrderForFreeTable() {
        UUID newId = UUID.randomUUID();
        DiningApi dining = new DiningApi(null) {
            @Override
            public List<TableView> tables() {
                return List.of(new TableView(t2, "T2", 2, true));
            }

            @Override
            public List<OpenOrderView> openOrders() {
                return List.of();
            }

            @Override
            public OrderView openOrder(UUID tableId) {
                return new OrderView(newId, tableId, "DINE_IN", "OPEN", "clerk", base, null, null,
                        List.of());
            }
        };
        TableMapViewModel vm = vm(dining);
        vm.refresh();
        assertEquals(newId, vm.openOrResume(vm.cells().get(0)));
    }

    @Test
    void refreshSurfacesErrorMessage() {
        DiningApi dining = new DiningApi(null) {
            @Override
            public List<OpenOrderView> openOrders() {
                throw new ApiException(0, null, "Cannot reach store server");
            }
        };
        TableMapViewModel vm = vm(dining);
        vm.refresh();
        assertEquals("Cannot reach store server", vm.errorMessage().get());
        assertTrue(vm.cells().isEmpty());
    }

    @Test
    void openOrResumeSurfacesErrorAndReturnsNull() {
        DiningApi dining = new DiningApi(null) {
            @Override
            public List<TableView> tables() {
                return List.of(new TableView(t2, "T2", 2, true));
            }

            @Override
            public List<OpenOrderView> openOrders() {
                return List.of();
            }

            @Override
            public OrderView openOrder(UUID tableId) {
                throw new ApiException(409, new ProblemDetail("Conflict", 409, "Table already open"),
                        "HTTP 409");
            }
        };
        TableMapViewModel vm = vm(dining);
        vm.refresh();
        assertNull(vm.openOrResume(vm.cells().get(0)));
        assertEquals("Table already open", vm.errorMessage().get());
    }

    /**
     * Regression (slice-2 lesson): with a deferred UI dispatcher, the observable results are only
     * visible after the queued runnables drain — but the plain return values are correct
     * immediately. Guards that no VM logic depends on the observable being written synchronously.
     */
    @Test
    void refreshWorksUnderDeferredDispatcher() {
        Deque<Runnable> queue = new ArrayDeque<>();
        Consumer<Runnable> deferred = queue::add;
        TableMapViewModel vm = new TableMapViewModel(diningWithOneSeated(), deferred, "Counter ",
                Duration.ofMinutes(45), fixedClock);
        vm.refresh();
        // Nothing applied yet.
        assertTrue(vm.cells().isEmpty());
        // Drain the queued UI mutation.
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
        assertEquals(2, vm.cells().size());
        assertEquals(TableState.SEATED, cell(vm, t1).state());
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=TableMapViewModelTest`
Expected: FAIL — compilation errors: `TableCell.TableState` / `TableCell.state()` / `openMinutes()` / `TakeawayRow` / `takeawayOrders()` / `openTakeaway()` / the 5-arg constructor do not exist yet.

- [ ] **Step 3: Rewrite `TableCell`**

Rewrite `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/TableCell.java`:

```java
package com.company.pos.terminal.viewmodel;

import java.util.UUID;

/**
 * Immutable snapshot of one dining table on the table map. {@code state} is derived from the open
 * order (if any): FREE (no open order), SEATED (open order, no items yet), ACTIVE (open order with
 * items). {@code openMinutes} is how long the occupying order has been open at snapshot time (0
 * when free); {@code attention} is true when that exceeds the configured dwell threshold.
 * {@code orderId} is the OPEN order's id, or {@code null} when {@code state == FREE}.
 */
public record TableCell(UUID tableId, String label, TableState state, int openMinutes,
        boolean attention, UUID orderId) {

    public enum TableState { FREE, SEATED, ACTIVE }

    public boolean occupied() {
        return state != TableState.FREE;
    }
}
```

- [ ] **Step 4: Create `TakeawayRow`**

Create `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/TakeawayRow.java`:

```java
package com.company.pos.terminal.viewmodel;

import java.util.UUID;

/**
 * Immutable snapshot of one open takeaway (QUICK_SERVICE) order for the takeaway list.
 * {@code openMinutes} is how long it has been open at snapshot time; {@code attention} is true
 * when that exceeds the configured dwell threshold.
 */
public record TakeawayRow(UUID orderId, String label, int openMinutes, int lineCount,
        boolean attention) {
}
```

- [ ] **Step 5: Rewrite `TableMapViewModel`**

Rewrite `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/TableMapViewModel.java`:

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.DiningApi;
import com.company.pos.terminal.api.dto.OpenOrderView;
import com.company.pos.terminal.api.dto.OrderView;
import com.company.pos.terminal.api.dto.TableView;
import com.company.pos.terminal.viewmodel.TableCell.TableState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * ViewModel for the dining table-map screen. Holds all logic and exposes JavaFX observable
 * properties; unit-testable without the FX toolkit.
 *
 * <p>{@link #refresh()}, {@link #openOrResume(TableCell)} and {@link #openTakeaway()} run
 * <b>synchronously</b> on the calling thread and side-effect the properties. The controller runs
 * them off the FX thread inside a {@code Task}.
 *
 * <p>Dine-in tables (label not starting with {@code counterPrefix}) become {@link #cells()};
 * open QUICK_SERVICE orders become {@link #takeawayOrders()}. Counter tables never appear in the
 * grid. State and dwell are derived purely from the {@code GET /dining/orders} summary; no
 * per-order fetch is issued.
 */
public class TableMapViewModel {

    private final DiningApi dining;
    private final Consumer<Runnable> ui;
    private final String counterPrefix;
    private final Duration dwellThreshold;
    private final Supplier<Instant> clock;
    private final ObservableList<TableCell> cells = FXCollections.observableArrayList();
    private final ObservableList<TakeawayRow> takeawayOrders = FXCollections.observableArrayList();
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public TableMapViewModel(DiningApi dining) {
        this(dining, Runnable::run);
    }

    public TableMapViewModel(DiningApi dining, Consumer<Runnable> ui) {
        this(dining, ui, "Counter ", Duration.ofMinutes(45), Instant::now);
    }

    public TableMapViewModel(DiningApi dining, Consumer<Runnable> ui, String counterPrefix,
            Duration dwellThreshold, Supplier<Instant> clock) {
        this.dining = dining;
        this.ui = ui;
        this.counterPrefix = counterPrefix;
        this.dwellThreshold = dwellThreshold;
        this.clock = clock;
    }

    public ObservableList<TableCell> cells() {
        return cells;
    }

    public ObservableList<TakeawayRow> takeawayOrders() {
        return takeawayOrders;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    /** Loads tables + open orders and rebuilds both {@link #cells()} and {@link #takeawayOrders()}. */
    public void refresh() {
        try {
            Map<UUID, OpenOrderView> orderByTable = new HashMap<>();
            List<TakeawayRow> takeaway = new ArrayList<>();
            for (OpenOrderView o : dining.openOrders()) {
                orderByTable.put(o.tableId(), o);
                if ("QUICK_SERVICE".equals(o.serviceType())) {
                    takeaway.add(new TakeawayRow(o.orderId(), o.tableLabel(),
                            openMinutes(o.openedAt()), o.lineCount(), attention(o.openedAt())));
                }
            }
            List<TableCell> next = new ArrayList<>();
            for (TableView t : dining.tables()) {
                if (!t.active() || isCounter(t)) {
                    continue;
                }
                OpenOrderView o = orderByTable.get(t.id());
                TableState state = o == null ? TableState.FREE
                        : (o.lineCount() == 0 ? TableState.SEATED : TableState.ACTIVE);
                int minutes = o == null ? 0 : openMinutes(o.openedAt());
                boolean attn = o != null && attention(o.openedAt());
                next.add(new TableCell(t.id(), t.label(), state, minutes, attn,
                        o == null ? null : o.orderId()));
            }
            ui.accept(() -> {
                cells.setAll(next);
                takeawayOrders.setAll(takeaway);
                errorMessage.set("");
            });
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
        }
    }

    /**
     * Occupied table → its existing open order id. Free table → opens a new DINE_IN order and
     * returns its id. Returns {@code null} and sets {@link #errorMessage()} on {@link ApiException}.
     */
    public UUID openOrResume(TableCell cell) {
        if (cell.occupied()) {
            ui.accept(() -> errorMessage.set(""));
            return cell.orderId();
        }
        try {
            OrderView opened = dining.openOrder(cell.tableId());
            ui.accept(() -> errorMessage.set(""));
            return opened.id();
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return null;
        }
    }

    /**
     * Opens a new QUICK_SERVICE order against the first active counter table (label starting with
     * {@code counterPrefix}) that has no open order, and returns its id. Sets
     * {@link #errorMessage()} to "All counters are busy" and returns {@code null} when none is
     * free, or to the server message on {@link ApiException}.
     */
    public UUID openTakeaway() {
        try {
            Set<UUID> busy = new HashSet<>();
            for (OpenOrderView o : dining.openOrders()) {
                busy.add(o.tableId());
            }
            for (TableView t : dining.tables()) {
                if (t.active() && isCounter(t) && !busy.contains(t.id())) {
                    OrderView opened = dining.openOrder(t.id(), "QUICK_SERVICE");
                    ui.accept(() -> errorMessage.set(""));
                    return opened.id();
                }
            }
            ui.accept(() -> errorMessage.set("All counters are busy"));
            return null;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return null;
        }
    }

    private boolean isCounter(TableView t) {
        return t.label() != null && t.label().startsWith(counterPrefix);
    }

    private int openMinutes(Instant openedAt) {
        if (openedAt == null) {
            return 0;
        }
        return (int) Duration.between(openedAt, clock.get()).toMinutes();
    }

    private boolean attention(Instant openedAt) {
        return openedAt != null
                && Duration.between(openedAt, clock.get()).compareTo(dwellThreshold) > 0;
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

- [ ] **Step 6: Run the tests to confirm they pass**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=TableMapViewModelTest`
Expected: PASS (all 12 methods).

- [ ] **Step 7: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/TableCell.java \
        pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/TakeawayRow.java \
        pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/TableMapViewModel.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/TableMapViewModelTest.java
git commit -m "feat(terminal): table-state model + takeaway bucketing in TableMapViewModel

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Terminal — segmented table-map screen, tiles, takeaway list, CSS

**Files:**
- Modify: `pos-terminal/src/main/resources/fxml/table-map.fxml`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/TableMapController.java`
- Modify: `pos-terminal/src/main/resources/css/app.css` (add state/segmented/takeaway classes; retire `.table-occupied`)
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/FxmlContractTest.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java`

**Interfaces:**
- Consumes: `TableMapViewModel` (Task 5), `TerminalConfig.takeawayLabelPrefix()` / `dwellAttention()` (Task 4), `Navigator.toOrder(UUID)` (existing).
- Produces: table-map screen with fx:ids `tablesSegment`, `takeawaySegment`, `tablesPane`, `takeawayPane`, `newTakeawayButton`, `takeawayList` (plus existing `storeLabel`, `errorLabel`, `refreshButton`, `tableFlow`); CSS classes `.segmented`, `.segment`, `.table-seated`, `.table-active`, `.attention-badge`, `.takeaway-row`.

- [ ] **Step 1: Write the failing contract tests**

Add to `pos-terminal/src/test/java/com/company/pos/terminal/FxmlContractTest.java`:

```java
    @Test
    void tableMapDeclaresSegmentedAndTakeawayNodes() throws Exception {
        String fxml = resource("/fxml/table-map.fxml");
        for (String id : new String[] {
            "tablesSegment", "takeawaySegment", "tablesPane", "takeawayPane",
            "newTakeawayButton", "takeawayList", "tableFlow"
        }) {
            assertTrue(fxml.contains("fx:id=\"" + id + "\""), "missing table-map node: " + id);
        }
    }
```

Add to `pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java`:

```java
    @Test
    void definesSliceSevenClasses() throws Exception {
        String css = css();
        for (String cls : new String[] {
            ".segmented", ".segment", ".table-seated", ".table-active",
            ".attention-badge", ".takeaway-row"
        }) {
            assertTrue(css.contains(cls), "missing style class: " + cls);
        }
    }
```

- [ ] **Step 2: Run them to confirm they fail**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=FxmlContractTest,AppCssTest`
Expected: FAIL — the new fx:ids and CSS classes are absent.

- [ ] **Step 3: Rewrite `table-map.fxml`**

Rewrite `pos-terminal/src/main/resources/fxml/table-map.fxml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.geometry.Insets?>
<?import javafx.scene.control.Button?>
<?import javafx.scene.control.Label?>
<?import javafx.scene.control.ScrollPane?>
<?import javafx.scene.control.ToggleButton?>
<?import javafx.scene.layout.BorderPane?>
<?import javafx.scene.layout.FlowPane?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.layout.Pane?>
<?import javafx.scene.layout.StackPane?>
<?import javafx.scene.layout.VBox?>

<!-- Full-window dining screen: a header toolbar with a Tables|Takeaway segmented control over a
     stack that shows either the dine-in tile grid or the takeaway list. -->
<BorderPane styleClass="screen" xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml">
  <top>
    <VBox spacing="16">
      <HBox spacing="16" alignment="CENTER_LEFT">
        <Label text="Tables" styleClass="title"/>
        <Label fx:id="storeLabel" styleClass="subtitle"/>
        <Pane HBox.hgrow="ALWAYS"/>
        <HBox styleClass="segmented" spacing="0">
          <ToggleButton fx:id="tablesSegment" text="Tables" selected="true" styleClass="segment"/>
          <ToggleButton fx:id="takeawaySegment" text="Takeaway" styleClass="segment"/>
        </HBox>
        <Button fx:id="refreshButton" text="Refresh" styleClass="btn-secondary"/>
      </HBox>

      <!-- Error banner: danger style, hidden (and not laid out) when empty. -->
      <Label fx:id="errorLabel" styleClass="error-banner" wrapText="true" maxWidth="Infinity"/>
    </VBox>
  </top>

  <center>
    <StackPane>
      <ScrollPane fx:id="tablesPane" fitToWidth="true" styleClass="table-scroll">
        <FlowPane fx:id="tableFlow" hgap="16" vgap="16">
          <padding><Insets top="24" right="4" bottom="24" left="4"/></padding>
        </FlowPane>
      </ScrollPane>

      <ScrollPane fx:id="takeawayPane" fitToWidth="true" styleClass="table-scroll">
        <VBox spacing="12">
          <padding><Insets top="24" right="4" bottom="24" left="4"/></padding>
          <Button fx:id="newTakeawayButton" text="+ New takeaway" styleClass="btn-primary"
                  maxWidth="Infinity"/>
          <VBox fx:id="takeawayList" spacing="12"/>
        </VBox>
      </ScrollPane>
    </StackPane>
  </center>
</BorderPane>
```

- [ ] **Step 4: Rewrite `TableMapController`**

Rewrite `pos-terminal/src/main/java/com/company/pos/terminal/view/TableMapController.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.TableCell;
import com.company.pos.terminal.viewmodel.TableMapViewModel;
import com.company.pos.terminal.viewmodel.TakeawayRow;
import java.time.Instant;
import java.util.UUID;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Thin controller for the dining screen. Binds FXML to a {@link TableMapViewModel}, renders
 * {@code vm.cells()} as a dine-in tile grid and {@code vm.takeawayOrders()} as a takeaway list,
 * and runs the (synchronous) VM calls off the FX thread via {@link FxTasks}. A segmented control
 * swaps which region is visible; polling repaints both. No business logic lives here.
 */
public class TableMapController implements Navigator.Screen {

    private static final System.Logger LOG = System.getLogger(TableMapController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final TableMapViewModel vm;
    private final ToggleGroup segmentGroup = new ToggleGroup();
    private Timeline poller;

    @FXML private Label storeLabel;
    @FXML private Label errorLabel;
    @FXML private Button refreshButton;
    @FXML private ToggleButton tablesSegment;
    @FXML private ToggleButton takeawaySegment;
    @FXML private ScrollPane tablesPane;
    @FXML private ScrollPane takeawayPane;
    @FXML private Button newTakeawayButton;
    @FXML private FlowPane tableFlow;
    @FXML private VBox takeawayList;

    public TableMapController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new TableMapViewModel(services.diningApi, Platform::runLater,
                services.config.takeawayLabelPrefix(), services.config.dwellAttention(),
                Instant::now);
    }

    @FXML
    public void initialize() {
        storeLabel.setText("Store " + services.config.storeId()
                + "  ·  Terminal " + services.config.terminalId());

        errorLabel.textProperty().bind(vm.errorMessage());
        errorLabel.visibleProperty().bind(vm.errorMessage().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        vm.cells().addListener((ListChangeListener<TableCell>) c -> renderTables());
        vm.takeawayOrders().addListener((ListChangeListener<TakeawayRow>) c -> renderTakeaway());

        refreshButton.setOnAction(e -> refresh());
        newTakeawayButton.setOnAction(e -> newTakeaway());

        // Segmented control: keep one segment always selected; swap region visibility.
        tablesSegment.setToggleGroup(segmentGroup);
        takeawaySegment.setToggleGroup(segmentGroup);
        segmentGroup.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null && was != null) {
                was.setSelected(true);          // no deselect-to-empty
                return;
            }
            showSegment();
        });
        showSegment();

        refresh();
        int seconds = Math.max(1, services.config.pollIntervalSeconds());
        poller = new Timeline(new KeyFrame(Duration.seconds(seconds), e -> refresh()));
        poller.setCycleCount(Timeline.INDEFINITE);
        poller.play();
    }

    /** Show the region for the selected segment; the other is hidden and unmanaged. */
    private void showSegment() {
        boolean tables = tablesSegment.isSelected();
        tablesPane.setVisible(tables);
        tablesPane.setManaged(tables);
        takeawayPane.setVisible(!tables);
        takeawayPane.setManaged(!tables);
    }

    private void refresh() {
        FxTasks.run(vm::refresh, () -> { },
                err -> LOG.log(System.Logger.Level.ERROR, "Unexpected error in refresh", err));
    }

    private void renderTables() {
        tableFlow.getChildren().clear();
        for (TableCell cell : vm.cells()) {
            tableFlow.getChildren().add(tileFor(cell));
        }
    }

    private Button tileFor(TableCell cell) {
        Button tile = new Button(captionFor(cell));
        tile.getStyleClass().addAll("table-cell", stateClass(cell.state()));
        if (cell.attention()) {
            tile.getStyleClass().add("attention-badge");
        }
        tile.setWrapText(true);
        tile.setOnAction(e -> open(cell));
        return tile;
    }

    private String captionFor(TableCell cell) {
        String att = cell.attention() ? " ⏰" : "";
        return switch (cell.state()) {
            case FREE -> cell.label() + "\nOpen";
            case SEATED -> cell.label() + "\nSeated · " + cell.openMinutes() + "m" + att;
            case ACTIVE -> cell.label() + "\nIn use · " + cell.openMinutes() + "m" + att;
        };
    }

    private String stateClass(TableCell.TableState state) {
        return switch (state) {
            case FREE -> "table-free";
            case SEATED -> "table-seated";
            case ACTIVE -> "table-active";
        };
    }

    private void renderTakeaway() {
        takeawayList.getChildren().clear();
        for (TakeawayRow row : vm.takeawayOrders()) {
            Button r = new Button(takeawayCaption(row));
            r.getStyleClass().add("takeaway-row");
            r.setMaxWidth(Double.MAX_VALUE);
            r.setWrapText(true);
            r.setOnAction(e -> resume(row.orderId()));
            takeawayList.getChildren().add(r);
        }
    }

    private String takeawayCaption(TakeawayRow row) {
        String att = row.attention() ? " ⏰" : "";
        return row.label() + "  ·  " + row.lineCount() + " items  ·  " + row.openMinutes() + "m" + att;
    }

    /** Open or resume the order for the tapped dine-in table, then hand off to the order screen. */
    private void open(TableCell cell) {
        UUID[] holder = new UUID[1];
        FxTasks.run(
                () -> holder[0] = vm.openOrResume(cell),
                () -> { if (holder[0] != null) navigator.toOrder(holder[0]); },
                err -> LOG.log(System.Logger.Level.ERROR, "Unexpected error in open", err));
    }

    /** Open a new takeaway order against a free counter, then hand off to the order screen. */
    private void newTakeaway() {
        UUID[] holder = new UUID[1];
        FxTasks.run(
                () -> holder[0] = vm.openTakeaway(),
                () -> { if (holder[0] != null) navigator.toOrder(holder[0]); },
                err -> LOG.log(System.Logger.Level.ERROR, "Unexpected error in new takeaway", err));
    }

    private void resume(UUID orderId) {
        navigator.toOrder(orderId);
    }

    @Override
    public void onLeave() {
        stopPolling();
    }

    private void stopPolling() {
        if (poller != null) {
            poller.stop();
        }
    }
}
```

- [ ] **Step 5: Update `app.css` — add slice-7 classes, retire `.table-occupied`**

In `pos-terminal/src/main/resources/css/app.css`, delete the `.table-occupied` block (the four lines starting `.table-occupied {` through the `.table-occupied:pressed { ... }` line, around lines 246-252). Then add the following after the `.table-cell:focused` rule (keep existing `.table-free`):

```css
/* Seated (order open, no items yet) — amber; Active (has items) — emerald. State is also
   carried in the tile caption text ("Seated"/"In use"), never colour alone. */
.table-seated {
    -fx-background-color: derive(-fx-accent, 82%);
    -fx-text-fill: derive(-fx-accent, -25%);
    -fx-border-color: -fx-accent;
}
.table-seated:hover   { -fx-background-color: derive(-fx-accent, 72%); }
.table-seated:pressed { -fx-background-color: derive(-fx-accent, 62%); }

.table-active {
    -fx-background-color: derive(-fx-primary, 86%);
    -fx-text-fill: -fx-primary-press;
    -fx-border-color: -fx-primary;
}
.table-active:hover   { -fx-background-color: derive(-fx-primary, 78%); }
.table-active:pressed { -fx-background-color: derive(-fx-primary, 70%); }

/* Long-open marker: a thick accent border. The caption also carries a ⏰ glyph. */
.attention-badge { -fx-border-color: -fx-accent; -fx-border-width: 4; }

/* Tables|Takeaway segmented control — mirrors .mode-toggle sizing. */
.segmented { -fx-border-color: -fx-border; -fx-border-radius: 8; -fx-background-radius: 8; }
.segment {
    -fx-min-height: 56px;
    -fx-min-width: 128px;
    -fx-font-size: 16px;
    -fx-font-weight: bold;
    -fx-background-color: -fx-canvas;
    -fx-text-fill: -fx-ink;
    -fx-background-radius: 8;
    -fx-padding: 8 20 8 20;
}
.segment:selected { -fx-background-color: -fx-primary; -fx-text-fill: white; }
.segment:hover    { -fx-border-color: -fx-primary; }
.segment:focused  { -fx-border-width: 2; }

/* Takeaway list row — a wide tappable card. */
.takeaway-row {
    -fx-min-height: 56px;
    -fx-font-size: 16px;
    -fx-font-weight: bold;
    -fx-alignment: CENTER_LEFT;
    -fx-background-color: -fx-card;
    -fx-text-fill: -fx-ink;
    -fx-border-color: -fx-border;
    -fx-border-radius: 12;
    -fx-background-radius: 12;
    -fx-padding: 12 20 12 20;
    -fx-cursor: hand;
}
.takeaway-row:hover   { -fx-border-color: -fx-primary; }
.takeaway-row:pressed { -fx-background-color: derive(-fx-card, -6%); }
.takeaway-row:focused { -fx-border-color: -fx-ink; -fx-border-width: 2; }
```

(All tokens used here — `-fx-card`, `-fx-border`, `-fx-accent`, `-fx-primary`, `-fx-primary-press` — are defined in the token block at the top of `app.css`.)

- [ ] **Step 6: Run the contract tests to confirm they pass**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=FxmlContractTest,AppCssTest`
Expected: PASS.

- [ ] **Step 7: Run the full terminal test suite**

Run: `./mvnw -f pos-terminal/pom.xml test`
Expected: PASS (all terminal tests — VM, API, config, contract, view build tests).

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/resources/fxml/table-map.fxml \
        pos-terminal/src/main/java/com/company/pos/terminal/view/TableMapController.java \
        pos-terminal/src/main/resources/css/app.css \
        pos-terminal/src/test/java/com/company/pos/terminal/FxmlContractTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java
git commit -m "feat(terminal): segmented table-map with states + takeaway list

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: Docs — run-modes + terminal README manual E2E

**Files:**
- Modify: `docs/run-modes.md`
- Modify: `pos-terminal/README.md`

**Interfaces:** none (documentation only).

- [ ] **Step 1: Document the summary change in `docs/run-modes.md`**

Find the dining section describing `GET /dining/orders` (search for `/dining/orders`). Add a sentence noting the open-order summary now carries `serviceType` (`DINE_IN` / `QUICK_SERVICE`), and that takeaway orders are `QUICK_SERVICE` orders opened against counter-labelled tables — the service charge already excludes non-`DINE_IN` orders, so takeaway is untaxed by the existing quote/close paths (no new logic). Keep the wording consistent with the surrounding entries. Example addition:

```markdown
`GET /dining/orders` — open-order summaries now include `serviceType`
(`DINE_IN` / `QUICK_SERVICE`). Takeaway is not a separate concept: a takeaway is a
`QUICK_SERVICE` order opened against a counter-labelled table. Service charge is already
gated on `DINE_IN` (`resolveApplyServiceCharge`), so takeaway orders quote and close untaxed
with no dedicated code path. No schema change — `serviceType` is a projection of the existing
`service_type` column.
```

- [ ] **Step 2: Add the slice-7 manual E2E section to `pos-terminal/README.md`**

Append a slice-7 section (match the heading style of the existing slice sections):

```markdown
## Slice 7 — Table states + takeaway (manual E2E)

Run the backend with seed data (`--spring.profiles.active=embedded,dev` — this now seeds
tables `T1`–`T6` and `Counter 1`–`Counter 3`), then `./mvnw -f pos-terminal/pom.xml javafx:run`
and log in as `manager` / `manager`.

1. **Dine-in states.** On **Tables**, every table shows **Open**. Tap `T1` → order screen →
   Back to tables: `T1` now shows **Seated · 0m**. Add an item to `T1`, return: it shows
   **In use · Nm**. Leave it open past the dwell window (default 45 min, or set
   `-Ddining.dwell.attention.minutes=1`): the tile gains a ⏰ marker and thick accent border.
2. **New takeaway.** Switch to the **Takeaway** segment → **+ New takeaway** → the order screen
   opens on a counter. Add items and pay: confirm the quote shows **no service charge** (takeaway
   is `QUICK_SERVICE`). The counter tile never appears on the dine-in **Tables** grid.
3. **Takeaway list + resume.** Open two takeaway orders; both appear as rows under **Takeaway**
   with item count and dwell. Tap a row to resume that order.
4. **All counters busy.** Open takeaway orders on all three counters, then **+ New takeaway** →
   the error banner shows "All counters are busy" and no navigation occurs.
```

- [ ] **Step 3: Commit**

```bash
git add docs/run-modes.md pos-terminal/README.md
git commit -m "docs: slice 7 table states + takeaway (run-modes + terminal E2E)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Self-Review

**1. Spec coverage:**
- Backend `OpenOrderView.serviceType` + projection → Task 1. ✅
- `DevDiningSeeder` (dine-in + counters, idempotent, dev-only) → Task 2. ✅
- Terminal DTO `serviceType` + `openOrder(serviceType)` overload → Task 3. ✅
- Config keys `dining.takeaway.label-prefix` / `dining.dwell.attention.minutes` → Task 4. ✅
- `TableState` enum, `TableCell` state/openMinutes/attention, `TakeawayRow`, VM derivation + bucketing + `openTakeaway` + injected clock + async-dispatcher test → Task 5. ✅
- Segmented `Tables | Takeaway` view, state tiles, takeaway list, New-takeaway, `.table-occupied` retired, new CSS classes, FXML/CSS contract tests → Task 6. ✅
- Manual E2E + run-modes note (incl. takeaway-skips-service-charge property) → Task 7. ✅
- `ModularityTests` re-run after backend changes → Tasks 1 and 2. ✅
- No migration: confirmed — only a projection field and dev seeder. ✅

**2. Placeholder scan:** No TBD/TODO; every code step shows complete code; every command has expected output. All CSS tokens referenced in Task 6 are confirmed present in `app.css`. ✅

**3. Type consistency:**
- `OpenOrderView` backend field is `ServiceType`; terminal DTO field is `String` — deliberate (enums cross as Strings). VM compares `"QUICK_SERVICE".equals(o.serviceType())`. ✅
- `TableCell(UUID, String, TableState, int, boolean, UUID)` used identically in the VM (`new TableCell(t.id(), t.label(), state, minutes, attn, orderId)`) and tests (`state()`, `openMinutes()`, `attention()`, `orderId()`, `occupied()`). ✅
- `TakeawayRow(UUID, String, int, int, boolean)` used identically in VM and tests (`orderId()`, `label()`, `openMinutes()`, `lineCount()`, `attention()`). ✅
- `openOrder(UUID)` and `openOrder(UUID, String)` — VM `openOrResume` uses the 1-arg, `openTakeaway` uses the 2-arg; tests override the matching arity. ✅
- `TableMapViewModel` 5-arg constructor signature matches every call site (controller + tests). ✅
- `TerminalConfig.takeawayLabelPrefix()` / `dwellAttention()` used by the controller exactly as declared. ✅
