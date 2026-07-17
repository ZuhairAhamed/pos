# Terminal Slice 11 — Table transfer — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Relocate an open dine-in order to a different, free table — a new `dining` backend operation plus a "Move table" action on the terminal order screen.

**Architecture:** Full-stack, two tracks. Backend: a `DiningOrder.moveToTable` domain setter + `DiningService.transferOrder` (validates OPEN order + active, free target) + a cashier-level `POST .../transfer` endpoint. Terminal: `DiningApi.transferOrder`, a pure `MoveTargets` free-table helper, `OrderViewModel.transfer`, a `MoveTableDialog` picker, and an order-screen button that fetches tables → picks a target → transfers → returns to the table map.

**Tech Stack:** Java 21, Spring Boot 3.3 / Spring Modulith (backend), JavaFX (terminal), JUnit 5 + AssertJ + MockMvc, Maven (two builds — root reactor for backend, `pos-terminal/pom.xml` for the terminal).

## Global Constraints

- JDK 21: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` before any mvn.
- **Two separate builds.** Backend tests via the ROOT reactor: `./mvnw test -Dtest=...` (uses the `embedded` SQLite profile — no Docker). Terminal tests via `./mvnw -f pos-terminal/pom.xml ... test`.
- **After the backend change, run `ModularityTests`** (`./mvnw test -Dtest=ModularityTests`) — the module-boundary enforcer. This slice stays inside `dining`, so it must still pass with no `allowedDependencies` edit.
- Money is `BigDecimal`. No database migration (transfer changes an existing `dining_order.table_id`). No new Maven dependencies.
- Transfer is **cashier-level** — no `@PreAuthorize` on the endpoint.
- Target-table rule: active + no open order; occupied → `conflict`, inactive → `validation`, unknown → `notFound`, non-open order → `validation`, same table → `validation`. A moved order keeps its `serviceType`.
- **FX-threading (terminal, the recurring bug class):** VM methods synchronous, return plain values; the controller runs them off the FX thread via `FxTasks.run`, reading results in `onDone` via a holder; observables written off-thread only inside `ui.accept`; `onError` never `setText` a bound label. Each new VM method gets an async-dispatcher regression test.

---

## File Structure

| File | Track | Responsibility | Task |
|------|-------|----------------|------|
| `dining/domain/DiningOrder.java` | BE | `moveToTable(UUID)` setter | 1 |
| `dining/api/DiningService.java` | BE | `transferOrder(UUID,UUID)` declaration | 1 |
| `dining/application/DefaultDiningService.java` | BE | `transferOrder` implementation | 1 |
| `src/test/.../dining/DiningTransferServiceTest.java` | BE | service-level tests | 1 |
| `dining/web/DiningController.java` | BE | `POST .../{id}/transfer` | 2 |
| `src/test/.../dining/DiningTransferControllerTest.java` | BE | web test | 2 |
| `pos-terminal/.../api/DiningApi.java` | FE | `transferOrder` client method | 3 |
| `pos-terminal/.../api/DiningApiTest.java` | FE | client test | 3 |
| `pos-terminal/.../order/MoveTargets.java` | FE | pure free-table helper | 4 |
| `pos-terminal/.../order/MoveTargetsTest.java` | FE | helper test | 4 |
| `pos-terminal/.../viewmodel/OrderViewModel.java` | FE | `transfer(UUID) → boolean` | 5 |
| `pos-terminal/.../viewmodel/OrderViewModelTest.java` | FE | VM tests (+ async) | 5 |
| `pos-terminal/.../view/MoveTableDialog.java` | FE | free-table picker | 6 |
| `pos-terminal/.../view/OrderController.java` | FE | Move-table button + flow | 6 |
| `pos-terminal/.../resources/fxml/order.fxml` | FE | `moveButton` | 6 |
| `pos-terminal/.../resources/css/app.css` | FE | `.move-box` (unasserted polish) | 6 |
| `pos-terminal/README.md` | FE | slice-11 manual E2E | 6 |

Order: **1 → 2** (backend), then **3 → 4 → 5 → 6** (terminal). Task 5 depends on Task 3; Task 6 depends on Tasks 4 and 5.

---

## Task 1: Backend — `moveToTable` + `transferOrder` service

**Files:**
- Modify: `src/main/java/com/company/pos/dining/domain/DiningOrder.java` (add `moveToTable`)
- Modify: `src/main/java/com/company/pos/dining/api/DiningService.java` (declare `transferOrder`)
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java` (implement)
- Test: `src/test/java/com/company/pos/dining/DiningTransferServiceTest.java`

**Interfaces:**
- Produces: `OrderView DiningService.transferOrder(UUID orderId, UUID targetTableId)` — moves an OPEN order to an active, free table; throws `DomainException` otherwise. `void DiningOrder.moveToTable(UUID targetTableId)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/dining/DiningTransferServiceTest.java` (mirrors `DiningLineServiceTest`'s seed setup so the fired-line test has a real product):

```java
package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.CourseTag;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OrderStatus;
import com.company.pos.dining.api.OrderView;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class DiningTransferServiceTest {

    @Autowired DiningService dining;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private UUID freshTable(String label) {
        return dining.registerTable(new RegisterTableCommand(label + UUID.randomUUID(), 4)).id();
    }

    @Test
    void transfersOpenOrderToFreeTable() {
        UUID from = freshTable("FROM");
        UUID to = freshTable("TO");
        UUID orderId = dining.openOrder(new OpenOrderCommand(from, null), "alice").id();

        OrderView moved = dining.transferOrder(orderId, to);

        assertThat(moved.id()).isEqualTo(orderId);
        assertThat(moved.tableId()).isEqualTo(to);
        assertThat(moved.status()).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void movedOrderKeepsItsLinesAndFiredState() {
        UUID from = freshTable("FROM");
        UUID to = freshTable("TO");
        UUID orderId = dining.openOrder(new OpenOrderCommand(from, null), "alice").id();
        dining.addLine(orderId,
                new AddLineCommand("BURGER", new BigDecimal("2"), "no onion", CourseTag.MAIN), "alice");
        dining.fireOrder(orderId, "alice");

        OrderView moved = dining.transferOrder(orderId, to);

        assertThat(moved.lines()).hasSize(1);
        assertThat(moved.lines().get(0).sku()).isEqualTo("BURGER");
        assertThat(moved.lines().get(0).firedAt()).isNotNull(); // fired state rides along
    }

    @Test
    void rejectsTransferToOccupiedTable() {
        UUID from = freshTable("FROM");
        UUID to = freshTable("TO");
        UUID orderId = dining.openOrder(new OpenOrderCommand(from, null), "alice").id();
        dining.openOrder(new OpenOrderCommand(to, null), "bob"); // target now occupied
        assertThatThrownBy(() -> dining.transferOrder(orderId, to))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsTransferToInactiveTable() {
        UUID from = freshTable("FROM");
        UUID to = freshTable("TO");
        UUID orderId = dining.openOrder(new OpenOrderCommand(from, null), "alice").id();
        dining.deactivateTable(to);
        assertThatThrownBy(() -> dining.transferOrder(orderId, to))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsTransferToUnknownTable() {
        UUID from = freshTable("FROM");
        UUID orderId = dining.openOrder(new OpenOrderCommand(from, null), "alice").id();
        assertThatThrownBy(() -> dining.transferOrder(orderId, UUID.randomUUID()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsTransferToSameTable() {
        UUID from = freshTable("FROM");
        UUID orderId = dining.openOrder(new OpenOrderCommand(from, null), "alice").id();
        assertThatThrownBy(() -> dining.transferOrder(orderId, from))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsTransferOfNonOpenOrder() {
        UUID from = freshTable("FROM");
        UUID to = freshTable("TO");
        UUID orderId = dining.openOrder(new OpenOrderCommand(from, null), "alice").id();
        dining.voidOrder(orderId, "test");
        assertThatThrownBy(() -> dining.transferOrder(orderId, to))
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=DiningTransferServiceTest`
Expected: compilation failure — `transferOrder` does not exist on `DiningService`.

- [ ] **Step 3: Add the domain setter**

In `DiningOrder.java`, add after `voidOrder()`:

```java
    public void moveToTable(UUID targetTableId) {
        this.tableId = targetTableId;
    }
```

- [ ] **Step 4: Declare the service method**

In `DiningService.java`, add after `List<OpenOrderView> listOpenOrders();`:

```java
    /** Relocates an OPEN order to a different, active, free table. Fails if the order is not open,
     *  the target is the same/unknown/inactive, or the target already has an open order. */
    OrderView transferOrder(UUID orderId, UUID targetTableId);
```

- [ ] **Step 5: Implement the service method**

In `DefaultDiningService.java`, add after the `getOrder` method (around line 137):

```java
    @Override
    public OrderView transferOrder(UUID orderId, UUID targetTableId) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        if (order.getTableId().equals(targetTableId)) {
            throw DomainException.validation("Order is already on that table");
        }
        DiningTable target = tables.findById(targetTableId)
                .orElseThrow(() -> DomainException.notFound("No table " + targetTableId));
        if (!target.isActive()) {
            throw DomainException.validation("Table " + target.getLabel() + " is inactive");
        }
        if (orders.existsByTableIdAndStatus(targetTableId, OrderStatus.OPEN)) {
            throw DomainException.conflict("Table " + target.getLabel() + " already has an open order");
        }
        order.moveToTable(targetTableId);
        return toOrderView(orders.save(order));
    }
```

- [ ] **Step 6: Run the transfer test + ModularityTests**

Run: `./mvnw test -Dtest=DiningTransferServiceTest`
Expected: PASS (7 tests).
Run: `./mvnw test -Dtest=ModularityTests`
Expected: PASS (boundary unchanged — the work stayed inside `dining`).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/pos/dining/domain/DiningOrder.java \
        src/main/java/com/company/pos/dining/api/DiningService.java \
        src/main/java/com/company/pos/dining/application/DefaultDiningService.java \
        src/test/java/com/company/pos/dining/DiningTransferServiceTest.java
git commit -m "feat(dining): transferOrder — relocate an open order to a free table

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Backend — transfer endpoint

**Files:**
- Modify: `src/main/java/com/company/pos/dining/web/DiningController.java` (add the endpoint)
- Test: `src/test/java/com/company/pos/dining/DiningTransferControllerTest.java`

**Interfaces:**
- Consumes: `DiningService.transferOrder` (Task 1).
- Produces: `POST /dining/orders/{orderId}/transfer?targetTableId=<uuid>` → `OrderView` (200), cashier-allowed (no `@PreAuthorize`).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/dining/DiningTransferControllerTest.java` (mixes `DiningService` for fixtures + MockMvc for the call, mirroring `DiningTableControllerTest`'s security helpers):

```java
package com.company.pos.dining;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.support.DatabaseCleaner;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class DiningTransferControllerTest {

    @Autowired MockMvc mvc;
    @Autowired DiningService dining;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    private static RequestPostProcessor cashier() {
        return jwt().jwt(j -> j.subject("cashier"))
                .authorities(new SimpleGrantedAuthority("ROLE_CASHIER"));
    }

    @Test
    void cashierTransfersOrderToFreeTable() throws Exception {
        UUID from = dining.registerTable(new RegisterTableCommand("XFR-FROM", 4)).id();
        UUID to = dining.registerTable(new RegisterTableCommand("XFR-TO", 4)).id();
        UUID orderId = dining.openOrder(new OpenOrderCommand(from, null), "cashier").id();

        mvc.perform(post("/dining/orders/" + orderId + "/transfer")
                        .param("targetTableId", to.toString())
                        .with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableId").value(to.toString()));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=DiningTransferControllerTest`
Expected: failure — no `/transfer` mapping (404/500), so the assertions don't match.

- [ ] **Step 3: Add the endpoint**

In `DiningController.java`, add after the `voidOrder` endpoint (end of the class, before the closing brace). No `@PreAuthorize` — cashier-level:

```java
    @PostMapping("/dining/orders/{orderId}/transfer")
    OrderView transferOrder(@PathVariable UUID orderId, @RequestParam UUID targetTableId) {
        return dining.transferOrder(orderId, targetTableId);
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=DiningTransferControllerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/pos/dining/web/DiningController.java \
        src/test/java/com/company/pos/dining/DiningTransferControllerTest.java
git commit -m "feat(dining): POST /dining/orders/{id}/transfer (cashier-level)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Terminal — `DiningApi.transferOrder`

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/DiningApiTest.java`

**Interfaces:**
- Produces: `OrderView DiningApi.transferOrder(UUID orderId, UUID targetTableId)` — `POST /dining/orders/{orderId}/transfer?targetTableId=...`, returns the moved `OrderView`.

- [ ] **Step 1: Write the failing test**

Add to `DiningApiTest.java` (before the class closing brace). `ORDER_JSON` parses to an OPEN order on table `4444...`:

```java
    @Test
    void transferOrderPostsTargetTableIdAndParsesOrder() throws Exception {
        try (StubServer stub = new StubServer(200, ORDER_JSON, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            java.util.UUID target = java.util.UUID.fromString("44444444-4444-4444-4444-444444444444");
            OrderView v = api.transferOrder(ORDER_ID, target);
            assertEquals("OPEN", v.status());
            assertEquals("POST", stub.lastMethod);
            assertEquals("/dining/orders/33333333-3333-3333-3333-333333333333/transfer", stub.lastPath);
            assertEquals("targetTableId=44444444-4444-4444-4444-444444444444", stub.lastQuery);
        }
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml -Dtest=DiningApiTest test`
Expected: compilation failure — `transferOrder` does not exist.

- [ ] **Step 3: Implement the method**

In `DiningApi.java`, add after the `fire` method:

```java
    /** POST /dining/orders/{orderId}/transfer?targetTableId=... — relocates the order to a free table. */
    public OrderView transferOrder(UUID orderId, UUID targetTableId) {
        return client.post("/dining/orders/" + orderId + "/transfer?targetTableId=" + targetTableId,
                null, new TypeReference<OrderView>() {});
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=DiningApiTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/DiningApiTest.java
git commit -m "feat(terminal): DiningApi.transferOrder

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Terminal — `MoveTargets` free-table helper

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/order/MoveTargets.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/order/MoveTargetsTest.java`

**Interfaces:**
- Produces: `static List<TableView> MoveTargets.freeTargets(List<TableView> tables, List<OpenOrderView> openOrders, UUID currentTableId)` — active tables minus the current table minus any occupied by an open order.

- [ ] **Step 1: Write the failing test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/order/MoveTargetsTest.java`:

```java
package com.company.pos.terminal.order;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.OpenOrderView;
import com.company.pos.terminal.api.dto.TableView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MoveTargetsTest {

    private final UUID t1 = UUID.randomUUID();
    private final UUID t2 = UUID.randomUUID();
    private final UUID t3 = UUID.randomUUID();
    private final UUID t4 = UUID.randomUUID();

    private TableView table(UUID id, String label, boolean active) {
        return new TableView(id, label, 4, active);
    }

    private OpenOrderView openOn(UUID tableId) {
        return new OpenOrderView(UUID.randomUUID(), tableId, "L", Instant.now(), 0, "DINE_IN");
    }

    @Test
    void excludesCurrentOccupiedAndInactive() {
        List<TableView> tables = List.of(
                table(t1, "T1", true),   // current → excluded
                table(t2, "T2", true),   // occupied → excluded
                table(t3, "T3", false),  // inactive → excluded
                table(t4, "T4", true));  // free → kept
        List<OpenOrderView> open = List.of(openOn(t1), openOn(t2));

        List<UUID> ids = MoveTargets.freeTargets(tables, open, t1).stream()
                .map(TableView::id).collect(Collectors.toList());

        assertEquals(List.of(t4), ids);
    }

    @Test
    void emptyWhenNoFreeTables() {
        List<TableView> tables = List.of(table(t1, "T1", true), table(t2, "T2", true));
        List<OpenOrderView> open = List.of(openOn(t2));
        assertTrue(MoveTargets.freeTargets(tables, open, t1).isEmpty());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=MoveTargetsTest test`
Expected: compilation failure — `MoveTargets` does not exist.

- [ ] **Step 3: Create the helper**

Create `pos-terminal/src/main/java/com/company/pos/terminal/order/MoveTargets.java`:

```java
package com.company.pos.terminal.order;

import com.company.pos.terminal.api.dto.OpenOrderView;
import com.company.pos.terminal.api.dto.TableView;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pure helper: given all tables, the current open orders, and the order's current table, returns
 * the tables an order may be transferred to — active tables that are neither the current table nor
 * already occupied by an open order. No FX, no I/O; unit-tested headlessly.
 */
public final class MoveTargets {

    private MoveTargets() {}

    public static List<TableView> freeTargets(List<TableView> tables, List<OpenOrderView> openOrders,
            UUID currentTableId) {
        Set<UUID> occupied = openOrders.stream()
                .map(OpenOrderView::tableId)
                .collect(Collectors.toSet());
        return tables.stream()
                .filter(TableView::active)
                .filter(t -> !t.id().equals(currentTableId))
                .filter(t -> !occupied.contains(t.id()))
                .collect(Collectors.toList());
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=MoveTargetsTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/order/MoveTargets.java \
        pos-terminal/src/test/java/com/company/pos/terminal/order/MoveTargetsTest.java
git commit -m "feat(terminal): MoveTargets free-table helper

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Terminal — `OrderViewModel.transfer`

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/OrderViewModel.java` (add after `voidOrder`)
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/OrderViewModelTest.java`

**Interfaces:**
- Consumes: `DiningApi.transferOrder(UUID, UUID)` (Task 3).
- Produces: `boolean OrderViewModel.transfer(UUID targetTableId)` — true on success; false + `errorMessage` on `ApiException`.

- [ ] **Step 1: Write the failing tests**

Add to `OrderViewModelTest.java` (before the class closing brace):

```java
    @Test
    void transferReturnsTrueOnSuccess() {
        OrderLineView line =
                new OrderLineView(firedLineId, "BURGER", new BigDecimal("1"), null, "MAIN", null, List.of());
        UUID[] captured = new UUID[1];
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(line));
                    }

                    @Override
                    public OrderView transferOrder(UUID oid, UUID targetTableId) {
                        captured[0] = targetTableId;
                        return orderWith(List.of(line));
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        UUID target = UUID.randomUUID();
        assertTrue(vm.transfer(target));
        assertEquals(target, captured[0]);
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void transferSurfacesErrorAndReturnsFalse() {
        OrderLineView line =
                new OrderLineView(firedLineId, "BURGER", new BigDecimal("1"), null, "MAIN", null, List.of());
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(line));
                    }

                    @Override
                    public OrderView transferOrder(UUID oid, UUID targetTableId) {
                        throw new ApiException(409,
                                new ProblemDetail("Conflict", 409, "Table T2 already has an open order"),
                                "HTTP 409");
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        assertFalse(vm.transfer(UUID.randomUUID()));
        assertEquals("Table T2 already has an open order", vm.errorMessage().get());
    }

    @Test
    void deferredDispatcherHoldsTransferErrorUntilDrained() {
        OrderLineView line =
                new OrderLineView(firedLineId, "BURGER", new BigDecimal("1"), null, "MAIN", null, List.of());
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(line));
                    }

                    @Override
                    public OrderView transferOrder(UUID oid, UUID targetTableId) {
                        throw new ApiException(409,
                                new ProblemDetail("Conflict", 409, "Table T2 already has an open order"),
                                "HTTP 409");
                    }
                };
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        OrderViewModel vm = new OrderViewModel(dining, cache(), queue::add);
        vm.load(orderId);
        while (!queue.isEmpty()) queue.poll().run();       // drain load → baseline
        boolean result = vm.transfer(UUID.randomUUID());
        assertFalse(result);                               // synchronous return
        assertEquals("", vm.errorMessage().get());         // deferred: error not applied yet
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals("Table T2 already has an open order", vm.errorMessage().get());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=OrderViewModelTest test`
Expected: compilation failure — `transfer` does not exist.

- [ ] **Step 3: Implement the method**

In `OrderViewModel.java`, add after the `voidOrder` method:

```java
    /**
     * Transfers the current order to another table. Returns true on success; on ApiException
     * surfaces the message via errorMessage and returns false. The caller navigates away on
     * success — no line refresh is done here.
     */
    public boolean transfer(UUID targetTableId) {
        try {
            dining.transferOrder(order.id(), targetTableId);
            ui.accept(() -> errorMessage.set(""));
            return true;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return false;
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=OrderViewModelTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/OrderViewModel.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/OrderViewModelTest.java
git commit -m "feat(terminal): OrderViewModel.transfer

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Terminal — Move-table dialog + order-screen button

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/MoveTableDialog.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/OrderController.java`
- Modify: `pos-terminal/src/main/resources/fxml/order.fxml`
- Modify: `pos-terminal/src/main/resources/css/app.css`
- Modify: `pos-terminal/README.md`

**Interfaces:**
- Consumes: `MoveTargets.freeTargets` (Task 4), `OrderViewModel.transfer`/`setError`/`currentOrder` (Task 5 + existing), `DiningApi.tables()`/`openOrders()` (existing).
- Produces: the working "Move table" flow. Dialog rendering is display-dependent → verified by the full suite compiling+passing plus the README manual E2E (no TestFX).

- [ ] **Step 1: Create `MoveTableDialog`**

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/MoveTableDialog.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.TableView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;

/**
 * Modal free-table picker for moving an order. Lists each candidate table (label + seats) as a
 * full-width touch button; tapping one closes the dialog with that table's id. Pure view; the
 * caller performs the transfer. Display-dependent — exercised by the manual E2E.
 *
 * @return the chosen table id, or empty on cancel.
 */
public final class MoveTableDialog {

    private MoveTableDialog() {}

    public static Optional<UUID> promptForTarget(List<TableView> freeTables) {
        Dialog<UUID> dialog = new Dialog<>();
        dialog.setTitle("Move table");
        dialog.setHeaderText("Move order to which table?");
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(cancel);
        dialog.getDialogPane().getStyleClass().add("move-dialog");

        VBox box = new VBox(8);
        box.getStyleClass().add("move-box");
        for (TableView t : freeTables) {
            Button b = new Button(t.label() + "  ·  " + t.seats() + " seats");
            b.getStyleClass().add("btn-secondary");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setOnAction(e -> {
                dialog.setResult(t.id());
                dialog.close();
            });
            box.getChildren().add(b);
        }
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        dialog.getDialogPane().setContent(scroll);

        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }
}
```

- [ ] **Step 2: Add the CSS (polish, unasserted)**

Append to `pos-terminal/src/main/resources/css/app.css`:

```css
/* Slice 11 — move-table picker */
.move-box { -fx-padding: 8; }
```

- [ ] **Step 3: Add `moveButton` to `order.fxml`**

In `order.fxml`, find the fire-button HBox (which since slice 10 also holds `voidButton`):

```xml
      <HBox spacing="12">
        <Button fx:id="fireButton" text="Fire to kitchen" styleClass="btn-primary" HBox.hgrow="ALWAYS" maxWidth="Infinity"/>
        <Button fx:id="voidButton" text="Void order" styleClass="btn-danger"/>
      </HBox>
```

and add the move button after `voidButton`:

```xml
      <HBox spacing="12">
        <Button fx:id="fireButton" text="Fire to kitchen" styleClass="btn-primary" HBox.hgrow="ALWAYS" maxWidth="Infinity"/>
        <Button fx:id="voidButton" text="Void order" styleClass="btn-danger"/>
        <Button fx:id="moveButton" text="Move table" styleClass="btn-secondary"/>
      </HBox>
```

- [ ] **Step 4: Wire the move flow in `OrderController`**

In `OrderController.java`:

(a) Add imports (with the other `dto`/`order` imports):

```java
import com.company.pos.terminal.api.dto.OpenOrderView;
import com.company.pos.terminal.api.dto.TableView;
import com.company.pos.terminal.order.MoveTargets;
import java.util.concurrent.atomic.AtomicReference;
```

(b) Add the field (with the other `@FXML` buttons):

```java
    @FXML private Button moveButton;
```

(c) In `initialize()`, alongside the other button handlers and the initial-disable block, add:

```java
        moveButton.setOnAction(e -> moveTable());
        moveButton.setDisable(true);
```

(d) In `afterCatalogLoaded()`, where `payButton`/`fireButton`/`voidButton` are enabled, add:

```java
        moveButton.setDisable(false);
```

(e) Add the flow method (near `voidOrder()`). An `AtomicReference` carries the fetched free-table list from the work thread to the FX thread (avoids the unchecked-warning of a generic array holder):

```java
    /** Move the order to another table: fetch free tables → pick one → transfer → tables. */
    private void moveTable() {
        AtomicReference<List<TableView>> free = new AtomicReference<>();
        FxTasks.run(
                () -> {
                    List<TableView> tables = services.diningApi.tables();
                    List<OpenOrderView> open = services.diningApi.openOrders();
                    free.set(MoveTargets.freeTargets(tables, open, vm.currentOrder().tableId()));
                },
                () -> {
                    List<TableView> targets = free.get();
                    if (targets.isEmpty()) {
                        vm.setError("No free tables available");
                        return;
                    }
                    Optional<UUID> target = MoveTableDialog.promptForTarget(targets);
                    if (target.isEmpty()) {
                        return;
                    }
                    boolean[] holder = {false};
                    FxTasks.run(
                            () -> holder[0] = vm.transfer(target.get()),
                            () -> {
                                if (holder[0]) {
                                    navigator.toTableMap();
                                }
                            },
                            err -> LOG.log(System.Logger.Level.ERROR, "Failed to transfer order", err));
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to load tables for move", err));
    }
```

- [ ] **Step 5: Run the full terminal suite**

Run: `./mvnw -f pos-terminal/pom.xml clean test`
Expected: BUILD SUCCESS, all tests pass (this slice added the Task 3/4/5 tests to the baseline; Task 6 adds no headless test — the dialog/button are manual-E2E). If compilation fails on an import or fx:id, fix against this task's edits.

- [ ] **Step 6: Add the Slice 11 manual-E2E section to the README**

Append to `pos-terminal/README.md` (after the Slice 10 section):

```markdown
## Slice 11 — Table transfer (manual E2E)

**Prereq:** backend on `embedded,dev`; log in (`manager`/`manager`). Seeded floor has several
dine-in tables (T1–T6) plus counters.

1. Open a table (e.g. T1), add a line or two. Tap **Move table**.
2. The picker lists only **free, active** tables (not T1, not any occupied table). Tap one.
3. The screen returns to the table map; the order now sits on the chosen table (its lines and
   any fired state came along). The old table is free again.
4. **Occupied target:** if you attempt a move while the only other tables are occupied, the
   picker shows just the genuinely free ones; if none are free, an error line reads
   "No free tables available".
```

- [ ] **Step 7: Final full-suite run and commit**

Run: `./mvnw -f pos-terminal/pom.xml clean test`
Expected: BUILD SUCCESS, all green.

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/MoveTableDialog.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/OrderController.java \
        pos-terminal/src/main/resources/fxml/order.fxml \
        pos-terminal/src/main/resources/css/app.css \
        pos-terminal/README.md
git commit -m "feat(terminal): move-table action — free-table picker on the order screen

Closes terminal slice 11 (table transfer).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- `DiningOrder.moveToTable` + `DiningService.transferOrder` (all reject paths) + service tests → Task 1. ✓
- `POST .../transfer` cashier-level endpoint + web test → Task 2. ✓
- `DiningApi.transferOrder` + test → Task 3. ✓
- `MoveTargets.freeTargets` + test → Task 4. ✓
- `OrderViewModel.transfer` + async regression → Task 5. ✓
- `MoveTableDialog` + Move-table button + `order.fxml` + flow + README E2E → Task 6. ✓
- No migration; `ModularityTests` run after the backend change (Task 1 Step 6); cashier-level (no `@PreAuthorize`); serviceType untouched (transfer only sets `tableId`). ✓

**Placeholder scan:** No TBD/TODO; every code step shows full code; commands have expected output. ✓

**Type consistency:** `transferOrder(UUID, UUID)` identical across backend service (T1), controller (T2), terminal `DiningApi` (T3), and the VM caller/test overrides (T5). `MoveTargets.freeTargets(List<TableView>, List<OpenOrderView>, UUID)` defined T4, called T6. `transfer(UUID) → boolean` defined T5, called T6. `moveButton` declared in `order.fxml` (T6 Step 3) and injected in the controller (T6 Step 4). `OrderView.tableId()`/`.status()` and `OrderLineView.firedAt()` match the backend records. ✓

**Backend test isolation note:** `DiningTransferServiceTest` appends a random UUID to table labels (labels are unique-constrained) and uses `DatabaseCleaner`; `DiningTransferControllerTest` uses fixed labels but cleans before/after each test — consistent with the existing dining tests.

**Note on `MoveTableDialog` Cancel:** the per-table buttons call `setResult(id) + close()`; the Cancel button (`CANCEL_CLOSE`) closes with no result set, so `showAndWait()` yields empty — the caller treats that as "no move". This is a standard JavaFX Dialog idiom.
