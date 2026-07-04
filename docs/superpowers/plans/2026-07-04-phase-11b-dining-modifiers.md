# Phase 11b — Dine-in Order Modifiers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Prerequisite:** Phase 11a is complete (the `menu` module, `MenuService.resolveSelections`, and the `cart` modifier-carrying `addLine(cartId, sku, qty, List<UUID> modifierOptionIds)` overload exist). This plan builds on those.

**Goal:** Let dine-in order lines carry modifier selections (resolved for the ticket + running line price), and change `closeOrder` to produce one cart line per order line so modified lines survive to the bill.

**Architecture:** `dining` gains a `menu :: api` dependency: `OrderLine` stores resolved modifier detail (for display) and the option ids; at close, each order line is added to the throwaway cart via the 11a `addLine(…, modifierOptionIds)` overload (which re-resolves for the authoritative price), replacing Phase-10's aggregate-by-sku.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Modulith, Spring Data JPA, Flyway (store-server only), embedded SQLite (tests), JUnit 5 + AssertJ.

## Global Constraints

- **JDK 21.** Prefix every Maven command with `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" &&`.
- **Money/price deltas are `BigDecimal`.**
- **Module boundaries enforced.** `dining` adds `"menu :: api"` to its existing `allowedDependencies` (`common, database, product::api, cart::api, sales::api, configuration::api`). Only `:: api` named interfaces crossed. Run `./mvnw test -Dtest=ModularityTests` after every task.
- **Flyway sequential.** After 11a's V29, this plan uses **V30** (`order_line_modifier`). Only `store-server` runs Flyway; `embedded` uses `ddl-auto`.
- **UUID PKs as `VARCHAR(36)`** via `@JdbcTypeCode(SqlTypes.VARCHAR)`.
- Tests `@SpringBootTest @ActiveProfiles("embedded")`, committing tests `@Import(DatabaseCleaner.class)`, seed products via `FakeErpClient` + `ProductSync.sync()`.
- **Resolution happens twice, deliberately** (per spec): the order line resolves at add-time for display; the cart re-resolves at close for the authoritative price.

---

### Task 1: Order line carries modifier selections

**Files:**
- Modify: `src/main/java/com/company/pos/dining/api/AddLineCommand.java` (add `modifierOptionIds`, keep a 4-arg convenience ctor)
- Create: `src/main/java/com/company/pos/dining/api/OrderLineModifierView.java`
- Modify: `src/main/java/com/company/pos/dining/api/OrderLineView.java` (add modifiers)
- Create: `src/main/java/com/company/pos/dining/domain/OrderLineModifier.java`
- Modify: `src/main/java/com/company/pos/dining/domain/OrderLine.java` (modifier collection + accessors)
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java` (menu dep; resolve on addLine; map modifiers)
- Modify: `src/main/java/com/company/pos/dining/package-info.java` (add `menu :: api`)
- Create: `src/main/resources/db/migration/dining/V30__create_order_line_modifier.sql`
- Create: `src/test/java/com/company/pos/dining/DiningOrderModifierTest.java`

**Interfaces:**
- Consumes (from 11a): `MenuService.resolveSelections(String sku, List<UUID> ids)→ModifierResolution{modifiers:List<ResolvedModifier>{optionId,name,priceDelta}, totalDelta}`.
- Consumes (from Phase 10): `OrderLine` ctor `(UUID id, UUID orderId, String sku, BigDecimal qty, String note, CourseTag course, String addedBy, Instant addedAt)`; `DiningOrder.addLine(OrderLine)`; `DefaultDiningService.load(UUID)`, `toOrderView(DiningOrder)`, `requireOpen(DiningOrder)`.
- Produces: `AddLineCommand(String sku, BigDecimal qty, String note, CourseTag course, List<UUID> modifierOptionIds)` with a 4-arg convenience ctor defaulting `modifierOptionIds` to `List.of()`; `OrderLine.addModifier(UUID,String,BigDecimal)`, `getModifiers()` (unmodifiable), `getModifierOptionIds()→List<UUID>`; `OrderLineModifierView(UUID optionId, String name, BigDecimal priceDelta)`; `OrderLineView` gains trailing `List<OrderLineModifierView> modifiers`.

- [ ] **Step 1: Write the failing test** — `DiningOrderModifierTest.java`

```java
package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OrderLineView;
import com.company.pos.dining.api.OrderView;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.menu.api.AddOptionCommand;
import com.company.pos.menu.api.CreateModifierGroupCommand;
import com.company.pos.menu.api.MenuService;
import com.company.pos.menu.api.ModifierGroupView;
import com.company.pos.product.api.ProductSync;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
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
class DiningOrderModifierTest {

    @Autowired DiningService dining;
    @Autowired MenuService menu;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    private UUID cheeseId;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Burger", "FOOD", "Food", "bcB",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        productSync.sync();
        ModifierGroupView addons = menu.createModifierGroup(new CreateModifierGroupCommand("Add-ons", 0, 3));
        cheeseId = menu.addOption(addons.id(), new AddOptionCommand("Extra cheese", new BigDecimal("2.00"))).id();
        menu.assignGroupToSku(addons.id(), "BURGER");
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private UUID openOrder(String label) {
        UUID tableId = dining.registerTable(new RegisterTableCommand(label, 4)).id();
        return dining.openOrder(new OpenOrderCommand(tableId, null), "alice").id();
    }

    @Test
    void addLineWithModifiersStoresDetail() {
        UUID orderId = openOrder("M1");
        OrderView order = dining.addLine(orderId,
                new AddLineCommand("BURGER", new BigDecimal("1"), "no onions", null, List.of(cheeseId)), "alice");

        OrderLineView line = order.lines().get(0);
        assertThat(line.modifiers()).extracting("name").containsExactly("Extra cheese");
    }

    @Test
    void invalidModifierSelectionIsRejected() {
        UUID orderId = openOrder("M2");
        assertThatThrownBy(() -> dining.addLine(orderId,
                new AddLineCommand("BURGER", new BigDecimal("1"), null, null, List.of(UUID.randomUUID())), "alice"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void plainAddLineStillWorks() {
        UUID orderId = openOrder("M3");
        OrderView order = dining.addLine(orderId,
                new AddLineCommand("BURGER", new BigDecimal("1"), null, null), "alice"); // 4-arg convenience ctor
        assertThat(order.lines().get(0).modifiers()).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=DiningOrderModifierTest`
Expected: FAIL — `AddLineCommand` has no 5-arg form / `OrderLineView.modifiers()` missing (compile error).

- [ ] **Step 3: Extend `AddLineCommand.java`**

```java
package com.company.pos.dining.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** {@code note} and {@code course} are optional (may be null). {@code modifierOptionIds} may be empty. */
public record AddLineCommand(String sku, BigDecimal qty, String note, CourseTag course,
        List<UUID> modifierOptionIds) {

    public AddLineCommand {
        if (modifierOptionIds == null) {
            modifierOptionIds = List.of();
        }
    }

    /** Convenience for a line with no modifiers (used by existing callers/tests). */
    public AddLineCommand(String sku, BigDecimal qty, String note, CourseTag course) {
        this(sku, qty, note, course, List.of());
    }
}
```

- [ ] **Step 4: Create `OrderLineModifier.java`**

```java
package com.company.pos.dining.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "order_line_modifier")
public class OrderLineModifier {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "order_line_id", nullable = false)
    private OrderLine orderLine;

    @Column(name = "option_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID optionId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "price_delta", nullable = false, precision = 19, scale = 4)
    private BigDecimal priceDelta;

    protected OrderLineModifier() {
    }

    OrderLineModifier(UUID id, OrderLine orderLine, UUID optionId, String name, BigDecimal priceDelta) {
        this.id = id;
        this.orderLine = orderLine;
        this.optionId = optionId;
        this.name = name;
        this.priceDelta = priceDelta;
    }

    public UUID getOptionId() {
        return optionId;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPriceDelta() {
        return priceDelta;
    }
}
```

- [ ] **Step 5: Extend `OrderLine.java`** — add the modifier collection + accessors (add imports `CascadeType`, `OneToMany`, `List`, `ArrayList`, `Collections`, `Identifiers`):

```java
    @jakarta.persistence.OneToMany(mappedBy = "orderLine",
            cascade = jakarta.persistence.CascadeType.ALL, orphanRemoval = true)
    private java.util.List<OrderLineModifier> modifiers = new java.util.ArrayList<>();

    public void addModifier(UUID optionId, String name, java.math.BigDecimal priceDelta) {
        modifiers.add(new OrderLineModifier(com.company.pos.common.util.Identifiers.newId(),
                this, optionId, name, priceDelta));
    }

    public java.util.List<OrderLineModifier> getModifiers() {
        return java.util.Collections.unmodifiableList(modifiers);
    }

    public java.util.List<UUID> getModifierOptionIds() {
        return modifiers.stream().map(OrderLineModifier::getOptionId).toList();
    }
```

- [ ] **Step 6: Extend the api view.** `OrderLineModifierView.java`:
```java
package com.company.pos.dining.api;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderLineModifierView(UUID optionId, String name, BigDecimal priceDelta) {
}
```
Add a trailing `List<OrderLineModifierView> modifiers` field to `OrderLineView` (add `import java.util.List;`).

- [ ] **Step 7: Wire `DefaultDiningService.java`.** Add `menu :: api` to `dining/package-info.java` `allowedDependencies`. Add a `MenuService menu` field + constructor param (import `com.company.pos.menu.api.MenuService`, `ModifierResolution`, `ResolvedModifier`). In `addLine`, after the existing qty + sku validation, resolve and attach modifiers:
```java
        OrderLine line = new OrderLine(Identifiers.newId(), order.getId(), command.sku(),
                command.qty(), command.note(), command.course(), addedBy, Instant.now());
        if (!command.modifierOptionIds().isEmpty()) {
            com.company.pos.menu.api.ModifierResolution res =
                    menu.resolveSelections(command.sku(), command.modifierOptionIds());
            for (com.company.pos.menu.api.ResolvedModifier m : res.modifiers()) {
                line.addModifier(m.optionId(), m.name(), m.priceDelta());
            }
        }
        order.addLine(line);
        return toOrderView(order);
```
In `toOrderView`, map each order line's modifiers into the new `OrderLineView` field:
```java
        l.getModifiers().stream()
                .map(m -> new com.company.pos.dining.api.OrderLineModifierView(
                        m.getOptionId(), m.getName(), m.getPriceDelta()))
                .toList()
```

- [ ] **Step 8: Create `V30__create_order_line_modifier.sql`**

```sql
CREATE TABLE order_line_modifier (
    id            VARCHAR(36) PRIMARY KEY,
    order_line_id VARCHAR(36) NOT NULL,
    option_id     VARCHAR(36) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    price_delta   NUMERIC(19, 4) NOT NULL,
    CONSTRAINT fk_order_line_modifier_line FOREIGN KEY (order_line_id) REFERENCES dining_order_line (id)
);
CREATE INDEX idx_order_line_modifier_line ON order_line_modifier (order_line_id);
```

- [ ] **Step 9: Run the test + boundaries**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest='DiningOrderModifierTest,ModularityTests'`
Expected: PASS (incl. `dining → menu :: api` allowed). Then `./mvnw test -Dtest='com.company.pos.dining.*'` — all prior dining tests still green (the 4-arg `AddLineCommand` convenience ctor keeps them compiling).

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/company/pos/dining/ src/main/resources/db/migration/dining/V30__create_order_line_modifier.sql \
        src/test/java/com/company/pos/dining/DiningOrderModifierTest.java
git commit -m "feat(dining): order lines carry modifier selections (resolved via menu) + V30

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: `closeOrder` — one cart line per order line

Replace Phase-10's aggregate-by-sku close with a per-order-line loop that passes modifier option ids to the cart. Plain lines still merge in the cart (so existing totals are unchanged); modified lines stay distinct.

**Files:**
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java` (`closeOrder` loop)
- Modify: `src/test/java/com/company/pos/dining/DiningCloseServiceTest.java` (add a modifier-close test)

**Interfaces:**
- Consumes: 11a `CartService.addLine(UUID cartId, String sku, BigDecimal quantity, List<UUID> modifierOptionIds)`; Task 1 `OrderLine.getModifierOptionIds()`.
- Produces: no api change.

- [ ] **Step 1: Write the failing test** — add to `DiningCloseServiceTest.java` (it seeds "BURGER" 30.00 already; add a cheese modifier in a local setup or extend `seed()`; here assume a helper that creates the group — add these fields/setup to the class if absent):

```java
    @Test
    void closingCarriesModifierPriceAndDetailToTheSale() {
        // Arrange: a cheese add-on on BURGER
        com.company.pos.menu.api.ModifierGroupView addons =
                menu.createModifierGroup(new com.company.pos.menu.api.CreateModifierGroupCommand("Add-ons", 0, 3));
        java.util.UUID cheeseId = menu.addOption(addons.id(),
                new com.company.pos.menu.api.AddOptionCommand("Extra cheese", new java.math.BigDecimal("2.00"))).id();
        menu.assignGroupToSku(addons.id(), "BURGER");

        UUID tableId = dining.registerTable(new RegisterTableCommand("CM1", 4)).id();
        UUID orderId = dining.openOrder(new OpenOrderCommand(tableId, null), "alice").id();
        dining.addLine(orderId, new com.company.pos.dining.api.AddLineCommand(
                "BURGER", new java.math.BigDecimal("1"), null, null, java.util.List.of(cheeseId)), "alice");

        // Act: close (effective 32.00 +15% = 4.80 → grand 36.80)
        SaleView sale = dining.closeOrder(orderId,
                new CloseOrderCommand(java.util.List.of(
                        new TenderInput(PaymentMethod.CASH, null, new java.math.BigDecimal("40.00"))),
                        java.util.Map.of(), null), "alice", false);

        assertThat(sale.grandTotal()).isEqualByComparingTo("36.80");
        assertThat(sale.lines().get(0).unitPrice()).isEqualByComparingTo("32.00");
        assertThat(sale.lines().get(0).modifiers()).extracting("name").containsExactly("Extra cheese");
    }
```
Add `@Autowired MenuService menu;` to the test class if not present (import `com.company.pos.menu.api.MenuService`).

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=DiningCloseServiceTest#closingCarriesModifierPriceAndDetailToTheSale`
Expected: FAIL — the current close aggregates by sku and drops modifiers, so the sale line has no modifiers / wrong price.

- [ ] **Step 3: Rewrite the close loop** in `DefaultDiningService.closeOrder`. Replace the `LinkedHashMap` sku-aggregation block:
```java
        // OLD (Phase 10):
        Map<String, BigDecimal> bySku = new LinkedHashMap<>();
        for (OrderLine line : order.getLines()) {
            bySku.merge(line.getSku(), line.getQty(), BigDecimal::add);
        }
        UUID cartId = carts.createCart();
        bySku.forEach((sku, qty) -> carts.addLine(cartId, sku, qty));
```
with a per-order-line loop that carries modifier ids:
```java
        // NEW (Phase 11b): one cart line per order line; the cart's own merge collapses
        // identical plain lines, while modified lines stay distinct.
        UUID cartId = carts.createCart();
        for (OrderLine line : order.getLines()) {
            carts.addLine(cartId, line.getSku(), line.getQty(), line.getModifierOptionIds());
        }
```
(Remove the now-unused `java.util.LinkedHashMap` / `java.util.Map` imports if nothing else uses them.)

- [ ] **Step 4: Run the new test + full dining regression**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=DiningCloseServiceTest`
Expected: PASS — the new modifier-close test passes AND the existing tests (`closingProducesSaleFreesTableAndMarksClosed` grand total 69.00 for two plain burgers, `multiTenderClosesAsOneBill`, etc.) still pass, because two plain same-sku order lines merge to one cart line via the cart's plain-add merge.

- [ ] **Step 5: Full verify**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw verify`
Expected: BUILD SUCCESS — all tests + `ModularityTests` + store-server Testcontainers validating V26–V30. If Docker is unavailable, run `./mvnw test` and note the skipped container tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/dining/application/DefaultDiningService.java \
        src/test/java/com/company/pos/dining/DiningCloseServiceTest.java
git commit -m "feat(dining): close one cart line per order line (carry modifiers; replace sku-merge)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage (11b portion):**
- Dine-in `OrderLine` carries modifier selections, resolved at add-time via `menu::api` for the ticket/running price → Task 1 ✅
- `AddLineCommand` gains `modifierOptionIds` (4-arg convenience ctor keeps Phase-10 callers compiling) → Task 1 ✅
- `closeOrder` produces one cart line per order line (replacing aggregate-by-sku), carrying modifier ids so the delta + detail reach the Sale → Task 2 ✅
- `dining → menu::api` dependency + `ModularityTests` gate → Task 1 ✅
- Migration V30 → Task 1 ✅
- Deliberate double-resolution (add-time display + close-time authoritative price) → realized by Task 1 storing detail and Task 2 re-resolving through `cart.addLine` ✅

**Placeholder scan:** No TBD/TODO; complete code in every code step. Task 2 Step 3 shows the exact old block to remove and the new block to insert.

**Type consistency:** `AddLineCommand` 5-arg canonical + 4-arg convenience is used consistently (Task 1 tests use both). `OrderLine.getModifierOptionIds()` (Task 1) is consumed by Task 2's close loop. `CartService.addLine(…, List<UUID>)` is the 11a-produced overload. `OrderLineView`'s trailing `modifiers` matches `SaleLineView`/`CartLineView` modifier-view shapes from 11a.

**Cross-plan dependency:** every 11b task depends on 11a being merged/available in the working tree. Do not start 11b until 11a's final `./mvnw verify` is green.
