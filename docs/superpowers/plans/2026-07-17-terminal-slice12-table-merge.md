# Terminal Slice 12 — Table merge — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Combine two occupied dine-in orders into one — a new `dining` backend operation (`mergeOrders`) plus a "Merge" action on the terminal order screen that folds another occupied table's order into the one you're viewing.

**Architecture:** Full-stack, two tracks. Backend: `DiningService.mergeOrders` recreates the absorbed order's lines on the survivor (lines can't be reassigned — `OrderLine.orderId` has no setter and the collections use `orphanRemoval=true`), preserving qty/note/course/modifiers/firedAt, then voids the absorbed order; a cashier-level `POST .../merge` endpoint. Terminal: `DiningApi.mergeOrders`, a pure `MergeTargets` occupied-table helper, `OrderViewModel.merge`, a `MergeTableDialog` picker, and an order-screen button that fetches orders → picks a target → merges → refreshes the (now-combined) order.

**Tech Stack:** Java 21, Spring Boot 3.3 / Spring Modulith (backend), JavaFX (terminal), JUnit 5 + AssertJ + MockMvc, Maven (two builds — root reactor for backend, `pos-terminal/pom.xml` for the terminal).

## Global Constraints

- JDK 21: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` before any mvn.
- **Two separate builds.** Backend tests via the ROOT reactor: `./mvnw test -Dtest=...` (uses the `embedded` SQLite profile — no Docker). Terminal tests via `./mvnw -f pos-terminal/pom.xml ... test`.
- **After the backend change, run `ModularityTests`** (`./mvnw test -Dtest=ModularityTests`) — the module-boundary enforcer. This slice stays inside `dining`, so it must still pass with no `allowedDependencies` edit.
- Money is `BigDecimal`. No database migration (merge recreates existing `order_line` rows and reuses the `VOIDED` status). No new Maven dependencies.
- Merge is **cashier-level** — no `@PreAuthorize` on the endpoint.
- **Survivor = the order being merged into (first arg); absorbed = the picked order (second arg).** The absorbed order is set to `VOIDED`. Reject rules: same order → `validation`, non-open survivor/absorbed → `validation`, non-dine-in survivor/absorbed → `validation`, absorbed with no lines → `validation`, unknown order → `notFound`.
- Line copy must preserve: `sku`, `qty`, `note`, `course`, `addedBy`, `addedAt`, every modifier snapshot (`optionId`/`name`/`priceDelta`), and `firedAt` (fired lines stay fired). No product/menu re-resolution.
- **FX-threading (terminal, the recurring bug class):** VM methods synchronous, return plain values; the controller runs them off the FX thread via `FxTasks.run`, reading results in `onDone` via a holder; observables written off-thread only inside `ui.accept`; `onError` never `setText` a bound label. Each new VM method gets an async-dispatcher regression test.

---

## File Structure

| File | Track | Responsibility | Task |
|------|-------|----------------|------|
| `dining/api/DiningService.java` | BE | `mergeOrders(UUID,UUID)` declaration | 1 |
| `dining/application/DefaultDiningService.java` | BE | `mergeOrders` implementation (+ any missing `OrderLine` getters) | 1 |
| `dining/domain/OrderLine.java` | BE | confirm/add `getAddedBy()`/`getAddedAt()`/`getFiredAt()` if missing | 1 |
| `src/test/.../dining/DiningMergeServiceTest.java` | BE | service-level tests | 1 |
| `dining/web/DiningController.java` | BE | `POST .../{id}/merge` | 2 |
| `src/test/.../dining/DiningMergeControllerTest.java` | BE | web test | 2 |
| `pos-terminal/.../api/DiningApi.java` | FE | `mergeOrders` client method | 3 |
| `pos-terminal/.../api/DiningApiTest.java` | FE | client test | 3 |
| `pos-terminal/.../order/MergeTargets.java` | FE | pure occupied-target helper | 4 |
| `pos-terminal/.../order/MergeTargetsTest.java` | FE | helper test | 4 |
| `pos-terminal/.../viewmodel/OrderViewModel.java` | FE | `merge(UUID) → boolean` | 5 |
| `pos-terminal/.../viewmodel/OrderViewModelTest.java` | FE | VM tests (+ async) | 5 |
| `pos-terminal/.../view/MergeTableDialog.java` | FE | occupied-table picker | 6 |
| `pos-terminal/.../view/OrderController.java` | FE | Merge button + flow | 6 |
| `pos-terminal/.../resources/fxml/order.fxml` | FE | `mergeButton` | 6 |
| `pos-terminal/.../resources/css/app.css` | FE | `.merge-box` (unasserted polish) | 6 |
| `pos-terminal/README.md` | FE | slice-12 manual E2E | 6 |

Order: **1 → 2** (backend), then **3 → 4 → 5 → 6** (terminal). Task 5 depends on Task 3; Task 6 depends on Tasks 4 and 5.

---

## Task 1: Backend — `mergeOrders` service

**Files:**
- Modify: `src/main/java/com/company/pos/dining/api/DiningService.java` (declare `mergeOrders`)
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java` (implement)
- Modify (only if getters are missing): `src/main/java/com/company/pos/dining/domain/OrderLine.java`
- Test: `src/test/java/com/company/pos/dining/DiningMergeServiceTest.java`

**Interfaces:**
- Produces: `OrderView DiningService.mergeOrders(UUID survivorOrderId, UUID absorbedOrderId)` — folds the absorbed order's lines onto the survivor (preserving qty/note/course/modifiers/firedAt), voids the absorbed order, returns the survivor's `OrderView`; throws `DomainException` on the reject paths.

- [ ] **Step 1: Confirm the `OrderLine` accessors the copy needs**

The merge copy reads `src.getSku()`, `src.getQty()`, `src.getNote()`, `src.getCourse()`, `src.getAddedBy()`, `src.getAddedAt()`, `src.getModifiers()`, `src.isFired()`, `src.getFiredAt()`, and each modifier's `getOptionId()`/`getName()`/`getPriceDelta()`. `getSku`/`getQty`/`getNote`/`getCourse`/`getModifiers`/`isFired` and the modifier getters are already used by `DefaultDiningService.fireOrder`/`priceCartFor`, so they exist. **Open `OrderLine.java` and confirm `getAddedBy()`, `getAddedAt()`, and `getFiredAt()` exist.** If any is missing, add the minimal getter(s) — package-private, matching the existing getter style in that file — e.g.:

```java
    public String getAddedBy() {
        return addedBy;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public Instant getFiredAt() {
        return firedAt;
    }
```

(Add only the ones that are actually missing. `OrderLine` and its getters are package-private/public consistent with the file — match what is there.)

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/company/pos/dining/DiningMergeServiceTest.java` (mirrors `DiningTransferServiceTest`'s seed setup):

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
class DiningMergeServiceTest {

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
        fake.addProduct(new ErpProduct("FRIES", "Fries", "FOOD", "Food", "bcFRIES",
                "EA", new BigDecimal("12.00"), "SAR", 1, true));
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

    private UUID openWithLine(UUID tableId, String sku, String qty, CourseTag course, String note) {
        UUID orderId = dining.openOrder(new OpenOrderCommand(tableId, null), "alice").id();
        dining.addLine(orderId, new AddLineCommand(sku, new BigDecimal(qty), note, course), "alice");
        return orderId;
    }

    @Test
    void mergeFoldsAbsorbedLinesOntoSurvivor() {
        UUID survivorTable = freshTable("SURV");
        UUID absorbedTable = freshTable("ABSB");
        UUID survivor = openWithLine(survivorTable, "BURGER", "1", CourseTag.MAIN, null);
        UUID absorbed = openWithLine(absorbedTable, "FRIES", "2", CourseTag.STARTER, "extra salt");

        OrderView merged = dining.mergeOrders(survivor, absorbed);

        assertThat(merged.id()).isEqualTo(survivor);
        assertThat(merged.status()).isEqualTo(OrderStatus.OPEN);
        assertThat(merged.lines()).hasSize(2);
        assertThat(merged.lines()).extracting(l -> l.sku()).containsExactlyInAnyOrder("BURGER", "FRIES");
    }

    @Test
    void mergedLinePreservesQtyNoteAndCourse() {
        UUID survivorTable = freshTable("SURV");
        UUID absorbedTable = freshTable("ABSB");
        UUID survivor = openWithLine(survivorTable, "BURGER", "1", CourseTag.MAIN, null);
        UUID absorbed = openWithLine(absorbedTable, "FRIES", "2", CourseTag.STARTER, "extra salt");

        OrderView merged = dining.mergeOrders(survivor, absorbed);

        var fries = merged.lines().stream().filter(l -> l.sku().equals("FRIES")).findFirst().orElseThrow();
        assertThat(fries.qty()).isEqualByComparingTo(new BigDecimal("2"));
        assertThat(fries.note()).isEqualTo("extra salt");
        assertThat(fries.course()).isEqualTo(CourseTag.STARTER);
    }

    @Test
    void firedAbsorbedLineArrivesStillFired() {
        UUID survivorTable = freshTable("SURV");
        UUID absorbedTable = freshTable("ABSB");
        UUID survivor = openWithLine(survivorTable, "BURGER", "1", CourseTag.MAIN, null);
        UUID absorbed = openWithLine(absorbedTable, "FRIES", "2", CourseTag.STARTER, null);
        dining.fireOrder(absorbed, "alice");

        OrderView merged = dining.mergeOrders(survivor, absorbed);

        var fries = merged.lines().stream().filter(l -> l.sku().equals("FRIES")).findFirst().orElseThrow();
        assertThat(fries.firedAt()).isNotNull(); // fired state rides along
    }

    @Test
    void absorbedOrderIsVoidedAndItsTableFreed() {
        UUID survivorTable = freshTable("SURV");
        UUID absorbedTable = freshTable("ABSB");
        UUID survivor = openWithLine(survivorTable, "BURGER", "1", CourseTag.MAIN, null);
        UUID absorbed = openWithLine(absorbedTable, "FRIES", "2", CourseTag.STARTER, null);

        dining.mergeOrders(survivor, absorbed);

        assertThat(dining.getOrder(absorbed).status()).isEqualTo(OrderStatus.VOIDED);
        // the absorbed table now has no OPEN order → it would be reusable
        assertThat(dining.listOpenOrders().stream().anyMatch(o -> o.tableId().equals(absorbedTable)))
                .isFalse();
    }

    @Test
    void rejectsMergeIntoItself() {
        UUID t = freshTable("SURV");
        UUID order = openWithLine(t, "BURGER", "1", CourseTag.MAIN, null);
        assertThatThrownBy(() -> dining.mergeOrders(order, order))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsAbsorbedWithNoLines() {
        UUID survivorTable = freshTable("SURV");
        UUID absorbedTable = freshTable("ABSB");
        UUID survivor = openWithLine(survivorTable, "BURGER", "1", CourseTag.MAIN, null);
        UUID emptyAbsorbed = dining.openOrder(new OpenOrderCommand(absorbedTable, null), "alice").id();
        assertThatThrownBy(() -> dining.mergeOrders(survivor, emptyAbsorbed))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsMergeOfNonOpenAbsorbed() {
        UUID survivorTable = freshTable("SURV");
        UUID absorbedTable = freshTable("ABSB");
        UUID survivor = openWithLine(survivorTable, "BURGER", "1", CourseTag.MAIN, null);
        UUID absorbed = openWithLine(absorbedTable, "FRIES", "1", CourseTag.STARTER, null);
        dining.voidOrder(absorbed, "test");
        assertThatThrownBy(() -> dining.mergeOrders(survivor, absorbed))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsUnknownOrder() {
        UUID survivorTable = freshTable("SURV");
        UUID survivor = openWithLine(survivorTable, "BURGER", "1", CourseTag.MAIN, null);
        assertThatThrownBy(() -> dining.mergeOrders(survivor, UUID.randomUUID()))
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=DiningMergeServiceTest`
Expected: compilation failure — `mergeOrders` does not exist on `DiningService`.

- [ ] **Step 4: Declare the service method**

In `DiningService.java`, add after the `transferOrder` declaration:

```java
    /** Merges the absorbed OPEN dine-in order's lines into the survivor OPEN dine-in order
     *  (preserving qty/note/course/modifiers/fired state), then voids the absorbed order.
     *  Fails if either order is not open or not dine-in, they are the same order, or the
     *  absorbed order has no lines. Returns the survivor. */
    OrderView mergeOrders(UUID survivorOrderId, UUID absorbedOrderId);
```

- [ ] **Step 5: Implement the service method**

In `DefaultDiningService.java`, add after the `transferOrder` method (around line 186). Confirm the imports for `OrderLine`, `OrderLineModifier`, `ServiceType`, and `Identifiers` are present (they are used elsewhere in the file — `addLine`/`fireOrder`/`priceCartFor`); add any that are missing.

```java
    @Override
    public OrderView mergeOrders(UUID survivorOrderId, UUID absorbedOrderId) {
        DiningOrder survivor = load(survivorOrderId);
        DiningOrder absorbed = load(absorbedOrderId);
        requireOpen(survivor);
        requireOpen(absorbed);
        if (survivorOrderId.equals(absorbedOrderId)) {
            throw DomainException.validation("Cannot merge an order into itself");
        }
        if (survivor.getServiceType() != ServiceType.DINE_IN
                || absorbed.getServiceType() != ServiceType.DINE_IN) {
            throw DomainException.validation("Only dine-in orders can be merged");
        }
        if (absorbed.getLines().isEmpty()) {
            throw DomainException.validation("The selected order has no lines to merge");
        }
        for (OrderLine src : absorbed.getLines()) {
            OrderLine copy = new OrderLine(Identifiers.newId(), survivor.getId(), src.getSku(),
                    src.getQty(), src.getNote(), src.getCourse(), src.getAddedBy(), src.getAddedAt());
            for (OrderLineModifier m : src.getModifiers()) {
                copy.addModifier(m.getOptionId(), m.getName(), m.getPriceDelta());
            }
            if (src.isFired()) {
                copy.fire(src.getFiredAt());
            }
            survivor.addLine(copy);
        }
        absorbed.voidOrder();
        return toOrderView(orders.save(survivor));
    }
```

- [ ] **Step 6: Run the merge test + ModularityTests**

Run: `./mvnw test -Dtest=DiningMergeServiceTest`
Expected: PASS (8 tests).
Run: `./mvnw test -Dtest=ModularityTests`
Expected: PASS (boundary unchanged — the work stayed inside `dining`).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/pos/dining/api/DiningService.java \
        src/main/java/com/company/pos/dining/application/DefaultDiningService.java \
        src/main/java/com/company/pos/dining/domain/OrderLine.java \
        src/test/java/com/company/pos/dining/DiningMergeServiceTest.java
git commit -m "feat(dining): mergeOrders — fold one open order into another, void the source

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

(If `OrderLine.java` was not modified in Step 1, drop it from the `git add`.)

---

## Task 2: Backend — merge endpoint

**Files:**
- Modify: `src/main/java/com/company/pos/dining/web/DiningController.java` (add the endpoint)
- Test: `src/test/java/com/company/pos/dining/DiningMergeControllerTest.java`

**Interfaces:**
- Consumes: `DiningService.mergeOrders` (Task 1).
- Produces: `POST /dining/orders/{survivorOrderId}/merge?absorbedOrderId=<uuid>` → `OrderView` (200), cashier-allowed (no `@PreAuthorize`).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/dining/DiningMergeControllerTest.java` (mixes `DiningService` for fixtures + MockMvc for the call, mirroring `DiningTransferControllerTest`):

```java
package com.company.pos.dining;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.CourseTag;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
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
class DiningMergeControllerTest {

    @Autowired MockMvc mvc;
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

    private static RequestPostProcessor cashier() {
        return jwt().jwt(j -> j.subject("cashier"))
                .authorities(new SimpleGrantedAuthority("ROLE_CASHIER"));
    }

    private UUID openWithLine(String label) {
        UUID table = dining.registerTable(new RegisterTableCommand(label, 4)).id();
        UUID orderId = dining.openOrder(new OpenOrderCommand(table, null), "cashier").id();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("1"), null, CourseTag.MAIN),
                "cashier");
        return orderId;
    }

    @Test
    void cashierMergesTwoOrders() throws Exception {
        UUID survivor = openWithLine("MRG-SURV");
        UUID absorbed = openWithLine("MRG-ABSB");

        mvc.perform(post("/dining/orders/" + survivor + "/merge")
                        .param("absorbedOrderId", absorbed.toString())
                        .with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(survivor.toString()))
                .andExpect(jsonPath("$.lines.length()").value(2));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=DiningMergeControllerTest`
Expected: failure — no `/merge` mapping (404/500), so the assertions don't match.

- [ ] **Step 3: Add the endpoint**

In `DiningController.java`, add after the `transferOrder` endpoint (end of the class, before the closing brace). No `@PreAuthorize` — cashier-level:

```java
    @PostMapping("/dining/orders/{survivorOrderId}/merge")
    OrderView mergeOrders(@PathVariable UUID survivorOrderId, @RequestParam UUID absorbedOrderId) {
        return dining.mergeOrders(survivorOrderId, absorbedOrderId);
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=DiningMergeControllerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/pos/dining/web/DiningController.java \
        src/test/java/com/company/pos/dining/DiningMergeControllerTest.java
git commit -m "feat(dining): POST /dining/orders/{id}/merge (cashier-level)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Terminal — `DiningApi.mergeOrders`

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/DiningApiTest.java`

**Interfaces:**
- Produces: `OrderView DiningApi.mergeOrders(UUID survivorOrderId, UUID absorbedOrderId)` — `POST /dining/orders/{survivorOrderId}/merge?absorbedOrderId=...`, returns the merged `OrderView`.

- [ ] **Step 1: Write the failing test**

Add to `DiningApiTest.java` (before the class closing brace). `ORDER_JSON` parses to an OPEN order; `ORDER_ID` is `33333333-3333-3333-3333-333333333333` (confirm both fixtures exist — they are used by the slice-11 `transferOrder` test in the same file):

```java
    @Test
    void mergeOrdersPostsAbsorbedIdAndParsesOrder() throws Exception {
        try (StubServer stub = new StubServer(200, ORDER_JSON, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            java.util.UUID absorbed = java.util.UUID.fromString("55555555-5555-5555-5555-555555555555");
            OrderView v = api.mergeOrders(ORDER_ID, absorbed);
            assertEquals("OPEN", v.status());
            assertEquals("POST", stub.lastMethod);
            assertEquals("/dining/orders/33333333-3333-3333-3333-333333333333/merge", stub.lastPath);
            assertEquals("absorbedOrderId=55555555-5555-5555-5555-555555555555", stub.lastQuery);
        }
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml -Dtest=DiningApiTest test`
Expected: compilation failure — `mergeOrders` does not exist.

- [ ] **Step 3: Implement the method**

In `DiningApi.java`, add after the `transferOrder` method:

```java
    /** POST /dining/orders/{survivorOrderId}/merge?absorbedOrderId=... — folds another order in. */
    public OrderView mergeOrders(UUID survivorOrderId, UUID absorbedOrderId) {
        return client.post("/dining/orders/" + survivorOrderId + "/merge?absorbedOrderId=" + absorbedOrderId,
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
git commit -m "feat(terminal): DiningApi.mergeOrders

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Terminal — `MergeTargets` occupied-table helper

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/order/MergeTargets.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/order/MergeTargetsTest.java`

**Interfaces:**
- Produces: `static List<OpenOrderView> MergeTargets.occupiedTargets(List<TableView> tables, List<OpenOrderView> openOrders, UUID currentTableId)` — the open **dine-in** orders on active tables other than the current table (candidates to absorb into the current order).

**Note on serviceType:** `OpenOrderView` carries `String serviceType` (values `"DINE_IN"` / `"QUICK_SERVICE"`, from slice 7). Dine-in filtering is done on the order's `serviceType`, so a takeaway/counter order is excluded even though its pseudo-table is active. The active-table filter still applies (an order on a deactivated table is excluded).

- [ ] **Step 1: Write the failing test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/order/MergeTargetsTest.java`:

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

class MergeTargetsTest {

    private final UUID t1 = UUID.randomUUID(); // current (occupied)
    private final UUID t2 = UUID.randomUUID(); // occupied dine-in → candidate
    private final UUID t3 = UUID.randomUUID(); // free (no order)
    private final UUID t4 = UUID.randomUUID(); // occupied but inactive table
    private final UUID t5 = UUID.randomUUID(); // occupied takeaway (QUICK_SERVICE)

    private TableView table(UUID id, boolean active) {
        return new TableView(id, "T", 4, active);
    }

    private OpenOrderView orderOn(UUID tableId, String serviceType) {
        return new OpenOrderView(UUID.randomUUID(), tableId, "T", Instant.now(), 1, serviceType);
    }

    @Test
    void keepsOtherOccupiedDineInExcludesRest() {
        List<TableView> tables = List.of(
                table(t1, true), table(t2, true), table(t3, true), table(t4, false), table(t5, true));
        List<OpenOrderView> open = List.of(
                orderOn(t1, "DINE_IN"),   // current → excluded
                orderOn(t2, "DINE_IN"),   // candidate → kept
                orderOn(t4, "DINE_IN"),   // inactive table → excluded
                orderOn(t5, "QUICK_SERVICE")); // takeaway → excluded
        // t3 has no order at all → not a candidate

        List<UUID> tableIds = MergeTargets.occupiedTargets(tables, open, t1).stream()
                .map(OpenOrderView::tableId).collect(Collectors.toList());

        assertEquals(List.of(t2), tableIds);
    }

    @Test
    void emptyWhenNoOtherOccupiedDineIn() {
        List<TableView> tables = List.of(table(t1, true), table(t3, true));
        List<OpenOrderView> open = List.of(orderOn(t1, "DINE_IN")); // only the current table
        assertTrue(MergeTargets.occupiedTargets(tables, open, t1).isEmpty());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=MergeTargetsTest test`
Expected: compilation failure — `MergeTargets` does not exist.

- [ ] **Step 3: Create the helper**

Create `pos-terminal/src/main/java/com/company/pos/terminal/order/MergeTargets.java`:

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
 * the open orders an order may be merged with — open DINE_IN orders on active tables other than the
 * current table. No FX, no I/O; unit-tested headlessly. Mirror of {@code MoveTargets} (which returns
 * FREE tables); this returns OCCUPIED dine-in orders.
 */
public final class MergeTargets {

    private MergeTargets() {}

    public static List<OpenOrderView> occupiedTargets(List<TableView> tables,
            List<OpenOrderView> openOrders, UUID currentTableId) {
        Set<UUID> activeTableIds = tables.stream()
                .filter(TableView::active)
                .map(TableView::id)
                .collect(Collectors.toSet());
        return openOrders.stream()
                .filter(o -> !o.tableId().equals(currentTableId))
                .filter(o -> "DINE_IN".equals(o.serviceType()))
                .filter(o -> activeTableIds.contains(o.tableId()))
                .collect(Collectors.toList());
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=MergeTargetsTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/order/MergeTargets.java \
        pos-terminal/src/test/java/com/company/pos/terminal/order/MergeTargetsTest.java
git commit -m "feat(terminal): MergeTargets occupied-dine-in helper

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Terminal — `OrderViewModel.merge`

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/OrderViewModel.java` (add after `transfer`)
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/OrderViewModelTest.java`

**Interfaces:**
- Consumes: `DiningApi.mergeOrders(UUID, UUID)` (Task 3).
- Produces: `boolean OrderViewModel.merge(UUID absorbedOrderId)` — true on success; false + `errorMessage` on `ApiException`.

- [ ] **Step 1: Write the failing tests**

Add to `OrderViewModelTest.java` (before the class closing brace). These mirror the slice-11 `transfer` tests exactly (same `orderWith`/`cache()`/`orderId`/`firedLineId` helpers and the 2-arg + 3-arg `OrderViewModel` constructors):

```java
    @Test
    void mergeReturnsTrueOnSuccess() {
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
                    public OrderView mergeOrders(UUID survivorId, UUID absorbedId) {
                        captured[0] = absorbedId;
                        return orderWith(List.of(line));
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        UUID absorbed = UUID.randomUUID();
        assertTrue(vm.merge(absorbed));
        assertEquals(absorbed, captured[0]);
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void mergeSurfacesErrorAndReturnsFalse() {
        OrderLineView line =
                new OrderLineView(firedLineId, "BURGER", new BigDecimal("1"), null, "MAIN", null, List.of());
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(line));
                    }

                    @Override
                    public OrderView mergeOrders(UUID survivorId, UUID absorbedId) {
                        throw new ApiException(400,
                                new ProblemDetail("Bad Request", 400, "The selected order has no lines to merge"),
                                "HTTP 400");
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        assertFalse(vm.merge(UUID.randomUUID()));
        assertEquals("The selected order has no lines to merge", vm.errorMessage().get());
    }

    @Test
    void deferredDispatcherHoldsMergeErrorUntilDrained() {
        OrderLineView line =
                new OrderLineView(firedLineId, "BURGER", new BigDecimal("1"), null, "MAIN", null, List.of());
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(line));
                    }

                    @Override
                    public OrderView mergeOrders(UUID survivorId, UUID absorbedId) {
                        throw new ApiException(400,
                                new ProblemDetail("Bad Request", 400, "The selected order has no lines to merge"),
                                "HTTP 400");
                    }
                };
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        OrderViewModel vm = new OrderViewModel(dining, cache(), queue::add);
        vm.load(orderId);
        while (!queue.isEmpty()) queue.poll().run();       // drain load → baseline
        boolean result = vm.merge(UUID.randomUUID());
        assertFalse(result);                               // synchronous return
        assertEquals("", vm.errorMessage().get());         // deferred: error not applied yet
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals("The selected order has no lines to merge", vm.errorMessage().get());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=OrderViewModelTest test`
Expected: compilation failure — `merge` does not exist.

- [ ] **Step 3: Implement the method**

In `OrderViewModel.java`, add after the `transfer` method:

```java
    /**
     * Merges another order into the current one. Returns true on success; on ApiException surfaces
     * the message via errorMessage and returns false. The caller reloads the current order on
     * success (the survivor is the viewed order).
     */
    public boolean merge(UUID absorbedOrderId) {
        try {
            dining.mergeOrders(order.id(), absorbedOrderId);
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
git commit -m "feat(terminal): OrderViewModel.merge

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Terminal — Merge-table dialog + order-screen button

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/MergeTableDialog.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/OrderController.java`
- Modify: `pos-terminal/src/main/resources/fxml/order.fxml`
- Modify: `pos-terminal/src/main/resources/css/app.css`
- Modify: `pos-terminal/README.md`

**Interfaces:**
- Consumes: `MergeTargets.occupiedTargets` (Task 4), `OrderViewModel.merge`/`setError`/`currentOrder`/`load` (Task 5 + existing), `DiningApi.tables()`/`openOrders()` (existing).
- Produces: the working "Merge" flow. Dialog rendering is display-dependent → verified by the full suite compiling+passing plus the README manual E2E (no TestFX).

- [ ] **Step 1: Create `MergeTableDialog`**

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/MergeTableDialog.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.OpenOrderView;
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
 * Modal occupied-table picker for merging. Lists each candidate open order (its table label + line
 * count) as a full-width touch button; tapping one closes the dialog with that order's id. Pure
 * view; the caller performs the merge. Display-dependent — exercised by the manual E2E.
 *
 * @return the chosen order id, or empty on cancel.
 */
public final class MergeTableDialog {

    private MergeTableDialog() {}

    public static Optional<UUID> promptForTarget(List<OpenOrderView> targets) {
        Dialog<UUID> dialog = new Dialog<>();
        dialog.setTitle("Merge tables");
        dialog.setHeaderText("Merge which table into this order?");
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(cancel);
        dialog.getDialogPane().getStyleClass().add("merge-dialog");

        VBox box = new VBox(8);
        box.getStyleClass().add("merge-box");
        for (OpenOrderView t : targets) {
            String items = t.lineCount() == 1 ? "1 item" : t.lineCount() + " items";
            Button b = new Button(t.tableLabel() + "  ·  " + items);
            b.getStyleClass().add("btn-secondary");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setOnAction(e -> {
                dialog.setResult(t.orderId());
                dialog.close();
            });
            box.getChildren().add(b);
        }
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        dialog.getDialogPane().setContent(scroll);

        return dialog.showAndWait();
    }
}
```

(Confirm `OpenOrderView` exposes `orderId()`, `tableLabel()`, and `lineCount()` — it does, per the slice-7 DTO `OpenOrderView(UUID orderId, UUID tableId, String tableLabel, Instant openedAt, int lineCount, String serviceType)`. If an accessor name differs, adapt.)

- [ ] **Step 2: Add the CSS (polish, unasserted)**

Append to `pos-terminal/src/main/resources/css/app.css`:

```css
/* Slice 12 — merge-table picker */
.merge-box { -fx-padding: 8; }
```

- [ ] **Step 3: Add `mergeButton` to `order.fxml`**

In `order.fxml`, find the action-bar HBox (which since slices 10–11 holds `fireButton`, `voidButton`, `moveButton`):

```xml
      <HBox spacing="12">
        <Button fx:id="fireButton" text="Fire to kitchen" styleClass="btn-primary" HBox.hgrow="ALWAYS" maxWidth="Infinity"/>
        <Button fx:id="voidButton" text="Void order" styleClass="btn-danger"/>
        <Button fx:id="moveButton" text="Move table" styleClass="btn-secondary"/>
      </HBox>
```

and add the merge button after `moveButton`:

```xml
      <HBox spacing="12">
        <Button fx:id="fireButton" text="Fire to kitchen" styleClass="btn-primary" HBox.hgrow="ALWAYS" maxWidth="Infinity"/>
        <Button fx:id="voidButton" text="Void order" styleClass="btn-danger"/>
        <Button fx:id="moveButton" text="Move table" styleClass="btn-secondary"/>
        <Button fx:id="mergeButton" text="Merge" styleClass="btn-secondary"/>
      </HBox>
```

(If the button layout differs from the above — e.g. buttons split across two rows — place `mergeButton` beside `moveButton` and note it. The exact container is confirmed by reading the file.)

- [ ] **Step 4: Wire the merge flow in `OrderController`**

In `OrderController.java`:

(a) The imports `OpenOrderView`, `TableView`, `AtomicReference`, and `java.util.List`/`Optional`/`UUID` were added by slice 11's `moveTable`; add only what is missing:

```java
import com.company.pos.terminal.order.MergeTargets;
```

(b) Add the field (with the other `@FXML` buttons, beside `moveButton`):

```java
    @FXML private Button mergeButton;
```

(c) In `initialize()`, alongside the other button handlers and the initial-disable block, add:

```java
        mergeButton.setOnAction(e -> mergeTable());
        mergeButton.setDisable(true);
```

(d) In `afterCatalogLoaded()`, where `payButton`/`fireButton`/`voidButton`/`moveButton` are enabled, add:

```java
        mergeButton.setDisable(false);
```

(e) Add the flow method (near `moveTable()`). It mirrors `moveTable` but fetches OCCUPIED targets and, on success, **reloads the current order** (the survivor is the viewed order — stay on the screen):

```java
    /** Merge another occupied table's order into this one: fetch targets → pick → merge → reload. */
    private void mergeTable() {
        AtomicReference<List<OpenOrderView>> candidates = new AtomicReference<>();
        FxTasks.run(
                () -> {
                    List<TableView> tables = services.diningApi.tables();
                    List<OpenOrderView> open = services.diningApi.openOrders();
                    candidates.set(MergeTargets.occupiedTargets(tables, open, vm.currentOrder().tableId()));
                },
                () -> {
                    List<OpenOrderView> targets = candidates.get();
                    if (targets.isEmpty()) {
                        vm.setError("No other occupied tables to merge");
                        return;
                    }
                    Optional<UUID> absorbed = MergeTableDialog.promptForTarget(targets);
                    if (absorbed.isEmpty()) {
                        return;
                    }
                    boolean[] holder = {false};
                    FxTasks.run(
                            () -> holder[0] = vm.merge(absorbed.get()),
                            () -> {
                                if (holder[0]) {
                                    vm.load(orderId);
                                }
                            },
                            err -> LOG.log(System.Logger.Level.ERROR, "Failed to merge orders", err));
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to load orders for merge", err));
    }
```

(Confirm the controller's own current-order id field is named `orderId` — it is used by slice-11's inner `moveTable` navigation context and by `vm.load`. If it is named differently, use the real field. `vm.load(...)` is the existing method the controller already calls to (re)load the order screen.)

- [ ] **Step 5: Run the full terminal suite**

Run: `./mvnw -f pos-terminal/pom.xml clean test`
Expected: BUILD SUCCESS, all tests pass (this slice added the Task 3/4/5 tests to the baseline; Task 6 adds no headless test — the dialog/button are manual-E2E). If compilation fails on an import or fx:id, fix against this task's edits.

- [ ] **Step 6: Add the Slice 12 manual-E2E section to the README**

Append to `pos-terminal/README.md` (after the Slice 11 section):

```markdown
## Slice 12 — Table merge (manual E2E)

**Prereq:** backend on `embedded,dev`; log in (`manager`/`manager`). Seeded floor has several
dine-in tables (T1–T6) plus counters.

1. Open two tables (e.g. T1 and T2), add a line or two to each; fire one of T2's lines.
2. On T1's order screen, tap **Merge**. The picker lists only **other occupied dine-in** tables
   (T2 appears with its line count; free tables and counters/takeaway do not). Tap T2.
3. The screen stays on T1 and now shows both tables' lines; T2's fired line arrived **still fired**.
   Go back to the table map — **T2 is free again** (its order is voided).
4. **No targets:** with only one occupied table, tapping **Merge** shows the error line
   "No other occupied tables to merge".
```

- [ ] **Step 7: Final full-suite run and commit**

Run: `./mvnw -f pos-terminal/pom.xml clean test`
Expected: BUILD SUCCESS, all green.

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/MergeTableDialog.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/OrderController.java \
        pos-terminal/src/main/resources/fxml/order.fxml \
        pos-terminal/src/main/resources/css/app.css \
        pos-terminal/README.md
git commit -m "feat(terminal): merge-table action — occupied-table picker on the order screen

Closes terminal slice 12 (table merge).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- `DiningService.mergeOrders` (recreate lines + preserve qty/note/course/modifiers/firedAt + void absorbed) + all reject paths + service tests → Task 1. ✓
- `POST .../merge` cashier-level endpoint + web test → Task 2. ✓
- `DiningApi.mergeOrders` + test → Task 3. ✓
- `MergeTargets.occupiedTargets` (occupied, dine-in, active, not-current) + test → Task 4. ✓
- `OrderViewModel.merge` + async regression → Task 5. ✓
- `MergeTableDialog` + Merge button + `order.fxml` + flow (survivor stays → `vm.load`) + README E2E → Task 6. ✓
- No migration; `ModularityTests` run after the backend change (Task 1 Step 6); cashier-level (no `@PreAuthorize`); reuse of `VOIDED` (no new status); dine-in-only restriction (Task 1 Step 5). ✓

**Placeholder scan:** No TBD/TODO; every code step shows full code; commands have expected output. The two "confirm the accessor/field name and adapt" notes (Task 1 Step 1 getters; Task 6 `orderId` field) are deliberate guardrails against a name mismatch, each with the concrete fallback shown — not placeholders. ✓

**Type consistency:** `mergeOrders(UUID survivorOrderId, UUID absorbedOrderId)` identical across backend service (T1), controller (T2), terminal `DiningApi` (T3), and the VM caller/test overrides (T5). `MergeTargets.occupiedTargets(List<TableView>, List<OpenOrderView>, UUID) → List<OpenOrderView>` defined T4, called T6. `merge(UUID) → boolean` defined T5, called T6. `MergeTableDialog.promptForTarget(List<OpenOrderView>) → Optional<UUID>` defined T6 Step 1, called T6 Step 4. `mergeButton` declared in `order.fxml` (T6 Step 3) and injected in the controller (T6 Step 4). `OpenOrderView.orderId()/.tableId()/.tableLabel()/.lineCount()/.serviceType()` and `OrderView.id()/.status()/.lines()`, `OrderLineView.sku()/.qty()/.note()/.course()/.firedAt()` match the existing records. ✓

**Backend test isolation note:** `DiningMergeServiceTest` appends a random UUID to table labels (labels are unique-constrained) and uses `DatabaseCleaner`; `DiningMergeControllerTest` uses fixed labels but cleans before/after each test — consistent with the existing dining tests.

**Note on the line-copy accessors:** Task 1 Step 1 explicitly confirms `getAddedBy()`/`getAddedAt()`/`getFiredAt()` before the copy relies on them, adding the minimal getter(s) if absent — this is the one place the merge reaches into `OrderLine` beyond what `fireOrder`/`priceCartFor` already read.

**Note on `MergeTableDialog` Cancel:** `showAndWait()` returns `Optional.empty()` when the Cancel button (`CANCEL_CLOSE`) closes the dialog without a per-table button having called `setResult`; the caller treats empty as "no merge". Standard JavaFX Dialog idiom (same as slice-11 `MoveTableDialog`, here returned directly since the result type is already `Optional<UUID>`).
