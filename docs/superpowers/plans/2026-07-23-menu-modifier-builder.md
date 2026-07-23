# Menu Modifier Builder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add edit/reactivate/list backend endpoints (with in-place mutators + event-based audit) for menu modifier groups and options, and a terminal MANAGER+ADMIN master/detail screen to manage groups, their options, and their SKU assignments.

**Architecture:** `menu` gains `ModifierGroup.rename/setSelections` + `ModifierOption.rename/reprice` mutators, `updateModifierGroup`/`reactivateModifierGroup`/`updateOption`/`deactivateOption`/`reactivateOption`/`listModifierGroups` on `MenuService`, and publishes a `MenuChanged` event on every modifier write; `audit` listens (async, post-commit). The terminal adds a `MenuAdminApi`, a synchronous `ModifierBuilderViewModel`, pure formatters, three I/O-free dialogs, and a single master/detail **Menu** screen (groups table → per-group options table + assigned-SKU list).

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Modulith; JavaFX terminal (separate Maven build).

## Global Constraints

- **No migration** — every edit UPDATEs an existing column; the `active` columns already exist (V26). **No new module dependency except `audit → menu :: api`** (one-way; `menu` must NOT depend on `audit`). Run `ModularityTests` after the audit change.
- **Scope is modifiers only** — modifier groups, options, group↔SKU assignments. Variant groups/members are a separate follow-up (#3g); do not touch them.
- **In-place edits** via new domain mutators (stable IDs). Past orders are unaffected (dining snapshots modifier name/priceDelta into `OrderLineModifier`).
- **Event-based audit**: `menu` publishes `MenuChanged`; `audit` listens. `DefaultMenuService` is class-level `@Transactional`, so publishing → outbox → async listener is deadlock-safe. Do NOT add a synchronous `audit.record` call inside `menu`.
- **Access:** all menu write/admin endpoints `@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")`; the terminal **Menu** tile is MANAGER+ADMIN (`services.session.isManager()`). The existing per-SKU read (`GET /menu/products/{sku}/modifier-groups`) stays open (used in ordering) and must keep returning **active options only**.
- **Terminal FX-threading convention.** ViewModel methods are synchronous, return plain values; the controller runs them off the FX thread via `FxTasks.run(work, onDone, onError)`, reading results in the FX-thread `onDone` via a `holder[]`. The only observable a VM writes off-thread is `errorMessage`, and only inside `ui.accept(...)`. Never call a blocking VM/HTTP method inside `onDone` — each mutation's `onDone` re-kicks `reload()`. Every VM needs an async-dispatcher regression test (deferred, undrained `ui`). Dialogs are I/O-free.
- **Builds:** backend `./mvnw` (JDK 21 — `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`); terminal `./mvnw -f pos-terminal/pom.xml` (headless).

---

### Task 1: Backend — menu domain mutators, api types, service edits + events

**Files:**
- Modify: `src/main/java/com/company/pos/menu/domain/ModifierGroup.java`
- Modify: `src/main/java/com/company/pos/menu/domain/ModifierOption.java`
- Create: `src/main/java/com/company/pos/menu/api/UpdateModifierGroupCommand.java`
- Create: `src/main/java/com/company/pos/menu/api/UpdateOptionCommand.java`
- Create: `src/main/java/com/company/pos/menu/api/ModifierOptionAdminView.java`
- Create: `src/main/java/com/company/pos/menu/api/ModifierGroupAdminView.java`
- Create: `src/main/java/com/company/pos/menu/api/MenuChangeType.java`
- Create: `src/main/java/com/company/pos/menu/api/MenuChanged.java`
- Modify: `src/main/java/com/company/pos/menu/api/MenuService.java`
- Modify: `src/main/java/com/company/pos/menu/application/DefaultMenuService.java`
- Modify: `src/main/java/com/company/pos/menu/infrastructure/ModifierGroupAssignmentRepository.java`
- Test: `src/test/java/com/company/pos/menu/ModifierAdminServiceTest.java`

**Interfaces:**
- Produces (Task 2/3/terminal): `MenuChanged(String entityRef, MenuChangeType type, String actor, String detail, BigDecimal oldPrice, BigDecimal newPrice)`; `MenuChangeType` (10 values); `ModifierGroupAdminView(UUID id, String name, int minSelections, int maxSelections, boolean active, List<ModifierOptionAdminView> options, List<String> assignedSkus)`; `ModifierOptionAdminView(UUID id, String name, BigDecimal priceDelta, boolean active)`; `UpdateModifierGroupCommand(String name, int minSelections, int maxSelections)`; `UpdateOptionCommand(String name, BigDecimal priceDelta)`; the six new `MenuService` methods.

- [ ] **Step 1: Domain mutators**

In `ModifierGroup.java`, add below `setActive`:

```java
    public void rename(String name) {
        this.name = name;
    }

    public void setSelections(int minSelections, int maxSelections) {
        this.minSelections = minSelections;
        this.maxSelections = maxSelections;
    }
```

In `ModifierOption.java`, add below `setActive`:

```java
    public void rename(String name) {
        this.name = name;
    }

    public void reprice(BigDecimal priceDelta) {
        this.priceDelta = priceDelta;
    }
```

- [ ] **Step 2: New api types**

Create `menu/api/UpdateModifierGroupCommand.java`:

```java
package com.company.pos.menu.api;

public record UpdateModifierGroupCommand(String name, int minSelections, int maxSelections) {
}
```

Create `menu/api/UpdateOptionCommand.java`:

```java
package com.company.pos.menu.api;

import java.math.BigDecimal;

public record UpdateOptionCommand(String name, BigDecimal priceDelta) {
}
```

Create `menu/api/ModifierOptionAdminView.java`:

```java
package com.company.pos.menu.api;

import java.math.BigDecimal;
import java.util.UUID;

public record ModifierOptionAdminView(UUID id, String name, BigDecimal priceDelta, boolean active) {
}
```

Create `menu/api/ModifierGroupAdminView.java`:

```java
package com.company.pos.menu.api;

import java.util.List;
import java.util.UUID;

public record ModifierGroupAdminView(UUID id, String name, int minSelections, int maxSelections,
        boolean active, List<ModifierOptionAdminView> options, List<String> assignedSkus) {
}
```

Create `menu/api/MenuChangeType.java`:

```java
package com.company.pos.menu.api;

public enum MenuChangeType {
    GROUP_CREATED, GROUP_UPDATED, GROUP_DEACTIVATED, GROUP_REACTIVATED,
    OPTION_ADDED, OPTION_UPDATED, OPTION_DEACTIVATED, OPTION_REACTIVATED,
    GROUP_ASSIGNED, GROUP_UNASSIGNED
}
```

Create `menu/api/MenuChanged.java`:

```java
package com.company.pos.menu.api;

import com.company.pos.common.events.DomainEvent;
import java.math.BigDecimal;

/**
 * Published by {@code DefaultMenuService} on every modifier-admin write. Consumed by the
 * {@code audit} module (async, after commit). {@code entityRef} is the group id (or the option id
 * for OPTION_*); {@code detail} carries the sku for GROUP_ASSIGNED/UNASSIGNED (else null);
 * {@code oldPrice}/{@code newPrice} are non-null only when an option's price delta changed.
 * {@code actor} is captured on the request thread so the async listener records the real user.
 */
public record MenuChanged(String entityRef, MenuChangeType type, String actor, String detail,
        BigDecimal oldPrice, BigDecimal newPrice) implements DomainEvent {
}
```

- [ ] **Step 3: Repository finder**

In `menu/infrastructure/ModifierGroupAssignmentRepository.java`, add (below the existing finders):

```java
    List<ModifierGroupAssignment> findByGroupId(UUID groupId);
```

(`List`/`UUID` are already imported.)

- [ ] **Step 4: Extend the MenuService interface**

In `menu/api/MenuService.java`, in the `// --- modifier admin ---` section (after `deactivateModifierGroup`), add:

```java
    ModifierGroupView updateModifierGroup(UUID groupId, UpdateModifierGroupCommand command);

    void reactivateModifierGroup(UUID groupId);

    ModifierOptionView updateOption(UUID groupId, UUID optionId, UpdateOptionCommand command);

    void deactivateOption(UUID groupId, UUID optionId);

    void reactivateOption(UUID groupId, UUID optionId);

    List<ModifierGroupAdminView> listModifierGroups();
```

- [ ] **Step 5: Implement in DefaultMenuService**

Add imports (with the other `com.company.pos.menu.api.*` imports + security/events):

```java
import com.company.pos.common.events.DomainEvents;
import com.company.pos.menu.api.MenuChangeType;
import com.company.pos.menu.api.MenuChanged;
import com.company.pos.menu.api.ModifierGroupAdminView;
import com.company.pos.menu.api.ModifierOptionAdminView;
import com.company.pos.menu.api.UpdateModifierGroupCommand;
import com.company.pos.menu.api.UpdateOptionCommand;
import com.company.pos.menu.domain.ModifierGroupAssignment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
```

(`ModifierGroupAssignment` may already be imported — keep one.)

Add `DomainEvents` to the constructor and store it. Change the field block + constructor to:

```java
    private final ModifierGroupRepository groups;
    private final ModifierOptionRepository options;
    private final ModifierGroupAssignmentRepository assignments;
    private final ProductCatalog products;
    private final VariantGroupRepository variantGroups;
    private final VariantMemberRepository variantMembers;
    private final DomainEvents events;

    DefaultMenuService(ModifierGroupRepository groups, ModifierOptionRepository options,
            ModifierGroupAssignmentRepository assignments, ProductCatalog products,
            VariantGroupRepository variantGroups, VariantMemberRepository variantMembers,
            DomainEvents events) {
        this.groups = groups;
        this.options = options;
        this.assignments = assignments;
        this.products = products;
        this.variantGroups = variantGroups;
        this.variantMembers = variantMembers;
        this.events = events;
    }
```

Replace `createModifierGroup`, `addOption`, `assignGroupToSku`, `unassignGroupFromSku`, and `deactivateModifierGroup` with these event-publishing versions (behaviour unchanged except the added `events.publish(...)` and the extracted `validateSelections`):

```java
    @Override
    public ModifierGroupView createModifierGroup(CreateModifierGroupCommand command) {
        if (command.name() == null || command.name().isBlank()) {
            throw DomainException.validation("Modifier group name is required");
        }
        validateSelections(command.minSelections(), command.maxSelections());
        ModifierGroup g = new ModifierGroup(Identifiers.newId(), command.name().trim(),
                command.minSelections(), command.maxSelections());
        ModifierGroup saved = groups.save(g);
        events.publish(new MenuChanged(saved.getId().toString(), MenuChangeType.GROUP_CREATED,
                actor(), null, null, null));
        return toGroupView(saved);
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
        events.publish(new MenuChanged(o.getId().toString(), MenuChangeType.OPTION_ADDED,
                actor(), null, null, null));
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
        events.publish(new MenuChanged(groupId.toString(), MenuChangeType.GROUP_ASSIGNED,
                actor(), sku, null, null));
    }

    @Override
    public void unassignGroupFromSku(UUID groupId, String sku) {
        assignments.deleteByGroupIdAndSku(groupId, sku);
        events.publish(new MenuChanged(groupId.toString(), MenuChangeType.GROUP_UNASSIGNED,
                actor(), sku, null, null));
    }

    @Override
    public void deactivateModifierGroup(UUID groupId) {
        loadGroup(groupId).setActive(false);
        events.publish(new MenuChanged(groupId.toString(), MenuChangeType.GROUP_DEACTIVATED,
                actor(), null, null, null));
    }
```

Add the six new methods (place them after `deactivateModifierGroup`):

```java
    @Override
    public ModifierGroupView updateModifierGroup(UUID groupId, UpdateModifierGroupCommand command) {
        ModifierGroup g = loadGroup(groupId);
        if (command.name() == null || command.name().isBlank()) {
            throw DomainException.validation("Modifier group name is required");
        }
        validateSelections(command.minSelections(), command.maxSelections());
        g.rename(command.name().trim());
        g.setSelections(command.minSelections(), command.maxSelections());
        events.publish(new MenuChanged(g.getId().toString(), MenuChangeType.GROUP_UPDATED,
                actor(), null, null, null));
        return toGroupView(g);
    }

    @Override
    public void reactivateModifierGroup(UUID groupId) {
        loadGroup(groupId).setActive(true);
        events.publish(new MenuChanged(groupId.toString(), MenuChangeType.GROUP_REACTIVATED,
                actor(), null, null, null));
    }

    @Override
    public ModifierOptionView updateOption(UUID groupId, UUID optionId, UpdateOptionCommand command) {
        loadGroup(groupId);
        ModifierOption o = loadOption(groupId, optionId);
        if (command.name() == null || command.name().isBlank()) {
            throw DomainException.validation("Option name is required");
        }
        if (command.priceDelta() == null) {
            throw DomainException.validation("Option priceDelta is required (may be 0)");
        }
        BigDecimal oldPrice = o.getPriceDelta();
        BigDecimal newPrice = command.priceDelta();
        boolean priceChanged = oldPrice.compareTo(newPrice) != 0;
        o.rename(command.name().trim());
        o.reprice(newPrice);
        events.publish(new MenuChanged(o.getId().toString(), MenuChangeType.OPTION_UPDATED,
                actor(), null, priceChanged ? oldPrice : null, priceChanged ? newPrice : null));
        return new ModifierOptionView(o.getId(), o.getName(), o.getPriceDelta());
    }

    @Override
    public void deactivateOption(UUID groupId, UUID optionId) {
        setOptionActive(groupId, optionId, false, MenuChangeType.OPTION_DEACTIVATED);
    }

    @Override
    public void reactivateOption(UUID groupId, UUID optionId) {
        setOptionActive(groupId, optionId, true, MenuChangeType.OPTION_REACTIVATED);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModifierGroupAdminView> listModifierGroups() {
        return groups.findAll().stream().map(this::toAdminView).collect(Collectors.toList());
    }
```

Add these private helpers (near `loadGroup`):

```java
    private void setOptionActive(UUID groupId, UUID optionId, boolean active, MenuChangeType type) {
        loadGroup(groupId);
        ModifierOption o = loadOption(groupId, optionId);
        o.setActive(active);
        events.publish(new MenuChanged(o.getId().toString(), type, actor(), null, null, null));
    }

    private ModifierOption loadOption(UUID groupId, UUID optionId) {
        ModifierOption o = options.findById(optionId)
                .orElseThrow(() -> DomainException.notFound("No option " + optionId));
        if (!o.getGroupId().equals(groupId)) {
            throw DomainException.validation("Option " + optionId + " does not belong to group " + groupId);
        }
        return o;
    }

    private void validateSelections(int min, int max) {
        if (min < 0 || max < min || max < 1) {
            throw DomainException.validation("Invalid min/max selections");
        }
    }

    private ModifierGroupAdminView toAdminView(ModifierGroup g) {
        List<ModifierOptionAdminView> opts = options.findByGroupId(g.getId()).stream()
                .map(o -> new ModifierOptionAdminView(o.getId(), o.getName(), o.getPriceDelta(), o.isActive()))
                .collect(Collectors.toList());
        List<String> skus = assignments.findByGroupId(g.getId()).stream()
                .map(ModifierGroupAssignment::getSku)
                .collect(Collectors.toList());
        return new ModifierGroupAdminView(g.getId(), g.getName(), g.getMinSelections(),
                g.getMaxSelections(), g.isActive(), opts, skus);
    }

    private static String actor() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null ? a.getName() : "system";
    }
```

> Note: entities are loaded inside the class-level `@Transactional`, so `rename`/`setSelections`/`reprice`/`setActive` flush via dirty-checking — no explicit `save` needed (the create/add paths still `save` new rows).

- [ ] **Step 6: Write the service test**

Create `src/test/java/com/company/pos/menu/ModifierAdminServiceTest.java`:

```java
package com.company.pos.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.menu.api.AddOptionCommand;
import com.company.pos.menu.api.CreateModifierGroupCommand;
import com.company.pos.menu.api.MenuChangeType;
import com.company.pos.menu.api.MenuChanged;
import com.company.pos.menu.api.MenuService;
import com.company.pos.menu.api.ModifierGroupAdminView;
import com.company.pos.menu.api.ModifierGroupView;
import com.company.pos.menu.api.ModifierOptionView;
import com.company.pos.menu.api.UpdateModifierGroupCommand;
import com.company.pos.menu.api.UpdateOptionCommand;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@SpringBootTest
@ActiveProfiles("embedded")
@RecordApplicationEvents
@Import(DatabaseCleaner.class)
class ModifierAdminServiceTest {

    @Autowired MenuService menu;
    @Autowired ApplicationEvents events;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    @Test
    void createPublishesGroupCreated() {
        ModifierGroupView g = menu.createModifierGroup(new CreateModifierGroupCommand("Add-ons", 0, 2));
        assertThat(typesFor(g.id().toString())).contains(MenuChangeType.GROUP_CREATED);
    }

    @Test
    void updateGroupRenamesAndSetsSelections() {
        ModifierGroupView g = menu.createModifierGroup(new CreateModifierGroupCommand("Add-ons", 0, 2));
        ModifierGroupView u = menu.updateModifierGroup(g.id(), new UpdateModifierGroupCommand("Extras", 1, 3));
        assertThat(u.name()).isEqualTo("Extras");
        assertThat(u.minSelections()).isEqualTo(1);
        assertThat(u.maxSelections()).isEqualTo(3);
        assertThat(typesFor(g.id().toString())).contains(MenuChangeType.GROUP_UPDATED);
    }

    @Test
    void updateGroupRejectsMinGreaterThanMax() {
        ModifierGroupView g = menu.createModifierGroup(new CreateModifierGroupCommand("Add-ons", 0, 2));
        assertThatThrownBy(() -> menu.updateModifierGroup(g.id(), new UpdateModifierGroupCommand("X", 3, 1)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void updateOptionRepricesAndRecordsOldNew() {
        ModifierGroupView g = menu.createModifierGroup(new CreateModifierGroupCommand("Add-ons", 0, 2));
        ModifierOptionView o = menu.addOption(g.id(), new AddOptionCommand("Cheese", new BigDecimal("2.00")));
        ModifierOptionView u = menu.updateOption(g.id(), o.id(), new UpdateOptionCommand("Extra cheese", new BigDecimal("2.50")));
        assertThat(u.name()).isEqualTo("Extra cheese");
        assertThat(u.priceDelta()).isEqualByComparingTo("2.50");
        MenuChanged updated = events.stream(MenuChanged.class)
                .filter(e -> e.type() == MenuChangeType.OPTION_UPDATED)
                .findFirst().orElseThrow();
        assertThat(updated.oldPrice()).isEqualByComparingTo("2.00");
        assertThat(updated.newPrice()).isEqualByComparingTo("2.50");
    }

    @Test
    void updateOptionRejectsWrongGroup() {
        ModifierGroupView g1 = menu.createModifierGroup(new CreateModifierGroupCommand("A", 0, 2));
        ModifierGroupView g2 = menu.createModifierGroup(new CreateModifierGroupCommand("B", 0, 2));
        ModifierOptionView o = menu.addOption(g1.id(), new AddOptionCommand("X", new BigDecimal("1.00")));
        assertThatThrownBy(() -> menu.updateOption(g2.id(), o.id(), new UpdateOptionCommand("X", new BigDecimal("1.00"))))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void deactivateThenReactivateOptionFlipsAndPublishes() {
        ModifierGroupView g = menu.createModifierGroup(new CreateModifierGroupCommand("Add-ons", 0, 2));
        ModifierOptionView o = menu.addOption(g.id(), new AddOptionCommand("Cheese", new BigDecimal("2.00")));
        menu.deactivateOption(g.id(), o.id());
        assertThat(activeOf(g.id(), o.id())).isFalse();
        menu.reactivateOption(g.id(), o.id());
        assertThat(activeOf(g.id(), o.id())).isTrue();
        assertThat(typesFor(o.id().toString()))
                .contains(MenuChangeType.OPTION_DEACTIVATED, MenuChangeType.OPTION_REACTIVATED);
    }

    @Test
    void listReturnsInactiveGroupsAndOptions() {
        ModifierGroupView g = menu.createModifierGroup(new CreateModifierGroupCommand("Add-ons", 0, 2));
        ModifierOptionView o = menu.addOption(g.id(), new AddOptionCommand("Cheese", new BigDecimal("2.00")));
        menu.deactivateOption(g.id(), o.id());
        menu.deactivateModifierGroup(g.id());
        ModifierGroupAdminView view = menu.listModifierGroups().stream()
                .filter(v -> v.id().equals(g.id())).findFirst().orElseThrow();
        assertThat(view.active()).isFalse();
        assertThat(view.options()).hasSize(1);
        assertThat(view.options().get(0).active()).isFalse();
        assertThat(view.assignedSkus()).isEmpty();
    }

    private boolean activeOf(UUID groupId, UUID optionId) {
        return menu.listModifierGroups().stream()
                .filter(v -> v.id().equals(groupId)).findFirst().orElseThrow()
                .options().stream().filter(o -> o.id().equals(optionId)).findFirst().orElseThrow()
                .active();
    }

    private List<MenuChangeType> typesFor(String entityRef) {
        return events.stream(MenuChanged.class)
                .filter(e -> e.entityRef().equals(entityRef))
                .map(MenuChanged::type)
                .toList();
    }
}
```

- [ ] **Step 7: Run the tests**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=ModifierAdminServiceTest,ModifierServiceTest`
Expected: PASS (new class + the existing `ModifierServiceTest`, which must still pass unchanged).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/company/pos/menu src/test/java/com/company/pos/menu/ModifierAdminServiceTest.java
git commit -m "feat(menu): modifier group/option edit+reactivate+list + MenuChanged events"
```

---

### Task 2: Backend — MenuController edit/list endpoints

**Files:**
- Modify: `src/main/java/com/company/pos/menu/web/MenuController.java`
- Test: `src/test/java/com/company/pos/menu/MenuControllerTest.java` (add cases)

**Interfaces:**
- Consumes: the Task-1 `MenuService` methods + `UpdateModifierGroupCommand`/`UpdateOptionCommand`/`ModifierGroupAdminView`.
- Produces (terminal): `GET /menu/modifier-groups` (MANAGER+ADMIN) → `List<ModifierGroupAdminView>`; `PUT /menu/modifier-groups/{groupId}`; `POST /menu/modifier-groups/{groupId}/reactivate`; `PUT /menu/modifier-groups/{groupId}/options/{optionId}`; `DELETE /menu/modifier-groups/{groupId}/options/{optionId}`; `POST /menu/modifier-groups/{groupId}/options/{optionId}/reactivate`.

- [ ] **Step 1: Add imports + handlers**

In `MenuController.java`, add imports:

```java
import com.company.pos.menu.api.ModifierGroupAdminView;
import com.company.pos.menu.api.UpdateModifierGroupCommand;
import com.company.pos.menu.api.UpdateOptionCommand;
import org.springframework.web.bind.annotation.PutMapping;
```

Add these handlers inside the `// --- modifier admin ---` section (after `deactivateGroup`):

```java
    @GetMapping("/menu/modifier-groups")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    List<ModifierGroupAdminView> listGroups() {
        return menu.listModifierGroups();
    }

    @PutMapping("/menu/modifier-groups/{groupId}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    ModifierGroupView updateGroup(@PathVariable UUID groupId, @RequestBody UpdateModifierGroupCommand body) {
        return menu.updateModifierGroup(groupId, body);
    }

    @PostMapping("/menu/modifier-groups/{groupId}/reactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    void reactivateGroup(@PathVariable UUID groupId) {
        menu.reactivateModifierGroup(groupId);
    }

    @PutMapping("/menu/modifier-groups/{groupId}/options/{optionId}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    ModifierOptionView updateOption(@PathVariable UUID groupId, @PathVariable UUID optionId,
            @RequestBody UpdateOptionCommand body) {
        return menu.updateOption(groupId, optionId, body);
    }

    @DeleteMapping("/menu/modifier-groups/{groupId}/options/{optionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    void deactivateOption(@PathVariable UUID groupId, @PathVariable UUID optionId) {
        menu.deactivateOption(groupId, optionId);
    }

    @PostMapping("/menu/modifier-groups/{groupId}/options/{optionId}/reactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    void reactivateOption(@PathVariable UUID groupId, @PathVariable UUID optionId) {
        menu.reactivateOption(groupId, optionId);
    }
```

- [ ] **Step 2: Add controller test cases**

In `MenuControllerTest.java`, add (the `manager()`/`cashier()` helpers already exist):

```java
    @Test
    void managerCanListModifierGroups() throws Exception {
        mvc.perform(get("/menu/modifier-groups").with(manager()))
                .andExpect(status().isOk());
    }

    @Test
    void cashierCannotListModifierGroups() throws Exception {
        mvc.perform(get("/menu/modifier-groups").with(cashier()))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerEditsModifierGroup() throws Exception {
        String created = mvc.perform(post("/menu/modifier-groups").with(manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Add-ons\",\"minSelections\":0,\"maxSelections\":2}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(created, "$.id");

        mvc.perform(put("/menu/modifier-groups/" + id).with(manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Extras\",\"minSelections\":1,\"maxSelections\":3}"))
                .andExpect(status().isOk());
    }

    @Test
    void cashierCannotEditModifierGroup() throws Exception {
        mvc.perform(put("/menu/modifier-groups/" + java.util.UUID.randomUUID()).with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Extras\",\"minSelections\":1,\"maxSelections\":3}"))
                .andExpect(status().isForbidden());
    }
```

Add the imports at the top of the test: `import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;` (get/post already imported).

- [ ] **Step 3: Run the tests**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=MenuControllerTest`
Expected: PASS (existing + 4 new cases).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/company/pos/menu/web/MenuController.java src/test/java/com/company/pos/menu/MenuControllerTest.java
git commit -m "feat(menu): edit/list modifier endpoints (MANAGER+ADMIN)"
```

---

### Task 3: Backend — audit listener for menu changes

**Files:**
- Modify: `src/main/java/com/company/pos/audit/api/AuditAction.java`
- Create: `src/main/java/com/company/pos/audit/application/MenuChangedAuditListener.java`
- Modify: `src/main/java/com/company/pos/audit/package-info.java`
- Test: `src/test/java/com/company/pos/audit/MenuChangedAuditTest.java`

**Interfaces:**
- Consumes: `MenuChanged`/`MenuChangeType` (Task 1); `DefaultAuditService.append(AuditAction, String actor, String entityRef, Map<String,String> details)`.

- [ ] **Step 1: Add audit actions**

In `audit/api/AuditAction.java`, add at the end of the enum (comma after the current last constant `TABLE_REACTIVATED`):

```java
    TABLE_REACTIVATED,
    MENU_GROUP_CREATED,
    MENU_GROUP_UPDATED,
    MENU_GROUP_DEACTIVATED,
    MENU_GROUP_REACTIVATED,
    MENU_OPTION_ADDED,
    MENU_OPTION_UPDATED,
    MENU_OPTION_DEACTIVATED,
    MENU_OPTION_REACTIVATED,
    MENU_GROUP_ASSIGNED,
    MENU_GROUP_UNASSIGNED
```

- [ ] **Step 2: Add the listener**

Create `audit/application/MenuChangedAuditListener.java`:

```java
package com.company.pos.audit.application;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.menu.api.MenuChangeType;
import com.company.pos.menu.api.MenuChanged;
import java.util.HashMap;
import java.util.Map;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Records menu modifier-admin actions into the audit trail. Runs async AFTER the publishing
 * transaction commits (the outbox redelivers on failure), mirroring {@code ProductChangedAuditListener}.
 * The actor rides on the event, so the real user is recorded.
 */
@Component
class MenuChangedAuditListener {

    private final DefaultAuditService audit;

    MenuChangedAuditListener(DefaultAuditService audit) {
        this.audit = audit;
    }

    @ApplicationModuleListener
    void on(MenuChanged event) {
        Map<String, String> details = new HashMap<>();
        if (event.detail() != null) {
            details.put("sku", event.detail());
        }
        if (event.oldPrice() != null) {
            details.put("oldPrice", event.oldPrice().toPlainString());
        }
        if (event.newPrice() != null) {
            details.put("newPrice", event.newPrice().toPlainString());
        }
        audit.append(actionFor(event.type()), event.actor(), event.entityRef(), details);
    }

    private static AuditAction actionFor(MenuChangeType type) {
        return switch (type) {
            case GROUP_CREATED -> AuditAction.MENU_GROUP_CREATED;
            case GROUP_UPDATED -> AuditAction.MENU_GROUP_UPDATED;
            case GROUP_DEACTIVATED -> AuditAction.MENU_GROUP_DEACTIVATED;
            case GROUP_REACTIVATED -> AuditAction.MENU_GROUP_REACTIVATED;
            case OPTION_ADDED -> AuditAction.MENU_OPTION_ADDED;
            case OPTION_UPDATED -> AuditAction.MENU_OPTION_UPDATED;
            case OPTION_DEACTIVATED -> AuditAction.MENU_OPTION_DEACTIVATED;
            case OPTION_REACTIVATED -> AuditAction.MENU_OPTION_REACTIVATED;
            case GROUP_ASSIGNED -> AuditAction.MENU_GROUP_ASSIGNED;
            case GROUP_UNASSIGNED -> AuditAction.MENU_GROUP_UNASSIGNED;
        };
    }
}
```

- [ ] **Step 3: Allow the dependency**

Replace `audit/package-info.java` with (adds `"menu :: api"`):

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "sales :: api", "product :: api",
                "dining :: api", "menu :: api", "configuration :: api" })
package com.company.pos.audit;
```

- [ ] **Step 4: Write the E2E audit test**

Drive the real endpoints (each commits) and Awaitility-await the async listener, mirroring the sub-project #3d `TableChangedAuditTest` / `AuditEventListenersTest`. Seed a product (for the assign path) and a MANAGER user.

Create `src/test/java/com/company/pos/audit/MenuChangedAuditTest.java`:

```java
package com.company.pos.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.audit.api.AuditRecordView;
import com.company.pos.audit.application.DefaultAuditService;
import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.util.Identifiers;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.support.DatabaseCleaner;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class MenuChangedAuditTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired DefaultAuditService audit;
    @Autowired DatabaseCleaner cleaner;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("STEAK", "Ribeye", "FOOD", "Food", "bcSTEAK",
                "EA", new BigDecimal("80.00"), "SAR", 1, true));
        productSync.sync();
        users.save(new User(Identifiers.newId(), "boss", "Boss User",
                encoder.encode("pw"), Set.of(Role.MANAGER)));
    }

    @AfterEach
    void tearDown() {
        cleaner.clean();
        fake.clear();
    }

    @Test
    void modifierLifecycleIsAudited() throws Exception {
        String token = login("boss");

        String created = mvc.perform(post("/menu/modifier-groups")
                        .header("Authorization", token).contentType("application/json")
                        .content("{\"name\":\"Add-ons\",\"minSelections\":0,\"maxSelections\":2}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String groupId = JsonPath.read(created, "$.id");

        String opt = mvc.perform(post("/menu/modifier-groups/" + groupId + "/options")
                        .header("Authorization", token).contentType("application/json")
                        .content("{\"name\":\"Cheese\",\"priceDelta\":2.00}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String optionId = JsonPath.read(opt, "$.id");

        mvc.perform(put("/menu/modifier-groups/" + groupId + "/options/" + optionId)
                        .header("Authorization", token).contentType("application/json")
                        .content("{\"name\":\"Extra cheese\",\"priceDelta\":2.50}"))
                .andExpect(status().isOk());

        mvc.perform(post("/menu/modifier-groups/" + groupId + "/assignments?sku=STEAK")
                        .header("Authorization", token))
                .andExpect(status().isNoContent());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<AuditRecordView> recent = audit.recent(50);
            assertThat(recent).anySatisfy(r -> {
                assertThat(r.action()).isEqualTo("MENU_GROUP_CREATED");
                assertThat(r.entityRef()).isEqualTo(groupId);
                assertThat(r.actor()).isEqualTo("boss");
            });
            assertThat(recent).anyMatch(r -> r.action().equals("MENU_OPTION_ADDED"));
            assertThat(recent).anyMatch(r -> r.action().equals("MENU_OPTION_UPDATED"));
            assertThat(recent).anyMatch(r -> r.action().equals("MENU_GROUP_ASSIGNED"));
        });

        assertThat(audit.verify().intact()).isTrue();
    }

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }
}
```

- [ ] **Step 5: Run the audit + boundary tests**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=MenuChangedAuditTest,ModularityTests`
Expected: PASS (`MenuChangedAuditTest`; `ModularityTests` confirms the new one-way `audit → menu :: api` edge introduces no cycle).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/audit src/test/java/com/company/pos/audit/MenuChangedAuditTest.java
git commit -m "feat(audit): record menu modifier changes via MenuChanged listener"
```

---

### Task 4: Terminal — MenuAdminApi, DTOs, view-model, formatters

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ModifierOptionAdminView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ModifierGroupAdminView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/ModifierGroupRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/ModifierOptionRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/MenuAdminApi.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/ModifierRows.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/ModifierBuilderViewModel.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/ModifierRowsTest.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/ModifierBuilderViewModelTest.java`

**Interfaces:**
- Consumes: `ApiClient` (`get`/`post`/`put`/`delete`; void endpoints pass `null` TypeReference), existing `dto.ProductView` (`sku`, `name`), `ApiException`/`ProblemDetail`.
- Produces (Task 6): `MenuAdminApi` (non-final); `ModifierBuilderViewModel(MenuAdminApi, ProductApi, Consumer<Runnable>)` with `loadGroups()`, `loadProducts()`, and boolean-returning mutations; `ModifierRows.selectionsLabel/priceDeltaLabel/skuLabel`.

- [ ] **Step 1: DTO mirrors + request records**

Create `terminal/api/dto/ModifierOptionAdminView.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ModifierOptionAdminView(UUID id, String name, BigDecimal priceDelta, boolean active) {
}
```

Create `terminal/api/dto/ModifierGroupAdminView.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ModifierGroupAdminView(UUID id, String name, int minSelections, int maxSelections,
        boolean active, List<ModifierOptionAdminView> options, List<String> assignedSkus) {
}
```

Create `terminal/api/ModifierGroupRequest.java`:

```java
package com.company.pos.terminal.api;

public record ModifierGroupRequest(String name, int minSelections, int maxSelections) {
}
```

Create `terminal/api/ModifierOptionRequest.java`:

```java
package com.company.pos.terminal.api;

import java.math.BigDecimal;

public record ModifierOptionRequest(String name, BigDecimal priceDelta) {
}
```

- [ ] **Step 2: API client**

Create `terminal/api/MenuAdminApi.java`:

```java
package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.ModifierGroupAdminView;
import com.fasterxml.jackson.core.type.TypeReference;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Typed client for the store server's menu modifier-admin endpoints (MANAGER/ADMIN-gated).
 *  Non-final so view-model tests can subclass with fakes. Void endpoints pass a null TypeReference. */
public class MenuAdminApi {

    private final ApiClient client;

    public MenuAdminApi(ApiClient client) {
        this.client = client;
    }

    public List<ModifierGroupAdminView> listGroups() {
        return client.get("/menu/modifier-groups", new TypeReference<List<ModifierGroupAdminView>>() {});
    }

    public void createGroup(String name, int min, int max) {
        client.post("/menu/modifier-groups", new ModifierGroupRequest(name, min, max), null);
    }

    public void updateGroup(UUID groupId, String name, int min, int max) {
        client.put("/menu/modifier-groups/" + groupId, new ModifierGroupRequest(name, min, max), null);
    }

    public void deactivateGroup(UUID groupId) {
        client.delete("/menu/modifier-groups/" + groupId);
    }

    public void reactivateGroup(UUID groupId) {
        client.post("/menu/modifier-groups/" + groupId + "/reactivate", null, null);
    }

    public void addOption(UUID groupId, String name, BigDecimal priceDelta) {
        client.post("/menu/modifier-groups/" + groupId + "/options",
                new ModifierOptionRequest(name, priceDelta), null);
    }

    public void updateOption(UUID groupId, UUID optionId, String name, BigDecimal priceDelta) {
        client.put("/menu/modifier-groups/" + groupId + "/options/" + optionId,
                new ModifierOptionRequest(name, priceDelta), null);
    }

    public void deactivateOption(UUID groupId, UUID optionId) {
        client.delete("/menu/modifier-groups/" + groupId + "/options/" + optionId);
    }

    public void reactivateOption(UUID groupId, UUID optionId) {
        client.post("/menu/modifier-groups/" + groupId + "/options/" + optionId + "/reactivate", null, null);
    }

    public void assignSku(UUID groupId, String sku) {
        client.post("/menu/modifier-groups/" + groupId + "/assignments?sku=" + sku, null, null);
    }

    public void unassignSku(UUID groupId, String sku) {
        client.delete("/menu/modifier-groups/" + groupId + "/assignments?sku=" + sku);
    }
}
```

> SKUs are URL-safe in this system (alphanumeric), interpolated raw into the query — matching the `CartApi`/`DiningApi`/`KitchenApi` convention.

- [ ] **Step 3: Pure formatters + test**

Create `terminal/viewmodel/ModifierRows.java`:

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.dto.ProductView;
import java.math.BigDecimal;
import java.util.List;

/** Pure display formatters for the modifier builder. */
public final class ModifierRows {

    private ModifierRows() {
    }

    /** "min–max" selection range, e.g. "1–3". */
    public static String selectionsLabel(int min, int max) {
        return min + "–" + max;
    }

    /** Signed 2-dp price delta, e.g. "+2.50", "-1.00", "0.00". */
    public static String priceDeltaLabel(BigDecimal delta) {
        if (delta == null) {
            return "";
        }
        BigDecimal d = delta.setScale(2, java.math.RoundingMode.HALF_UP);
        return d.signum() > 0 ? "+" + d.toPlainString() : d.toPlainString();
    }

    /** "sku — name" if the sku is a known product, else the bare sku. */
    public static String skuLabel(String sku, List<ProductView> products) {
        if (products != null) {
            for (ProductView p : products) {
                if (p.sku().equals(sku)) {
                    return sku + " — " + p.name();
                }
            }
        }
        return sku;
    }
}
```

Create `terminal/src/test/java/com/company/pos/terminal/viewmodel/ModifierRowsTest.java`:

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.company.pos.terminal.api.dto.ProductView;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModifierRowsTest {

    @Test
    void selectionsLabelJoinsMinMax() {
        assertEquals("1–3", ModifierRows.selectionsLabel(1, 3));
    }

    @Test
    void priceDeltaLabelSignsAndScales() {
        assertEquals("+2.50", ModifierRows.priceDeltaLabel(new BigDecimal("2.5")));
        assertEquals("-1.00", ModifierRows.priceDeltaLabel(new BigDecimal("-1")));
        assertEquals("0.00", ModifierRows.priceDeltaLabel(BigDecimal.ZERO));
    }

    @Test
    void skuLabelResolvesName() {
        List<ProductView> products = List.of(
                new ProductView("STEAK", "Ribeye", "Food", "bc", new BigDecimal("80.00")));
        assertEquals("STEAK — Ribeye", ModifierRows.skuLabel("STEAK", products));
        assertEquals("NOPE", ModifierRows.skuLabel("NOPE", products));
    }
}
```

> If the terminal `dto.ProductView` constructor differs from `(sku, name, category, barcode, unitPrice)`, adjust the test's `new ProductView(...)` to the actual record components (check `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ProductView.java`) — the fields used here are only `sku()` and `name()`.

- [ ] **Step 4: View-model**

Create `terminal/viewmodel/ModifierBuilderViewModel.java`:

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.MenuAdminApi;
import com.company.pos.terminal.api.ProductApi;
import com.company.pos.terminal.api.dto.ModifierGroupAdminView;
import com.company.pos.terminal.api.dto.ProductView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the modifier builder. Synchronous like the other admin VMs — the controller runs it
 * off the FX thread via FxTasks and reads the return value; the only observable written off-thread is
 * {@code errorMessage}, inside the {@code ui} dispatcher. Mutations return true on success; the
 * controller re-kicks reload() in onDone.
 */
public class ModifierBuilderViewModel {

    private final MenuAdminApi menu;
    private final ProductApi productApi;
    private final Consumer<Runnable> ui;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public ModifierBuilderViewModel(MenuAdminApi menu, ProductApi productApi, Consumer<Runnable> ui) {
        this.menu = menu;
        this.productApi = productApi;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    public List<ModifierGroupAdminView> loadGroups() {
        try {
            List<ModifierGroupAdminView> list = menu.listGroups();
            clearError();
            return list;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public List<ProductView> loadProducts() {
        try {
            List<ProductView> list = productApi.list();
            clearError();
            return list;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public boolean createGroup(String name, int min, int max) {
        String err = validateGroup(name, min, max);
        if (err != null) {
            setError(err);
            return false;
        }
        return run(() -> menu.createGroup(name.trim(), min, max));
    }

    public boolean updateGroup(UUID groupId, String name, int min, int max) {
        String err = validateGroup(name, min, max);
        if (err != null) {
            setError(err);
            return false;
        }
        return run(() -> menu.updateGroup(groupId, name.trim(), min, max));
    }

    public boolean deactivateGroup(UUID groupId) {
        return run(() -> menu.deactivateGroup(groupId));
    }

    public boolean reactivateGroup(UUID groupId) {
        return run(() -> menu.reactivateGroup(groupId));
    }

    public boolean addOption(UUID groupId, String name, BigDecimal priceDelta) {
        String err = validateOption(name, priceDelta);
        if (err != null) {
            setError(err);
            return false;
        }
        return run(() -> menu.addOption(groupId, name.trim(), priceDelta));
    }

    public boolean updateOption(UUID groupId, UUID optionId, String name, BigDecimal priceDelta) {
        String err = validateOption(name, priceDelta);
        if (err != null) {
            setError(err);
            return false;
        }
        return run(() -> menu.updateOption(groupId, optionId, name.trim(), priceDelta));
    }

    public boolean deactivateOption(UUID groupId, UUID optionId) {
        return run(() -> menu.deactivateOption(groupId, optionId));
    }

    public boolean reactivateOption(UUID groupId, UUID optionId) {
        return run(() -> menu.reactivateOption(groupId, optionId));
    }

    public boolean assign(UUID groupId, String sku) {
        if (sku == null || sku.isBlank()) {
            setError("Choose a product to assign");
            return false;
        }
        return run(() -> menu.assignSku(groupId, sku));
    }

    public boolean unassign(UUID groupId, String sku) {
        return run(() -> menu.unassignSku(groupId, sku));
    }

    private boolean run(Runnable call) {
        try {
            call.run();
            clearError();
            return true;
        } catch (ApiException e) {
            fail(e);
            return false;
        }
    }

    private static String validateGroup(String name, int min, int max) {
        if (name == null || name.isBlank()) {
            return "Group name is required";
        }
        if (min < 0 || max < 1 || max < min) {
            return "Invalid min/max selections";
        }
        return null;
    }

    private static String validateOption(String name, BigDecimal priceDelta) {
        if (name == null || name.isBlank()) {
            return "Option name is required";
        }
        if (priceDelta == null) {
            return "Price delta is required (may be 0)";
        }
        return null;
    }

    private void clearError() {
        ui.accept(() -> errorMessage.set(""));
    }

    private void setError(String msg) {
        ui.accept(() -> errorMessage.set(msg));
    }

    private void fail(ApiException e) {
        String msg = messageOf(e);
        ui.accept(() -> errorMessage.set(msg));
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

- [ ] **Step 5: View-model test**

Create `terminal/src/test/java/com/company/pos/terminal/viewmodel/ModifierBuilderViewModelTest.java`:

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.MenuAdminApi;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.ProductApi;
import com.company.pos.terminal.api.dto.ModifierGroupAdminView;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModifierBuilderViewModelTest {

    private ModifierBuilderViewModel vm(MenuAdminApi m) {
        return new ModifierBuilderViewModel(m, new ProductApi(null), Runnable::run);
    }

    @Test
    void loadGroupsReturnsListAndClearsError() {
        MenuAdminApi m = new MenuAdminApi(null) {
            @Override public List<ModifierGroupAdminView> listGroups() {
                return List.of(new ModifierGroupAdminView(UUID.randomUUID(), "Add-ons", 0, 2, true,
                        List.of(), List.of()));
            }
        };
        ModifierBuilderViewModel vm = vm(m);
        assertEquals(1, vm.loadGroups().size());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void createGroupRejectsBlankNameWithoutCallingApi() {
        boolean[] called = {false};
        MenuAdminApi m = new MenuAdminApi(null) {
            @Override public void createGroup(String name, int min, int max) {
                called[0] = true;
            }
        };
        ModifierBuilderViewModel vm = vm(m);
        assertFalse(vm.createGroup("  ", 0, 2));
        assertFalse(called[0]);
        assertEquals("Group name is required", vm.errorMessage().get());
    }

    @Test
    void createGroupRejectsBadSelectionsWithoutCallingApi() {
        boolean[] called = {false};
        MenuAdminApi m = new MenuAdminApi(null) {
            @Override public void createGroup(String name, int min, int max) {
                called[0] = true;
            }
        };
        ModifierBuilderViewModel vm = vm(m);
        assertFalse(vm.createGroup("X", 3, 1));
        assertFalse(called[0]);
        assertEquals("Invalid min/max selections", vm.errorMessage().get());
    }

    @Test
    void addOptionRejectsNullPriceWithoutCallingApi() {
        boolean[] called = {false};
        MenuAdminApi m = new MenuAdminApi(null) {
            @Override public void addOption(UUID g, String name, BigDecimal d) {
                called[0] = true;
            }
        };
        ModifierBuilderViewModel vm = vm(m);
        assertFalse(vm.addOption(UUID.randomUUID(), "Cheese", null));
        assertFalse(called[0]);
    }

    @Test
    void updateGroupSuccessReturnsTrue() {
        MenuAdminApi m = new MenuAdminApi(null) {
            @Override public void updateGroup(UUID g, String name, int min, int max) { /* ok */ }
        };
        assertTrue(vm(m).updateGroup(UUID.randomUUID(), "Extras", 1, 3));
    }

    @Test
    void deferredDispatcherHoldsErrorUntilDrained() {
        MenuAdminApi m = new MenuAdminApi(null) {
            @Override public List<ModifierGroupAdminView> listGroups() {
                throw new ApiException(500, new ProblemDetail("Error", 500, "boom"), "HTTP 500");
            }
        };
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        ModifierBuilderViewModel vm = new ModifierBuilderViewModel(m, new ProductApi(null), queue::add);
        assertNull(vm.loadGroups());
        assertEquals("", vm.errorMessage().get());   // deferred: not applied yet
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
        assertEquals("boom", vm.errorMessage().get());
    }
}
```

- [ ] **Step 6: Run the tests**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=ModifierRowsTest,ModifierBuilderViewModelTest`
Expected: PASS (3 + 6 tests).

- [ ] **Step 7: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ModifierOptionAdminView.java pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ModifierGroupAdminView.java pos-terminal/src/main/java/com/company/pos/terminal/api/ModifierGroupRequest.java pos-terminal/src/main/java/com/company/pos/terminal/api/ModifierOptionRequest.java pos-terminal/src/main/java/com/company/pos/terminal/api/MenuAdminApi.java pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/ModifierRows.java pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/ModifierBuilderViewModel.java pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/ModifierRowsTest.java pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/ModifierBuilderViewModelTest.java
git commit -m "feat(terminal): MenuAdminApi + ModifierBuilderViewModel + formatters"
```

---

### Task 5: Terminal — the three I/O-free dialogs

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/ModifierGroupFormDialog.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/ModifierOptionFormDialog.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/SkuPickerDialog.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/view/ModifierGroupFormDialogTest.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/view/ModifierOptionFormDialogTest.java`

**Interfaces:**
- Consumes: `dto.ProductView` (for the SKU picker). No HTTP — pure input collection.
- Produces (Task 6): `ModifierGroupFormDialog.promptForGroup(String name, int min, int max)` → `Optional<GroupResult>` (`GroupResult(String name, int min, int max)`) + static `validate`; `ModifierOptionFormDialog.promptForOption(String name, String priceDelta)` → `Optional<OptionResult>` (`OptionResult(String name, BigDecimal priceDelta)`) + static `parseDelta`/`validate`; `SkuPickerDialog.pickSku(List<ProductView>)` → `Optional<String>`.

- [ ] **Step 1: Group form dialog**

Create `terminal/view/ModifierGroupFormDialog.java`:

```java
package com.company.pos.terminal.view;

import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/** Modal to create or edit a modifier group. Pure view — collects input only. Only {@link #validate}
 *  is unit-tested. */
public final class ModifierGroupFormDialog {

    private ModifierGroupFormDialog() {
    }

    public record GroupResult(String name, int min, int max) {
    }

    /** {@code currentName} null on create. */
    public static Optional<GroupResult> promptForGroup(String currentName, int currentMin, int currentMax) {
        boolean editing = currentName != null;
        Dialog<GroupResult> dialog = new Dialog<>();
        dialog.setTitle(editing ? "Edit modifier group" : "New modifier group");
        dialog.setHeaderText(editing ? "Edit \"" + currentName + "\"" : "Create a modifier group");
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = new ButtonType(editing ? "Save" : "Create", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);

        TextField name = new TextField();
        name.setPromptText("name (e.g. Add-ons)");
        if (editing) {
            name.setText(currentName);
        }
        Spinner<Integer> min = new Spinner<>(0, 99, editing ? currentMin : 0);
        min.setEditable(true);
        Spinner<Integer> max = new Spinner<>(1, 99, editing ? Math.max(currentMax, 1) : 1);
        max.setEditable(true);

        VBox box = new VBox(12, field("Name", name), field("Min selections", min),
                field("Max selections", max));
        box.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(
                validate(name.getText(), min.getValue(), max.getValue()) != null);
        name.textProperty().addListener((o, a, b) -> revalidate.run());
        min.valueProperty().addListener((o, a, b) -> revalidate.run());
        max.valueProperty().addListener((o, a, b) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> {
            if (bt != submit || validate(name.getText(), min.getValue(), max.getValue()) != null) {
                return null;
            }
            return new GroupResult(name.getText().trim(), min.getValue(), max.getValue());
        });
        return dialog.showAndWait();
    }

    /** Null when valid; else a message. */
    static String validate(String name, int min, int max) {
        if (name == null || name.isBlank()) {
            return "Group name is required";
        }
        if (min < 0 || max < 1 || max < min) {
            return "Invalid min/max selections";
        }
        return null;
    }

    private static VBox field(String labelText, Node control) {
        Label l = new Label(labelText);
        l.getStyleClass().add("field-label");
        VBox b = new VBox(6, l, control);
        b.getStyleClass().add("field");
        return b;
    }
}
```

- [ ] **Step 2: Option form dialog**

Create `terminal/view/ModifierOptionFormDialog.java`:

```java
package com.company.pos.terminal.view;

import java.math.BigDecimal;
import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/** Modal to create or edit a modifier option. Pure view — collects input only. Only
 *  {@link #parseDelta} and {@link #validate} are unit-tested. */
public final class ModifierOptionFormDialog {

    private ModifierOptionFormDialog() {
    }

    public record OptionResult(String name, BigDecimal priceDelta) {
    }

    /** {@code currentName} null on create. {@code currentDelta} is the plain-string delta or "". */
    public static Optional<OptionResult> promptForOption(String currentName, String currentDelta) {
        boolean editing = currentName != null;
        Dialog<OptionResult> dialog = new Dialog<>();
        dialog.setTitle(editing ? "Edit option" : "New option");
        dialog.setHeaderText(editing ? "Edit \"" + currentName + "\"" : "Add an option");
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = new ButtonType(editing ? "Save" : "Add", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);

        TextField name = new TextField();
        name.setPromptText("name (e.g. Extra cheese)");
        if (editing) {
            name.setText(currentName);
        }
        TextField delta = new TextField();
        delta.setPromptText("price delta (e.g. 2.50, -1.00, 0)");
        delta.setText(currentDelta == null ? "" : currentDelta);

        VBox box = new VBox(12, field("Name", name), field("Price delta", delta));
        box.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(validate(name.getText(), delta.getText()) != null);
        name.textProperty().addListener((o, a, b) -> revalidate.run());
        delta.textProperty().addListener((o, a, b) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> {
            if (bt != submit || validate(name.getText(), delta.getText()) != null) {
                return null;
            }
            return new OptionResult(name.getText().trim(), parseDelta(delta.getText()));
        });
        return dialog.showAndWait();
    }

    /** Parse a signed decimal (negatives allowed), or null if blank/unparseable. */
    static BigDecimal parseDelta(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Null when valid; else a message. */
    static String validate(String name, String delta) {
        if (name == null || name.isBlank()) {
            return "Option name is required";
        }
        if (parseDelta(delta) == null) {
            return "Price delta must be a number (may be 0 or negative)";
        }
        return null;
    }

    private static VBox field(String labelText, Node control) {
        Label l = new Label(labelText);
        l.getStyleClass().add("field-label");
        VBox b = new VBox(6, l, control);
        b.getStyleClass().add("field");
        return b;
    }
}
```

- [ ] **Step 3: SKU picker dialog**

Create `terminal/view/SkuPickerDialog.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.ProductView;
import com.company.pos.terminal.viewmodel.ModifierRows;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** Modal to pick a product SKU to assign a modifier group to. Pure view — the controller passes in
 *  the product list; returns the chosen sku. */
public final class SkuPickerDialog {

    private SkuPickerDialog() {
    }

    public static Optional<String> pickSku(List<ProductView> products) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Assign to product");
        dialog.setHeaderText("Choose a product");
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = new ButtonType("Assign", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);

        List<ProductView> list = new ArrayList<>(products == null ? List.of() : products);
        ComboBox<ProductView> combo = new ComboBox<>(FXCollections.observableArrayList(list));
        combo.setPromptText("product");
        combo.setCellFactory(cb -> labelCell());
        combo.setButtonCell(labelCell());

        VBox box = new VBox(6, label("Product"), combo);
        box.setAlignment(Pos.TOP_LEFT);
        box.getStyleClass().add("field");
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(combo.getValue() == null);
        combo.valueProperty().addListener((o, a, b) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> {
            if (bt != submit || combo.getValue() == null) {
                return null;
            }
            return combo.getValue().sku();
        });
        return dialog.showAndWait();
    }

    private static javafx.scene.control.ListCell<ProductView> labelCell() {
        return new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(ProductView item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : ModifierRows.skuLabel(item.sku(), java.util.List.of(item)));
            }
        };
    }

    private static Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("field-label");
        return l;
    }
}
```

- [ ] **Step 4: Dialog tests**

Create `terminal/src/test/java/com/company/pos/terminal/view/ModifierGroupFormDialogTest.java`:

```java
package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ModifierGroupFormDialogTest {

    @Test
    void validAccepted() {
        assertNull(ModifierGroupFormDialog.validate("Add-ons", 0, 2));
        assertNull(ModifierGroupFormDialog.validate("Forced", 1, 1));
    }

    @Test
    void blankNameRejected() {
        assertNotNull(ModifierGroupFormDialog.validate("  ", 0, 2));
    }

    @Test
    void badSelectionsRejected() {
        assertNotNull(ModifierGroupFormDialog.validate("X", 3, 1));   // min > max
        assertNotNull(ModifierGroupFormDialog.validate("X", 0, 0));   // max < 1
    }
}
```

Create `terminal/src/test/java/com/company/pos/terminal/view/ModifierOptionFormDialogTest.java`:

```java
package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ModifierOptionFormDialogTest {

    @Test
    void parseDeltaHandlesSignAndBlank() {
        assertEquals(new BigDecimal("2.50"), ModifierOptionFormDialog.parseDelta(" 2.50 "));
        assertEquals(new BigDecimal("-1"), ModifierOptionFormDialog.parseDelta("-1"));
        assertNull(ModifierOptionFormDialog.parseDelta("  "));
        assertNull(ModifierOptionFormDialog.parseDelta("abc"));
    }

    @Test
    void validateChecksNameAndDelta() {
        assertNull(ModifierOptionFormDialog.validate("Cheese", "2.50"));
        assertNull(ModifierOptionFormDialog.validate("No ice", "-1.00"));
        assertNull(ModifierOptionFormDialog.validate("Free", "0"));
        assertNotNull(ModifierOptionFormDialog.validate(" ", "2.50"));
        assertNotNull(ModifierOptionFormDialog.validate("Cheese", "abc"));
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=ModifierGroupFormDialogTest,ModifierOptionFormDialogTest`
Expected: PASS (3 + 2 tests).

- [ ] **Step 6: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/ModifierGroupFormDialog.java pos-terminal/src/main/java/com/company/pos/terminal/view/ModifierOptionFormDialog.java pos-terminal/src/main/java/com/company/pos/terminal/view/SkuPickerDialog.java pos-terminal/src/test/java/com/company/pos/terminal/view/ModifierGroupFormDialogTest.java pos-terminal/src/test/java/com/company/pos/terminal/view/ModifierOptionFormDialogTest.java
git commit -m "feat(terminal): I/O-free modifier group/option/sku-picker dialogs"
```

---

### Task 6: Terminal — the modifier builder screen, wiring, Menu tile

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/ModifierBuilderController.java`
- Create: `pos-terminal/src/main/resources/fxml/modifier-builder.fxml`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/AdminController.java`
- Modify: `pos-terminal/src/main/resources/fxml/admin.fxml`

**Interfaces:**
- Consumes: `ModifierBuilderViewModel` (Task 4), the three dialogs (Task 5), `ModifierRows` (Task 4), `dto.ModifierGroupAdminView`/`ModifierOptionAdminView`/`ProductView`, existing `Services`/`Navigator`/`FxTasks`.

- [ ] **Step 1: Register MenuAdminApi in Services**

In `terminal/app/Services.java`: add `import com.company.pos.terminal.api.MenuAdminApi;`, add the field `public final MenuAdminApi menuAdminApi;` next to `menuApi`, and initialize it in the constructor `this.menuAdminApi = new MenuAdminApi(apiClient);` (after `this.menuApi = new MenuApi(apiClient);`).

- [ ] **Step 2: The controller**

Create `terminal/view/ModifierBuilderController.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.ModifierGroupAdminView;
import com.company.pos.terminal.api.dto.ModifierOptionAdminView;
import com.company.pos.terminal.api.dto.ProductView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.ModifierBuilderViewModel;
import com.company.pos.terminal.viewmodel.ModifierRows;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;

/**
 * MANAGER/ADMIN modifier builder. Master/detail: a groups table on top; selecting a group renders its
 * options table + assigned-SKU list from the cached admin view (no extra fetch). All I/O runs in
 * FxTasks work lambdas; each mutation's onDone re-kicks reload(); dialogs are I/O-free.
 */
public class ModifierBuilderController {

    private static final System.Logger LOG = System.getLogger(ModifierBuilderController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final ModifierBuilderViewModel vm;

    private List<ModifierGroupAdminView> allGroups = new ArrayList<>();
    private List<ProductView> products = new ArrayList<>();

    @FXML private Label errorLabel;
    @FXML private TextField searchField;
    @FXML private Button backButton;
    @FXML private TableView<ModifierGroupAdminView> groupsTable;
    @FXML private TableColumn<ModifierGroupAdminView, String> gNameCol;
    @FXML private TableColumn<ModifierGroupAdminView, String> gSelCol;
    @FXML private TableColumn<ModifierGroupAdminView, String> gOptCol;
    @FXML private TableColumn<ModifierGroupAdminView, String> gStatusCol;
    @FXML private Button newGroupButton;
    @FXML private Button editGroupButton;
    @FXML private Button deactivateGroupButton;
    @FXML private Button reactivateGroupButton;
    @FXML private Label detailTitle;
    @FXML private TableView<ModifierOptionAdminView> optionsTable;
    @FXML private TableColumn<ModifierOptionAdminView, String> oNameCol;
    @FXML private TableColumn<ModifierOptionAdminView, String> oPriceCol;
    @FXML private TableColumn<ModifierOptionAdminView, String> oStatusCol;
    @FXML private Button addOptionButton;
    @FXML private Button editOptionButton;
    @FXML private Button deactivateOptionButton;
    @FXML private Button reactivateOptionButton;
    @FXML private ListView<String> assignedList;
    @FXML private Button assignButton;
    @FXML private Button unassignButton;

    public ModifierBuilderController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new ModifierBuilderViewModel(services.menuAdminApi, services.productApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        gNameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        gSelCol.setCellValueFactory(c -> new SimpleStringProperty(
                ModifierRows.selectionsLabel(c.getValue().minSelections(), c.getValue().maxSelections())));
        gOptCol.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().options().size())));
        gStatusCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().active() ? "Active" : "Inactive"));

        oNameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        oPriceCol.setCellValueFactory(c -> new SimpleStringProperty(ModifierRows.priceDeltaLabel(c.getValue().priceDelta())));
        oStatusCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().active() ? "Active" : "Inactive"));
        assignedList.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String sku, boolean empty) {
                super.updateItem(sku, empty);
                setText(empty || sku == null ? null : ModifierRows.skuLabel(sku, products));
            }
        });

        errorLabel.textProperty().bind(vm.errorMessage());
        searchField.textProperty().addListener((o, a, b) -> applyFilter());
        groupsTable.getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> renderDetail(sel));
        optionsTable.getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> refreshOptionButtons(sel));
        assignedList.getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> unassignButton.setDisable(sel == null));

        backButton.setOnAction(e -> navigator.toAdmin());
        newGroupButton.setOnAction(e -> newGroup());
        editGroupButton.setOnAction(e -> editGroup());
        deactivateGroupButton.setOnAction(e -> groupActive(false));
        reactivateGroupButton.setOnAction(e -> groupActive(true));
        addOptionButton.setOnAction(e -> addOption());
        editOptionButton.setOnAction(e -> editOption());
        deactivateOptionButton.setOnAction(e -> optionActive(false));
        reactivateOptionButton.setOnAction(e -> optionActive(true));
        assignButton.setOnAction(e -> assign());
        unassignButton.setOnAction(e -> unassign());

        renderDetail(null);
        reload();
    }

    @SuppressWarnings("unchecked")
    private void reload() {
        UUID keep = selectedGroupId();
        final List<ModifierGroupAdminView>[] gh = new List[1];
        final List<ProductView>[] ph = new List[1];
        FxTasks.run(
                () -> {
                    gh[0] = vm.loadGroups();
                    ph[0] = vm.loadProducts();
                },
                () -> {
                    if (gh[0] != null) {
                        allGroups = gh[0];
                    }
                    if (ph[0] != null) {
                        products = ph[0];
                    }
                    applyFilter();
                    reselect(keep);
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Load modifier groups failed", err));
    }

    private void applyFilter() {
        String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        List<ModifierGroupAdminView> shown = allGroups.stream()
                .filter(g -> q.isEmpty() || g.name().toLowerCase().contains(q))
                .toList();
        groupsTable.setItems(FXCollections.observableArrayList(shown));
        refreshGroupButtons(selectedGroup());
    }

    private void reselect(UUID id) {
        if (id == null) {
            return;
        }
        for (ModifierGroupAdminView g : groupsTable.getItems()) {
            if (g.id().equals(id)) {
                groupsTable.getSelectionModel().select(g);
                return;
            }
        }
    }

    private ModifierGroupAdminView selectedGroup() {
        return groupsTable.getSelectionModel().getSelectedItem();
    }

    private UUID selectedGroupId() {
        ModifierGroupAdminView g = selectedGroup();
        return g == null ? null : g.id();
    }

    private void renderDetail(ModifierGroupAdminView g) {
        refreshGroupButtons(g);
        if (g == null) {
            detailTitle.setText("Select a group");
            optionsTable.setItems(FXCollections.observableArrayList());
            assignedList.setItems(FXCollections.observableArrayList());
        } else {
            detailTitle.setText(g.name());
            optionsTable.setItems(FXCollections.observableArrayList(g.options()));
            assignedList.setItems(FXCollections.observableArrayList(g.assignedSkus()));
        }
        refreshOptionButtons(null);
        unassignButton.setDisable(true);
        boolean hasGroup = g != null;
        addOptionButton.setDisable(!hasGroup);
        assignButton.setDisable(!hasGroup);
    }

    private void refreshGroupButtons(ModifierGroupAdminView g) {
        editGroupButton.setDisable(g == null);
        deactivateGroupButton.setDisable(g == null || !g.active());
        reactivateGroupButton.setDisable(g == null || g.active());
    }

    private void refreshOptionButtons(ModifierOptionAdminView o) {
        editOptionButton.setDisable(o == null);
        deactivateOptionButton.setDisable(o == null || !o.active());
        reactivateOptionButton.setDisable(o == null || o.active());
    }

    // --- group actions ---
    private void newGroup() {
        Optional<ModifierGroupFormDialog.GroupResult> r = ModifierGroupFormDialog.promptForGroup(null, 0, 1);
        r.ifPresent(res -> kick(() -> vm.createGroup(res.name(), res.min(), res.max())));
    }

    private void editGroup() {
        ModifierGroupAdminView g = selectedGroup();
        if (g == null) {
            return;
        }
        Optional<ModifierGroupFormDialog.GroupResult> r =
                ModifierGroupFormDialog.promptForGroup(g.name(), g.minSelections(), g.maxSelections());
        r.ifPresent(res -> kick(() -> vm.updateGroup(g.id(), res.name(), res.min(), res.max())));
    }

    private void groupActive(boolean active) {
        ModifierGroupAdminView g = selectedGroup();
        if (g == null) {
            return;
        }
        kick(() -> active ? vm.reactivateGroup(g.id()) : vm.deactivateGroup(g.id()));
    }

    // --- option actions ---
    private void addOption() {
        ModifierGroupAdminView g = selectedGroup();
        if (g == null) {
            return;
        }
        Optional<ModifierOptionFormDialog.OptionResult> r = ModifierOptionFormDialog.promptForOption(null, "");
        r.ifPresent(res -> kick(() -> vm.addOption(g.id(), res.name(), res.priceDelta())));
    }

    private void editOption() {
        ModifierGroupAdminView g = selectedGroup();
        ModifierOptionAdminView o = optionsTable.getSelectionModel().getSelectedItem();
        if (g == null || o == null) {
            return;
        }
        Optional<ModifierOptionFormDialog.OptionResult> r =
                ModifierOptionFormDialog.promptForOption(o.name(), o.priceDelta().toPlainString());
        r.ifPresent(res -> kick(() -> vm.updateOption(g.id(), o.id(), res.name(), res.priceDelta())));
    }

    private void optionActive(boolean active) {
        ModifierGroupAdminView g = selectedGroup();
        ModifierOptionAdminView o = optionsTable.getSelectionModel().getSelectedItem();
        if (g == null || o == null) {
            return;
        }
        kick(() -> active ? vm.reactivateOption(g.id(), o.id()) : vm.deactivateOption(g.id(), o.id()));
    }

    // --- assignment actions ---
    private void assign() {
        ModifierGroupAdminView g = selectedGroup();
        if (g == null) {
            return;
        }
        Optional<String> sku = SkuPickerDialog.pickSku(products);
        sku.ifPresent(s -> kick(() -> vm.assign(g.id(), s)));
    }

    private void unassign() {
        ModifierGroupAdminView g = selectedGroup();
        String sku = assignedList.getSelectionModel().getSelectedItem();
        if (g == null || sku == null) {
            return;
        }
        kick(() -> vm.unassign(g.id(), sku));
    }

    /** Run a boolean-returning VM mutation off-thread; reload on success. */
    private void kick(java.util.function.BooleanSupplier work) {
        final boolean[] holder = {false};
        FxTasks.run(() -> holder[0] = work.getAsBoolean(),
                () -> { if (holder[0]) reload(); },
                err -> LOG.log(System.Logger.Level.ERROR, "Modifier mutation failed", err));
    }
}
```

- [ ] **Step 3: The FXML**

Create `terminal/src/main/resources/fxml/modifier-builder.fxml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.scene.control.Button?>
<?import javafx.scene.control.Label?>
<?import javafx.scene.control.ListView?>
<?import javafx.scene.control.TableColumn?>
<?import javafx.scene.control.TableView?>
<?import javafx.scene.control.TextField?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.layout.Pane?>
<?import javafx.scene.layout.VBox?>

<VBox styleClass="screen" spacing="16" xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml">
  <HBox spacing="16" alignment="CENTER_LEFT">
    <Label text="Modifier builder" styleClass="title"/>
    <Pane HBox.hgrow="ALWAYS"/>
    <Label fx:id="errorLabel" styleClass="error-text"/>
    <TextField fx:id="searchField" promptText="Search group"/>
    <Button fx:id="backButton" text="Back" styleClass="btn-secondary"/>
  </HBox>

  <TableView fx:id="groupsTable" VBox.vgrow="ALWAYS">
    <columns>
      <TableColumn fx:id="gNameCol" text="Group" prefWidth="240"/>
      <TableColumn fx:id="gSelCol" text="Selections" prefWidth="120"/>
      <TableColumn fx:id="gOptCol" text="Options" prefWidth="90"/>
      <TableColumn fx:id="gStatusCol" text="Status" prefWidth="120"/>
    </columns>
  </TableView>
  <HBox spacing="12" alignment="CENTER_LEFT">
    <Button fx:id="newGroupButton" text="New group" styleClass="btn-primary"/>
    <Button fx:id="editGroupButton" text="Edit"/>
    <Button fx:id="deactivateGroupButton" text="Deactivate"/>
    <Button fx:id="reactivateGroupButton" text="Reactivate"/>
  </HBox>

  <Label fx:id="detailTitle" text="Select a group" styleClass="subtitle"/>
  <HBox spacing="24" VBox.vgrow="ALWAYS">
    <VBox spacing="8" HBox.hgrow="ALWAYS">
      <Label text="Options" styleClass="field-label"/>
      <TableView fx:id="optionsTable" VBox.vgrow="ALWAYS">
        <columns>
          <TableColumn fx:id="oNameCol" text="Option" prefWidth="200"/>
          <TableColumn fx:id="oPriceCol" text="Price delta" prefWidth="120"/>
          <TableColumn fx:id="oStatusCol" text="Status" prefWidth="110"/>
        </columns>
      </TableView>
      <HBox spacing="12" alignment="CENTER_LEFT">
        <Button fx:id="addOptionButton" text="Add option"/>
        <Button fx:id="editOptionButton" text="Edit"/>
        <Button fx:id="deactivateOptionButton" text="Deactivate"/>
        <Button fx:id="reactivateOptionButton" text="Reactivate"/>
      </HBox>
    </VBox>
    <VBox spacing="8" prefWidth="260">
      <Label text="Assigned products" styleClass="field-label"/>
      <ListView fx:id="assignedList" VBox.vgrow="ALWAYS"/>
      <HBox spacing="12" alignment="CENTER_LEFT">
        <Button fx:id="assignButton" text="Assign"/>
        <Button fx:id="unassignButton" text="Unassign"/>
      </HBox>
    </VBox>
  </HBox>
</VBox>
```

- [ ] **Step 4: Navigator route**

In `terminal/app/Navigator.java`, after the `toSettings()` method, add:

```java
    public void toMenu() {
        com.company.pos.terminal.view.ModifierBuilderController controller =
                new com.company.pos.terminal.view.ModifierBuilderController(services, this);
        setScene("/fxml/modifier-builder.fxml", controller);
    }
```

- [ ] **Step 5: Admin tile — controller**

In `terminal/view/AdminController.java`, add the field (next to `settingsButton`):

```java
    @FXML private Button menuButton;
```

and in `initialize()`, after the `settingsButton` block, add (MANAGER+ADMIN gate, reusing the existing `manager` boolean):

```java
        menuButton.setVisible(manager);
        menuButton.setManaged(manager);
        menuButton.setOnAction(e -> navigator.toMenu());
```

- [ ] **Step 6: Admin tile — FXML**

In `terminal/src/main/resources/fxml/admin.fxml`, add the tile after the `settingsButton` button, inside the tiles `HBox`:

```xml
      <Button fx:id="menuButton" text="Menu" styleClass="home-tile"/>
```

- [ ] **Step 7: Run the full terminal suite**

Run: `./mvnw -f pos-terminal/pom.xml clean test`
Expected: PASS (full suite, including the new modifier tests; the FXML loads with its 23 `fx:id`s matching the controller's `@FXML` fields).

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/ModifierBuilderController.java pos-terminal/src/main/resources/fxml/modifier-builder.fxml pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java pos-terminal/src/main/java/com/company/pos/terminal/view/AdminController.java pos-terminal/src/main/resources/fxml/admin.fxml
git commit -m "feat(terminal): modifier builder screen + Menu tile (MANAGER/ADMIN)"
```

---

## Self-Review

**Spec coverage:** domain mutators (T1) ✓; UpdateModifierGroupCommand/UpdateOptionCommand + admin views + MenuChangeType/MenuChanged (T1) ✓; updateGroup/reactivateGroup/updateOption/deactivateOption/reactivateOption/listModifierGroups (T1) ✓; MenuChanged on all modifier writes incl. existing (T1) ✓; endpoints GET-list/PUT-group/reactivate/PUT-option/DELETE-option/reactivate (T2) ✓; audit listener + 10 actions + one-way dep (T3) ✓; MenuAdminApi + admin DTOs + VM + formatters (T4) ✓; three I/O-free dialogs (T5) ✓; master/detail screen + Menu tile MANAGER+ADMIN (T6) ✓; no migration / no 2nd audit path / active-options-only in ordering read preserved (Global Constraints) ✓; variants untouched ✓.

**Type consistency:** `MenuChanged(entityRef, type, actor, detail, oldPrice, newPrice)` produced T1, consumed T3. `ModifierGroupAdminView`/`ModifierOptionAdminView` server records (T1) mirrored by terminal DTOs (T4) with identical component order. `ModifierBuilderViewModel` boolean mutations consumed by the controller's `kick(...)` (T6). Dialog result records `GroupResult(name,min,max)` / `OptionResult(name,priceDelta)` produced T5, consumed T6. `MenuAdminApi` signatures match the VM calls.

**Placeholder scan:** none — every step carries full code or an exact edit target.

**Notes:** (1) The `ProductView` terminal record's constructor is used only in `ModifierRowsTest`; the plan flags checking its actual component list. (2) `MenuAdminApi` void PUT/POST/DELETE pass a `null` `TypeReference` (the established void-endpoint convention). (3) SKUs are interpolated raw into the `?sku=` query, matching the `CartApi`/`DiningApi`/`KitchenApi` convention (SKUs are URL-safe). (4) fx:id⇄@FXML bijection for `modifier-builder.fxml` is large (23 ids) — the implementer must reconcile exactly or the FXML `LoadException`s at runtime.
