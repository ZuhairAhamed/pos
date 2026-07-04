# Phase 11a — Menu Module + Cart/Sale Modifier Carry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `menu` module (modifier groups/options + variant groups, local admin CRUD) and make the **cart line** carry modifier selections whose price folds into the line and flows through the existing pricing→tax→sale→receipt pipeline, itemized on the bill. Quick-service path only; dine-in integration is Phase 11b.

**Architecture:** New Tier-2 `menu` module is a POS-side overlay keyed by product `sku` (ERP `product` catalog untouched). The cart line — already PK'd by a `UUID id` — becomes id-identified (drop the `(cart_id, sku)` unique constraint) and gains a modifier child collection; effective unit price = base + Σ deltas folds into the one seam sales already reads (`CartLineView.unitPrice`). Sale lines correlate to cart lines by list index to copy modifier detail.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Modulith, Spring Data JPA, Flyway (store-server only), embedded SQLite (tests), JUnit 5 + AssertJ + MockMvc.

## Global Constraints

- **JDK 21.** Prefix every Maven command with `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" &&`.
- **Money/price deltas are `BigDecimal`** — never `double`. Quantities are `BigDecimal`.
- **Module boundaries enforced.** `menu` `package-info.java` declares `allowedDependencies = { "common", "database", "product :: api" }`; `cart` adds `"menu :: api"` to its existing allowed list. Only `:: api` named interfaces may be imported. Run `./mvnw test -Dtest=ModularityTests` after every task.
- **Authorization is method security** (`@PreAuthorize`), not URL rules. Admin menu CRUD requires `ROLE_MANAGER` or `ROLE_ADMIN`; read endpoints (`groupsForSku`, `variant-groups`) are any-authenticated.
- **Flyway versions globally sequential.** Current max **V25**; this plan uses **V26** (menu), **V27** (drop cart_line unique constraint), **V28** (cart_line_modifier), **V29** (sale_line_modifier). Only `store-server` runs Flyway; `embedded` (tests) uses `ddl-auto`.
- **UUID PKs stored as `VARCHAR(36)`** via `@JdbcTypeCode(SqlTypes.VARCHAR)`.
- Tests are `@SpringBootTest @ActiveProfiles("embedded")`; committing (non-`@Transactional`) tests `@Import(DatabaseCleaner.class)` and clean before/after. Seed products via `FakeErpClient.addProduct(new ErpProduct(sku,name,catCode,catName,barcode,uom,price,currency,version,active))` + `ProductSync.sync()`.
- Forced modifier group = `minSelections >= 1`; optional = `minSelections == 0`. A group attaches to many SKUs via `modifier_group_assignment` rows.

---

### Task 1: `menu` module scaffold — api + boundaries

**Files:**
- Create: `src/main/java/com/company/pos/menu/package-info.java`
- Create: `src/main/java/com/company/pos/menu/api/ModifierOptionView.java`
- Create: `src/main/java/com/company/pos/menu/api/ModifierGroupView.java`
- Create: `src/main/java/com/company/pos/menu/api/VariantMemberView.java`
- Create: `src/main/java/com/company/pos/menu/api/VariantGroupView.java`
- Create: `src/main/java/com/company/pos/menu/api/ResolvedModifier.java`
- Create: `src/main/java/com/company/pos/menu/api/ModifierResolution.java`
- Create: `src/main/java/com/company/pos/menu/api/CreateModifierGroupCommand.java`
- Create: `src/main/java/com/company/pos/menu/api/AddOptionCommand.java`
- Create: `src/main/java/com/company/pos/menu/api/CreateVariantGroupCommand.java`
- Create: `src/main/java/com/company/pos/menu/api/AddVariantMemberCommand.java`
- Create: `src/main/java/com/company/pos/menu/api/MenuService.java`
- Modify: `src/test/java/com/company/pos/ModularityTests.java`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: the whole `menu.api` surface used by every later task and by 11b. Signatures below.

- [ ] **Step 1: Write the failing boundary test.** Add to `ModularityTests.java`:

```java
    @Test
    void detectsTheMenuModule() {
        Set<String> names = modules.stream()
                .map(ApplicationModule::getName)
                .collect(Collectors.toSet());
        assertThat(names).contains("menu");
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=ModularityTests#detectsTheMenuModule`
Expected: FAIL — no `menu` module.

- [ ] **Step 3: Create `package-info.java`**

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "product :: api" })
package com.company.pos.menu;
```

- [ ] **Step 4: Create the view DTOs**

`ModifierOptionView.java`:
```java
package com.company.pos.menu.api;

import java.math.BigDecimal;
import java.util.UUID;

public record ModifierOptionView(UUID id, String name, BigDecimal priceDelta) {
}
```

`ModifierGroupView.java`:
```java
package com.company.pos.menu.api;

import java.util.List;
import java.util.UUID;

public record ModifierGroupView(UUID id, String name, int minSelections, int maxSelections,
        List<ModifierOptionView> options) {
}
```

`VariantMemberView.java`:
```java
package com.company.pos.menu.api;

public record VariantMemberView(String sku, String displayLabel) {
}
```

`VariantGroupView.java`:
```java
package com.company.pos.menu.api;

import java.util.List;
import java.util.UUID;

public record VariantGroupView(UUID id, String name, List<VariantMemberView> members) {
}
```

`ResolvedModifier.java`:
```java
package com.company.pos.menu.api;

import java.math.BigDecimal;
import java.util.UUID;

public record ResolvedModifier(UUID optionId, String name, BigDecimal priceDelta) {
}
```

`ModifierResolution.java`:
```java
package com.company.pos.menu.api;

import java.math.BigDecimal;
import java.util.List;

/** The validated modifier selection for one line: the resolved options and their summed delta. */
public record ModifierResolution(List<ResolvedModifier> modifiers, BigDecimal totalDelta) {
}
```

- [ ] **Step 5: Create the command DTOs**

`CreateModifierGroupCommand.java`:
```java
package com.company.pos.menu.api;

public record CreateModifierGroupCommand(String name, int minSelections, int maxSelections) {
}
```

`AddOptionCommand.java`:
```java
package com.company.pos.menu.api;

import java.math.BigDecimal;

public record AddOptionCommand(String name, BigDecimal priceDelta) {
}
```

`CreateVariantGroupCommand.java`:
```java
package com.company.pos.menu.api;

public record CreateVariantGroupCommand(String name) {
}
```

`AddVariantMemberCommand.java`:
```java
package com.company.pos.menu.api;

public record AddVariantMemberCommand(String sku, String displayLabel) {
}
```

- [ ] **Step 6: Create the facade** `MenuService.java`

```java
package com.company.pos.menu.api;

import java.util.List;
import java.util.UUID;

public interface MenuService {

    // --- modifier admin (MANAGER/ADMIN) ---
    ModifierGroupView createModifierGroup(CreateModifierGroupCommand command);

    ModifierOptionView addOption(UUID groupId, AddOptionCommand command);

    void assignGroupToSku(UUID groupId, String sku);

    void unassignGroupFromSku(UUID groupId, String sku);

    void deactivateModifierGroup(UUID groupId);

    // --- variant admin (MANAGER/ADMIN) ---
    VariantGroupView createVariantGroup(CreateVariantGroupCommand command);

    VariantMemberView addVariantMember(UUID variantGroupId, AddVariantMemberCommand command);

    void deactivateVariantGroup(UUID variantGroupId);

    // --- queries (any authenticated caller / used by cart + dining) ---
    List<ModifierGroupView> groupsForSku(String sku);

    /**
     * Validate {@code selectedOptionIds} against every modifier group assigned to {@code sku}
     * (each option must belong to an assigned+active group; each group's min/max enforced),
     * and return the resolved names + deltas with their sum. Throws
     * {@code DomainException.validation} on any rule violation.
     */
    ModifierResolution resolveSelections(String sku, List<UUID> selectedOptionIds);

    List<VariantGroupView> listVariantGroups();
}
```

- [ ] **Step 7: Run the module tests**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=ModularityTests`
Expected: PASS (`detectsTheMenuModule` + `verifiesModuleBoundaries`; the api layer imports only `java.*`).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/company/pos/menu/ src/test/java/com/company/pos/ModularityTests.java
git commit -m "feat(menu): module scaffold — api DTOs, commands, MenuService facade

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Modifier groups, options, assignments + `resolveSelections`

Implements the modifier side of `MenuService` (the variant methods are temporary `UnsupportedOperationException` stubs, replaced in Task 3), the entities/repos, and the V26 migration (which defines **all** menu tables so store-server has them up front).

**Files:**
- Create: `src/main/java/com/company/pos/menu/domain/ModifierGroup.java`
- Create: `src/main/java/com/company/pos/menu/domain/ModifierOption.java`
- Create: `src/main/java/com/company/pos/menu/domain/ModifierGroupAssignment.java`
- Create: `src/main/java/com/company/pos/menu/infrastructure/ModifierGroupRepository.java`
- Create: `src/main/java/com/company/pos/menu/infrastructure/ModifierOptionRepository.java`
- Create: `src/main/java/com/company/pos/menu/infrastructure/ModifierGroupAssignmentRepository.java`
- Create: `src/main/java/com/company/pos/menu/application/DefaultMenuService.java`
- Create: `src/main/resources/db/migration/menu/V26__create_menu_tables.sql`
- Modify: `src/main/resources/application-store-server.yml` (append the menu Flyway location)
- Create: `src/test/java/com/company/pos/menu/ModifierServiceTest.java`

**Interfaces:**
- Consumes: Task 1 api; `com.company.pos.product.api.ProductCatalog.findBySku(String)→Optional<ProductView>`; `com.company.pos.common.util.Identifiers.newId()`; `com.company.pos.common.exception.DomainException`.
- Produces: `DefaultMenuService` (`@Service`, package-private, implements `MenuService`); entities with getters used only within `menu`; repos `ModifierGroupRepository`, `ModifierOptionRepository extends JpaRepository<ModifierOption,UUID>` with `List<ModifierOption> findByGroupId(UUID groupId)`, `ModifierGroupAssignmentRepository` with `List<ModifierGroupAssignment> findBySku(String sku)` and `boolean existsByGroupIdAndSku(UUID groupId, String sku)` and `void deleteByGroupIdAndSku(UUID,String)`.

- [ ] **Step 1: Write the failing service test** — `ModifierServiceTest.java`

```java
package com.company.pos.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.menu.api.AddOptionCommand;
import com.company.pos.menu.api.CreateModifierGroupCommand;
import com.company.pos.menu.api.MenuService;
import com.company.pos.menu.api.ModifierGroupView;
import com.company.pos.menu.api.ModifierOptionView;
import com.company.pos.menu.api.ModifierResolution;
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
class ModifierServiceTest {

    @Autowired MenuService menu;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("STEAK", "Ribeye", "FOOD", "Food", "bcSTEAK",
                "EA", new BigDecimal("80.00"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private UUID tempGroupAssignedToSteak() {
        // forced single-select: temperature
        ModifierGroupView g = menu.createModifierGroup(new CreateModifierGroupCommand("Temperature", 1, 1));
        menu.addOption(g.id(), new AddOptionCommand("Rare", new BigDecimal("0.00")));
        menu.addOption(g.id(), new AddOptionCommand("Medium", new BigDecimal("0.00")));
        menu.assignGroupToSku(g.id(), "STEAK");
        return g.id();
    }

    @Test
    void groupsForSkuReturnsAssignedGroupWithOptions() {
        tempGroupAssignedToSteak();
        List<ModifierGroupView> groups = menu.groupsForSku("STEAK");
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).name()).isEqualTo("Temperature");
        assertThat(groups.get(0).minSelections()).isEqualTo(1);
        assertThat(groups.get(0).options()).extracting(ModifierOptionView::name)
                .containsExactlyInAnyOrder("Rare", "Medium");
    }

    @Test
    void resolveSumsDeltasForValidSelection() {
        ModifierGroupView addons = menu.createModifierGroup(new CreateModifierGroupCommand("Add-ons", 0, 2));
        ModifierOptionView cheese = menu.addOption(addons.id(), new AddOptionCommand("Extra cheese", new BigDecimal("2.00")));
        ModifierOptionView bacon = menu.addOption(addons.id(), new AddOptionCommand("Bacon", new BigDecimal("3.50")));
        menu.assignGroupToSku(addons.id(), "STEAK");

        ModifierResolution r = menu.resolveSelections("STEAK", List.of(cheese.id(), bacon.id()));
        assertThat(r.totalDelta()).isEqualByComparingTo("5.50");
        assertThat(r.modifiers()).extracting("name").containsExactlyInAnyOrder("Extra cheese", "Bacon");
    }

    @Test
    void forcedGroupWithNoSelectionIsRejected() {
        tempGroupAssignedToSteak();
        assertThatThrownBy(() -> menu.resolveSelections("STEAK", List.of()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void exceedingMaxSelectionsIsRejected() {
        UUID gid = tempGroupAssignedToSteak(); // min1 max1
        List<UUID> optionIds = menu.groupsForSku("STEAK").get(0).options().stream()
                .map(ModifierOptionView::id).toList();
        // pick BOTH temperatures → exceeds max 1
        assertThatThrownBy(() -> menu.resolveSelections("STEAK", optionIds))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void optionFromUnassignedGroupIsRejected() {
        ModifierGroupView other = menu.createModifierGroup(new CreateModifierGroupCommand("Sauce", 0, 1));
        ModifierOptionView ketchup = menu.addOption(other.id(), new AddOptionCommand("Ketchup", new BigDecimal("0.00")));
        // NOT assigned to STEAK
        assertThatThrownBy(() -> menu.resolveSelections("STEAK", List.of(ketchup.id())))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void assignToUnknownSkuIsRejected() {
        ModifierGroupView g = menu.createModifierGroup(new CreateModifierGroupCommand("X", 0, 1));
        assertThatThrownBy(() -> menu.assignGroupToSku(g.id(), "NOPE"))
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=ModifierServiceTest`
Expected: FAIL — `DefaultMenuService` / entities do not exist (compile error).

- [ ] **Step 3: Create `ModifierGroup.java`**

```java
package com.company.pos.menu.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "modifier_group")
public class ModifierGroup {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "min_selections", nullable = false)
    private int minSelections;

    @Column(name = "max_selections", nullable = false)
    private int maxSelections;

    @Column(nullable = false)
    private boolean active = true;

    protected ModifierGroup() {
    }

    public ModifierGroup(UUID id, String name, int minSelections, int maxSelections) {
        this.id = id;
        this.name = name;
        this.minSelections = minSelections;
        this.maxSelections = maxSelections;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getMinSelections() {
        return minSelections;
    }

    public int getMaxSelections() {
        return maxSelections;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
```

- [ ] **Step 4: Create `ModifierOption.java`**

```java
package com.company.pos.menu.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "modifier_option")
public class ModifierOption {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "group_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID groupId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "price_delta", nullable = false, precision = 19, scale = 4)
    private BigDecimal priceDelta;

    @Column(nullable = false)
    private boolean active = true;

    protected ModifierOption() {
    }

    public ModifierOption(UUID id, UUID groupId, String name, BigDecimal priceDelta) {
        this.id = id;
        this.groupId = groupId;
        this.name = name;
        this.priceDelta = priceDelta;
    }

    public UUID getId() {
        return id;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPriceDelta() {
        return priceDelta;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
```

- [ ] **Step 5: Create `ModifierGroupAssignment.java`**

```java
package com.company.pos.menu.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "modifier_group_assignment",
        uniqueConstraints = @UniqueConstraint(name = "uq_mga_group_sku",
                columnNames = { "group_id", "sku" }))
public class ModifierGroupAssignment {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "group_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID groupId;

    @Column(nullable = false, length = 64)
    private String sku;

    protected ModifierGroupAssignment() {
    }

    public ModifierGroupAssignment(UUID id, UUID groupId, String sku) {
        this.id = id;
        this.groupId = groupId;
        this.sku = sku;
    }

    public UUID getId() {
        return id;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public String getSku() {
        return sku;
    }
}
```

- [ ] **Step 6: Create the three repositories**

`ModifierGroupRepository.java`:
```java
package com.company.pos.menu.infrastructure;

import com.company.pos.menu.domain.ModifierGroup;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModifierGroupRepository extends JpaRepository<ModifierGroup, UUID> {
}
```

`ModifierOptionRepository.java`:
```java
package com.company.pos.menu.infrastructure;

import com.company.pos.menu.domain.ModifierOption;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModifierOptionRepository extends JpaRepository<ModifierOption, UUID> {

    List<ModifierOption> findByGroupId(UUID groupId);
}
```

`ModifierGroupAssignmentRepository.java`:
```java
package com.company.pos.menu.infrastructure;

import com.company.pos.menu.domain.ModifierGroupAssignment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModifierGroupAssignmentRepository extends JpaRepository<ModifierGroupAssignment, UUID> {

    List<ModifierGroupAssignment> findBySku(String sku);

    boolean existsByGroupIdAndSku(UUID groupId, String sku);

    void deleteByGroupIdAndSku(UUID groupId, String sku);
}
```

- [ ] **Step 7: Create `DefaultMenuService.java`** (modifier methods real; variant methods stubbed)

```java
package com.company.pos.menu.application;

import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.menu.api.AddOptionCommand;
import com.company.pos.menu.api.AddVariantMemberCommand;
import com.company.pos.menu.api.CreateModifierGroupCommand;
import com.company.pos.menu.api.CreateVariantGroupCommand;
import com.company.pos.menu.api.MenuService;
import com.company.pos.menu.api.ModifierGroupView;
import com.company.pos.menu.api.ModifierOptionView;
import com.company.pos.menu.api.ModifierResolution;
import com.company.pos.menu.api.ResolvedModifier;
import com.company.pos.menu.api.VariantGroupView;
import com.company.pos.menu.api.VariantMemberView;
import com.company.pos.menu.domain.ModifierGroup;
import com.company.pos.menu.domain.ModifierGroupAssignment;
import com.company.pos.menu.domain.ModifierOption;
import com.company.pos.menu.infrastructure.ModifierGroupAssignmentRepository;
import com.company.pos.menu.infrastructure.ModifierGroupRepository;
import com.company.pos.menu.infrastructure.ModifierOptionRepository;
import com.company.pos.product.api.ProductCatalog;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultMenuService implements MenuService {

    private final ModifierGroupRepository groups;
    private final ModifierOptionRepository options;
    private final ModifierGroupAssignmentRepository assignments;
    private final ProductCatalog products;

    DefaultMenuService(ModifierGroupRepository groups, ModifierOptionRepository options,
            ModifierGroupAssignmentRepository assignments, ProductCatalog products) {
        this.groups = groups;
        this.options = options;
        this.assignments = assignments;
        this.products = products;
    }

    @Override
    public ModifierGroupView createModifierGroup(CreateModifierGroupCommand command) {
        if (command.name() == null || command.name().isBlank()) {
            throw DomainException.validation("Modifier group name is required");
        }
        if (command.minSelections() < 0 || command.maxSelections() < command.minSelections()
                || command.maxSelections() < 1) {
            throw DomainException.validation("Invalid min/max selections");
        }
        ModifierGroup g = new ModifierGroup(Identifiers.newId(), command.name().trim(),
                command.minSelections(), command.maxSelections());
        return toGroupView(groups.save(g));
    }

    @Override
    public ModifierOptionView addOption(UUID groupId, AddOptionCommand command) {
        loadGroup(groupId);
        if (command.name() == null || command.name().isBlank()) {
            throw DomainException.validation("Option name is required");
        }
        if (command.priceDelta() == null) {
            throw DomainException.validation("Option priceDelta is required (may be 0)");
        }
        ModifierOption o = new ModifierOption(Identifiers.newId(), groupId, command.name().trim(),
                command.priceDelta());
        o = options.save(o);
        return new ModifierOptionView(o.getId(), o.getName(), o.getPriceDelta());
    }

    @Override
    public void assignGroupToSku(UUID groupId, String sku) {
        loadGroup(groupId);
        products.findBySku(sku)
                .orElseThrow(() -> DomainException.validation("Unknown sku " + sku));
        if (assignments.existsByGroupIdAndSku(groupId, sku)) {
            return; // idempotent
        }
        assignments.save(new ModifierGroupAssignment(Identifiers.newId(), groupId, sku));
    }

    @Override
    public void unassignGroupFromSku(UUID groupId, String sku) {
        assignments.deleteByGroupIdAndSku(groupId, sku);
    }

    @Override
    public void deactivateModifierGroup(UUID groupId) {
        loadGroup(groupId).setActive(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModifierGroupView> groupsForSku(String sku) {
        List<ModifierGroupView> result = new ArrayList<>();
        for (ModifierGroupAssignment a : assignments.findBySku(sku)) {
            groups.findById(a.getGroupId())
                    .filter(ModifierGroup::isActive)
                    .ifPresent(g -> result.add(toGroupView(g)));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public ModifierResolution resolveSelections(String sku, List<UUID> selectedOptionIds) {
        List<UUID> selected = selectedOptionIds == null ? List.of() : selectedOptionIds;

        // The groups assigned & active for this sku, keyed by id.
        Map<UUID, ModifierGroup> assignedGroups = new java.util.HashMap<>();
        for (ModifierGroupAssignment a : assignments.findBySku(sku)) {
            groups.findById(a.getGroupId()).filter(ModifierGroup::isActive)
                    .ifPresent(g -> assignedGroups.put(g.getId(), g));
        }

        // Resolve each selected option; it must be active and belong to an assigned group.
        List<ResolvedModifier> resolved = new ArrayList<>();
        Map<UUID, Long> perGroupCount = new java.util.HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (UUID optionId : selected) {
            ModifierOption o = options.findById(optionId)
                    .filter(ModifierOption::isActive)
                    .orElseThrow(() -> DomainException.validation("Unknown or inactive option " + optionId));
            if (!assignedGroups.containsKey(o.getGroupId())) {
                throw DomainException.validation(
                        "Option " + o.getName() + " is not offered for sku " + sku);
            }
            perGroupCount.merge(o.getGroupId(), 1L, Long::sum);
            resolved.add(new ResolvedModifier(o.getId(), o.getName(), o.getPriceDelta()));
            total = total.add(o.getPriceDelta());
        }

        // Enforce each assigned group's min/max against the count picked from it.
        for (ModifierGroup g : assignedGroups.values()) {
            long count = perGroupCount.getOrDefault(g.getId(), 0L);
            if (count < g.getMinSelections()) {
                throw DomainException.validation(
                        "Group " + g.getName() + " requires at least " + g.getMinSelections() + " selection(s)");
            }
            if (count > g.getMaxSelections()) {
                throw DomainException.validation(
                        "Group " + g.getName() + " allows at most " + g.getMaxSelections() + " selection(s)");
            }
        }
        return new ModifierResolution(resolved, total);
    }

    // --- variant methods: implemented in Task 3 ---
    @Override
    public VariantGroupView createVariantGroup(CreateVariantGroupCommand command) {
        throw new UnsupportedOperationException("Implemented in Task 3");
    }

    @Override
    public VariantMemberView addVariantMember(UUID variantGroupId, AddVariantMemberCommand command) {
        throw new UnsupportedOperationException("Implemented in Task 3");
    }

    @Override
    public void deactivateVariantGroup(UUID variantGroupId) {
        throw new UnsupportedOperationException("Implemented in Task 3");
    }

    @Override
    @Transactional(readOnly = true)
    public List<VariantGroupView> listVariantGroups() {
        throw new UnsupportedOperationException("Implemented in Task 3");
    }

    private ModifierGroup loadGroup(UUID groupId) {
        return groups.findById(groupId)
                .orElseThrow(() -> DomainException.notFound("No modifier group " + groupId));
    }

    private ModifierGroupView toGroupView(ModifierGroup g) {
        List<ModifierOptionView> opts = options.findByGroupId(g.getId()).stream()
                .filter(ModifierOption::isActive)
                .map(o -> new ModifierOptionView(o.getId(), o.getName(), o.getPriceDelta()))
                .collect(Collectors.toList());
        return new ModifierGroupView(g.getId(), g.getName(), g.getMinSelections(),
                g.getMaxSelections(), opts);
    }
}
```

- [ ] **Step 8: Create `V26__create_menu_tables.sql`** (all menu tables — variant tables used in Task 3)

```sql
CREATE TABLE modifier_group (
    id             VARCHAR(36) PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    min_selections INTEGER NOT NULL,
    max_selections INTEGER NOT NULL,
    active         BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE modifier_option (
    id          VARCHAR(36) PRIMARY KEY,
    group_id    VARCHAR(36) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    price_delta NUMERIC(19, 4) NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_modifier_option_group FOREIGN KEY (group_id) REFERENCES modifier_group (id)
);
CREATE INDEX idx_modifier_option_group ON modifier_option (group_id);

CREATE TABLE modifier_group_assignment (
    id       VARCHAR(36) PRIMARY KEY,
    group_id VARCHAR(36) NOT NULL,
    sku      VARCHAR(64) NOT NULL,
    CONSTRAINT uq_mga_group_sku UNIQUE (group_id, sku),
    CONSTRAINT fk_mga_group FOREIGN KEY (group_id) REFERENCES modifier_group (id)
);
CREATE INDEX idx_mga_sku ON modifier_group_assignment (sku);

CREATE TABLE variant_group (
    id     VARCHAR(36) PRIMARY KEY,
    name   VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE variant_member (
    id               VARCHAR(36) PRIMARY KEY,
    variant_group_id VARCHAR(36) NOT NULL,
    sku              VARCHAR(64) NOT NULL,
    display_label    VARCHAR(100) NOT NULL,
    CONSTRAINT fk_variant_member_group FOREIGN KEY (variant_group_id) REFERENCES variant_group (id)
);
CREATE INDEX idx_variant_member_group ON variant_member (variant_group_id);
```

- [ ] **Step 9: Register the Flyway location** in `application-store-server.yml`

Append `,classpath:db/migration/menu` to the end of the existing `spring.flyway.locations` value.

- [ ] **Step 10: Run the service test**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=ModifierServiceTest`
Expected: PASS (all 6 tests).

- [ ] **Step 11: Run boundary check**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=ModularityTests`
Expected: PASS (`menu → product :: api` allowed).

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/company/pos/menu/ src/main/resources/db/migration/menu/ \
        src/main/resources/application-store-server.yml \
        src/test/java/com/company/pos/menu/ModifierServiceTest.java
git commit -m "feat(menu): modifier groups/options/assignments + resolveSelections + V26

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Variant groups + members + `MenuController`

Implements the variant methods (replacing the Task 2 stubs) over the V26 `variant_group`/`variant_member` tables, and adds the HTTP surface (admin CRUD manager-gated; read endpoints any-auth).

**Files:**
- Create: `src/main/java/com/company/pos/menu/domain/VariantGroup.java`
- Create: `src/main/java/com/company/pos/menu/domain/VariantMember.java`
- Create: `src/main/java/com/company/pos/menu/infrastructure/VariantGroupRepository.java`
- Create: `src/main/java/com/company/pos/menu/infrastructure/VariantMemberRepository.java`
- Modify: `src/main/java/com/company/pos/menu/application/DefaultMenuService.java`
- Create: `src/main/java/com/company/pos/menu/web/MenuController.java`
- Create: `src/test/java/com/company/pos/menu/VariantServiceTest.java`
- Create: `src/test/java/com/company/pos/menu/MenuControllerTest.java`

**Interfaces:**
- Consumes: Task 1 api; Task 2 `DefaultMenuService`, `ProductCatalog`.
- Produces: `VariantGroup` (getters `getId`/`getName`/`isActive`, `setActive`), `VariantMember` (getters `getSku`/`getDisplayLabel`/`getVariantGroupId`); `VariantGroupRepository`; `VariantMemberRepository` with `List<VariantMember> findByVariantGroupId(UUID id)`.

- [ ] **Step 1: Write the failing service test** — `VariantServiceTest.java`

```java
package com.company.pos.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.menu.api.AddVariantMemberCommand;
import com.company.pos.menu.api.CreateVariantGroupCommand;
import com.company.pos.menu.api.MenuService;
import com.company.pos.menu.api.VariantGroupView;
import com.company.pos.menu.api.VariantMemberView;
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
class VariantServiceTest {

    @Autowired MenuService menu;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("BEER-S", "Draft Beer Small", "BEV", "Bev", "bcBS",
                "EA", new BigDecimal("4.00"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("BEER-L", "Draft Beer Large", "BEV", "Bev", "bcBL",
                "EA", new BigDecimal("7.00"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    @Test
    void createGroupAddMembersAndList() {
        VariantGroupView g = menu.createVariantGroup(new CreateVariantGroupCommand("Draft Beer"));
        menu.addVariantMember(g.id(), new AddVariantMemberCommand("BEER-S", "Small"));
        menu.addVariantMember(g.id(), new AddVariantMemberCommand("BEER-L", "Large"));

        assertThat(menu.listVariantGroups()).hasSize(1);
        VariantGroupView loaded = menu.listVariantGroups().get(0);
        assertThat(loaded.name()).isEqualTo("Draft Beer");
        assertThat(loaded.members()).extracting(VariantMemberView::sku)
                .containsExactlyInAnyOrder("BEER-S", "BEER-L");
    }

    @Test
    void memberWithUnknownSkuIsRejected() {
        VariantGroupView g = menu.createVariantGroup(new CreateVariantGroupCommand("X"));
        assertThatThrownBy(() -> menu.addVariantMember(g.id(), new AddVariantMemberCommand("NOPE", "Nope")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void deactivatedGroupIsExcludedFromList() {
        VariantGroupView g = menu.createVariantGroup(new CreateVariantGroupCommand("Temp"));
        menu.deactivateVariantGroup(g.id());
        assertThat(menu.listVariantGroups()).extracting(VariantGroupView::id).doesNotContain(g.id());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=VariantServiceTest`
Expected: FAIL — variant methods throw `UnsupportedOperationException` / entities missing.

- [ ] **Step 3: Create `VariantGroup.java`**

```java
package com.company.pos.menu.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "variant_group")
public class VariantGroup {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    protected VariantGroup() {
    }

    public VariantGroup(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
```

- [ ] **Step 4: Create `VariantMember.java`**

```java
package com.company.pos.menu.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "variant_member")
public class VariantMember {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "variant_group_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID variantGroupId;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(name = "display_label", nullable = false, length = 100)
    private String displayLabel;

    protected VariantMember() {
    }

    public VariantMember(UUID id, UUID variantGroupId, String sku, String displayLabel) {
        this.id = id;
        this.variantGroupId = variantGroupId;
        this.sku = sku;
        this.displayLabel = displayLabel;
    }

    public UUID getId() {
        return id;
    }

    public UUID getVariantGroupId() {
        return variantGroupId;
    }

    public String getSku() {
        return sku;
    }

    public String getDisplayLabel() {
        return displayLabel;
    }
}
```

- [ ] **Step 5: Create the two repositories**

`VariantGroupRepository.java`:
```java
package com.company.pos.menu.infrastructure;

import com.company.pos.menu.domain.VariantGroup;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VariantGroupRepository extends JpaRepository<VariantGroup, UUID> {
}
```

`VariantMemberRepository.java`:
```java
package com.company.pos.menu.infrastructure;

import com.company.pos.menu.domain.VariantMember;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VariantMemberRepository extends JpaRepository<VariantMember, UUID> {

    List<VariantMember> findByVariantGroupId(UUID variantGroupId);
}
```

- [ ] **Step 6: Implement the variant methods** in `DefaultMenuService.java`

Add fields + constructor params for the two repos (import them + the domain classes):
```java
    private final VariantGroupRepository variantGroups;
    private final VariantMemberRepository variantMembers;
```
Extend the constructor to accept and assign both. Then replace the four variant stubs with:
```java
    @Override
    public VariantGroupView createVariantGroup(CreateVariantGroupCommand command) {
        if (command.name() == null || command.name().isBlank()) {
            throw DomainException.validation("Variant group name is required");
        }
        VariantGroup g = variantGroups.save(new VariantGroup(Identifiers.newId(), command.name().trim()));
        return toVariantView(g);
    }

    @Override
    public VariantMemberView addVariantMember(UUID variantGroupId, AddVariantMemberCommand command) {
        variantGroups.findById(variantGroupId)
                .orElseThrow(() -> DomainException.notFound("No variant group " + variantGroupId));
        products.findBySku(command.sku())
                .orElseThrow(() -> DomainException.validation("Unknown sku " + command.sku()));
        VariantMember m = variantMembers.save(new VariantMember(Identifiers.newId(),
                variantGroupId, command.sku(), command.displayLabel()));
        return new VariantMemberView(m.getSku(), m.getDisplayLabel());
    }

    @Override
    public void deactivateVariantGroup(UUID variantGroupId) {
        variantGroups.findById(variantGroupId)
                .orElseThrow(() -> DomainException.notFound("No variant group " + variantGroupId))
                .setActive(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VariantGroupView> listVariantGroups() {
        return variantGroups.findAll().stream()
                .filter(VariantGroup::isActive)
                .map(this::toVariantView)
                .collect(Collectors.toList());
    }
```
Add the helper + imports (`com.company.pos.menu.domain.VariantGroup`, `VariantMember`, `VariantMemberView`, the two repositories):
```java
    private VariantGroupView toVariantView(VariantGroup g) {
        List<VariantMemberView> members = variantMembers.findByVariantGroupId(g.getId()).stream()
                .map(m -> new VariantMemberView(m.getSku(), m.getDisplayLabel()))
                .collect(Collectors.toList());
        return new VariantGroupView(g.getId(), g.getName(), members);
    }
```

- [ ] **Step 7: Run the variant service test**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=VariantServiceTest`
Expected: PASS (3 tests).

- [ ] **Step 8: Create `MenuController.java`**

```java
package com.company.pos.menu.web;

import com.company.pos.menu.api.AddOptionCommand;
import com.company.pos.menu.api.AddVariantMemberCommand;
import com.company.pos.menu.api.CreateModifierGroupCommand;
import com.company.pos.menu.api.CreateVariantGroupCommand;
import com.company.pos.menu.api.MenuService;
import com.company.pos.menu.api.ModifierGroupView;
import com.company.pos.menu.api.ModifierOptionView;
import com.company.pos.menu.api.VariantGroupView;
import com.company.pos.menu.api.VariantMemberView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MenuController {

    private final MenuService menu;

    MenuController(MenuService menu) {
        this.menu = menu;
    }

    // --- modifier admin (MANAGER/ADMIN) ---
    @PostMapping("/menu/modifier-groups")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    ModifierGroupView createGroup(@RequestBody CreateModifierGroupCommand body) {
        return menu.createModifierGroup(body);
    }

    @PostMapping("/menu/modifier-groups/{groupId}/options")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    ModifierOptionView addOption(@PathVariable UUID groupId, @RequestBody AddOptionCommand body) {
        return menu.addOption(groupId, body);
    }

    @PostMapping("/menu/modifier-groups/{groupId}/assignments")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    void assign(@PathVariable UUID groupId, @RequestParam String sku) {
        menu.assignGroupToSku(groupId, sku);
    }

    @DeleteMapping("/menu/modifier-groups/{groupId}/assignments")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    void unassign(@PathVariable UUID groupId, @RequestParam String sku) {
        menu.unassignGroupFromSku(groupId, sku);
    }

    @DeleteMapping("/menu/modifier-groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    void deactivateGroup(@PathVariable UUID groupId) {
        menu.deactivateModifierGroup(groupId);
    }

    // --- variant admin (MANAGER/ADMIN) ---
    @PostMapping("/menu/variant-groups")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    VariantGroupView createVariantGroup(@RequestBody CreateVariantGroupCommand body) {
        return menu.createVariantGroup(body);
    }

    @PostMapping("/menu/variant-groups/{groupId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    VariantMemberView addMember(@PathVariable UUID groupId, @RequestBody AddVariantMemberCommand body) {
        return menu.addVariantMember(groupId, body);
    }

    @DeleteMapping("/menu/variant-groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    void deactivateVariantGroup(@PathVariable UUID groupId) {
        menu.deactivateVariantGroup(groupId);
    }

    // --- reads (any authenticated caller) ---
    @GetMapping("/menu/products/{sku}/modifier-groups")
    List<ModifierGroupView> groupsForSku(@PathVariable String sku) {
        return menu.groupsForSku(sku);
    }

    @GetMapping("/menu/variant-groups")
    List<VariantGroupView> variantGroups() {
        return menu.listVariantGroups();
    }
}
```

- [ ] **Step 9: Write the failing controller test** — `MenuControllerTest.java`

```java
package com.company.pos.menu;

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
class MenuControllerTest {

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
    void managerCreatesModifierGroup() throws Exception {
        mvc.perform(post("/menu/modifier-groups").with(manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Add-ons\",\"minSelections\":0,\"maxSelections\":2}"))
                .andExpect(status().isCreated());
    }

    @Test
    void cashierCannotCreateModifierGroup() throws Exception {
        mvc.perform(post("/menu/modifier-groups").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Add-ons\",\"minSelections\":0,\"maxSelections\":2}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anyCashierCanReadVariantGroups() throws Exception {
        mvc.perform(get("/menu/variant-groups").with(cashier()))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 10: Run controller test**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=MenuControllerTest`
Expected: PASS (3 tests).

- [ ] **Step 11: Run the whole menu module + boundaries**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest='com.company.pos.menu.*,ModularityTests'`
Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/company/pos/menu/ src/test/java/com/company/pos/menu/VariantServiceTest.java \
        src/test/java/com/company/pos/menu/MenuControllerTest.java
git commit -m "feat(menu): variant groups/members + MenuController (admin CRUD + reads)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Cart line-identity refactor (sku-keyed → lineId)

Migrate cart lines from sku-keyed to their existing `UUID id` identity, so duplicate SKUs can coexist (prerequisite for modifiers). No modifier behavior yet — plain `addLine` still merges by sku. Drops the `(cart_id, sku)` unique constraint.

**Files:**
- Modify: `src/main/java/com/company/pos/cart/domain/CartLine.java` (expose `getId()`, drop unique constraint)
- Modify: `src/main/java/com/company/pos/cart/domain/Cart.java` (id-based update/remove; keep sku merge for plain add)
- Modify: `src/main/java/com/company/pos/cart/api/CartService.java` (update/remove signatures → `UUID lineId`)
- Modify: `src/main/java/com/company/pos/cart/api/CartLineView.java` (add `UUID lineId`)
- Modify: `src/main/java/com/company/pos/cart/application/DefaultCartService.java`
- Modify: `src/main/java/com/company/pos/cart/web/CartController.java`
- Create: `src/main/resources/db/migration/cart/V27__drop_cart_line_sku_unique.sql`
- Modify: existing cart tests that call `updateLine`/`removeLine` by sku (find with grep in Step 1)

**Interfaces:**
- Consumes: existing `Cart`/`CartLine`.
- Produces: `CartService.updateLine(UUID cartId, UUID lineId, BigDecimal quantity)`, `removeLine(UUID cartId, UUID lineId)`; `CartLineView(UUID lineId, String sku, String name, BigDecimal quantity, BigDecimal unitPrice, String currencyCode)`; `Cart.findLineById(UUID)`, `setLineQuantityById(UUID, BigDecimal)`, `removeLineById(UUID)`; `CartLine.getId()`.

- [ ] **Step 1: Find the callers to migrate**

Run: `grep -rn "updateLine\|removeLine\|\.lineId\|CartLineView(" src/main/java src/test/java | grep -i cart`
Note every test that calls `updateLine(cartId, "SKU", ...)` / `removeLine(cartId, "SKU")` and every `CartLineView(...)` constructor use — these migrate in Step 7.

- [ ] **Step 2: Write the failing test** — add to the existing cart service test (find it: `grep -rln "class .*CartServiceTest" src/test/java`; if none, create `src/test/java/com/company/pos/cart/CartLineIdTest.java` with the standard `@SpringBootTest @ActiveProfiles("embedded") @Import(DatabaseCleaner.class)` header, autowiring `CartService carts`, `ProductSync productSync`, `FakeErpClient fake`, seeding one product "COLA" 4.50 as in `SalesSummaryReportTest`):

```java
    @Test
    void updateAndRemoveByLineId() {
        UUID cartId = carts.createCart();
        CartView c = carts.addLine(cartId, "COLA", new BigDecimal("1"));
        UUID lineId = c.lines().get(0).lineId();

        CartView afterUpdate = carts.updateLine(cartId, lineId, new BigDecimal("3"));
        assertThat(afterUpdate.lines().get(0).quantity()).isEqualByComparingTo("3");

        CartView afterRemove = carts.removeLine(cartId, lineId);
        assertThat(afterRemove.lines()).isEmpty();
    }
```

- [ ] **Step 3: Run to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=CartLineIdTest` (or the existing class name)
Expected: FAIL — `CartLineView.lineId()` and the id-based `updateLine`/`removeLine` don't exist (compile error).

- [ ] **Step 4: Update the domain.** In `CartLine.java` add the getter and remove the sku unique constraint:

Change the class annotation from
```java
@Table(name = "cart_line",
        uniqueConstraints = @UniqueConstraint(name = "uq_cart_line_sku",
                columnNames = { "cart_id", "sku" }))
```
to
```java
@Table(name = "cart_line")
```
(remove the now-unused `import jakarta.persistence.UniqueConstraint;`). Add:
```java
    public UUID getId() {
        return id;
    }
```

In `Cart.java` add id-based operations (keep the existing sku-based `addLine`/`findLine` for now):
```java
    public Optional<CartLine> findLineById(UUID lineId) {
        return lines.stream().filter(l -> l.getId().equals(lineId)).findFirst();
    }

    public void setLineQuantityById(UUID lineId, BigDecimal quantity) {
        findLineById(lineId).ifPresent(line -> line.setQuantity(quantity));
    }

    public void removeLineById(UUID lineId) {
        lines.removeIf(l -> l.getId().equals(lineId));
        renumber();
    }
```

- [ ] **Step 5: Update the api.** `CartLineView.java`:
```java
package com.company.pos.cart.api;

import java.math.BigDecimal;
import java.util.UUID;

public record CartLineView(UUID lineId, String sku, String name, BigDecimal quantity,
        BigDecimal unitPrice, String currencyCode) {
}
```
`CartService.java` — change the two signatures:
```java
    CartView updateLine(UUID cartId, UUID lineId, BigDecimal quantity);

    CartView removeLine(UUID cartId, UUID lineId);
```

- [ ] **Step 6: Update `DefaultCartService.java`.** Replace the `updateLine`/`removeLine` bodies and the `toView` line mapping:
```java
    @Override
    public CartView updateLine(UUID cartId, UUID lineId, BigDecimal quantity) {
        requirePositive(quantity);
        Cart cart = openCart(cartId);
        if (cart.findLineById(lineId).isEmpty()) {
            throw DomainException.notFound("No line " + lineId);
        }
        cart.setLineQuantityById(lineId, quantity);
        return toView(cart);
    }

    @Override
    public CartView removeLine(UUID cartId, UUID lineId) {
        Cart cart = openCart(cartId);
        if (cart.findLineById(lineId).isEmpty()) {
            throw DomainException.notFound("No line " + lineId);
        }
        cart.removeLineById(lineId);
        return toView(cart);
    }
```
In the `toView` mapping, add `l.getId()` as the first `CartLineView` arg (find the `new CartLineView(...)` in `toView` and prepend `l.getId(),`).

- [ ] **Step 7: Migrate the callers.** In `CartController.java`, change the update/remove line endpoints to take a `UUID lineId` path variable instead of `sku` (e.g. `PUT /carts/{cartId}/lines/{lineId}`, `DELETE /carts/{cartId}/lines/{lineId}`) and pass it through. Update every test found in Step 1 that called `updateLine`/`removeLine` by sku to first read the `lineId` from the returned `CartView` and pass that. Fix every `CartLineView(...)` constructor call in tests to include the new leading `lineId` arg (or read via `.lineId()`).

- [ ] **Step 8: Create `V27__drop_cart_line_sku_unique.sql`**

```sql
-- Modifiers allow two lines with the same sku but different selections, so the
-- (cart_id, sku) uniqueness no longer holds. cart_line keeps its own id PK.
ALTER TABLE cart_line DROP CONSTRAINT uq_cart_line_sku;
```

- [ ] **Step 9: Run cart tests + boundaries**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest='com.company.pos.cart.*,ModularityTests'`
Expected: PASS. Then run the checkout/sales tests that build carts to catch any missed `CartLineView` change: `./mvnw test -Dtest='com.company.pos.sales.*'` — expected PASS.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/company/pos/cart/ src/main/resources/db/migration/cart/V27__drop_cart_line_sku_unique.sql src/test/java/com/company/pos/cart/
git commit -m "refactor(cart): identify lines by lineId (not sku); drop uq_cart_line_sku + V27

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Cart carries modifier selections

Add `addLine(cartId, sku, qty, modifierOptionIds)` that resolves via `menu::api`, stores the modifiers on the line, folds their delta into the effective unit price, and merges only when sku + modifier set match.

**Files:**
- Create: `src/main/java/com/company/pos/cart/domain/CartLineModifier.java`
- Modify: `src/main/java/com/company/pos/cart/domain/CartLine.java` (modifier collection, basePrice, effective price, `modifierKey()`)
- Modify: `src/main/java/com/company/pos/cart/domain/Cart.java` (modifier-aware add + merge)
- Modify: `src/main/java/com/company/pos/cart/api/CartService.java` (new overload)
- Modify: `src/main/java/com/company/pos/cart/api/CartLineView.java` (basePrice + modifiers)
- Create: `src/main/java/com/company/pos/cart/api/CartLineModifierView.java`
- Modify: `src/main/java/com/company/pos/cart/application/DefaultCartService.java` (menu dep)
- Modify: `src/main/java/com/company/pos/cart/package-info.java` (add `menu :: api`)
- Modify: `src/main/java/com/company/pos/cart/web/CartController.java` (accept modifier ids)
- Create: `src/main/resources/db/migration/cart/V28__create_cart_line_modifier.sql`
- Create: `src/test/java/com/company/pos/cart/CartModifierTest.java`

**Interfaces:**
- Consumes: Task 1 `MenuService.resolveSelections(sku, List<UUID>)→ModifierResolution{modifiers:[{optionId,name,priceDelta}], totalDelta}`; Task 4 `CartLine.getId()`.
- Produces: `CartService.addLine(UUID cartId, String sku, BigDecimal quantity, List<UUID> modifierOptionIds)`; `CartLineView(UUID lineId, String sku, String name, BigDecimal quantity, BigDecimal basePrice, BigDecimal unitPrice, String currencyCode, List<CartLineModifierView> modifiers)`; `CartLineModifierView(UUID optionId, String name, BigDecimal priceDelta)`; `CartLine.getBasePrice()`, `getModifiers()` (unmodifiable), `getUnitPrice()` returns effective price.

- [ ] **Step 1: Write the failing test** — `CartModifierTest.java`

```java
package com.company.pos.cart;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartLineView;
import com.company.pos.cart.api.CartService;
import com.company.pos.cart.api.CartView;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.menu.api.AddOptionCommand;
import com.company.pos.menu.api.CreateModifierGroupCommand;
import com.company.pos.menu.api.MenuService;
import com.company.pos.menu.api.ModifierGroupView;
import com.company.pos.menu.api.ModifierOptionView;
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
class CartModifierTest {

    @Autowired CartService carts;
    @Autowired MenuService menu;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    private UUID cheeseId;
    private UUID baconId;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Burger", "FOOD", "Food", "bcB",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        productSync.sync();
        ModifierGroupView addons = menu.createModifierGroup(new CreateModifierGroupCommand("Add-ons", 0, 3));
        cheeseId = menu.addOption(addons.id(), new AddOptionCommand("Extra cheese", new BigDecimal("2.00"))).id();
        baconId = menu.addOption(addons.id(), new AddOptionCommand("Bacon", new BigDecimal("3.50"))).id();
        menu.assignGroupToSku(addons.id(), "BURGER");
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    @Test
    void modifierDeltaFoldsIntoUnitPriceAndDetailIsStored() {
        UUID cartId = carts.createCart();
        CartView c = carts.addLine(cartId, "BURGER", new BigDecimal("1"), List.of(cheeseId, baconId));

        CartLineView line = c.lines().get(0);
        assertThat(line.basePrice()).isEqualByComparingTo("30.00");
        assertThat(line.unitPrice()).isEqualByComparingTo("35.50"); // 30 + 2 + 3.50
        assertThat(line.modifiers()).extracting("name").containsExactlyInAnyOrder("Extra cheese", "Bacon");
    }

    @Test
    void differentModifierSetsCreateSeparateLines() {
        UUID cartId = carts.createCart();
        carts.addLine(cartId, "BURGER", new BigDecimal("1"), List.of(cheeseId));
        CartView c = carts.addLine(cartId, "BURGER", new BigDecimal("1"), List.of(baconId));
        assertThat(c.lines()).hasSize(2);
    }

    @Test
    void sameSkuSameModifiersMergeQuantity() {
        UUID cartId = carts.createCart();
        carts.addLine(cartId, "BURGER", new BigDecimal("1"), List.of(cheeseId));
        CartView c = carts.addLine(cartId, "BURGER", new BigDecimal("1"), List.of(cheeseId));
        assertThat(c.lines()).hasSize(1);
        assertThat(c.lines().get(0).quantity()).isEqualByComparingTo("2");
    }

    @Test
    void plainLineHasBaseUnitPriceAndNoModifiers() {
        UUID cartId = carts.createCart();
        CartView c = carts.addLine(cartId, "BURGER", new BigDecimal("1"));
        assertThat(c.lines().get(0).unitPrice()).isEqualByComparingTo("30.00");
        assertThat(c.lines().get(0).modifiers()).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=CartModifierTest`
Expected: FAIL — the 4-arg `addLine` and `CartLineView.basePrice()/modifiers()` don't exist.

- [ ] **Step 3: Create `CartLineModifier.java`**

```java
package com.company.pos.cart.domain;

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
@Table(name = "cart_line_modifier")
public class CartLineModifier {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "line_id", nullable = false)
    private CartLine line;

    @Column(name = "option_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID optionId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "price_delta", nullable = false, precision = 19, scale = 4)
    private BigDecimal priceDelta;

    protected CartLineModifier() {
    }

    CartLineModifier(UUID id, CartLine line, UUID optionId, String name, BigDecimal priceDelta) {
        this.id = id;
        this.line = line;
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

- [ ] **Step 4: Extend `CartLine.java`** — add a base price, a modifier collection, effective price, and a merge key. Add imports (`CascadeType`, `OneToMany`, `OrderColumn` not needed; `List`, `ArrayList`, `Collections`, `TreeSet`, `stream`), the fields, ctor changes, and methods:

```java
    @Column(name = "base_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal basePrice;

    @jakarta.persistence.OneToMany(mappedBy = "line", cascade = jakarta.persistence.CascadeType.ALL,
            orphanRemoval = true)
    private java.util.List<CartLineModifier> modifiers = new java.util.ArrayList<>();
```
Change the constructor to accept and set `basePrice` (the product unit price) and to keep `unitPrice` as the effective price. Add a factory used by `Cart` that also attaches modifiers:
```java
    void addModifier(UUID optionId, String name, BigDecimal priceDelta) {
        modifiers.add(new CartLineModifier(com.company.pos.common.util.Identifiers.newId(),
                this, optionId, name, priceDelta));
    }

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public java.util.List<CartLineModifier> getModifiers() {
        return java.util.Collections.unmodifiableList(modifiers);
    }

    /** Recompute effective unit price = base + Σ deltas. Call after modifiers change. */
    void recomputeUnitPrice() {
        BigDecimal sum = basePrice;
        for (CartLineModifier m : modifiers) {
            sum = sum.add(m.getPriceDelta());
        }
        this.unitPrice = sum;
    }

    /** Merge identity: sku + the SORTED set of option ids (empty for a plain line). */
    String modifierKey() {
        return modifiers.stream().map(m -> m.getOptionId().toString())
                .sorted().collect(java.util.stream.Collectors.joining(","));
    }
```
Update the existing sku-only constructor so `basePrice` defaults to `unitPrice` and no modifiers (so plain lines are unaffected): set `this.basePrice = unitPrice;`.

- [ ] **Step 5: Extend `Cart.java`** — add a modifier-aware add that merges on `(sku, modifierKey)`:

```java
    public void addLineWithModifiers(String sku, String name, BigDecimal quantity, BigDecimal basePrice,
            String currency, java.util.List<com.company.pos.menu.api.ResolvedModifier> mods) {
        if (this.currencyCode == null) {
            this.currencyCode = currency;
        }
        String key = mods.stream().map(m -> m.optionId().toString()).sorted()
                .collect(java.util.stream.Collectors.joining(","));
        for (CartLine line : lines) {
            if (line.getSku().equals(sku) && line.modifierKey().equals(key)) {
                line.addQuantity(quantity);
                return;
            }
        }
        CartLine line = new CartLine(this, lines.size() + 1, sku, name, quantity, basePrice, currency);
        for (com.company.pos.menu.api.ResolvedModifier m : mods) {
            line.addModifier(m.optionId(), m.name(), m.priceDelta());
        }
        line.recomputeUnitPrice();
        lines.add(line);
    }
```
(The existing sku-based `addLine`/`findLine`/`setLineQuantity` stay for the plain path; note `findLine(sku)` now returns the first sku match, which is fine because the plain `addLine` only ever creates plain lines and merges them.)

- [ ] **Step 6: Add the api overload + view.** `CartLineModifierView.java`:
```java
package com.company.pos.cart.api;

import java.math.BigDecimal;
import java.util.UUID;

public record CartLineModifierView(UUID optionId, String name, BigDecimal priceDelta) {
}
```
`CartLineView.java`:
```java
package com.company.pos.cart.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CartLineView(UUID lineId, String sku, String name, BigDecimal quantity,
        BigDecimal basePrice, BigDecimal unitPrice, String currencyCode,
        List<CartLineModifierView> modifiers) {
}
```
`CartService.java` — add:
```java
    CartView addLine(UUID cartId, String sku, BigDecimal quantity, java.util.List<UUID> modifierOptionIds);
```

- [ ] **Step 7: Wire `DefaultCartService.java`** — add `menu :: api` dependency and implement the overload + updated mapping. Add field/ctor param `MenuService menu` (import `com.company.pos.menu.api.MenuService`, `ModifierResolution`). Implement:
```java
    @Override
    public CartView addLine(UUID cartId, String sku, BigDecimal quantity, java.util.List<UUID> modifierOptionIds) {
        requirePositive(quantity);
        if (modifierOptionIds == null || modifierOptionIds.isEmpty()) {
            return addLine(cartId, sku, quantity); // plain path
        }
        Cart cart = openCart(cartId);
        ProductView product = catalogue.findBySku(sku)
                .orElseThrow(() -> DomainException.notFound("Unknown sku " + sku));
        ModifierResolution resolution = menu.resolveSelections(sku, modifierOptionIds);
        cart.addLineWithModifiers(sku, product.name(), quantity, product.unitPrice(),
                product.currencyCode(), resolution.modifiers());
        return toView(cart);
    }
```
Update `toView`'s line mapping to the new `CartLineView` shape (lineId, sku, name, quantity, basePrice, unitPrice, currency, modifiers):
```java
    // inside toView, mapping each CartLine l:
    new CartLineView(l.getId(), l.getSku(), l.getName(), l.getQuantity(), l.getBasePrice(),
            l.getUnitPrice(), l.getCurrencyCode(),
            l.getModifiers().stream()
                    .map(m -> new com.company.pos.cart.api.CartLineModifierView(
                            m.getOptionId(), m.getName(), m.getPriceDelta()))
                    .toList())
```
In `cart/package-info.java`, add `"menu :: api"` to `allowedDependencies`.

- [ ] **Step 8: Controller.** In `CartController.java`, extend the add-line endpoint to accept optional modifier option ids (e.g. a request body field `modifierOptionIds` or `@RequestParam List<UUID> modifierOptionIds`) and call the 4-arg `addLine` when present, else the 3-arg. Keep the existing plain add working.

- [ ] **Step 9: Create `V28__create_cart_line_modifier.sql`**

```sql
CREATE TABLE cart_line_modifier (
    id          VARCHAR(36) PRIMARY KEY,
    line_id     VARCHAR(36) NOT NULL,
    option_id   VARCHAR(36) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    price_delta NUMERIC(19, 4) NOT NULL,
    CONSTRAINT fk_cart_line_modifier_line FOREIGN KEY (line_id) REFERENCES cart_line (id)
);
CREATE INDEX idx_cart_line_modifier_line ON cart_line_modifier (line_id);

ALTER TABLE cart_line ADD COLUMN base_price NUMERIC(19, 4);
UPDATE cart_line SET base_price = unit_price WHERE base_price IS NULL;
```

- [ ] **Step 10: Run modifier + cart + boundary tests**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest='com.company.pos.cart.*,ModularityTests'`
Expected: PASS (incl. `cart → menu :: api` allowed). If a prior cart/sales test breaks on the new `CartLineView` arity, fix its constructor/accessor use.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/company/pos/cart/ src/main/resources/db/migration/cart/V28__create_cart_line_modifier.sql \
        src/test/java/com/company/pos/cart/CartModifierTest.java
git commit -m "feat(cart): carry modifier selections; fold delta into effective unit price + V28

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Sale line + receipt itemize modifiers

Persist the modifier breakdown on the `SaleLine` (copied from the cart line by list index at checkout) and render modifiers as indented sub-items on the receipt. Totals/tax already reflect the folded price — no math change.

**Files:**
- Create: `src/main/java/com/company/pos/sales/domain/SaleLineModifier.java`
- Modify: `src/main/java/com/company/pos/sales/domain/SaleLine.java` (modifier child collection)
- Modify: `src/main/java/com/company/pos/sales/api/SaleLineView.java` (add modifiers)
- Create: `src/main/java/com/company/pos/sales/api/SaleLineModifierView.java`
- Modify: `src/main/java/com/company/pos/sales/application/DefaultSalesService.java` (copy modifiers from cart line by index; map to view)
- Modify: `src/main/java/com/company/pos/receipt/api/ReceiptLineData.java` (add modifiers)
- Create: `src/main/java/com/company/pos/receipt/api/ReceiptLineModifierData.java`
- Modify: the receipt renderer (find with `grep -rln "ReceiptLineData" src/main/java/com/company/pos/receipt`) to print modifiers
- Modify: wherever `ReceiptData` is built from the sale (find with `grep -rln "new ReceiptData\|ReceiptLineData(" src/main/java`)
- Create: `src/main/resources/db/migration/sales/V29__create_sale_line_modifier.sql`
- Create: `src/test/java/com/company/pos/sales/SaleModifierTest.java`

**Interfaces:**
- Consumes: Task 5 `CartLineView.modifiers()→List<CartLineModifierView>{optionId,name,priceDelta}`, `CartView.lines()` (order-preserving); the checkout pipeline maps `cart.lines().get(i)` ↔ sale line `i`.
- Produces: `SaleLineView` gains trailing `List<SaleLineModifierView> modifiers`; `SaleLineModifierView(UUID optionId, String name, BigDecimal priceDelta)`; `ReceiptLineData` gains trailing `List<ReceiptLineModifierData> modifiers`; `ReceiptLineModifierData(String name, BigDecimal priceDelta)`.

- [ ] **Step 1: Write the failing test** — `SaleModifierTest.java`

```java
package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.menu.api.AddOptionCommand;
import com.company.pos.menu.api.CreateModifierGroupCommand;
import com.company.pos.menu.api.MenuService;
import com.company.pos.menu.api.ModifierGroupView;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SaleLineView;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
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
class SaleModifierTest {

    @Autowired SalesService sales;
    @Autowired CartService carts;
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

    @Test
    void saleLineReflectsModifierPriceAndPersistsDetail() {
        UUID cartId = carts.createCart();
        carts.addLine(cartId, "BURGER", new BigDecimal("1"), List.of(cheeseId));
        // effective unit price 32.00; +15% tax = 4.80; grand 36.80
        SaleView sale = sales.checkout(new CheckoutCommand(cartId,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("40.00")))), "cashier");

        assertThat(sale.grandTotal()).isEqualByComparingTo("36.80");
        SaleLineView line = sale.lines().get(0);
        assertThat(line.unitPrice()).isEqualByComparingTo("32.00");
        assertThat(line.modifiers()).extracting("name").containsExactly("Extra cheese");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=SaleModifierTest`
Expected: FAIL — `SaleLineView.modifiers()` doesn't exist.

- [ ] **Step 3: Create `SaleLineModifier.java`** (mirror `CartLineModifier`, `@Table("sale_line_modifier")`, `@ManyToOne @JoinColumn(name="sale_line_id")` to `SaleLine`, fields `optionId`/`name`/`priceDelta`, getters). Full code:

```java
package com.company.pos.sales.domain;

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
@Table(name = "sale_line_modifier")
public class SaleLineModifier {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "sale_line_id", nullable = false)
    private SaleLine saleLine;

    @Column(name = "option_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID optionId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "price_delta", nullable = false, precision = 19, scale = 4)
    private BigDecimal priceDelta;

    protected SaleLineModifier() {
    }

    public SaleLineModifier(UUID id, SaleLine saleLine, UUID optionId, String name, BigDecimal priceDelta) {
        this.id = id;
        this.saleLine = saleLine;
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

- [ ] **Step 4: Extend `SaleLine.java`** — add a modifier collection + accessor + adder (mirror the cart line pattern):
```java
    @jakarta.persistence.OneToMany(mappedBy = "saleLine",
            cascade = jakarta.persistence.CascadeType.ALL, orphanRemoval = true)
    private java.util.List<SaleLineModifier> modifiers = new java.util.ArrayList<>();

    public void addModifier(UUID optionId, String name, BigDecimal priceDelta) {
        modifiers.add(new SaleLineModifier(com.company.pos.common.util.Identifiers.newId(),
                this, optionId, name, priceDelta));
    }

    public java.util.List<SaleLineModifier> getModifiers() {
        return java.util.Collections.unmodifiableList(modifiers);
    }
```

- [ ] **Step 5: Extend the api.** `SaleLineModifierView.java`:
```java
package com.company.pos.sales.api;

import java.math.BigDecimal;
import java.util.UUID;

public record SaleLineModifierView(UUID optionId, String name, BigDecimal priceDelta) {
}
```
Add a trailing `List<SaleLineModifierView> modifiers` field to `SaleLineView` (append after `lineDiscountReason`; add `import java.util.List;`).

- [ ] **Step 6: Copy modifiers at checkout** in `DefaultSalesService.java`. The sale-line build loop already iterates `i` over `taxed.lines()`; the cart line at the same index carries the modifiers (pricing/discount/tax preserve order and count). After constructing each `SaleLine sl` and before/after `sale.addLine(sl)`, attach modifiers from `cart.lines().get(i)`:
```java
        // after building sale line `sl` for index i:
        for (var m : cart.lines().get(i).modifiers()) {
            sl.addModifier(m.optionId(), m.name(), m.priceDelta());
        }
```
(Refactor the existing loop so the `SaleLine` is held in a local `sl` before `sale.addLine(sl)`.) Then, where `SaleLineView` is built for the returned `SaleView`, map the modifiers:
```java
        sl.getModifiers().stream()
                .map(m -> new SaleLineModifierView(m.getOptionId(), m.getName(), m.getPriceDelta()))
                .toList()
```

- [ ] **Step 7: Extend receipt.** `ReceiptLineModifierData.java`:
```java
package com.company.pos.receipt.api;

import java.math.BigDecimal;

public record ReceiptLineModifierData(String name, BigDecimal priceDelta) {
}
```
Add a trailing `List<ReceiptLineModifierData> modifiers` field to `ReceiptLineData` (and default it to `List.of()` in the existing convenience constructor). In the receipt renderer, after printing each line, print each modifier indented (e.g. `"  + " + name` and, if `priceDelta` is non-zero, the delta). Where `ReceiptData` is assembled from the sale, populate each line's modifiers from the sale line's modifiers.

- [ ] **Step 8: Create `V29__create_sale_line_modifier.sql`**

```sql
CREATE TABLE sale_line_modifier (
    id           VARCHAR(36) PRIMARY KEY,
    sale_line_id VARCHAR(36) NOT NULL,
    option_id    VARCHAR(36) NOT NULL,
    name         VARCHAR(100) NOT NULL,
    price_delta  NUMERIC(19, 4) NOT NULL,
    CONSTRAINT fk_sale_line_modifier_line FOREIGN KEY (sale_line_id) REFERENCES sale_line (id)
);
CREATE INDEX idx_sale_line_modifier_line ON sale_line_modifier (sale_line_id);
```

- [ ] **Step 9: Run the sale test + full regression**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=SaleModifierTest` → PASS.
Then: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw verify` → BUILD SUCCESS (all tests + ModularityTests + store-server Testcontainers validating V26–V29). If Docker is unavailable, run `./mvnw test` and note the skipped container tests.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/company/pos/sales/ src/main/java/com/company/pos/receipt/ \
        src/main/resources/db/migration/sales/V29__create_sale_line_modifier.sql \
        src/test/java/com/company/pos/sales/SaleModifierTest.java
git commit -m "feat(sales,receipt): itemize line modifiers on the sale + receipt + V29

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage (11a portion):**
- New `menu` module (modifier groups/options + selection rules + variant groups, local admin CRUD) → Tasks 1–3 ✅
- `resolveSelections` validation (forced min, max, unassigned option, unknown sku) → Task 2 ✅
- Cart line carries modifiers, folds delta into effective unit price, merges on sku+modifier set → Tasks 4–5 ✅
- Cart line-id identity (drop `uq_cart_line_sku`; update/remove by lineId) → Task 4 ✅
- Sale line + receipt itemize modifiers; totals/tax already reflect folded price → Task 6 ✅
- Migrations V26–V29 + Flyway location registration → Tasks 2,4,5,6 ✅
- **Deferred to 11b (not this plan):** dining `OrderLine` modifiers + `closeOrder` one-line-per-order-line rewrite. Called out; 11b consumes `MenuService.resolveSelections` and `CartService.addLine(…, modifierOptionIds)` produced here.
- **Deliberate trim:** `variantGroupsForSku` (per-sku variant lookup) from the spec is not built — `listVariantGroups()` covers MVP; the ordering UI maps sku→group client-side. Flagged, YAGNI.

**Placeholder scan:** No TBD/TODO; every code/test step shows complete code. Two integration steps (Task 6 receipt renderer, Task 4/5 controller endpoints) reference "find with grep" because the exact renderer/endpoint shape is discovered at implementation — each names the grep and the concrete change required, not a vague "handle it."

**Type consistency:** `MenuService` signatures (Task 1) match all call sites (Tasks 2,3,5). `CartLineView` evolves across Tasks 4→5 (lineId added, then basePrice+modifiers) — Task 5's final shape is the one Task 6 consumes; both list the full arg order. `ResolvedModifier(optionId,name,priceDelta)` is used identically in menu (Task 2) and cart (Task 5). `addLine(cartId,sku,qty,List<UUID>)` produced in Task 5 is consumed by 11b.

**Note on cross-task fragility:** Task 4 changes `CartLineView`'s arity and the `updateLine`/`removeLine` signatures — every cart/sales/customer test that builds or reads a `CartLineView` or calls those methods must be migrated in Task 4 Step 7 / Step 9. The grep in Task 4 Step 1 is what surfaces them; do not skip it.
