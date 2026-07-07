# Phase 12 — Kitchen Station Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an incremental "fire to kitchen" action to dining orders and a new `kitchen` module that routes fired lines to prep stations and prints one ticket per station.

**Architecture:** `dining` gains per-line fired-state and a `fireOrder` method that stamps un-fired lines and publishes a `KitchenTicketsFired` fact. A new `kitchen` module owns a POS-local `sku → station` overlay and a passive `@ApplicationModuleListener` that resolves each fired line's station, groups by station, and prints via a dedicated `KitchenPrinter` port. Communication is one-way (`kitchen → dining :: api`); the module graph stays acyclic.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Modulith (event publication registry / transactional outbox), Spring Data JPA, Flyway (store-server) / Hibernate ddl-auto (embedded), JUnit 5 + AssertJ + Awaitility + MockMvc.

## Global Constraints

- **JDK 21 required.** Run `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` before every Maven command.
- **Maven, not Gradle:** `./mvnw`.
- **Money is `BigDecimal`**, never `double`. Monetary columns are `NUMERIC(19,4)`.
- **UUID PKs stored as `VARCHAR(36)`** via `@JdbcTypeCode(SqlTypes.VARCHAR)` (see `OrderLine`).
- **Flyway versions are globally sequential.** Latest existing is **V30**; use **V31** then **V32** in that order. Per-module directories under `src/main/resources/db/migration/<module>/`. Only store-server runs Flyway; embedded uses `ddl-auto`.
- **Authorization is method security** (`@PreAuthorize`), not URL rules. Admin actions: `@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")`. Firing is routine floor work — no `@PreAuthorize` (any authenticated caller), matching `addLine`.
- **Typed config lives in `configuration`** (`SettingKey` enum), read via `ConfigurationService.getString(...)`.
- **Module boundaries are enforced** by `ModularityTests` (`modules.verify()`). A module may depend only on another's `@NamedInterface`. Update `package-info.java` `allowedDependencies` when adding a cross-module dependency, and never import another module's domain/infrastructure.
- **Events for facts, calls for queries.** Side-effect listeners run after commit, async, in their own transaction, and must never block or roll back the publisher.
- After any change, re-run the affected module's tests **and** `ModularityTests`.

---

## File Structure

**Task 1 — dining fire state (modify `dining`):**
- Modify: `src/main/java/com/company/pos/dining/domain/OrderLine.java` (add `firedAt`)
- Create: `src/main/java/com/company/pos/dining/api/KitchenTicketsFired.java` (event + nested `FiredLine`, `FiredModifier`)
- Create: `src/main/java/com/company/pos/dining/api/package-info.java` (`@NamedInterface("api")` — dining's first external consumer arrives in Task 3)
- Modify: `src/main/java/com/company/pos/dining/api/OrderLineView.java` (add `firedAt`)
- Modify: `src/main/java/com/company/pos/dining/api/DiningService.java` (add `fireOrder`)
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java` (inject `DomainEvents`; `fireOrder`; lock fired lines in `updateLine`/`removeLine`; map `firedAt`)
- Modify: `src/main/java/com/company/pos/dining/web/DiningController.java` (POST fire endpoint)
- Create: `src/main/resources/db/migration/dining/V32__add_order_line_fired_at.sql`
- Test: `src/test/java/com/company/pos/dining/DiningFireServiceTest.java`

**Task 2 — kitchen station overlay (new `kitchen` module):**
- Create: `src/main/java/com/company/pos/kitchen/package-info.java`
- Create: `src/main/java/com/company/pos/kitchen/api/package-info.java`
- Create: `src/main/java/com/company/pos/kitchen/api/KitchenService.java`
- Create: `src/main/java/com/company/pos/kitchen/api/StationAssignmentView.java`
- Create: `src/main/java/com/company/pos/kitchen/api/AssignStationCommand.java`
- Create: `src/main/java/com/company/pos/kitchen/domain/StationAssignment.java`
- Create: `src/main/java/com/company/pos/kitchen/infrastructure/StationAssignmentRepository.java`
- Create: `src/main/java/com/company/pos/kitchen/application/DefaultKitchenService.java`
- Create: `src/main/java/com/company/pos/kitchen/web/KitchenController.java`
- Modify: `src/main/java/com/company/pos/configuration/api/SettingKey.java` (add `KITCHEN_DEFAULT_STATION`)
- Create: `src/main/resources/db/migration/kitchen/V31__create_kitchen_station_assignment.sql`
- Modify: `src/main/resources/application-store-server.yml` (append kitchen to Flyway `locations`)
- Test: `src/test/java/com/company/pos/kitchen/KitchenStationServiceTest.java`
- Test: `src/test/java/com/company/pos/kitchen/KitchenControllerTest.java`

**Task 3 — printer port + routing listener (modify `device`, `kitchen`):**
- Create: `src/main/java/com/company/pos/device/api/KitchenPrinter.java`
- Create: `src/main/java/com/company/pos/device/infrastructure/InMemoryKitchenPrinter.java`
- Modify: `src/main/java/com/company/pos/kitchen/package-info.java` (add `dining :: api`, `device :: api`)
- Create: `src/main/java/com/company/pos/kitchen/application/KitchenTicketsFiredListener.java`
- Test: `src/test/java/com/company/pos/kitchen/KitchenRoutingTest.java`

---

## Task 1: Fire action on the dining order

Adds per-line fired-state, the `fireOrder` service method (which publishes the `KitchenTicketsFired` fact), fired-line locking, and the HTTP endpoint. No consumer of the event exists yet — Task 3 adds the listener; until then the event has no subscribers, so no outbox rows are created. This task is fully testable at the `dining` service layer on order state.

**Files:**
- Create: `src/main/java/com/company/pos/dining/api/KitchenTicketsFired.java`
- Create: `src/main/java/com/company/pos/dining/api/package-info.java`
- Modify: `src/main/java/com/company/pos/dining/domain/OrderLine.java`
- Modify: `src/main/java/com/company/pos/dining/api/OrderLineView.java`
- Modify: `src/main/java/com/company/pos/dining/api/DiningService.java`
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java`
- Modify: `src/main/java/com/company/pos/dining/web/DiningController.java`
- Create: `src/main/resources/db/migration/dining/V32__add_order_line_fired_at.sql`
- Test: `src/test/java/com/company/pos/dining/DiningFireServiceTest.java`

**Interfaces:**
- Consumes: existing `DefaultDiningService` fields (`orders`, `tables`, `products`), `ProductCatalog.findBySku` → `Optional<ProductView>` with `ProductView.name()`, `com.company.pos.common.events.DomainEvents.publish(DomainEvent)`.
- Produces (Task 3 relies on these exact names):
  - `DiningService.fireOrder(UUID orderId, String firedBy) : OrderView`
  - `com.company.pos.dining.api.KitchenTicketsFired(UUID orderId, String tableLabel, java.time.Instant firedAt, List<FiredLine> lines) implements DomainEvent`
    - nested `KitchenTicketsFired.FiredLine(String sku, String name, BigDecimal qty, String note, CourseTag course, List<FiredModifier> modifiers)`
    - nested `KitchenTicketsFired.FiredModifier(String name)`
  - `OrderLineView` gains a `java.time.Instant firedAt` component (positioned after `course`, before `modifiers`).
  - `com.company.pos.dining.api` becomes a `@NamedInterface("api")`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/dining/DiningFireServiceTest.java`:

```java
package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.CourseTag;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OrderView;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Instant;
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
class DiningFireServiceTest {

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

    private UUID openOrderOnFreshTable() {
        UUID tableId = dining.registerTable(new RegisterTableCommand("L" + UUID.randomUUID(), 4)).id();
        return dining.openOrder(new OpenOrderCommand(tableId, null), "alice").id();
    }

    @Test
    void fireStampsAllUnfiredLines() {
        UUID orderId = openOrderOnFreshTable();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("1"), null, CourseTag.MAIN), "alice");

        OrderView fired = dining.fireOrder(orderId, "alice");

        assertThat(fired.lines()).hasSize(1);
        assertThat(fired.lines().get(0).firedAt()).isNotNull();
    }

    @Test
    void secondFireRoutesOnlyNewlyAddedLines() {
        UUID orderId = openOrderOnFreshTable();
        UUID burgerLineId = dining.addLine(orderId,
                new AddLineCommand("BURGER", new BigDecimal("1"), null, CourseTag.MAIN), "alice")
                .lines().get(0).id();
        dining.fireOrder(orderId, "alice");
        Instant burgerFiredAt = dining.getOrder(orderId).lines().stream()
                .filter(l -> l.id().equals(burgerLineId)).findFirst().orElseThrow().firedAt();

        dining.addLine(orderId, new AddLineCommand("FRIES", new BigDecimal("1"), null, CourseTag.MAIN), "alice");
        OrderView afterSecondFire = dining.fireOrder(orderId, "alice");

        // the burger's firedAt is unchanged; the fries line is now fired too
        assertThat(afterSecondFire.lines()).allSatisfy(l -> assertThat(l.firedAt()).isNotNull());
        Instant burgerAfter = afterSecondFire.lines().stream()
                .filter(l -> l.id().equals(burgerLineId)).findFirst().orElseThrow().firedAt();
        assertThat(burgerAfter).isEqualTo(burgerFiredAt);
    }

    @Test
    void firingWithNothingUnfiredIsRejected() {
        UUID orderId = openOrderOnFreshTable();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("1"), null, CourseTag.MAIN), "alice");
        dining.fireOrder(orderId, "alice");

        assertThatThrownBy(() -> dining.fireOrder(orderId, "alice"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void updatingAFiredLineIsRejected() {
        UUID orderId = openOrderOnFreshTable();
        UUID lineId = dining.addLine(orderId,
                new AddLineCommand("BURGER", new BigDecimal("1"), null, CourseTag.MAIN), "alice")
                .lines().get(0).id();
        dining.fireOrder(orderId, "alice");

        assertThatThrownBy(() -> dining.updateLine(orderId, lineId, new BigDecimal("2"), null, CourseTag.MAIN))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void removingAFiredLineIsRejected() {
        UUID orderId = openOrderOnFreshTable();
        UUID lineId = dining.addLine(orderId,
                new AddLineCommand("BURGER", new BigDecimal("1"), null, CourseTag.MAIN), "alice")
                .lines().get(0).id();
        dining.fireOrder(orderId, "alice");

        assertThatThrownBy(() -> dining.removeLine(orderId, lineId))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void unfiredLinesRemainEditableAfterAPartialFire() {
        UUID orderId = openOrderOnFreshTable();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("1"), null, CourseTag.MAIN), "alice");
        dining.fireOrder(orderId, "alice");
        UUID friesLineId = dining.addLine(orderId,
                new AddLineCommand("FRIES", new BigDecimal("1"), null, CourseTag.MAIN), "alice")
                .lines().stream().filter(l -> l.sku().equals("FRIES")).findFirst().orElseThrow().id();

        OrderView updated = dining.updateLine(orderId, friesLineId, new BigDecimal("3"), "crispy", CourseTag.MAIN);

        assertThat(updated.lines().stream().filter(l -> l.id().equals(friesLineId)).findFirst()
                .orElseThrow().qty()).isEqualByComparingTo("3");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=DiningFireServiceTest
```
Expected: FAIL to compile — `DiningService.fireOrder(...)` and `OrderLineView.firedAt()` do not exist yet.

- [ ] **Step 3: Add `firedAt` to the `OrderLine` domain entity**

In `src/main/java/com/company/pos/dining/domain/OrderLine.java`, add the column field after the `addedAt` field:

```java
    @Column(name = "fired_at")
    private Instant firedAt;
```

And add these methods (near the other getters):

```java
    public Instant getFiredAt() {
        return firedAt;
    }

    public boolean isFired() {
        return firedAt != null;
    }

    public void fire(Instant when) {
        this.firedAt = when;
    }
```

(`java.time.Instant` is already imported.)

- [ ] **Step 4: Create the `KitchenTicketsFired` event in `dining.api`**

Create `src/main/java/com/company/pos/dining/api/KitchenTicketsFired.java`:

```java
package com.company.pos.dining.api;

import com.company.pos.common.events.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by {@code dining} when order lines are fired to the kitchen. Self-contained snapshot
 * (like {@code SaleCompleted}) — carries product name, quantity, modifiers, note and course, but no
 * money (cooks don't need price). The {@code kitchen} module listens and routes each line to its
 * station.
 */
public record KitchenTicketsFired(UUID orderId, String tableLabel, Instant firedAt,
        List<FiredLine> lines) implements DomainEvent {

    public record FiredLine(String sku, String name, BigDecimal qty, String note,
            CourseTag course, List<FiredModifier> modifiers) {
    }

    public record FiredModifier(String name) {
    }
}
```

- [ ] **Step 5: Tag `dining.api` as a named interface**

Create `src/main/java/com/company/pos/dining/api/package-info.java`:

```java
@org.springframework.modulith.NamedInterface("api")
package com.company.pos.dining.api;
```

- [ ] **Step 6: Add `firedAt` to `OrderLineView`**

In `src/main/java/com/company/pos/dining/api/OrderLineView.java`, add an `Instant firedAt` component after `course` and before `modifiers`. The record becomes:

```java
package com.company.pos.dining.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderLineView(UUID id, String sku, BigDecimal qty, String note, CourseTag course,
        Instant firedAt, List<OrderLineModifierView> modifiers) {
}
```

(Keep whatever imports the file already had; add `java.time.Instant`.)

- [ ] **Step 7: Add `fireOrder` to the `DiningService` interface**

In `src/main/java/com/company/pos/dining/api/DiningService.java`, add:

```java
    OrderView fireOrder(UUID orderId, String firedBy);
```

- [ ] **Step 8: Implement in `DefaultDiningService`**

In `src/main/java/com/company/pos/dining/application/DefaultDiningService.java`:

1. Add imports:

```java
import com.company.pos.common.events.DomainEvents;
import com.company.pos.dining.api.KitchenTicketsFired;
import com.company.pos.product.api.ProductView;
import java.util.ArrayList;
```

2. Add a `DomainEvents events` field, add it to the constructor parameter list, and assign it:

```java
    private final DomainEvents events;
```
(constructor: add `DomainEvents events` as the final parameter and `this.events = events;`)

3. Update `toOrderView` to map `firedAt` into the view (new component between `course` and modifiers):

```java
    OrderView toOrderView(DiningOrder o) {
        List<OrderLineView> lineViews = o.getLines().stream()
                .map(l -> new OrderLineView(l.getId(), l.getSku(), l.getQty(), l.getNote(),
                        l.getCourse(), l.getFiredAt(),
                        l.getModifiers().stream()
                                .map(m -> new com.company.pos.dining.api.OrderLineModifierView(
                                        m.getOptionId(), m.getName(),
                                        m.getPriceDelta()))
                                .toList()))
                .toList();
        return new OrderView(o.getId(), o.getTableId(), o.getServiceType(), o.getStatus(),
                o.getOpenedBy(), o.getOpenedAt(), o.getClosedAt(), o.getSaleId(), lineViews);
    }
```

4. Add the fired-line guard in `updateLine` (right after `OrderLine line = requireLine(order, lineId);`) and in `removeLine` (right after its `requireLine`):

```java
        if (line.isFired()) {
            throw DomainException.validation("Line " + lineId + " was already sent to the kitchen");
        }
```

5. Add the `fireOrder` method (place it near `closeOrder`):

```java
    @Override
    public OrderView fireOrder(UUID orderId, String firedBy) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        List<OrderLine> toFire = order.getLines().stream().filter(l -> !l.isFired()).toList();
        if (toFire.isEmpty()) {
            throw DomainException.validation("Order " + orderId + " has no unfired lines to fire");
        }
        Instant now = Instant.now();
        List<KitchenTicketsFired.FiredLine> firedLines = new ArrayList<>();
        for (OrderLine line : toFire) {
            line.fire(now);
            String name = products.findBySku(line.getSku())
                    .map(ProductView::name).orElse(line.getSku());
            List<KitchenTicketsFired.FiredModifier> mods = line.getModifiers().stream()
                    .map(m -> new KitchenTicketsFired.FiredModifier(m.getName()))
                    .toList();
            firedLines.add(new KitchenTicketsFired.FiredLine(line.getSku(), name, line.getQty(),
                    line.getNote(), line.getCourse(), mods));
        }
        String tableLabel = tables.findById(order.getTableId())
                .map(DiningTable::getLabel).orElse("?");
        events.publish(new KitchenTicketsFired(order.getId(), tableLabel, now, firedLines));
        return toOrderView(order);
    }
```

(`firedBy` is accepted for parity with `addLine`/`closeOrder` audit signatures; it is not persisted in this phase — the fired fact is carried by the event and the line's `firedAt` timestamp.)

- [ ] **Step 9: Add the HTTP endpoint**

In `src/main/java/com/company/pos/dining/web/DiningController.java`, add (near the other order endpoints; no `@PreAuthorize` — firing is routine floor work, matching `addLine`):

```java
    @PostMapping("/dining/orders/{orderId}/fire")
    OrderView fire(@PathVariable UUID orderId, Authentication authentication) {
        return dining.fireOrder(orderId, authentication.getName());
    }
```

(`Authentication` and `@PostMapping`/`@PathVariable` are already imported.)

- [ ] **Step 10: Create the V32 migration**

Create `src/main/resources/db/migration/dining/V32__add_order_line_fired_at.sql`:

```sql
ALTER TABLE dining_order_line ADD COLUMN fired_at TIMESTAMP;
```

- [ ] **Step 11: Run the test to verify it passes**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=DiningFireServiceTest
```
Expected: PASS (all 6 tests green).

- [ ] **Step 12: Run the full dining suite + ModularityTests**

```bash
./mvnw test -Dtest='com.company.pos.dining.*,ModularityTests'
```
Expected: PASS. (Confirms the `OrderLineView` component change didn't break `DiningLineServiceTest`/`DiningCloseServiceTest`/etc., and boundaries still verify with the new `dining.api` named interface.)

- [ ] **Step 13: Commit**

```bash
git add src/main/java/com/company/pos/dining src/main/resources/db/migration/dining/V32__add_order_line_fired_at.sql src/test/java/com/company/pos/dining/DiningFireServiceTest.java
git commit -m "$(cat <<'EOF'
feat(dining): incremental fire-to-kitchen action + per-line fired state

Adds OrderLine.firedAt, DiningService.fireOrder (stamps unfired lines,
publishes KitchenTicketsFired), locks fired lines against update/remove,
exposes POST /dining/orders/{id}/fire, and tags dining.api as a named
interface for its first consumer (kitchen). V32 adds order_line.fired_at.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Kitchen station overlay (new `kitchen` module)

A new module owning the `sku → station` assignment overlay and its admin API. Independent of Task 1 — no dining dependency yet (the routing listener that consumes `dining :: api` arrives in Task 3, which expands this module's `allowedDependencies`).

**Files:**
- Create: `src/main/java/com/company/pos/kitchen/package-info.java`
- Create: `src/main/java/com/company/pos/kitchen/api/package-info.java`
- Create: `src/main/java/com/company/pos/kitchen/api/KitchenService.java`
- Create: `src/main/java/com/company/pos/kitchen/api/StationAssignmentView.java`
- Create: `src/main/java/com/company/pos/kitchen/api/AssignStationCommand.java`
- Create: `src/main/java/com/company/pos/kitchen/domain/StationAssignment.java`
- Create: `src/main/java/com/company/pos/kitchen/infrastructure/StationAssignmentRepository.java`
- Create: `src/main/java/com/company/pos/kitchen/application/DefaultKitchenService.java`
- Create: `src/main/java/com/company/pos/kitchen/web/KitchenController.java`
- Modify: `src/main/java/com/company/pos/configuration/api/SettingKey.java`
- Create: `src/main/resources/db/migration/kitchen/V31__create_kitchen_station_assignment.sql`
- Modify: `src/main/resources/application-store-server.yml`
- Test: `src/test/java/com/company/pos/kitchen/KitchenStationServiceTest.java`
- Test: `src/test/java/com/company/pos/kitchen/KitchenControllerTest.java`

**Interfaces:**
- Consumes: `com.company.pos.configuration.api.ConfigurationService.getString(SettingKey)`, `SettingKey.KITCHEN_DEFAULT_STATION` (added here).
- Produces (Task 3 relies on these):
  - `KitchenService.stationFor(String sku) : String` (assignment name, else the configured default)
  - `KitchenService.assignSku(String, String) : StationAssignmentView`, `unassignSku(String) : void`, `listAssignments() : List<StationAssignmentView>`
  - `StationAssignmentView(String sku, String stationName)`

- [ ] **Step 1: Write the failing service test**

Create `src/test/java/com/company/pos/kitchen/KitchenStationServiceTest.java`:

```java
package com.company.pos.kitchen;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.kitchen.api.KitchenService;
import com.company.pos.support.DatabaseCleaner;
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
class KitchenStationServiceTest {

    @Autowired KitchenService kitchen;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    @Test
    void assignsAndResolvesStation() {
        kitchen.assignSku("BURGER", "Grill");
        assertThat(kitchen.stationFor("BURGER")).isEqualTo("Grill");
    }

    @Test
    void reassignUpdatesInPlace() {
        kitchen.assignSku("BURGER", "Grill");
        kitchen.assignSku("BURGER", "Flat-top");
        assertThat(kitchen.stationFor("BURGER")).isEqualTo("Flat-top");
        assertThat(kitchen.listAssignments()).hasSize(1);
    }

    @Test
    void unassignedSkuFallsBackToDefaultStation() {
        // KITCHEN_DEFAULT_STATION default is "Kitchen"
        assertThat(kitchen.stationFor("UNMAPPED")).isEqualTo("Kitchen");
    }

    @Test
    void unassignRemovesTheMapping() {
        kitchen.assignSku("FRIES", "Fryer");
        kitchen.unassignSku("FRIES");
        assertThat(kitchen.listAssignments()).isEmpty();
        assertThat(kitchen.stationFor("FRIES")).isEqualTo("Kitchen");
    }

    @Test
    void listReturnsAllAssignments() {
        kitchen.assignSku("BURGER", "Grill");
        kitchen.assignSku("FRIES", "Fryer");
        assertThat(kitchen.listAssignments()).hasSize(2);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=KitchenStationServiceTest
```
Expected: FAIL to compile — the `kitchen` module does not exist yet.

- [ ] **Step 3: Create the module `package-info` files**

`src/main/java/com/company/pos/kitchen/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "configuration :: api" })
package com.company.pos.kitchen;
```

`src/main/java/com/company/pos/kitchen/api/package-info.java`:

```java
@org.springframework.modulith.NamedInterface("api")
package com.company.pos.kitchen.api;
```

- [ ] **Step 4: Create the API types**

`src/main/java/com/company/pos/kitchen/api/StationAssignmentView.java`:

```java
package com.company.pos.kitchen.api;

public record StationAssignmentView(String sku, String stationName) {
}
```

`src/main/java/com/company/pos/kitchen/api/AssignStationCommand.java`:

```java
package com.company.pos.kitchen.api;

public record AssignStationCommand(String sku, String stationName) {
}
```

`src/main/java/com/company/pos/kitchen/api/KitchenService.java`:

```java
package com.company.pos.kitchen.api;

import java.util.List;

public interface KitchenService {

    StationAssignmentView assignSku(String sku, String stationName);

    void unassignSku(String sku);

    List<StationAssignmentView> listAssignments();

    /** The station a SKU routes to: its explicit assignment, else the configured default station. */
    String stationFor(String sku);
}
```

- [ ] **Step 5: Create the domain entity**

`src/main/java/com/company/pos/kitchen/domain/StationAssignment.java`:

```java
package com.company.pos.kitchen.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "kitchen_station_assignment")
public class StationAssignment {

    @Id
    @Column(length = 64)
    private String sku;

    @Column(name = "station_name", nullable = false, length = 100)
    private String stationName;

    protected StationAssignment() {
        // JPA
    }

    public StationAssignment(String sku, String stationName) {
        this.sku = sku;
        this.stationName = stationName;
    }

    public String getSku() {
        return sku;
    }

    public String getStationName() {
        return stationName;
    }

    public void setStationName(String stationName) {
        this.stationName = stationName;
    }
}
```

- [ ] **Step 6: Create the repository**

`src/main/java/com/company/pos/kitchen/infrastructure/StationAssignmentRepository.java`:

```java
package com.company.pos.kitchen.infrastructure;

import com.company.pos.kitchen.domain.StationAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StationAssignmentRepository extends JpaRepository<StationAssignment, String> {
}
```

- [ ] **Step 7: Add the config key**

In `src/main/java/com/company/pos/configuration/api/SettingKey.java`, change the last enum constant's trailing `;` to `,` and append the new key:

```java
    DINING_TABLE_DEFAULT_SEATS("dining.table.default.seats", "4"),
    KITCHEN_DEFAULT_STATION("kitchen.default.station", "Kitchen");
```

- [ ] **Step 8: Implement the service**

`src/main/java/com/company/pos/kitchen/application/DefaultKitchenService.java`:

```java
package com.company.pos.kitchen.application;

import com.company.pos.common.exception.DomainException;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.kitchen.api.KitchenService;
import com.company.pos.kitchen.api.StationAssignmentView;
import com.company.pos.kitchen.domain.StationAssignment;
import com.company.pos.kitchen.infrastructure.StationAssignmentRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultKitchenService implements KitchenService {

    private final StationAssignmentRepository assignments;
    private final ConfigurationService config;

    DefaultKitchenService(StationAssignmentRepository assignments, ConfigurationService config) {
        this.assignments = assignments;
        this.config = config;
    }

    @Override
    public StationAssignmentView assignSku(String sku, String stationName) {
        if (sku == null || sku.isBlank()) {
            throw DomainException.validation("sku is required");
        }
        if (stationName == null || stationName.isBlank()) {
            throw DomainException.validation("stationName is required");
        }
        String key = sku.trim();
        String station = stationName.trim();
        StationAssignment saved = assignments.findById(key)
                .map(existing -> {
                    existing.setStationName(station);
                    return existing;
                })
                .orElseGet(() -> assignments.save(new StationAssignment(key, station)));
        return new StationAssignmentView(saved.getSku(), saved.getStationName());
    }

    @Override
    public void unassignSku(String sku) {
        assignments.deleteById(sku);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StationAssignmentView> listAssignments() {
        return assignments.findAll().stream()
                .map(a -> new StationAssignmentView(a.getSku(), a.getStationName()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public String stationFor(String sku) {
        return assignments.findById(sku)
                .map(StationAssignment::getStationName)
                .orElseGet(() -> config.getString(SettingKey.KITCHEN_DEFAULT_STATION));
    }
}
```

- [ ] **Step 9: Create the V31 migration**

`src/main/resources/db/migration/kitchen/V31__create_kitchen_station_assignment.sql`:

```sql
CREATE TABLE kitchen_station_assignment (
    sku          VARCHAR(64) PRIMARY KEY,
    station_name VARCHAR(100) NOT NULL
);
```

- [ ] **Step 10: Register the kitchen migration path (store-server)**

In `src/main/resources/application-store-server.yml`, append `,classpath:db/migration/kitchen` to the end of the `flyway.locations` line (it currently ends with `...,classpath:db/migration/menu`).

- [ ] **Step 11: Run the service test to verify it passes**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=KitchenStationServiceTest
```
Expected: PASS (5 tests).

- [ ] **Step 12: Write the controller auth test**

Create `src/test/java/com/company/pos/kitchen/KitchenControllerTest.java`:

```java
package com.company.pos.kitchen;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class KitchenControllerTest {

    @Autowired MockMvc mvc;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    private static RequestPostProcessor cashier() {
        return jwt().jwt(j -> j.subject("cashier")).authorities(new SimpleGrantedAuthority("ROLE_CASHIER"));
    }

    private static RequestPostProcessor manager() {
        return jwt().jwt(j -> j.subject("manager")).authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    @Test
    void managerAssignsStation() throws Exception {
        mvc.perform(post("/kitchen/stations/assignments").with(manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"BURGER\",\"stationName\":\"Grill\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void cashierCannotAssignStation() throws Exception {
        mvc.perform(post("/kitchen/stations/assignments").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"BURGER\",\"stationName\":\"Grill\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anyCashierCanListAssignments() throws Exception {
        mvc.perform(get("/kitchen/stations/assignments").with(cashier()))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 13: Create the controller**

`src/main/java/com/company/pos/kitchen/web/KitchenController.java`:

```java
package com.company.pos.kitchen.web;

import com.company.pos.kitchen.api.AssignStationCommand;
import com.company.pos.kitchen.api.KitchenService;
import com.company.pos.kitchen.api.StationAssignmentView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class KitchenController {

    private final KitchenService kitchen;

    KitchenController(KitchenService kitchen) {
        this.kitchen = kitchen;
    }

    @PostMapping("/kitchen/stations/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    StationAssignmentView assign(@RequestBody AssignStationCommand body) {
        return kitchen.assignSku(body.sku(), body.stationName());
    }

    @DeleteMapping("/kitchen/stations/assignments/{sku}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    void unassign(@PathVariable String sku) {
        kitchen.unassignSku(sku);
    }

    @GetMapping("/kitchen/stations/assignments")
    List<StationAssignmentView> list() {
        return kitchen.listAssignments();
    }
}
```

- [ ] **Step 14: Run the controller test + ModularityTests**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest='KitchenControllerTest,KitchenStationServiceTest,ModularityTests'
```
Expected: PASS. (`ModularityTests` confirms the new `kitchen` module's boundaries are legal.)

- [ ] **Step 15: Commit**

```bash
git add src/main/java/com/company/pos/kitchen src/main/java/com/company/pos/configuration/api/SettingKey.java src/main/resources/db/migration/kitchen src/main/resources/application-store-server.yml src/test/java/com/company/pos/kitchen
git commit -m "$(cat <<'EOF'
feat(kitchen): sku→station overlay module + admin API

New kitchen module owning a POS-local sku→station assignment overlay
(assign/unassign/list + stationFor with configurable default via
KITCHEN_DEFAULT_STATION). Admin endpoints are MANAGER/ADMIN-gated; list is
any-auth. V31 creates kitchen_station_assignment.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Kitchen printer port + routing listener

Adds the dedicated `KitchenPrinter` port (with an in-memory fake), expands the `kitchen` module to consume `dining :: api` and `device :: api`, and implements the async listener that resolves stations, groups fired lines, and prints one ticket per station. This is the integration task that closes the loop.

**Files:**
- Create: `src/main/java/com/company/pos/device/api/KitchenPrinter.java`
- Create: `src/main/java/com/company/pos/device/infrastructure/InMemoryKitchenPrinter.java`
- Modify: `src/main/java/com/company/pos/kitchen/package-info.java`
- Create: `src/main/java/com/company/pos/kitchen/application/KitchenTicketsFiredListener.java`
- Test: `src/test/java/com/company/pos/kitchen/KitchenRoutingTest.java`

**Interfaces:**
- Consumes: `KitchenTicketsFired` (+ nested `FiredLine`, `FiredModifier`) from `dining :: api` (Task 1); `KitchenService.stationFor` (Task 2); `com.company.pos.device.api.PrintLine`.
- Produces: `com.company.pos.device.api.KitchenPrinter` port; `InMemoryKitchenPrinter` fake with `tickets() : List<List<PrintLine>>` and `clear()`.

- [ ] **Step 1: Write the failing routing test**

Create `src/test/java/com/company/pos/kitchen/KitchenRoutingTest.java`. This is a non-`@Transactional` end-to-end test (the listener runs after commit, async) using DatabaseCleaner + Awaitility, matching `LowStockAlertTest`:

```java
package com.company.pos.kitchen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.device.api.PrintLine;
import com.company.pos.device.infrastructure.InMemoryKitchenPrinter;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.CourseTag;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.kitchen.api.KitchenService;
import com.company.pos.product.api.ProductSync;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
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
class KitchenRoutingTest {

    @Autowired DiningService dining;
    @Autowired KitchenService kitchen;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired InMemoryKitchenPrinter printer;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        printer.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("FRIES", "Fries", "FOOD", "Food", "bcFRIES",
                "EA", new BigDecimal("12.00"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("WATER", "Water", "BEV", "Beverages", "bcWATER",
                "EA", new BigDecimal("5.00"), "SAR", 1, true));
        productSync.sync();
        kitchen.assignSku("BURGER", "Grill");
        kitchen.assignSku("FRIES", "Fryer");
        // WATER intentionally unmapped -> default station "Kitchen"
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
        printer.clear();
    }

    private UUID openOrderOnFreshTable() {
        UUID tableId = dining.registerTable(new RegisterTableCommand("L" + UUID.randomUUID(), 4)).id();
        return dining.openOrder(new OpenOrderCommand(tableId, null), "alice").id();
    }

    private static boolean anyTicketContains(List<List<PrintLine>> tickets, String text) {
        return tickets.stream().anyMatch(t -> t.stream().anyMatch(l -> l.text().contains(text)));
    }

    @Test
    void firingPrintsOneTicketPerStation() {
        UUID orderId = openOrderOnFreshTable();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("2"), "no onions", CourseTag.MAIN), "alice");
        dining.addLine(orderId, new AddLineCommand("FRIES", new BigDecimal("1"), null, CourseTag.MAIN), "alice");
        dining.addLine(orderId, new AddLineCommand("WATER", new BigDecimal("1"), null, CourseTag.DRINK), "alice");

        dining.fireOrder(orderId, "alice");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<List<PrintLine>> tickets = printer.tickets();
            assertThat(tickets).hasSize(3); // Grill, Fryer, Kitchen (default)
            assertThat(anyTicketContains(tickets, "Grill")).isTrue();
            assertThat(anyTicketContains(tickets, "Fryer")).isTrue();
            assertThat(anyTicketContains(tickets, "Kitchen")).isTrue();
            assertThat(anyTicketContains(tickets, "Beef Burger")).isTrue();
            assertThat(anyTicketContains(tickets, "no onions")).isTrue();
        });
    }

    @Test
    void secondFirePrintsOnlyTheNewlyAddedLine() {
        UUID orderId = openOrderOnFreshTable();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("1"), null, CourseTag.MAIN), "alice");
        dining.fireOrder(orderId, "alice");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(printer.tickets()).hasSize(1));
        printer.clear();

        dining.addLine(orderId, new AddLineCommand("FRIES", new BigDecimal("1"), null, CourseTag.MAIN), "alice");
        dining.fireOrder(orderId, "alice");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<List<PrintLine>> tickets = printer.tickets();
            assertThat(tickets).hasSize(1); // only the Fryer ticket for the new fries line
            assertThat(anyTicketContains(tickets, "Fries")).isTrue();
            assertThat(anyTicketContains(tickets, "Beef Burger")).isFalse();
        });
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=KitchenRoutingTest
```
Expected: FAIL to compile — `InMemoryKitchenPrinter` does not exist.

- [ ] **Step 3: Create the `KitchenPrinter` port**

`src/main/java/com/company/pos/device/api/KitchenPrinter.java`:

```java
package com.company.pos.device.api;

import java.util.List;

/** Outbound port for the kitchen ticket printer. Distinct from {@link Printer} (receipts). */
public interface KitchenPrinter {

    void print(List<PrintLine> lines);

    void cut();
}
```

- [ ] **Step 4: Create the in-memory fake**

`src/main/java/com/company/pos/device/infrastructure/InMemoryKitchenPrinter.java`:

```java
package com.company.pos.device.infrastructure;

import com.company.pos.device.api.KitchenPrinter;
import com.company.pos.device.api.PrintLine;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Default {@link KitchenPrinter} adapter: records each printed ticket in memory (one per print call). */
@Component
public class InMemoryKitchenPrinter implements KitchenPrinter {

    private final List<List<PrintLine>> tickets = new ArrayList<>();

    @Override
    public synchronized void print(List<PrintLine> lines) {
        tickets.add(List.copyOf(lines));
    }

    @Override
    public synchronized void cut() {
        // no-op for the in-memory fake; a real adapter would issue a paper cut
    }

    public synchronized List<List<PrintLine>> tickets() {
        return List.copyOf(tickets);
    }

    public synchronized void clear() {
        tickets.clear();
    }
}
```

- [ ] **Step 5: Expand the kitchen module's allowed dependencies**

Replace `src/main/java/com/company/pos/kitchen/package-info.java` with:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
            "common", "database",
            "configuration :: api", "dining :: api", "device :: api" })
package com.company.pos.kitchen;
```

- [ ] **Step 6: Create the routing listener**

`src/main/java/com/company/pos/kitchen/application/KitchenTicketsFiredListener.java`:

```java
package com.company.pos.kitchen.application;

import com.company.pos.device.api.KitchenPrinter;
import com.company.pos.device.api.PrintLine;
import com.company.pos.dining.api.KitchenTicketsFired;
import com.company.pos.kitchen.api.KitchenService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Prints fired order lines as kitchen tickets, one per station (after-commit, async, own tx —
 * a dead printer never rolls back or blocks the fire). At-least-once outbox delivery means a
 * replay can reprint a ticket; not deduped (same known gap as inventory/cashdrawer listeners).
 */
@Component
class KitchenTicketsFiredListener {

    private final KitchenService kitchen;
    private final KitchenPrinter printer;

    KitchenTicketsFiredListener(KitchenService kitchen, KitchenPrinter printer) {
        this.kitchen = kitchen;
        this.printer = printer;
    }

    @ApplicationModuleListener
    void on(KitchenTicketsFired event) {
        Map<String, List<KitchenTicketsFired.FiredLine>> byStation = new LinkedHashMap<>();
        for (KitchenTicketsFired.FiredLine line : event.lines()) {
            String station = kitchen.stationFor(line.sku());
            byStation.computeIfAbsent(station, s -> new ArrayList<>()).add(line);
        }
        for (Map.Entry<String, List<KitchenTicketsFired.FiredLine>> entry : byStation.entrySet()) {
            printer.print(buildTicket(event, entry.getKey(), entry.getValue()));
            printer.cut();
        }
    }

    private List<PrintLine> buildTicket(KitchenTicketsFired event, String station,
            List<KitchenTicketsFired.FiredLine> lines) {
        List<PrintLine> out = new ArrayList<>();
        out.add(new PrintLine("*** " + station + " ***", true));
        out.add(new PrintLine("Table " + event.tableLabel() + "   " + event.firedAt(), false));
        for (KitchenTicketsFired.FiredLine line : lines) {
            out.add(new PrintLine(line.qty().stripTrailingZeros().toPlainString() + " x " + line.name(), true));
            for (KitchenTicketsFired.FiredModifier m : line.modifiers()) {
                out.add(new PrintLine("   + " + m.name(), false));
            }
            if (line.note() != null && !line.note().isBlank()) {
                out.add(new PrintLine("   note: " + line.note(), false));
            }
            if (line.course() != null) {
                out.add(new PrintLine("   [" + line.course() + "]", false));
            }
        }
        return out;
    }
}
```

- [ ] **Step 7: Run the routing test to verify it passes**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=KitchenRoutingTest
```
Expected: PASS (2 tests).

- [ ] **Step 8: Run ModularityTests + the kitchen and dining suites**

```bash
./mvnw test -Dtest='ModularityTests,com.company.pos.kitchen.*,com.company.pos.dining.*'
```
Expected: PASS. (`ModularityTests` confirms `kitchen → dining :: api` + `device :: api` are legal and the graph stays acyclic.)

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/company/pos/device/api/KitchenPrinter.java src/main/java/com/company/pos/device/infrastructure/InMemoryKitchenPrinter.java src/main/java/com/company/pos/kitchen/package-info.java src/main/java/com/company/pos/kitchen/application/KitchenTicketsFiredListener.java src/test/java/com/company/pos/kitchen/KitchenRoutingTest.java
git commit -m "$(cat <<'EOF'
feat(kitchen): route fired lines to stations, print one ticket per station

Adds a dedicated KitchenPrinter port (+ in-memory fake) and an async
@ApplicationModuleListener on KitchenTicketsFired that resolves each line's
station, groups by station, and prints one ticket + cut per station. Async
after-commit via the outbox — a dead printer never blocks/rolls back a fire.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Final Verification (after all tasks)

- [ ] **Full build in both persistence modes:**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw verify
```
Expected: green — all module tests + `ModularityTests`. The Testcontainers PostgreSQL tests validate V31/V32 apply cleanly (needs a running Docker daemon).

---

## Self-Review

**1. Spec coverage:**
- Incremental fire action (`POST /dining/orders/{id}/fire`) → Task 1. ✅
- Per-line fired state & locking → Task 1 (`firedAt`, `updateLine`/`removeLine` guards). ✅
- POS-local `sku → station` overlay, MANAGER/ADMIN admin, default fallback → Task 2. ✅
- One ticket per station, dedicated `KitchenPrinter` port → Task 3. ✅
- Async via `KitchenTicketsFired` + `@ApplicationModuleListener` outbox → event published Task 1, consumed Task 3. ✅
- Config `KITCHEN_DEFAULT_STATION`, course stays enum, V31/V32, boundaries, tests → all covered. ✅
- Deferred (KDS, cancel/re-fire, per-station printers, print dedupe) → not built, matching spec. ✅

**2. Placeholder scan:** No TBD/TODO; every step shows complete code or an exact command with expected output.

**3. Type consistency:** `fireOrder(UUID, String) : OrderView`, `KitchenTicketsFired` + nested `FiredLine`/`FiredModifier`, `OrderLineView.firedAt` (Instant, after `course`), `KitchenService.stationFor`, `InMemoryKitchenPrinter.tickets()/clear()` are named identically wherever produced (Task 1/2) and consumed (Task 3). The single `new OrderLineView(...)` call site (`DefaultDiningService.toOrderView`) is updated in Task 1 Step 8.

**One cross-task note for the executor:** Task 1 publishes `KitchenTicketsFired` before any listener exists (Task 3 adds it). With no subscriber, Spring Modulith creates no `event_publication` rows, so Task 1 leaves no dangling outbox state — this is intentional incremental sequencing, not a leak.
