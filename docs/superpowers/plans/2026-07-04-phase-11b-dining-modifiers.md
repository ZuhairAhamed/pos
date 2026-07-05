# Phase 11b — Dine-in Order Modifiers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Prerequisite:** Phase 11a is complete (the `menu` module, `MenuService.resolveSelections`, and the `cart` modifier-carrying `addLine(cartId, sku, qty, List<UUID> modifierOptionIds)` overload exist). This plan builds on those.

**Goal:** Let dine-in order lines carry modifier selections (resolved once at add-time for the ticket, validation, and the price the guest is quoted), and change `closeOrder` to produce one cart line per order line so modified lines survive to the bill — billing the **snapshotted** modifier prices.

**Architecture:** `dining` gains a `menu :: api` dependency. `OrderLine` stores the resolved modifier detail (option id + name + `priceDelta`), captured at add-time via `menu.resolveSelections`. Those stored deltas are the **authoritative** modifier prices. At close, each order line is added to the throwaway cart **pre-resolved** — via a new `cart` seam `addLinePreResolved(cartId, sku, qty, List<CartLineModifierInput>)` that folds the caller-supplied deltas into the effective unit price **without** re-calling `resolveSelections`. This replaces Phase-10's aggregate-by-sku close.

**Billing contract (snapshot-at-order-time):** the guest pays the modifier price quoted when the item was ordered. A mid-service modifier price change or option deactivation **never** re-prices an open check and **never** blocks closing a table (a re-resolve-at-close design was rejected precisely because deactivating a modifier would make `resolveSelections` throw and leave the table unclosable — contrary to the system's offline-first "always able to close" principle). *Scope:* only the modifier **deltas** are snapshotted; the **base** product price is still read from the current catalog at close (unchanged Phase-10 behavior).

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Modulith, Spring Data JPA, Flyway (store-server only), embedded SQLite (tests), JUnit 5 + AssertJ.

## Global Constraints

- **JDK 21.** Prefix every Maven command with `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" &&`.
- **Money/price deltas are `BigDecimal`** (`NUMERIC(19,4)` columns) — never `double`.
- **Module boundaries enforced.** `dining` adds `"menu :: api"` to its existing `allowedDependencies` (`common, database, product::api, cart::api, sales::api, configuration::api`). Only `:: api` named interfaces crossed. The dependency graph must stay ACYCLIC (`menu` imports nothing from `cart`/`dining`; `cart` does not depend on `dining`). Run `./mvnw test -Dtest=ModularityTests` after every task.
- **Flyway sequential.** Current max after 11a is **V29**; this plan uses **V30** (`order_line_modifier`). Only `store-server` runs Flyway; `embedded` uses `ddl-auto`. NOTE: an 11a optional follow-up also eyed a "V30 base_price NOT NULL" — that was NOT built; V30 here is `order_line_modifier`. If that follow-up is ever done, it takes the next free number, not V30.
- **UUID PKs as `VARCHAR(36)`** via `@JdbcTypeCode(SqlTypes.VARCHAR)`.
- Tests `@SpringBootTest @ActiveProfiles("embedded")`, committing tests `@Import(DatabaseCleaner.class)` (clean before/after), seed products via `FakeErpClient.addProduct(...)` + `ProductSync.sync()`.
- **Resolution happens exactly once — at add-time.** The order line resolves via `menu.resolveSelections` when the line is added (validates the selection, captures name+delta). Close does **NOT** re-resolve; it folds the stored snapshot via the new pre-resolved cart seam. This is the billing contract above — do not reintroduce a `resolveSelections` call in the close path.

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
        assertThat(line.modifiers()).extracting("priceDelta").containsExactly(new BigDecimal("2.00"));
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
git commit -m "feat(dining): order lines carry modifier selections (resolved once at add-time) + V30

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Cart pre-resolved add-line seam

Add a `cart` path that folds **caller-supplied** modifier deltas into a line's effective unit price **without** calling `menu.resolveSelections`. This is what lets `closeOrder` bill the order line's snapshotted deltas (the billing contract). As part of adding it, refactor the cart **domain** to take a cart-owned modifier carrier instead of `menu::api`'s `ResolvedModifier`, removing the `cart.domain → menu.api` coupling (the `menu` resolve stays in `cart`'s application layer, on the `optionIds` path only).

**Files:**
- Create: `src/main/java/com/company/pos/cart/api/CartLineModifierInput.java`
- Modify: `src/main/java/com/company/pos/cart/api/CartService.java` (add `addLinePreResolved`)
- Modify: `src/main/java/com/company/pos/cart/domain/Cart.java` (`addLineWithModifiers` takes `CartLineModifierInput`)
- Modify: `src/main/java/com/company/pos/cart/application/DefaultCartService.java` (map on the menu path; implement `addLinePreResolved`)
- Create: `src/test/java/com/company/pos/cart/CartPreResolvedModifierTest.java`

**Interfaces:**
- Consumes: existing `Cart.addLineWithModifiers`, `ProductCatalog.findBySku`, `CartLine.modifierKeyOf(Stream<UUID>)`.
- Produces: `cart.api.CartLineModifierInput(UUID optionId, String name, BigDecimal priceDelta)`; `CartService.addLinePreResolved(UUID cartId, String sku, BigDecimal quantity, List<CartLineModifierInput> modifiers)` — folds the given deltas into the effective unit price (base from the current catalog + Σ supplied deltas), merging on (sku + sorted option-id set) exactly like the resolve path; empty/null `modifiers` delegates to the plain `addLine` (merges by sku). Does NOT call `menu.resolveSelections`.

- [ ] **Step 1: Write the failing test** — `CartPreResolvedModifierTest.java`

The key behavioral proof: `addLinePreResolved` folds a delta for an option that is **not assigned to the sku in `menu`** (so the resolve path would reject it) — proving no `resolveSelections` happens and the caller's snapshot is authoritative.

```java
package com.company.pos.cart;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartLineModifierInput;
import com.company.pos.cart.api.CartService;
import com.company.pos.cart.api.CartView;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
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
class CartPreResolvedModifierTest {

    @Autowired CartService carts;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Burger", "FOOD", "Food", "bcB",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    @Test
    void foldsSuppliedDeltasWithoutReResolving() {
        // An option id that was never created/assigned in `menu` — the resolve path would reject it.
        UUID phantomOption = UUID.randomUUID();
        UUID cartId = carts.createCart();
        CartView c = carts.addLinePreResolved(cartId, "BURGER", new BigDecimal("1"),
                List.of(new CartLineModifierInput(phantomOption, "Extra cheese", new BigDecimal("2.00"))));

        assertThat(c.lines()).hasSize(1);
        assertThat(c.lines().get(0).basePrice()).isEqualByComparingTo("30.00");
        assertThat(c.lines().get(0).unitPrice()).isEqualByComparingTo("32.00"); // 30 + 2 snapshot delta
        assertThat(c.lines().get(0).modifiers()).extracting("name").containsExactly("Extra cheese");
    }

    @Test
    void sameSkuSameModifiersMergeQuantity() {
        UUID opt = UUID.randomUUID();
        UUID cartId = carts.createCart();
        carts.addLinePreResolved(cartId, "BURGER", new BigDecimal("1"),
                List.of(new CartLineModifierInput(opt, "Extra cheese", new BigDecimal("2.00"))));
        CartView c = carts.addLinePreResolved(cartId, "BURGER", new BigDecimal("1"),
                List.of(new CartLineModifierInput(opt, "Extra cheese", new BigDecimal("2.00"))));
        assertThat(c.lines()).hasSize(1);
        assertThat(c.lines().get(0).quantity()).isEqualByComparingTo("2");
    }

    @Test
    void emptyModifiersDelegatesToPlainMerge() {
        UUID cartId = carts.createCart();
        carts.addLinePreResolved(cartId, "BURGER", new BigDecimal("1"), List.of());
        CartView c = carts.addLinePreResolved(cartId, "BURGER", new BigDecimal("1"), List.of());
        assertThat(c.lines()).hasSize(1); // merged as a plain line
        assertThat(c.lines().get(0).quantity()).isEqualByComparingTo("2");
        assertThat(c.lines().get(0).modifiers()).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=CartPreResolvedModifierTest`
Expected: FAIL — `CartLineModifierInput` and `addLinePreResolved` don't exist (compile error).

- [ ] **Step 3: Create `CartLineModifierInput.java`**

```java
package com.company.pos.cart.api;

import java.math.BigDecimal;
import java.util.UUID;

/** A pre-resolved modifier supplied by a caller (e.g. dining close), folded into the line
 *  without re-resolving against the menu. */
public record CartLineModifierInput(UUID optionId, String name, BigDecimal priceDelta) {
}
```

- [ ] **Step 4: Refactor `Cart.addLineWithModifiers`** to take `CartLineModifierInput` instead of `menu.api.ResolvedModifier` (removes the domain→menu coupling). Replace the method with:

```java
    public void addLineWithModifiers(String sku, String name, BigDecimal quantity, BigDecimal basePrice,
            String currency, java.util.List<com.company.pos.cart.api.CartLineModifierInput> mods) {
        if (this.currencyCode == null) {
            this.currencyCode = currency;
        }
        String key = CartLine.modifierKeyOf(
                mods.stream().map(com.company.pos.cart.api.CartLineModifierInput::optionId));
        for (CartLine line : lines) {
            if (line.getSku().equals(sku) && line.modifierKey().equals(key)) {
                line.addQuantity(quantity);
                return;
            }
        }
        CartLine line = new CartLine(this, lines.size() + 1, sku, name, quantity, basePrice, currency);
        for (com.company.pos.cart.api.CartLineModifierInput m : mods) {
            line.addModifier(m.optionId(), m.name(), m.priceDelta());
        }
        line.recomputeUnitPrice();
        lines.add(line);
    }
```

(This is a straight type swap — `CartLineModifierInput` has the same `optionId()`/`name()`/`priceDelta()` accessors `ResolvedModifier` had, so the body is otherwise unchanged. `Cart.java` no longer imports/references anything from `com.company.pos.menu`.)

- [ ] **Step 5: Update `DefaultCartService`** — map on the menu path, and add the pre-resolved method. In the existing 4-arg `addLine(cartId, sku, qty, List<UUID> modifierOptionIds)`, after `menu.resolveSelections`, map the resolution to `CartLineModifierInput` before calling the domain method:

```java
    @Override
    public CartView addLine(UUID cartId, String sku, BigDecimal quantity, List<UUID> modifierOptionIds) {
        requirePositive(quantity);
        if (modifierOptionIds == null || modifierOptionIds.isEmpty()) {
            return addLine(cartId, sku, quantity); // plain path
        }
        Cart cart = openCart(cartId);
        ProductView product = catalogue.findBySku(sku)
                .orElseThrow(() -> DomainException.notFound("Unknown sku " + sku));
        ModifierResolution resolution = menu.resolveSelections(sku, modifierOptionIds);
        List<com.company.pos.cart.api.CartLineModifierInput> mods = resolution.modifiers().stream()
                .map(m -> new com.company.pos.cart.api.CartLineModifierInput(
                        m.optionId(), m.name(), m.priceDelta()))
                .toList();
        cart.addLineWithModifiers(sku, product.name(), quantity, product.unitPrice(),
                product.currencyCode(), mods);
        return toView(cart);
    }
```

Then add the pre-resolved method (no `menu` call — the deltas are the caller's snapshot; the base price/name/currency still come from the current catalog):

```java
    @Override
    public CartView addLinePreResolved(UUID cartId, String sku, BigDecimal quantity,
            List<com.company.pos.cart.api.CartLineModifierInput> modifiers) {
        requirePositive(quantity);
        if (modifiers == null || modifiers.isEmpty()) {
            return addLine(cartId, sku, quantity); // plain path merges by sku
        }
        Cart cart = openCart(cartId);
        ProductView product = catalogue.findBySku(sku)
                .orElseThrow(() -> DomainException.notFound("Unknown sku " + sku));
        cart.addLineWithModifiers(sku, product.name(), quantity, product.unitPrice(),
                product.currencyCode(), modifiers);
        return toView(cart);
    }
```

- [ ] **Step 6: Declare it on the `CartService` interface** — add:

```java
    CartView addLinePreResolved(UUID cartId, String sku, BigDecimal quantity,
            java.util.List<CartLineModifierInput> modifiers);
```

- [ ] **Step 7: Run the new test + cart regression + boundaries**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest='CartPreResolvedModifierTest,CartModifierTest,CartServiceTest,ModularityTests'`
Expected: PASS. `CartModifierTest` (the 11a resolve path) still passes because the menu path now maps `ResolvedModifier → CartLineModifierInput` with identical values; `ModularityTests` still green (`cart` still depends on `menu::api` for the resolve path — the change only removed the coupling from the *domain* layer, not the module).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/company/pos/cart/ src/test/java/com/company/pos/cart/CartPreResolvedModifierTest.java
git commit -m "feat(cart): addLinePreResolved folds snapshot modifier deltas without re-resolving

Refactors the cart domain to a cart-owned CartLineModifierInput carrier (drops the
cart.domain -> menu.api coupling; the menu resolve stays in the application layer).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `closeOrder` — one cart line per order line (snapshot deltas)

Replace Phase-10's aggregate-by-sku close with a per-order-line loop that carries each order line's **stored** modifier snapshot to the cart via `addLinePreResolved`. Plain lines still merge in the cart (so existing totals are unchanged); modified lines stay distinct; no re-resolution happens at close.

**Files:**
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java` (`closeOrder` loop)
- Modify: `src/test/java/com/company/pos/dining/DiningCloseServiceTest.java` (add a modifier-close test)

**Interfaces:**
- Consumes: Task 2 `CartService.addLinePreResolved(UUID cartId, String sku, BigDecimal quantity, List<CartLineModifierInput>)`; Task 1 `OrderLine.getModifiers()` (each `OrderLineModifier` → `getOptionId()`/`getName()`/`getPriceDelta()`).
- Produces: no api change.

- [ ] **Step 1: Write the failing test** — add to `DiningCloseServiceTest.java` (it seeds "BURGER" 30.00 already; this test creates a cheese add-on locally; add `@Autowired MenuService menu;` to the class if not present, importing `com.company.pos.menu.api.MenuService`):

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
with a per-order-line loop that carries the stored modifier snapshot:
```java
        // NEW (Phase 11b): one cart line per order line, carrying the modifier deltas
        // SNAPSHOTTED at add-time (no re-resolve — the guest pays the quoted price). The
        // cart's own merge collapses identical plain lines; modified lines stay distinct.
        UUID cartId = carts.createCart();
        for (OrderLine line : order.getLines()) {
            java.util.List<com.company.pos.cart.api.CartLineModifierInput> mods =
                    line.getModifiers().stream()
                            .map(m -> new com.company.pos.cart.api.CartLineModifierInput(
                                    m.getOptionId(), m.getName(), m.getPriceDelta()))
                            .toList();
            carts.addLinePreResolved(cartId, line.getSku(), line.getQty(), mods);
        }
```
(Remove the now-unused `java.util.LinkedHashMap` / `java.util.Map` imports if nothing else uses them.)

- [ ] **Step 4: Run the new test + full dining regression**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=DiningCloseServiceTest`
Expected: PASS — the new modifier-close test passes AND the existing tests (`closingProducesSaleFreesTableAndMarksClosed` grand total 69.00 for two plain burgers, `multiTenderClosesAsOneBill`, etc.) still pass, because two plain same-sku order lines have empty modifiers → `addLinePreResolved` delegates to the plain `addLine` which merges them to one cart line.

- [ ] **Step 5: Full verify**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw verify`
Expected: BUILD SUCCESS — all tests + `ModularityTests` + store-server Testcontainers validating V26–V30. If Docker is unavailable, run `./mvnw test` and note the skipped container tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/dining/application/DefaultDiningService.java \
        src/test/java/com/company/pos/dining/DiningCloseServiceTest.java
git commit -m "feat(dining): close one cart line per order line, billing snapshot modifier deltas

Replaces the Phase-10 sku-merge; carries each order line's add-time modifier snapshot
to the cart via addLinePreResolved (no re-resolve — snapshot-at-order-time contract).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage (11b portion):**
- Dine-in `OrderLine` carries modifier selections, resolved once at add-time via `menu::api` (validation + snapshot of the quoted price) → Task 1 ✅
- `AddLineCommand` gains `modifierOptionIds` (4-arg convenience ctor keeps Phase-10 callers compiling) → Task 1 ✅
- New `cart` pre-resolved seam (`CartLineModifierInput` + `addLinePreResolved`) that folds snapshot deltas without re-resolving; domain decoupled from `menu.api` → Task 2 ✅
- `closeOrder` produces one cart line per order line (replacing aggregate-by-sku), carrying the snapshotted modifier deltas so the delta + detail reach the Sale → Task 3 ✅
- **Snapshot-at-order-time billing contract** (guest pays the quoted price; mid-service menu edits never re-price or block a close) → realized by Task 1 storing the deltas + Task 2/3 folding them without re-resolution ✅
- `dining → menu::api` dependency + `ModularityTests` gate → Task 1 ✅
- Migration V30 → Task 1 ✅

**Placeholder scan:** No TBD/TODO; complete code in every code step. Task 2 Step 4 and Task 3 Step 3 show the exact old block/signature and the new one.

**Type consistency:** `AddLineCommand` 5-arg canonical + 4-arg convenience is used consistently (Task 1 tests use both). `cart.api.CartLineModifierInput(optionId,name,priceDelta)` has the same accessors as the `ResolvedModifier` it replaces in the domain method, so Task 2's refactor is a pure type swap; it is consumed by Task 3's close loop and produced by Task 2. `OrderLine.getModifiers()` (Task 1) feeds Task 3. `OrderLineView`'s trailing `modifiers` matches the `SaleLineView`/`CartLineView` modifier-view shapes from 11a.

**Cross-plan dependency:** every 11b task depends on 11a being available in the working tree (same `phase-11-menu` branch). Task 2 modifies 11a cart code (additively, plus one domain type-swap) — run `CartModifierTest` in Task 2 to confirm the 11a resolve path is unbroken.

**Contract decision (recorded):** the 11a final review flagged that sale-line deltas are snapshot-at-add, not re-resolved; for long-lived dine-in orders this plan makes the **snapshot** the explicit billing contract (chosen over re-resolve-at-close, which would let a mid-service option deactivation block closing a table). Only modifier deltas are snapshotted; the base product price remains as-of-close (unchanged Phase-10 behavior).
