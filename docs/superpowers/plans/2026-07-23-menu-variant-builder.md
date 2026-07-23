# Menu Variant Builder Implementation Plan (#3g)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add member `active` lifecycle (soft-delete + restore), group rename + reactivate, admin endpoints, event-based audit, and a terminal MANAGER+ADMIN master/detail screen to manage variant groups and their members.

**Architecture:** `menu` gains `VariantGroup.rename` + `VariantMember.relabel/deactivate/reactivate/isActive` mutators; a V35 Flyway migration adds the `active` column to `variant_member`; new service methods publish `MenuChanged` on every variant write; `audit` extends its exhaustive switch with 8 new `MenuChangeType` values. The terminal adds a `VariantAdminApi`, a synchronous `VariantBuilderViewModel`, pure `VariantRows` formatters, two I/O-free dialogs (reusing `SkuPickerDialog` for SKU selection), and a single master/detail **Variants** screen. The existing modifier tile in `admin.fxml` is relabelled from "Menu" to "Modifiers" to make room for the new "Variants" tile.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Modulith; JavaFX terminal (separate Maven build).

## Global Constraints

- **One migration (Flyway V35):** `ALTER TABLE variant_member ADD COLUMN active BOOLEAN NOT NULL DEFAULT true;` — store-server only; Flyway ceiling was V34. No other schema changes.
- **No new module dependency.** The `audit → menu :: api` edge already exists from #3f. Keep it one-way — `menu` must NOT depend on `audit`. Run `ModularityTests` after the audit switch extension.
- **Scope is variants only.** Modifier groups/options/assignments are already done in #3f; do not touch modifier code except the tile relabel ("Menu" → "Modifiers" in `admin.fxml`).
- **In-place mutators (stable IDs).** Past orders are unaffected: the order line stores the chosen SKU as an immutable snapshot; there is no price-delta column that could drift.
- **Event-based audit.** `menu` publishes `MenuChanged`; `audit` listens async via `@ApplicationModuleListener`. `DefaultMenuService` is class-level `@Transactional` — publishing inside the transaction is deadlock-safe. Do NOT add a synchronous audit call inside `menu`.
- **Ordering read-path change.** `listVariantGroups()` (the ungated ordering read) must also filter `.filter(VariantMember::isActive)` so deactivated members never appear on the ordering screen. `listVariantGroupsAdmin()` does NOT filter — returns everything.
- **Access:** all 6 new variant admin endpoints `@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")`. The existing ungated `GET /menu/variant-groups` stays ungated. The terminal Variants tile is gated on `services.session.isManager()` (MANAGER||ADMIN). `SecurityConfig` is not touched.
- **Money:** not applicable — variants have no price field. The shared `MenuChanged` price fields (`oldPrice`, `newPrice`) are always `null` for variant events (stay `BigDecimal` in the record, passed null).
- **Terminal FX-threading convention.** ViewModel methods are synchronous, return plain values; mutations return `boolean`. The controller runs them off the FX thread via `FxTasks.run(work, onDone, onError)`, reading results in `onDone` via a `holder[]`. The only observable a VM writes off-thread is `errorMessage`, inside `ui.accept(...)`. Never call a blocking VM/HTTP method inside `onDone` — each mutation's `onDone` re-kicks `reload()`. Every VM needs an async-dispatcher regression test (deferred, undrained `ui`). Dialogs are I/O-free (`showAndWait()` in `onDone` is allowed; it's UI, not I/O).
- **Builds:** backend `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw`; terminal `./mvnw -f pos-terminal/pom.xml` (headless, separate build, not the root reactor).

---

### Task 1: Backend — migration, domain mutators, api types, service methods + events

**Files:**
- Create: `src/main/resources/db/migration/menu/V35__variant_member_active.sql`
- Modify: `src/main/java/com/company/pos/menu/domain/VariantGroup.java`
- Modify: `src/main/java/com/company/pos/menu/domain/VariantMember.java`
- Create: `src/main/java/com/company/pos/menu/api/UpdateVariantGroupCommand.java`
- Create: `src/main/java/com/company/pos/menu/api/UpdateVariantMemberCommand.java`
- Create: `src/main/java/com/company/pos/menu/api/VariantMemberAdminView.java`
- Create: `src/main/java/com/company/pos/menu/api/VariantGroupAdminView.java`
- Modify: `src/main/java/com/company/pos/menu/api/MenuChangeType.java`
- Modify: `src/main/java/com/company/pos/menu/api/MenuService.java`
- Modify: `src/main/java/com/company/pos/menu/application/DefaultMenuService.java`
- Test: `src/test/java/com/company/pos/menu/VariantAdminServiceTest.java`

**Interfaces:**
- Produces (Task 2/3/terminal): `UpdateVariantGroupCommand(String name)`; `UpdateVariantMemberCommand(String displayLabel)`; `VariantGroupAdminView(UUID id, String name, boolean active, List<VariantMemberAdminView> members)`; `VariantMemberAdminView(UUID id, String sku, String displayLabel, boolean active)`; 8 new `MenuChangeType` values; 5 new `MenuService` methods + retro-audited existing 3 variant writes + active-member filter on `listVariantGroups()`.
- Consumes (from prior work): `MenuChanged(entityRef, type, actor, detail, oldPrice, newPrice)` (already exists); `DomainEvents events` (already injected in `DefaultMenuService`); `Identifiers.newId()`, `DomainException.notFound/validation`, `actor()` (all already in `DefaultMenuService`).

- [ ] **Step 1: Flyway migration**

Create `src/main/resources/db/migration/menu/V35__variant_member_active.sql`:

```sql
ALTER TABLE variant_member ADD COLUMN active BOOLEAN NOT NULL DEFAULT true;
```

This is the only schema change in #3g. Store-server runs Flyway; `embedded` picks it up via Hibernate `ddl-auto`.

- [ ] **Step 2: Domain mutators — VariantGroup**

In `src/main/java/com/company/pos/menu/domain/VariantGroup.java`, add after `setActive(boolean active)`:

```java
    public void rename(String name) {
        this.name = name;
    }
```

The full updated file (verbatim — all fields/constructor/getters preserved):

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

    public void rename(String name) {
        this.name = name;
    }
}
```

- [ ] **Step 3: Domain mutators — VariantMember**

`VariantMember` currently has no `active` field and no mutators. Add the `active` field + JPA column + `isActive()`, `relabel()`, `deactivate()`, `reactivate()`. The SKU is identity and has no setter. Write the full updated file:

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

    @Column(nullable = false)
    private boolean active = true;

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

    public boolean isActive() {
        return active;
    }

    public void relabel(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    public void deactivate() {
        this.active = false;
    }

    public void reactivate() {
        this.active = true;
    }
}
```

- [ ] **Step 4: New api types**

Create `src/main/java/com/company/pos/menu/api/UpdateVariantGroupCommand.java`:

```java
package com.company.pos.menu.api;

public record UpdateVariantGroupCommand(String name) {
}
```

Create `src/main/java/com/company/pos/menu/api/UpdateVariantMemberCommand.java`:

```java
package com.company.pos.menu.api;

public record UpdateVariantMemberCommand(String displayLabel) {
}
```

Create `src/main/java/com/company/pos/menu/api/VariantMemberAdminView.java`:

```java
package com.company.pos.menu.api;

import java.util.UUID;

public record VariantMemberAdminView(UUID id, String sku, String displayLabel, boolean active) {
}
```

Create `src/main/java/com/company/pos/menu/api/VariantGroupAdminView.java`:

```java
package com.company.pos.menu.api;

import java.util.List;
import java.util.UUID;

public record VariantGroupAdminView(UUID id, String name, boolean active,
        List<VariantMemberAdminView> members) {
}
```

- [ ] **Step 5: Extend MenuChangeType**

In `src/main/java/com/company/pos/menu/api/MenuChangeType.java`, add 8 new values after `GROUP_UNASSIGNED`. Write the full updated file:

```java
package com.company.pos.menu.api;

public enum MenuChangeType {
    GROUP_CREATED, GROUP_UPDATED, GROUP_DEACTIVATED, GROUP_REACTIVATED,
    OPTION_ADDED, OPTION_UPDATED, OPTION_DEACTIVATED, OPTION_REACTIVATED,
    GROUP_ASSIGNED, GROUP_UNASSIGNED,
    VARIANT_GROUP_CREATED, VARIANT_GROUP_UPDATED, VARIANT_GROUP_DEACTIVATED, VARIANT_GROUP_REACTIVATED,
    VARIANT_MEMBER_ADDED, VARIANT_MEMBER_UPDATED, VARIANT_MEMBER_DEACTIVATED, VARIANT_MEMBER_REACTIVATED
}
```

- [ ] **Step 6: Extend MenuService interface**

In `src/main/java/com/company/pos/menu/api/MenuService.java`, in the `// --- variant admin ---` section (after `deactivateVariantGroup`), add these 5 new method signatures + import additions. The full updated file:

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

    ModifierGroupView updateModifierGroup(UUID groupId, UpdateModifierGroupCommand command);

    void reactivateModifierGroup(UUID groupId);

    ModifierOptionView updateOption(UUID groupId, UUID optionId, UpdateOptionCommand command);

    void deactivateOption(UUID groupId, UUID optionId);

    void reactivateOption(UUID groupId, UUID optionId);

    List<ModifierGroupAdminView> listModifierGroups();

    // --- variant admin (MANAGER/ADMIN) ---
    VariantGroupView createVariantGroup(CreateVariantGroupCommand command);

    VariantMemberView addVariantMember(UUID variantGroupId, AddVariantMemberCommand command);

    void deactivateVariantGroup(UUID variantGroupId);

    VariantGroupAdminView updateVariantGroup(UUID variantGroupId, UpdateVariantGroupCommand command);

    VariantGroupAdminView reactivateVariantGroup(UUID variantGroupId);

    void updateVariantMember(UUID variantGroupId, UUID memberId, UpdateVariantMemberCommand command);

    void deactivateVariantMember(UUID variantGroupId, UUID memberId);

    void reactivateVariantMember(UUID variantGroupId, UUID memberId);

    List<VariantGroupAdminView> listVariantGroupsAdmin();

    // --- queries (any authenticated caller / used by cart + dining) ---
    List<ModifierGroupView> groupsForSku(String sku);

    ModifierResolution resolveSelections(String sku, List<UUID> selectedOptionIds);

    List<VariantGroupView> listVariantGroups();
}
```

- [ ] **Step 7: Implement the 5 new methods + retro-audit existing variant writes + active-member filter in DefaultMenuService**

In `src/main/java/com/company/pos/menu/application/DefaultMenuService.java`:

**Step 7a — add imports** (add after the existing `UpdateOptionCommand` import line):

```java
import com.company.pos.menu.api.UpdateVariantGroupCommand;
import com.company.pos.menu.api.UpdateVariantMemberCommand;
import com.company.pos.menu.api.VariantGroupAdminView;
import com.company.pos.menu.api.VariantMemberAdminView;
```

**Step 7b — retro-audit `createVariantGroup`:** replace the existing body:

```java
    @Override
    public VariantGroupView createVariantGroup(CreateVariantGroupCommand command) {
        if (command.name() == null || command.name().isBlank()) {
            throw DomainException.validation("Variant group name is required");
        }
        VariantGroup g = variantGroups.save(new VariantGroup(Identifiers.newId(), command.name().trim()));
        events.publish(new MenuChanged(g.getId().toString(), MenuChangeType.VARIANT_GROUP_CREATED,
                actor(), null, null, null));
        return toVariantView(g);
    }
```

**Step 7c — retro-audit `addVariantMember`:** replace the existing body (add dup-active-SKU guard):

```java
    @Override
    public VariantMemberView addVariantMember(UUID variantGroupId, AddVariantMemberCommand command) {
        variantGroups.findById(variantGroupId)
                .orElseThrow(() -> DomainException.notFound("No variant group " + variantGroupId));
        products.findBySku(command.sku())
                .orElseThrow(() -> DomainException.validation("Unknown sku " + command.sku()));
        boolean dupActive = variantMembers.findByVariantGroupId(variantGroupId).stream()
                .anyMatch(m -> m.isActive() && m.getSku().equals(command.sku()));
        if (dupActive) {
            throw DomainException.validation("SKU " + command.sku() + " is already active in this group");
        }
        VariantMember m = variantMembers.save(new VariantMember(Identifiers.newId(),
                variantGroupId, command.sku(), command.displayLabel()));
        events.publish(new MenuChanged(m.getId().toString(), MenuChangeType.VARIANT_MEMBER_ADDED,
                actor(), command.sku(), null, null));
        return new VariantMemberView(m.getSku(), m.getDisplayLabel());
    }
```

**Step 7d — retro-audit `deactivateVariantGroup`:** replace the existing body:

```java
    @Override
    public void deactivateVariantGroup(UUID variantGroupId) {
        variantGroups.findById(variantGroupId)
                .orElseThrow(() -> DomainException.notFound("No variant group " + variantGroupId))
                .setActive(false);
        events.publish(new MenuChanged(variantGroupId.toString(), MenuChangeType.VARIANT_GROUP_DEACTIVATED,
                actor(), null, null, null));
    }
```

**Step 7e — active-member filter on `listVariantGroups()`:** replace the existing body:

```java
    @Override
    @Transactional(readOnly = true)
    public List<VariantGroupView> listVariantGroups() {
        return variantGroups.findAll().stream()
                .filter(VariantGroup::isActive)
                .map(this::toVariantView)
                .collect(Collectors.toList());
    }
```

And update the private `toVariantView` helper to filter active members:

```java
    private VariantGroupView toVariantView(VariantGroup g) {
        List<VariantMemberView> members = variantMembers.findByVariantGroupId(g.getId()).stream()
                .filter(VariantMember::isActive)
                .map(m -> new VariantMemberView(m.getSku(), m.getDisplayLabel()))
                .collect(Collectors.toList());
        return new VariantGroupView(g.getId(), g.getName(), members);
    }
```

**Step 7f — add the 5 new service methods** (insert after `deactivateVariantGroup`, before `listVariantGroups`):

```java
    @Override
    public VariantGroupAdminView updateVariantGroup(UUID variantGroupId, UpdateVariantGroupCommand command) {
        VariantGroup g = variantGroups.findById(variantGroupId)
                .orElseThrow(() -> DomainException.notFound("No variant group " + variantGroupId));
        if (command.name() == null || command.name().isBlank()) {
            throw DomainException.validation("Variant group name is required");
        }
        g.rename(command.name().trim());
        events.publish(new MenuChanged(g.getId().toString(), MenuChangeType.VARIANT_GROUP_UPDATED,
                actor(), null, null, null));
        return toVariantAdminView(g);
    }

    @Override
    public VariantGroupAdminView reactivateVariantGroup(UUID variantGroupId) {
        VariantGroup g = variantGroups.findById(variantGroupId)
                .orElseThrow(() -> DomainException.notFound("No variant group " + variantGroupId));
        g.setActive(true);
        events.publish(new MenuChanged(g.getId().toString(), MenuChangeType.VARIANT_GROUP_REACTIVATED,
                actor(), null, null, null));
        return toVariantAdminView(g);
    }

    @Override
    public void updateVariantMember(UUID variantGroupId, UUID memberId, UpdateVariantMemberCommand command) {
        VariantMember m = loadMember(variantGroupId, memberId);
        if (command.displayLabel() == null || command.displayLabel().isBlank()) {
            throw DomainException.validation("Display label is required");
        }
        m.relabel(command.displayLabel().trim());
        events.publish(new MenuChanged(m.getId().toString(), MenuChangeType.VARIANT_MEMBER_UPDATED,
                actor(), m.getSku(), null, null));
    }

    @Override
    public void deactivateVariantMember(UUID variantGroupId, UUID memberId) {
        VariantMember m = loadMember(variantGroupId, memberId);
        m.deactivate();
        events.publish(new MenuChanged(m.getId().toString(), MenuChangeType.VARIANT_MEMBER_DEACTIVATED,
                actor(), m.getSku(), null, null));
    }

    @Override
    public void reactivateVariantMember(UUID variantGroupId, UUID memberId) {
        VariantMember m = loadMember(variantGroupId, memberId);
        m.reactivate();
        events.publish(new MenuChanged(m.getId().toString(), MenuChangeType.VARIANT_MEMBER_REACTIVATED,
                actor(), m.getSku(), null, null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<VariantGroupAdminView> listVariantGroupsAdmin() {
        return variantGroups.findAll().stream()
                .map(this::toVariantAdminView)
                .collect(Collectors.toList());
    }
```

**Step 7g — add private helpers** (after `toVariantView`):

```java
    private VariantMember loadMember(UUID variantGroupId, UUID memberId) {
        variantGroups.findById(variantGroupId)
                .orElseThrow(() -> DomainException.notFound("No variant group " + variantGroupId));
        VariantMember m = variantMembers.findById(memberId)
                .orElseThrow(() -> DomainException.notFound("No variant member " + memberId));
        if (!m.getVariantGroupId().equals(variantGroupId)) {
            throw DomainException.validation(
                    "Member " + memberId + " does not belong to group " + variantGroupId);
        }
        return m;
    }

    private VariantGroupAdminView toVariantAdminView(VariantGroup g) {
        List<VariantMemberAdminView> members = variantMembers.findByVariantGroupId(g.getId()).stream()
                .map(m -> new VariantMemberAdminView(m.getId(), m.getSku(), m.getDisplayLabel(), m.isActive()))
                .collect(Collectors.toList());
        return new VariantGroupAdminView(g.getId(), g.getName(), g.isActive(), members);
    }
```

> Note: `VariantMember` does not have a `findById` on the JPA repository yet — it inherits it from `JpaRepository<VariantMember, UUID>`, which already provides `findById(UUID)`. No new finder is needed.

- [ ] **Step 8: Write the service test**

Create `src/test/java/com/company/pos/menu/VariantAdminServiceTest.java`:

```java
package com.company.pos.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.menu.api.AddVariantMemberCommand;
import com.company.pos.menu.api.CreateVariantGroupCommand;
import com.company.pos.menu.api.MenuChangeType;
import com.company.pos.menu.api.MenuChanged;
import com.company.pos.menu.api.MenuService;
import com.company.pos.menu.api.UpdateVariantGroupCommand;
import com.company.pos.menu.api.UpdateVariantMemberCommand;
import com.company.pos.menu.api.VariantGroupAdminView;
import com.company.pos.menu.api.VariantGroupView;
import com.company.pos.menu.api.VariantMemberView;
import com.company.pos.support.DatabaseCleaner;
import com.company.pos.common.util.Identifiers;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
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
class VariantAdminServiceTest {

    @Autowired MenuService menu;
    @Autowired ApplicationEvents events;
    @Autowired DatabaseCleaner cleaner;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("BEER-S", "Small Beer", "DRINKS", "Drinks", "bcBEERS",
                "EA", new BigDecimal("10.00"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("BEER-L", "Large Beer", "DRINKS", "Drinks", "bcBEERL",
                "EA", new BigDecimal("14.00"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void tearDown() {
        cleaner.clean();
        fake.clear();
    }

    // --- group lifecycle ---

    @Test
    void createGroupPublishesVariantGroupCreated() {
        VariantGroupView g = menu.createVariantGroup(new CreateVariantGroupCommand("Sizes"));
        assertThat(typesFor(g.id().toString())).contains(MenuChangeType.VARIANT_GROUP_CREATED);
    }

    @Test
    void renameGroupUpdatesNameAndPublishesUpdated() {
        VariantGroupView g = menu.createVariantGroup(new CreateVariantGroupCommand("Sizes"));
        VariantGroupAdminView u = menu.updateVariantGroup(g.id(),
                new UpdateVariantGroupCommand("Beer sizes"));
        assertThat(u.name()).isEqualTo("Beer sizes");
        assertThat(typesFor(g.id().toString())).contains(MenuChangeType.VARIANT_GROUP_UPDATED);
    }

    @Test
    void renameGroupRejectsBlankName() {
        VariantGroupView g = menu.createVariantGroup(new CreateVariantGroupCommand("Sizes"));
        assertThatThrownBy(() -> menu.updateVariantGroup(g.id(),
                new UpdateVariantGroupCommand("  ")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void deactivateGroupPublishesDeactivated() {
        VariantGroupView g = menu.createVariantGroup(new CreateVariantGroupCommand("Sizes"));
        menu.deactivateVariantGroup(g.id());
        assertThat(typesFor(g.id().toString())).contains(MenuChangeType.VARIANT_GROUP_DEACTIVATED);
    }

    @Test
    void reactivateGroupFlipsActiveAndPublishesReactivated() {
        VariantGroupView g = menu.createVariantGroup(new CreateVariantGroupCommand("Sizes"));
        menu.deactivateVariantGroup(g.id());
        VariantGroupAdminView r = menu.reactivateVariantGroup(g.id());
        assertThat(r.active()).isTrue();
        assertThat(typesFor(g.id().toString())).contains(MenuChangeType.VARIANT_GROUP_REACTIVATED);
    }

    // --- member lifecycle ---

    @Test
    void addMemberPublishesVariantMemberAdded() {
        VariantGroupView g = menu.createVariantGroup(new CreateVariantGroupCommand("Sizes"));
        VariantMemberView m = menu.addVariantMember(g.id(),
                new AddVariantMemberCommand("BEER-S", "Small"));
        assertThat(m.sku()).isEqualTo("BEER-S");
        assertThat(events.stream(MenuChanged.class)
                .anyMatch(e -> e.type() == MenuChangeType.VARIANT_MEMBER_ADDED)).isTrue();
    }

    @Test
    void relabelMemberUpdatesLabelAndPublishesUpdated() {
        VariantGroupView g = menu.createVariantGroup(new CreateVariantGroupCommand("Sizes"));
        VariantMemberView m = menu.addVariantMember(g.id(),
                new AddVariantMemberCommand("BEER-S", "Small"));
        UUID memberId = memberIdFor(g.id(), "BEER-S");
        menu.updateVariantMember(g.id(), memberId, new UpdateVariantMemberCommand("Sm"));
        VariantGroupAdminView admin = menu.listVariantGroupsAdmin().stream()
                .filter(v -> v.id().equals(g.id())).findFirst().orElseThrow();
        assertThat(admin.members().get(0).displayLabel()).isEqualTo("Sm");
        assertThat(typesFor(memberId.toString())).contains(MenuChangeType.VARIANT_MEMBER_UPDATED);
    }

    @Test
    void relabelMemberRejectsBlankLabel() {
        VariantGroupView g = menu.createVariantGroup(new CreateVariantGroupCommand("Sizes"));
        menu.addVariantMember(g.id(), new AddVariantMemberCommand("BEER-S", "Small"));
        UUID memberId = memberIdFor(g.id(), "BEER-S");
        assertThatThrownBy(() -> menu.updateVariantMember(g.id(), memberId,
                new UpdateVariantMemberCommand("  ")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void deactivateMemberFlipsActiveAndPublishesDeactivated() {
        VariantGroupView g = menu.createVariantGroup(new CreateVariantGroupCommand("Sizes"));
        menu.addVariantMember(g.id(), new AddVariantMemberCommand("BEER-S", "Small"));
        UUID memberId = memberIdFor(g.id(), "BEER-S");
        menu.deactivateVariantMember(g.id(), memberId);
        assertThat(activeMemberOf(g.id(), memberId)).isFalse();
        assertThat(typesFor(memberId.toString())).contains(MenuChangeType.VARIANT_MEMBER_DEACTIVATED);
    }

    @Test
    void reactivateMemberFlipsActiveAndPublishesReactivated() {
        VariantGroupView g = menu.createVariantGroup(new CreateVariantGroupCommand("Sizes"));
        menu.addVariantMember(g.id(), new AddVariantMemberCommand("BEER-S", "Small"));
        UUID memberId = memberIdFor(g.id(), "BEER-S");
        menu.deactivateVariantMember(g.id(), memberId);
        menu.reactivateVariantMember(g.id(), memberId);
        assertThat(activeMemberOf(g.id(), memberId)).isTrue();
        assertThat(typesFor(memberId.toString())).contains(MenuChangeType.VARIANT_MEMBER_REACTIVATED);
    }

    @Test
    void memberNotBelongingToGroupThrows() {
        VariantGroupView g1 = menu.createVariantGroup(new CreateVariantGroupCommand("Sizes"));
        VariantGroupView g2 = menu.createVariantGroup(new CreateVariantGroupCommand("Temps"));
        menu.addVariantMember(g1.id(), new AddVariantMemberCommand("BEER-S", "Small"));
        UUID memberId = memberIdFor(g1.id(), "BEER-S");
        assertThatThrownBy(() -> menu.updateVariantMember(g2.id(), memberId,
                new UpdateVariantMemberCommand("X")))
                .isInstanceOf(DomainException.class);
    }

    // --- admin list ---

    @Test
    void adminListIncludesInactiveGroupsAndMembers() {
        VariantGroupView g = menu.createVariantGroup(new CreateVariantGroupCommand("Sizes"));
        menu.addVariantMember(g.id(), new AddVariantMemberCommand("BEER-S", "Small"));
        UUID memberId = memberIdFor(g.id(), "BEER-S");
        menu.deactivateVariantMember(g.id(), memberId);
        menu.deactivateVariantGroup(g.id());
        VariantGroupAdminView view = menu.listVariantGroupsAdmin().stream()
                .filter(v -> v.id().equals(g.id())).findFirst().orElseThrow();
        assertThat(view.active()).isFalse();
        assertThat(view.members()).hasSize(1);
        assertThat(view.members().get(0).active()).isFalse();
    }

    @Test
    void orderingListExcludesInactiveMember() {
        VariantGroupView g = menu.createVariantGroup(new CreateVariantGroupCommand("Sizes"));
        menu.addVariantMember(g.id(), new AddVariantMemberCommand("BEER-S", "Small"));
        menu.addVariantMember(g.id(), new AddVariantMemberCommand("BEER-L", "Large"));
        UUID memberId = memberIdFor(g.id(), "BEER-S");
        menu.deactivateVariantMember(g.id(), memberId);
        List<VariantGroupView> ordering = menu.listVariantGroups();
        VariantGroupView view = ordering.stream().filter(v -> v.id().equals(g.id())).findFirst().orElseThrow();
        assertThat(view.members()).hasSize(1);
        assertThat(view.members().get(0).sku()).isEqualTo("BEER-L");
    }

    @Test
    void duplicateActiveSkuInGroupRejected() {
        VariantGroupView g = menu.createVariantGroup(new CreateVariantGroupCommand("Sizes"));
        menu.addVariantMember(g.id(), new AddVariantMemberCommand("BEER-S", "Small"));
        assertThatThrownBy(() -> menu.addVariantMember(g.id(),
                new AddVariantMemberCommand("BEER-S", "Another Small")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void deactivatedSkuCanBeAddedAgain() {
        VariantGroupView g = menu.createVariantGroup(new CreateVariantGroupCommand("Sizes"));
        menu.addVariantMember(g.id(), new AddVariantMemberCommand("BEER-S", "Small"));
        UUID memberId = memberIdFor(g.id(), "BEER-S");
        menu.deactivateVariantMember(g.id(), memberId);
        // Deactivated — a new active entry with same SKU should be accepted
        assertThat(menu.addVariantMember(g.id(),
                new AddVariantMemberCommand("BEER-S", "Small again")).sku()).isEqualTo("BEER-S");
    }

    // --- helpers ---

    private UUID memberIdFor(UUID groupId, String sku) {
        return menu.listVariantGroupsAdmin().stream()
                .filter(v -> v.id().equals(groupId)).findFirst().orElseThrow()
                .members().stream().filter(m -> m.sku().equals(sku)).findFirst().orElseThrow()
                .id();
    }

    private boolean activeMemberOf(UUID groupId, UUID memberId) {
        return menu.listVariantGroupsAdmin().stream()
                .filter(v -> v.id().equals(groupId)).findFirst().orElseThrow()
                .members().stream().filter(m -> m.id().equals(memberId)).findFirst().orElseThrow()
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

- [ ] **Step 9: Run the service test (TDD: write failing test → implement → run passes)**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=VariantAdminServiceTest`

Expected: **PASS** (all 13 test methods). Also run the existing service test to ensure no regression:

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=VariantAdminServiceTest,ModifierAdminServiceTest`

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add src/main/resources/db/migration/menu/V35__variant_member_active.sql \
        src/main/java/com/company/pos/menu/domain/VariantGroup.java \
        src/main/java/com/company/pos/menu/domain/VariantMember.java \
        src/main/java/com/company/pos/menu/api/UpdateVariantGroupCommand.java \
        src/main/java/com/company/pos/menu/api/UpdateVariantMemberCommand.java \
        src/main/java/com/company/pos/menu/api/VariantMemberAdminView.java \
        src/main/java/com/company/pos/menu/api/VariantGroupAdminView.java \
        src/main/java/com/company/pos/menu/api/MenuChangeType.java \
        src/main/java/com/company/pos/menu/api/MenuService.java \
        src/main/java/com/company/pos/menu/application/DefaultMenuService.java \
        src/test/java/com/company/pos/menu/VariantAdminServiceTest.java
git commit -m "feat(menu): variant group/member edit+reactivate+admin-list + MenuChanged events (V35)"
```

---

### Task 2: Backend — MenuController 6 new variant admin endpoints

**Files:**
- Modify: `src/main/java/com/company/pos/menu/web/MenuController.java`
- Test: `src/test/java/com/company/pos/menu/MenuControllerTest.java` (add 12 new cases)

**Interfaces:**
- Consumes: the Task-1 `MenuService` methods + `UpdateVariantGroupCommand`/`UpdateVariantMemberCommand`/`VariantGroupAdminView`.
- Produces (terminal): 6 new gated endpoints (see below); existing ungated `GET /menu/variant-groups` is untouched.

All 6 new endpoints are `@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")`:
- `GET  /menu/variant-groups/admin` → `listVariantGroupsAdmin()`
- `PUT  /menu/variant-groups/{id}` → `updateVariantGroup()` → returns `VariantGroupAdminView`
- `POST /menu/variant-groups/{id}/reactivate` → `reactivateVariantGroup()` (204)
- `PUT  /menu/variant-groups/{groupId}/members/{memberId}` → `updateVariantMember()` (204)
- `DELETE /menu/variant-groups/{groupId}/members/{memberId}` → `deactivateVariantMember()` (204)
- `POST /menu/variant-groups/{groupId}/members/{memberId}/reactivate` → `reactivateVariantMember()` (204)

- [ ] **Step 1: Add imports + 6 handlers to MenuController**

In `src/main/java/com/company/pos/menu/web/MenuController.java`, add imports after the existing `VariantMemberView` import:

```java
import com.company.pos.menu.api.UpdateVariantGroupCommand;
import com.company.pos.menu.api.UpdateVariantMemberCommand;
import com.company.pos.menu.api.VariantGroupAdminView;
```

Add these 6 handlers inside the `// --- variant admin ---` section, after `deactivateVariantGroup` and before `// --- reads ---`:

```java
    @GetMapping("/menu/variant-groups/admin")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    List<VariantGroupAdminView> listVariantGroupsAdmin() {
        return menu.listVariantGroupsAdmin();
    }

    @PutMapping("/menu/variant-groups/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    VariantGroupAdminView updateVariantGroup(@PathVariable UUID id,
            @RequestBody UpdateVariantGroupCommand body) {
        return menu.updateVariantGroup(id, body);
    }

    @PostMapping("/menu/variant-groups/{id}/reactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    void reactivateVariantGroup(@PathVariable UUID id) {
        menu.reactivateVariantGroup(id);
    }

    @PutMapping("/menu/variant-groups/{groupId}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    void updateVariantMember(@PathVariable UUID groupId, @PathVariable UUID memberId,
            @RequestBody UpdateVariantMemberCommand body) {
        menu.updateVariantMember(groupId, memberId, body);
    }

    @DeleteMapping("/menu/variant-groups/{groupId}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    void deactivateVariantMember(@PathVariable UUID groupId, @PathVariable UUID memberId) {
        menu.deactivateVariantMember(groupId, memberId);
    }

    @PostMapping("/menu/variant-groups/{groupId}/members/{memberId}/reactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    void reactivateVariantMember(@PathVariable UUID groupId, @PathVariable UUID memberId) {
        menu.reactivateVariantMember(groupId, memberId);
    }
```

> All annotations (`PutMapping`, `DeleteMapping`, `PostMapping`, `GetMapping`, `ResponseStatus`, `PreAuthorize`, `PathVariable`, `RequestBody`, `HttpStatus`) are already imported in `MenuController.java` from Task 1 / existing code.

- [ ] **Step 2: Write the 12 controller test cases — all upfront**

In `src/test/java/com/company/pos/menu/MenuControllerTest.java`, add these 12 test methods. The existing `manager()`/`cashier()` JWT helpers and all MockMvc static imports are already present:

```java
    // --- variant admin — 6 endpoints × happy + cashier-403 = 12 tests ---

    @Test
    void managerCanListVariantGroupsAdmin() throws Exception {
        mvc.perform(get("/menu/variant-groups/admin").with(manager()))
                .andExpect(status().isOk());
    }

    @Test
    void cashierCannotListVariantGroupsAdmin() throws Exception {
        mvc.perform(get("/menu/variant-groups/admin").with(cashier()))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanUpdateVariantGroup() throws Exception {
        String created = mvc.perform(post("/menu/variant-groups").with(manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sizes\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(created, "$.id");
        mvc.perform(put("/menu/variant-groups/" + id).with(manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Beer sizes\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void cashierCannotUpdateVariantGroup() throws Exception {
        mvc.perform(put("/menu/variant-groups/" + java.util.UUID.randomUUID()).with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Beer sizes\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanReactivateVariantGroup() throws Exception {
        String created = mvc.perform(post("/menu/variant-groups").with(manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sizes\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(created, "$.id");
        mvc.perform(post("/menu/variant-groups/" + id + "/reactivate").with(manager()))
                .andExpect(status().isNoContent());
    }

    @Test
    void cashierCannotReactivateVariantGroup() throws Exception {
        mvc.perform(post("/menu/variant-groups/" + java.util.UUID.randomUUID() + "/reactivate")
                        .with(cashier()))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanUpdateVariantMember() throws Exception {
        // random UUID group/member → 404 (not 403) proves authz passes; full correctness in VariantAdminServiceTest
        mvc.perform(put("/menu/variant-groups/" + java.util.UUID.randomUUID()
                        + "/members/" + java.util.UUID.randomUUID()).with(manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayLabel\":\"Sm\"}"))
                .andExpect(result ->
                        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                                .isNotEqualTo(403));
    }

    @Test
    void cashierCannotUpdateVariantMember() throws Exception {
        mvc.perform(put("/menu/variant-groups/" + java.util.UUID.randomUUID()
                        + "/members/" + java.util.UUID.randomUUID()).with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayLabel\":\"Sm\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanDeactivateVariantMember() throws Exception {
        mvc.perform(delete("/menu/variant-groups/" + java.util.UUID.randomUUID()
                        + "/members/" + java.util.UUID.randomUUID()).with(manager()))
                .andExpect(result ->
                        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                                .isNotEqualTo(403));
    }

    @Test
    void cashierCannotDeactivateVariantMember() throws Exception {
        mvc.perform(delete("/menu/variant-groups/" + java.util.UUID.randomUUID()
                        + "/members/" + java.util.UUID.randomUUID()).with(cashier()))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanReactivateVariantMember() throws Exception {
        mvc.perform(post("/menu/variant-groups/" + java.util.UUID.randomUUID()
                        + "/members/" + java.util.UUID.randomUUID() + "/reactivate").with(manager()))
                .andExpect(result ->
                        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                                .isNotEqualTo(403));
    }

    @Test
    void cashierCannotReactivateVariantMember() throws Exception {
        mvc.perform(post("/menu/variant-groups/" + java.util.UUID.randomUUID()
                        + "/members/" + java.util.UUID.randomUUID() + "/reactivate").with(cashier()))
                .andExpect(status().isForbidden());
    }
```

> **Design note on member 403-vs-404 pattern:** member endpoints with a random UUID return a `DomainException.notFound` → 404 for MANAGER. That is NOT a 403, proving authorization has passed. For cashier they return 403 before the handler runs. This separates security from business logic — which is what the test is measuring. The full end-to-end 204 for member operations is proven in `VariantAdminServiceTest`.

- [ ] **Step 3: Run the controller tests**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=MenuControllerTest`

Expected: **PASS** (existing tests + 12 new cases).

- [ ] **Step 4: Run ModularityTests to verify no boundary violation**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=ModularityTests`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/pos/menu/web/MenuController.java \
        src/test/java/com/company/pos/menu/MenuControllerTest.java
git commit -m "feat(menu): 6 variant admin endpoints (MANAGER+ADMIN)"
```

---

### Task 3: Backend — audit listener extension for variant events

**Files:**
- Modify: `src/main/java/com/company/pos/audit/api/AuditAction.java`
- Modify: `src/main/java/com/company/pos/audit/application/MenuChangedAuditListener.java`
- Test: `src/test/java/com/company/pos/audit/MenuChangedVariantAuditTest.java`

**Interfaces:**
- Consumes: `MenuChanged`/`MenuChangeType` (Task 1); `DefaultAuditService.append(AuditAction, String actor, String entityRef, Map<String,String> details)` (already used by the modifier listener).
- No change to `audit/package-info.java` — `audit → menu :: api` already exists from #3f.

- [ ] **Step 1: Add 8 new AuditAction constants**

In `src/main/java/com/company/pos/audit/api/AuditAction.java`, add after `MENU_GROUP_UNASSIGNED` (the current last constant):

```java
    MENU_GROUP_UNASSIGNED,
    MENU_VARIANT_GROUP_CREATED,
    MENU_VARIANT_GROUP_UPDATED,
    MENU_VARIANT_GROUP_DEACTIVATED,
    MENU_VARIANT_GROUP_REACTIVATED,
    MENU_VARIANT_MEMBER_ADDED,
    MENU_VARIANT_MEMBER_UPDATED,
    MENU_VARIANT_MEMBER_DEACTIVATED,
    MENU_VARIANT_MEMBER_REACTIVATED
```

Write the full updated enum (verbatim):

```java
package com.company.pos.audit.api;

public enum AuditAction {
    SALE_COMPLETED,
    RETURN_COMPLETED,
    DISCOUNT_OVERRIDE,
    LOGIN_SUCCEEDED,
    LOGIN_FAILED,
    PIN_LOGIN_SUCCEEDED,
    PIN_LOGIN_FAILED,
    PRICE_CHANGED,
    SETTING_CHANGED,
    USER_CREATED,
    USER_UPDATED,
    USER_DEACTIVATED,
    USER_REACTIVATED,
    USER_CREDENTIAL_RESET,
    PRODUCT_CREATED,
    PRODUCT_UPDATED,
    PRODUCT_DEACTIVATED,
    PRODUCT_REACTIVATED,
    CATEGORY_CREATED,
    TABLE_CREATED,
    TABLE_UPDATED,
    TABLE_DEACTIVATED,
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
    MENU_GROUP_UNASSIGNED,
    MENU_VARIANT_GROUP_CREATED,
    MENU_VARIANT_GROUP_UPDATED,
    MENU_VARIANT_GROUP_DEACTIVATED,
    MENU_VARIANT_GROUP_REACTIVATED,
    MENU_VARIANT_MEMBER_ADDED,
    MENU_VARIANT_MEMBER_UPDATED,
    MENU_VARIANT_MEMBER_DEACTIVATED,
    MENU_VARIANT_MEMBER_REACTIVATED
}
```

- [ ] **Step 2: Extend MenuChangedAuditListener switch**

In `src/main/java/com/company/pos/audit/application/MenuChangedAuditListener.java`, extend the `actionFor` switch by adding 8 new cases. The switch is exhaustive (no `default`) so the compiler will fail if any `MenuChangeType` value is missing.

Replace the `actionFor` method body with the full updated switch:

```java
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
            case VARIANT_GROUP_CREATED -> AuditAction.MENU_VARIANT_GROUP_CREATED;
            case VARIANT_GROUP_UPDATED -> AuditAction.MENU_VARIANT_GROUP_UPDATED;
            case VARIANT_GROUP_DEACTIVATED -> AuditAction.MENU_VARIANT_GROUP_DEACTIVATED;
            case VARIANT_GROUP_REACTIVATED -> AuditAction.MENU_VARIANT_GROUP_REACTIVATED;
            case VARIANT_MEMBER_ADDED -> AuditAction.MENU_VARIANT_MEMBER_ADDED;
            case VARIANT_MEMBER_UPDATED -> AuditAction.MENU_VARIANT_MEMBER_UPDATED;
            case VARIANT_MEMBER_DEACTIVATED -> AuditAction.MENU_VARIANT_MEMBER_DEACTIVATED;
            case VARIANT_MEMBER_REACTIVATED -> AuditAction.MENU_VARIANT_MEMBER_REACTIVATED;
        };
    }
```

Also update the `on(MenuChanged event)` handler's `details` block to carry `detail` as `sku` for variant-member events (the existing code already does this for `GROUP_ASSIGNED`/`GROUP_UNASSIGNED` — the same `event.detail()` carry works for `VARIANT_MEMBER_*` since `detail` = sku). No change is needed to the `on` method body — it already uses `if (event.detail() != null) details.put("sku", event.detail())` generically.

The complete updated file is:

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
 * Records menu modifier-admin and variant-admin actions into the audit trail. Runs async AFTER the
 * publishing transaction commits (the outbox redelivers on failure). The actor rides on the event,
 * so the real user is recorded.
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
            case VARIANT_GROUP_CREATED -> AuditAction.MENU_VARIANT_GROUP_CREATED;
            case VARIANT_GROUP_UPDATED -> AuditAction.MENU_VARIANT_GROUP_UPDATED;
            case VARIANT_GROUP_DEACTIVATED -> AuditAction.MENU_VARIANT_GROUP_DEACTIVATED;
            case VARIANT_GROUP_REACTIVATED -> AuditAction.MENU_VARIANT_GROUP_REACTIVATED;
            case VARIANT_MEMBER_ADDED -> AuditAction.MENU_VARIANT_MEMBER_ADDED;
            case VARIANT_MEMBER_UPDATED -> AuditAction.MENU_VARIANT_MEMBER_UPDATED;
            case VARIANT_MEMBER_DEACTIVATED -> AuditAction.MENU_VARIANT_MEMBER_DEACTIVATED;
            case VARIANT_MEMBER_REACTIVATED -> AuditAction.MENU_VARIANT_MEMBER_REACTIVATED;
        };
    }
}
```

- [ ] **Step 3: Write the E2E audit test**

Create `src/test/java/com/company/pos/audit/MenuChangedVariantAuditTest.java`:

```java
package com.company.pos.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class MenuChangedVariantAuditTest {

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
        fake.addProduct(new ErpProduct("BEER-S", "Small Beer", "DRINKS", "Drinks", "bcBEERS",
                "EA", new BigDecimal("10.00"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("BEER-L", "Large Beer", "DRINKS", "Drinks", "bcBEERL",
                "EA", new BigDecimal("14.00"), "SAR", 1, true));
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
    void variantLifecycleIsAudited() throws Exception {
        String token = login("boss");

        // 1. Create group → VARIANT_GROUP_CREATED
        String groupJson = mvc.perform(post("/menu/variant-groups")
                        .header("Authorization", token).contentType("application/json")
                        .content("{\"name\":\"Sizes\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String groupId = JsonPath.read(groupJson, "$.id");

        // 2. Add member → VARIANT_MEMBER_ADDED
        String memberJson = mvc.perform(post("/menu/variant-groups/" + groupId + "/members")
                        .header("Authorization", token).contentType("application/json")
                        .content("{\"sku\":\"BEER-S\",\"displayLabel\":\"Small\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        // 3. Get admin list to retrieve the member id
        String adminJson = mvc.perform(get("/menu/variant-groups/admin")
                        .header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // JsonPath: first group's first member id
        String memberId = JsonPath.read(adminJson, "$[0].members[0].id");

        // 4. Relabel member → VARIANT_MEMBER_UPDATED
        mvc.perform(put("/menu/variant-groups/" + groupId + "/members/" + memberId)
                        .header("Authorization", token).contentType("application/json")
                        .content("{\"displayLabel\":\"Sm\"}"))
                .andExpect(status().isNoContent());

        // 5. Rename group → VARIANT_GROUP_UPDATED
        mvc.perform(put("/menu/variant-groups/" + groupId)
                        .header("Authorization", token).contentType("application/json")
                        .content("{\"name\":\"Beer sizes\"}"))
                .andExpect(status().isOk());

        // 6. Deactivate member → VARIANT_MEMBER_DEACTIVATED
        mvc.perform(delete("/menu/variant-groups/" + groupId + "/members/" + memberId)
                        .header("Authorization", token))
                .andExpect(status().isNoContent());

        // 7. Reactivate member → VARIANT_MEMBER_REACTIVATED
        mvc.perform(post("/menu/variant-groups/" + groupId + "/members/" + memberId + "/reactivate")
                        .header("Authorization", token))
                .andExpect(status().isNoContent());

        // 8. Deactivate group → VARIANT_GROUP_DEACTIVATED
        mvc.perform(delete("/menu/variant-groups/" + groupId)
                        .header("Authorization", token))
                .andExpect(status().isNoContent());

        // 9. Reactivate group → VARIANT_GROUP_REACTIVATED
        mvc.perform(post("/menu/variant-groups/" + groupId + "/reactivate")
                        .header("Authorization", token))
                .andExpect(status().isNoContent());

        // Wait for async listener (outbox + @ApplicationModuleListener fires post-commit)
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<AuditRecordView> recent = audit.recent(100);
            assertThat(recent).anySatisfy(r -> {
                assertThat(r.action()).isEqualTo("MENU_VARIANT_GROUP_CREATED");
                assertThat(r.entityRef()).isEqualTo(groupId);
                assertThat(r.actor()).isEqualTo("boss");
            });
            assertThat(recent).anyMatch(r -> r.action().equals("MENU_VARIANT_MEMBER_ADDED"));
            assertThat(recent).anyMatch(r -> r.action().equals("MENU_VARIANT_MEMBER_UPDATED"));
            assertThat(recent).anyMatch(r -> r.action().equals("MENU_VARIANT_GROUP_UPDATED"));
            assertThat(recent).anyMatch(r -> r.action().equals("MENU_VARIANT_MEMBER_DEACTIVATED"));
            assertThat(recent).anyMatch(r -> r.action().equals("MENU_VARIANT_MEMBER_REACTIVATED"));
            assertThat(recent).anyMatch(r -> r.action().equals("MENU_VARIANT_GROUP_DEACTIVATED"));
            assertThat(recent).anyMatch(r -> r.action().equals("MENU_VARIANT_GROUP_REACTIVATED"));
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

- [ ] **Step 4: Run the audit + boundary tests**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=MenuChangedVariantAuditTest,MenuChangedAuditTest,ModularityTests`

Expected: **PASS** (`MenuChangedVariantAuditTest` new; `MenuChangedAuditTest` must still pass unchanged; `ModularityTests` confirms no new cycle from the 8-case switch extension — `audit → menu :: api` is still one-way).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/pos/audit/api/AuditAction.java \
        src/main/java/com/company/pos/audit/application/MenuChangedAuditListener.java \
        src/test/java/com/company/pos/audit/MenuChangedVariantAuditTest.java
git commit -m "feat(audit): record variant group/member changes via MenuChanged listener"
```

---

### Task 4: Terminal — VariantAdminApi, DTOs, VariantBuilderViewModel, VariantRows formatters

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/VariantMemberAdminView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/VariantGroupAdminView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/VariantGroupRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/VariantMemberRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/VariantAdminApi.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/VariantRows.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/VariantBuilderViewModel.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/VariantRowsTest.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/VariantBuilderViewModelTest.java`

**Interfaces:**
- Consumes: `ApiClient` (`get`/`post`/`put`/`delete`; void endpoints pass `null` TypeReference); `ApiException`/`ProblemDetail`; existing `Services.apiClient`.
- Produces (Task 6): `VariantAdminApi` (non-final); `VariantBuilderViewModel(VariantAdminApi, Consumer<Runnable>)` with `load()` and boolean-returning mutations; `VariantRows.memberLabel(sku, displayLabel)`, `VariantRows.statusLabel(boolean active)`.

- [ ] **Step 1: DTO mirrors + request records**

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/VariantMemberAdminView.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VariantMemberAdminView(UUID id, String sku, String displayLabel, boolean active) {
}
```

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/VariantGroupAdminView.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VariantGroupAdminView(UUID id, String name, boolean active,
        List<VariantMemberAdminView> members) {
}
```

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/VariantGroupRequest.java`:

```java
package com.company.pos.terminal.api;

public record VariantGroupRequest(String name) {
}
```

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/VariantMemberRequest.java`:

```java
package com.company.pos.terminal.api;

public record VariantMemberRequest(String sku, String displayLabel) {
}
```

> `VariantMemberRequest` is used only for add-member (sku + label). Relabel uses a dedicated `VariantMemberLabelRequest` with only `displayLabel`. Create it:

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/VariantMemberLabelRequest.java`:

```java
package com.company.pos.terminal.api;

public record VariantMemberLabelRequest(String displayLabel) {
}
```

- [ ] **Step 2: VariantAdminApi client**

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/VariantAdminApi.java`:

```java
package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.VariantGroupAdminView;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.UUID;

/**
 * Typed client for the store server's variant-admin endpoints (MANAGER/ADMIN-gated).
 * Non-final so view-model tests can subclass with fakes. Void endpoints pass a null TypeReference.
 */
public class VariantAdminApi {

    private final ApiClient client;

    public VariantAdminApi(ApiClient client) {
        this.client = client;
    }

    public List<VariantGroupAdminView> listAdmin() {
        return client.get("/menu/variant-groups/admin",
                new TypeReference<List<VariantGroupAdminView>>() {});
    }

    public void createGroup(String name) {
        client.post("/menu/variant-groups", new VariantGroupRequest(name), null);
    }

    public void updateGroup(UUID groupId, String name) {
        client.put("/menu/variant-groups/" + groupId, new VariantGroupRequest(name), null);
    }

    public void deactivateGroup(UUID groupId) {
        client.delete("/menu/variant-groups/" + groupId);
    }

    public void reactivateGroup(UUID groupId) {
        client.post("/menu/variant-groups/" + groupId + "/reactivate", null, null);
    }

    public void addMember(UUID groupId, String sku, String displayLabel) {
        client.post("/menu/variant-groups/" + groupId + "/members",
                new VariantMemberRequest(sku, displayLabel), null);
    }

    public void updateMember(UUID groupId, UUID memberId, String displayLabel) {
        client.put("/menu/variant-groups/" + groupId + "/members/" + memberId,
                new VariantMemberLabelRequest(displayLabel), null);
    }

    public void deactivateMember(UUID groupId, UUID memberId) {
        client.delete("/menu/variant-groups/" + groupId + "/members/" + memberId);
    }

    public void reactivateMember(UUID groupId, UUID memberId) {
        client.post("/menu/variant-groups/" + groupId + "/members/" + memberId + "/reactivate",
                null, null);
    }
}
```

- [ ] **Step 3: Pure formatters — VariantRows**

Create `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/VariantRows.java`:

```java
package com.company.pos.terminal.viewmodel;

/**
 * Pure display formatters for the variant builder. No JavaFX imports; no HTTP.
 * Null-safe throughout.
 */
public final class VariantRows {

    private VariantRows() {
    }

    /**
     * "sku — displayLabel", e.g. "BEER-S — Small". Null-safe: returns the bare sku if label is null.
     */
    public static String memberLabel(String sku, String displayLabel) {
        if (sku == null) {
            return "";
        }
        if (displayLabel == null || displayLabel.isBlank()) {
            return sku;
        }
        return sku + " — " + displayLabel;
    }

    /** "Active" or "Inactive". */
    public static String statusLabel(boolean active) {
        return active ? "Active" : "Inactive";
    }
}
```

- [ ] **Step 4: VariantRows test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/VariantRowsTest.java`:

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VariantRowsTest {

    @Test
    void memberLabelCombinesSkuAndLabel() {
        assertEquals("BEER-S — Small", VariantRows.memberLabel("BEER-S", "Small"));
    }

    @Test
    void memberLabelNullLabelReturnsBareSkuOnly() {
        assertEquals("BEER-S", VariantRows.memberLabel("BEER-S", null));
        assertEquals("BEER-S", VariantRows.memberLabel("BEER-S", "  "));
    }

    @Test
    void memberLabelNullSkuReturnsEmpty() {
        assertEquals("", VariantRows.memberLabel(null, "Small"));
    }

    @Test
    void statusLabelActiveInactive() {
        assertEquals("Active", VariantRows.statusLabel(true));
        assertEquals("Inactive", VariantRows.statusLabel(false));
    }
}
```

- [ ] **Step 5: VariantBuilderViewModel**

Create `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/VariantBuilderViewModel.java`:

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.VariantAdminApi;
import com.company.pos.terminal.api.dto.VariantGroupAdminView;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the variant builder. Synchronous like the other admin VMs — the controller runs it
 * off the FX thread via FxTasks. The only observable written off-thread is {@code errorMessage},
 * inside the {@code ui} dispatcher. Validation short-circuits BEFORE the API call (tests assert
 * the API was not called on invalid input). Mutations return true on success.
 */
public class VariantBuilderViewModel {

    private final VariantAdminApi api;
    private final Consumer<Runnable> ui;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public VariantBuilderViewModel(VariantAdminApi api, Consumer<Runnable> ui) {
        this.api = api;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    public List<VariantGroupAdminView> load() {
        try {
            List<VariantGroupAdminView> list = api.listAdmin();
            clearError();
            return list;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    // --- group mutations ---

    public boolean createGroup(String name) {
        String err = validateName(name);
        if (err != null) {
            setError(err);
            return false;
        }
        return run(() -> api.createGroup(name.trim()));
    }

    public boolean updateGroup(UUID groupId, String name) {
        String err = validateName(name);
        if (err != null) {
            setError(err);
            return false;
        }
        return run(() -> api.updateGroup(groupId, name.trim()));
    }

    public boolean deactivateGroup(UUID groupId) {
        return run(() -> api.deactivateGroup(groupId));
    }

    public boolean reactivateGroup(UUID groupId) {
        return run(() -> api.reactivateGroup(groupId));
    }

    // --- member mutations ---

    public boolean addMember(UUID groupId, String sku, String displayLabel) {
        String err = validateSku(sku);
        if (err != null) {
            setError(err);
            return false;
        }
        String labelErr = validateLabel(displayLabel);
        if (labelErr != null) {
            setError(labelErr);
            return false;
        }
        return run(() -> api.addMember(groupId, sku.trim(), displayLabel.trim()));
    }

    public boolean updateMember(UUID groupId, UUID memberId, String displayLabel) {
        String err = validateLabel(displayLabel);
        if (err != null) {
            setError(err);
            return false;
        }
        return run(() -> api.updateMember(groupId, memberId, displayLabel.trim()));
    }

    public boolean deactivateMember(UUID groupId, UUID memberId) {
        return run(() -> api.deactivateMember(groupId, memberId));
    }

    public boolean reactivateMember(UUID groupId, UUID memberId) {
        return run(() -> api.reactivateMember(groupId, memberId));
    }

    // --- helpers ---

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

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            return "Group name is required";
        }
        return null;
    }

    private static String validateSku(String sku) {
        if (sku == null || sku.isBlank()) {
            return "SKU is required";
        }
        return null;
    }

    private static String validateLabel(String label) {
        if (label == null || label.isBlank()) {
            return "Display label is required";
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

- [ ] **Step 6: VariantBuilderViewModelTest**

Create `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/VariantBuilderViewModelTest.java`:

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.VariantAdminApi;
import com.company.pos.terminal.api.dto.VariantGroupAdminView;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VariantBuilderViewModelTest {

    private VariantBuilderViewModel vm(VariantAdminApi api) {
        return new VariantBuilderViewModel(api, Runnable::run);
    }

    @Test
    void loadReturnsListAndClearsError() {
        VariantAdminApi api = new VariantAdminApi(null) {
            @Override public List<VariantGroupAdminView> listAdmin() {
                return List.of(new VariantGroupAdminView(UUID.randomUUID(), "Sizes", true, List.of()));
            }
        };
        VariantBuilderViewModel vm = vm(api);
        assertEquals(1, vm.load().size());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void loadReturnsNullOnApiError() {
        VariantAdminApi api = new VariantAdminApi(null) {
            @Override public List<VariantGroupAdminView> listAdmin() {
                throw new ApiException(500, new ProblemDetail("Error", 500, "boom"), "HTTP 500");
            }
        };
        assertNull(vm(api).load());
    }

    @Test
    void createGroupRejectsBlankNameWithoutCallingApi() {
        boolean[] called = {false};
        VariantAdminApi api = new VariantAdminApi(null) {
            @Override public void createGroup(String name) { called[0] = true; }
        };
        VariantBuilderViewModel vm = vm(api);
        assertFalse(vm.createGroup("  "));
        assertFalse(called[0]);
        assertEquals("Group name is required", vm.errorMessage().get());
    }

    @Test
    void addMemberRejectsBlankSkuWithoutCallingApi() {
        boolean[] called = {false};
        VariantAdminApi api = new VariantAdminApi(null) {
            @Override public void addMember(UUID g, String sku, String label) { called[0] = true; }
        };
        VariantBuilderViewModel vm = vm(api);
        assertFalse(vm.addMember(UUID.randomUUID(), "  ", "Small"));
        assertFalse(called[0]);
        assertEquals("SKU is required", vm.errorMessage().get());
    }

    @Test
    void addMemberRejectsBlankLabelWithoutCallingApi() {
        boolean[] called = {false};
        VariantAdminApi api = new VariantAdminApi(null) {
            @Override public void addMember(UUID g, String sku, String label) { called[0] = true; }
        };
        VariantBuilderViewModel vm = vm(api);
        assertFalse(vm.addMember(UUID.randomUUID(), "BEER-S", "  "));
        assertFalse(called[0]);
        assertEquals("Display label is required", vm.errorMessage().get());
    }

    @Test
    void updateMemberRejectsBlankLabelWithoutCallingApi() {
        boolean[] called = {false};
        VariantAdminApi api = new VariantAdminApi(null) {
            @Override public void updateMember(UUID g, UUID m, String label) { called[0] = true; }
        };
        VariantBuilderViewModel vm = vm(api);
        assertFalse(vm.updateMember(UUID.randomUUID(), UUID.randomUUID(), "  "));
        assertFalse(called[0]);
        assertEquals("Display label is required", vm.errorMessage().get());
    }

    @Test
    void createGroupSuccessReturnsTrue() {
        VariantAdminApi api = new VariantAdminApi(null) {
            @Override public void createGroup(String name) { /* ok */ }
        };
        assertTrue(vm(api).createGroup("Sizes"));
    }

    @Test
    void deferredDispatcherHoldsErrorUntilDrained() {
        VariantAdminApi api = new VariantAdminApi(null) {
            @Override public List<VariantGroupAdminView> listAdmin() {
                throw new ApiException(500, new ProblemDetail("Error", 500, "boom"), "HTTP 500");
            }
        };
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        VariantBuilderViewModel vm = new VariantBuilderViewModel(api, queue::add);
        assertNull(vm.load());
        assertEquals("", vm.errorMessage().get());   // deferred: not applied yet
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
        assertEquals("boom", vm.errorMessage().get());
    }
}
```

- [ ] **Step 7: Run terminal tests**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=VariantRowsTest,VariantBuilderViewModelTest`

Expected: **PASS** (4 + 8 tests).

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/dto/VariantMemberAdminView.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/VariantGroupAdminView.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/VariantGroupRequest.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/VariantMemberRequest.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/VariantMemberLabelRequest.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/VariantAdminApi.java \
        pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/VariantRows.java \
        pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/VariantBuilderViewModel.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/VariantRowsTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/VariantBuilderViewModelTest.java
git commit -m "feat(terminal): VariantAdminApi + VariantBuilderViewModel + VariantRows formatters"
```

---

### Task 5: Terminal — I/O-free dialogs (VariantGroupFormDialog, VariantMemberFormDialog)

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/VariantGroupFormDialog.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/VariantMemberFormDialog.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/view/VariantGroupFormDialogTest.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/view/VariantMemberFormDialogTest.java`

**Note:** Adding a member reuses the existing `SkuPickerDialog` (already in `pos-terminal/src/main/java/com/company/pos/terminal/view/SkuPickerDialog.java`) to pick the SKU first — no change to that file. `VariantMemberFormDialog` then collects only the display label.

**Interfaces:**
- Consumes: no HTTP, no VM imports. Pure view input collection.
- Produces (Task 6): `VariantGroupFormDialog.promptForGroup(String currentName)` → `Optional<GroupResult>` (`GroupResult(String name)`) + static `validate(String name)`; `VariantMemberFormDialog.promptForLabel(String currentLabel)` → `Optional<MemberResult>` (`MemberResult(String displayLabel)`) + static `validate(String label)`.

- [ ] **Step 1: Group form dialog**

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/VariantGroupFormDialog.java`:

```java
package com.company.pos.terminal.view;

import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Modal to create or rename a variant group. Pure view — collects name only.
 * No HTTP, no ViewModel import. Only {@link #validate} is unit-tested headlessly.
 */
public final class VariantGroupFormDialog {

    private VariantGroupFormDialog() {
    }

    public record GroupResult(String name) {
    }

    /**
     * {@code currentName} null on create; non-null on rename (pre-fills the field).
     */
    public static Optional<GroupResult> promptForGroup(String currentName) {
        boolean editing = currentName != null;
        Dialog<GroupResult> dialog = new Dialog<>();
        dialog.setTitle(editing ? "Edit variant group" : "New variant group");
        dialog.setHeaderText(editing ? "Rename \"" + currentName + "\"" : "Create a variant group");
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = new ButtonType(editing ? "Save" : "Create", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);

        TextField nameField = new TextField();
        nameField.setPromptText("name (e.g. Sizes)");
        if (editing) {
            nameField.setText(currentName);
        }

        VBox box = new VBox(12, fieldBox("Name", nameField));
        box.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(validate(nameField.getText()) != null);
        nameField.textProperty().addListener((o, a, b) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> {
            if (bt != submit || validate(nameField.getText()) != null) {
                return null;
            }
            return new GroupResult(nameField.getText().trim());
        });
        return dialog.showAndWait();
    }

    /** Null when valid; non-null message when invalid. */
    static String validate(String name) {
        if (name == null || name.isBlank()) {
            return "Group name is required";
        }
        return null;
    }

    private static VBox fieldBox(String labelText, Node control) {
        Label l = new Label(labelText);
        l.getStyleClass().add("field-label");
        VBox b = new VBox(6, l, control);
        b.getStyleClass().add("field");
        return b;
    }
}
```

- [ ] **Step 2: Member label dialog**

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/VariantMemberFormDialog.java`:

```java
package com.company.pos.terminal.view;

import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Modal to collect a display label for a variant member. Used both on add-member (after
 * {@link SkuPickerDialog} has picked the SKU) and on relabel. Pure view — no HTTP, no ViewModel.
 * Only {@link #validate} is unit-tested headlessly.
 */
public final class VariantMemberFormDialog {

    private VariantMemberFormDialog() {
    }

    public record MemberResult(String displayLabel) {
    }

    /**
     * {@code currentLabel} null on add-member; non-null on relabel (pre-fills).
     */
    public static Optional<MemberResult> promptForLabel(String currentLabel) {
        boolean editing = currentLabel != null;
        Dialog<MemberResult> dialog = new Dialog<>();
        dialog.setTitle(editing ? "Edit label" : "Member label");
        dialog.setHeaderText(editing ? "Relabel member" : "Set a display label for this member");
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = new ButtonType(editing ? "Save" : "Add", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);

        TextField labelField = new TextField();
        labelField.setPromptText("label (e.g. Small)");
        if (editing) {
            labelField.setText(currentLabel);
        }

        VBox box = new VBox(12, fieldBox("Display label", labelField));
        box.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(validate(labelField.getText()) != null);
        labelField.textProperty().addListener((o, a, b) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> {
            if (bt != submit || validate(labelField.getText()) != null) {
                return null;
            }
            return new MemberResult(labelField.getText().trim());
        });
        return dialog.showAndWait();
    }

    /** Null when valid; non-null message when invalid. */
    static String validate(String label) {
        if (label == null || label.isBlank()) {
            return "Display label is required";
        }
        return null;
    }

    private static VBox fieldBox(String labelText, Node control) {
        Label l = new Label(labelText);
        l.getStyleClass().add("field-label");
        VBox b = new VBox(6, l, control);
        b.getStyleClass().add("field");
        return b;
    }
}
```

- [ ] **Step 3: Group form dialog test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/view/VariantGroupFormDialogTest.java`:

```java
package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class VariantGroupFormDialogTest {

    @Test
    void validNameAccepted() {
        assertNull(VariantGroupFormDialog.validate("Sizes"));
        assertNull(VariantGroupFormDialog.validate("Beer sizes"));
    }

    @Test
    void blankNameRejected() {
        assertNotNull(VariantGroupFormDialog.validate("  "));
        assertNotNull(VariantGroupFormDialog.validate(null));
        assertNotNull(VariantGroupFormDialog.validate(""));
    }
}
```

- [ ] **Step 4: Member form dialog test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/view/VariantMemberFormDialogTest.java`:

```java
package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class VariantMemberFormDialogTest {

    @Test
    void validLabelAccepted() {
        assertNull(VariantMemberFormDialog.validate("Small"));
        assertNull(VariantMemberFormDialog.validate("Large (500ml)"));
    }

    @Test
    void blankLabelRejected() {
        assertNotNull(VariantMemberFormDialog.validate("  "));
        assertNotNull(VariantMemberFormDialog.validate(null));
        assertNotNull(VariantMemberFormDialog.validate(""));
    }
}
```

- [ ] **Step 5: Run dialog tests**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=VariantGroupFormDialogTest,VariantMemberFormDialogTest`

Expected: **PASS** (2 + 2 tests).

- [ ] **Step 6: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/VariantGroupFormDialog.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/VariantMemberFormDialog.java \
        pos-terminal/src/test/java/com/company/pos/terminal/view/VariantGroupFormDialogTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/view/VariantMemberFormDialogTest.java
git commit -m "feat(terminal): I/O-free variant group/member label dialogs"
```

---

### Task 6: Terminal — VariantBuilderController, FXML, Navigator, Services, admin tile wiring

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/VariantBuilderController.java`
- Create: `pos-terminal/src/main/resources/fxml/variant-builder.fxml`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/AdminController.java`
- Modify: `pos-terminal/src/main/resources/fxml/admin.fxml`

**Interfaces:**
- Consumes: `VariantBuilderViewModel` (Task 4), `VariantGroupFormDialog`/`VariantMemberFormDialog` (Task 5), `SkuPickerDialog` (existing, unchanged), `VariantRows` (Task 4), `dto.VariantGroupAdminView`/`VariantMemberAdminView`, `Services`/`Navigator`/`FxTasks` (all existing).
- The screen does NOT implement `Navigator.Screen` (no socket/timer — matches `TablesController`/`ModifierBuilderController`).

**fx:id ↔ @FXML bijection (required exact match — FXML `LoadException` if mismatched):**

| fx:id in FXML | @FXML field in controller | Type |
|---|---|---|
| `errorLabel` | `errorLabel` | `Label` |
| `searchField` | `searchField` | `TextField` |
| `backButton` | `backButton` | `Button` |
| `groupsTable` | `groupsTable` | `TableView<VariantGroupAdminView>` |
| `gNameCol` | `gNameCol` | `TableColumn<VariantGroupAdminView, String>` |
| `gMemberCol` | `gMemberCol` | `TableColumn<VariantGroupAdminView, String>` |
| `gStatusCol` | `gStatusCol` | `TableColumn<VariantGroupAdminView, String>` |
| `newGroupButton` | `newGroupButton` | `Button` |
| `editGroupButton` | `editGroupButton` | `Button` |
| `deactivateGroupButton` | `deactivateGroupButton` | `Button` |
| `reactivateGroupButton` | `reactivateGroupButton` | `Button` |
| `detailTitle` | `detailTitle` | `Label` |
| `membersTable` | `membersTable` | `TableView<VariantMemberAdminView>` |
| `mSkuCol` | `mSkuCol` | `TableColumn<VariantMemberAdminView, String>` |
| `mLabelCol` | `mLabelCol` | `TableColumn<VariantMemberAdminView, String>` |
| `mStatusCol` | `mStatusCol` | `TableColumn<VariantMemberAdminView, String>` |
| `addMemberButton` | `addMemberButton` | `Button` |
| `editMemberButton` | `editMemberButton` | `Button` |
| `deactivateMemberButton` | `deactivateMemberButton` | `Button` |
| `reactivateMemberButton` | `reactivateMemberButton` | `Button` |

Total: 20 fx:id ↔ @FXML pairs — every entry in this table must appear in both the FXML and the controller.

- [ ] **Step 1: Register VariantAdminApi in Services**

In `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`:

Add import (after `MenuAdminApi` import):
```java
import com.company.pos.terminal.api.VariantAdminApi;
```

Add field (after `menuAdminApi`):
```java
    public final VariantAdminApi variantAdminApi;
```

Initialize in the constructor body (after `this.menuAdminApi = new MenuAdminApi(apiClient);`):
```java
        this.variantAdminApi = new VariantAdminApi(apiClient);
```

`Services` is a `final` class — the existing field declaration order and constructor body are preserved; only these three additions are made.

- [ ] **Step 2: Add Navigator.toVariants()**

In `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java`, add after `toMenu()`:

```java
    public void toVariants() {
        com.company.pos.terminal.view.VariantBuilderController controller =
                new com.company.pos.terminal.view.VariantBuilderController(services, this);
        setScene("/fxml/variant-builder.fxml", controller);
    }
```

- [ ] **Step 3: Create VariantBuilderController**

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/VariantBuilderController.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.VariantGroupAdminView;
import com.company.pos.terminal.api.dto.VariantMemberAdminView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.VariantBuilderViewModel;
import com.company.pos.terminal.viewmodel.VariantRows;
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
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;

/**
 * MANAGER/ADMIN variant builder. Master/detail: groups table (master) → members table (detail).
 * Selecting a group renders its members from the already-loaded admin view — no per-selection fetch.
 * All I/O runs in FxTasks work lambdas; each mutation's onDone re-kicks reload(); dialogs are I/O-free.
 * Adding a member first uses SkuPickerDialog (re-uses existing), then VariantMemberFormDialog for label.
 * Does NOT implement Navigator.Screen (no socket/timer).
 */
public class VariantBuilderController {

    private static final System.Logger LOG = System.getLogger(VariantBuilderController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final VariantBuilderViewModel vm;

    private List<VariantGroupAdminView> allGroups = new ArrayList<>();

    @FXML private Label errorLabel;
    @FXML private TextField searchField;
    @FXML private Button backButton;
    @FXML private TableView<VariantGroupAdminView> groupsTable;
    @FXML private TableColumn<VariantGroupAdminView, String> gNameCol;
    @FXML private TableColumn<VariantGroupAdminView, String> gMemberCol;
    @FXML private TableColumn<VariantGroupAdminView, String> gStatusCol;
    @FXML private Button newGroupButton;
    @FXML private Button editGroupButton;
    @FXML private Button deactivateGroupButton;
    @FXML private Button reactivateGroupButton;
    @FXML private Label detailTitle;
    @FXML private TableView<VariantMemberAdminView> membersTable;
    @FXML private TableColumn<VariantMemberAdminView, String> mSkuCol;
    @FXML private TableColumn<VariantMemberAdminView, String> mLabelCol;
    @FXML private TableColumn<VariantMemberAdminView, String> mStatusCol;
    @FXML private Button addMemberButton;
    @FXML private Button editMemberButton;
    @FXML private Button deactivateMemberButton;
    @FXML private Button reactivateMemberButton;

    public VariantBuilderController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new VariantBuilderViewModel(services.variantAdminApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        // Groups table columns
        gNameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        gMemberCol.setCellValueFactory(c -> new SimpleStringProperty(
                String.valueOf(c.getValue().members().size())));
        gStatusCol.setCellValueFactory(c -> new SimpleStringProperty(
                VariantRows.statusLabel(c.getValue().active())));

        // Members table columns
        mSkuCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().sku()));
        mLabelCol.setCellValueFactory(c -> new SimpleStringProperty(
                VariantRows.memberLabel(c.getValue().sku(), c.getValue().displayLabel())));
        mStatusCol.setCellValueFactory(c -> new SimpleStringProperty(
                VariantRows.statusLabel(c.getValue().active())));

        errorLabel.textProperty().bind(vm.errorMessage());
        searchField.textProperty().addListener((o, a, b) -> applyFilter());
        groupsTable.getSelectionModel().selectedItemProperty()
                .addListener((o, a, sel) -> renderDetail(sel));
        membersTable.getSelectionModel().selectedItemProperty()
                .addListener((o, a, sel) -> refreshMemberButtons(sel));

        backButton.setOnAction(e -> navigator.toAdmin());
        newGroupButton.setOnAction(e -> newGroup());
        editGroupButton.setOnAction(e -> editGroup());
        deactivateGroupButton.setOnAction(e -> groupActive(false));
        reactivateGroupButton.setOnAction(e -> groupActive(true));
        addMemberButton.setOnAction(e -> addMember());
        editMemberButton.setOnAction(e -> editMember());
        deactivateMemberButton.setOnAction(e -> memberActive(false));
        reactivateMemberButton.setOnAction(e -> memberActive(true));

        renderDetail(null);
        reload();
    }

    @SuppressWarnings("unchecked")
    private void reload() {
        UUID keep = selectedGroupId();
        final List<VariantGroupAdminView>[] gh = new List[1];
        FxTasks.run(
                () -> gh[0] = vm.load(),
                () -> {
                    if (gh[0] != null) {
                        allGroups = gh[0];
                    }
                    applyFilter();
                    reselect(keep);
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Load variant groups failed", err));
    }

    private void applyFilter() {
        String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        List<VariantGroupAdminView> shown = allGroups.stream()
                .filter(g -> q.isEmpty() || g.name().toLowerCase().contains(q))
                .toList();
        groupsTable.setItems(FXCollections.observableArrayList(shown));
        refreshGroupButtons(selectedGroup());
    }

    private void reselect(UUID id) {
        if (id == null) {
            return;
        }
        for (VariantGroupAdminView g : groupsTable.getItems()) {
            if (g.id().equals(id)) {
                groupsTable.getSelectionModel().select(g);
                return;
            }
        }
    }

    private VariantGroupAdminView selectedGroup() {
        return groupsTable.getSelectionModel().getSelectedItem();
    }

    private UUID selectedGroupId() {
        VariantGroupAdminView g = selectedGroup();
        return g == null ? null : g.id();
    }

    private void renderDetail(VariantGroupAdminView g) {
        refreshGroupButtons(g);
        if (g == null) {
            detailTitle.setText("Select a group");
            membersTable.setItems(FXCollections.observableArrayList());
        } else {
            detailTitle.setText(g.name());
            membersTable.setItems(FXCollections.observableArrayList(g.members()));
        }
        refreshMemberButtons(null);
        boolean hasGroup = g != null;
        addMemberButton.setDisable(!hasGroup);
    }

    private void refreshGroupButtons(VariantGroupAdminView g) {
        editGroupButton.setDisable(g == null);
        deactivateGroupButton.setDisable(g == null || !g.active());
        reactivateGroupButton.setDisable(g == null || g.active());
    }

    private void refreshMemberButtons(VariantMemberAdminView m) {
        editMemberButton.setDisable(m == null);
        deactivateMemberButton.setDisable(m == null || !m.active());
        reactivateMemberButton.setDisable(m == null || m.active());
    }

    // --- group actions ---

    private void newGroup() {
        Optional<VariantGroupFormDialog.GroupResult> r = VariantGroupFormDialog.promptForGroup(null);
        r.ifPresent(res -> kick(() -> vm.createGroup(res.name())));
    }

    private void editGroup() {
        VariantGroupAdminView g = selectedGroup();
        if (g == null) {
            return;
        }
        Optional<VariantGroupFormDialog.GroupResult> r =
                VariantGroupFormDialog.promptForGroup(g.name());
        r.ifPresent(res -> kick(() -> vm.updateGroup(g.id(), res.name())));
    }

    private void groupActive(boolean active) {
        VariantGroupAdminView g = selectedGroup();
        if (g == null) {
            return;
        }
        kick(() -> active ? vm.reactivateGroup(g.id()) : vm.deactivateGroup(g.id()));
    }

    // --- member actions ---

    private void addMember() {
        VariantGroupAdminView g = selectedGroup();
        if (g == null) {
            return;
        }
        // Step 1: pick the SKU via existing SkuPickerDialog (loads product list off-thread first)
        // The controller loads products together with groups during reload() — but SkuPickerDialog
        // needs the current product list. Load it inline via FxTasks so we don't block FX thread.
        // Pattern: kick a load task, in onDone open the dialog chain.
        final com.company.pos.terminal.api.dto.ProductView[][] ph =
                new com.company.pos.terminal.api.dto.ProductView[1][];
        FxTasks.run(
                () -> {
                    List<com.company.pos.terminal.api.dto.ProductView> list =
                            services.productApi.list();
                    ph[0] = list != null
                            ? list.toArray(new com.company.pos.terminal.api.dto.ProductView[0])
                            : new com.company.pos.terminal.api.dto.ProductView[0];
                },
                () -> {
                    Optional<String> sku = SkuPickerDialog.pickSku(
                            java.util.Arrays.asList(ph[0]));
                    sku.ifPresent(s -> {
                        Optional<VariantMemberFormDialog.MemberResult> labelResult =
                                VariantMemberFormDialog.promptForLabel(null);
                        labelResult.ifPresent(lr ->
                                kick(() -> vm.addMember(g.id(), s, lr.displayLabel())));
                    });
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Load products for SKU picker failed", err));
    }

    private void editMember() {
        VariantGroupAdminView g = selectedGroup();
        VariantMemberAdminView m = membersTable.getSelectionModel().getSelectedItem();
        if (g == null || m == null) {
            return;
        }
        Optional<VariantMemberFormDialog.MemberResult> r =
                VariantMemberFormDialog.promptForLabel(m.displayLabel());
        r.ifPresent(res -> kick(() -> vm.updateMember(g.id(), m.id(), res.displayLabel())));
    }

    private void memberActive(boolean active) {
        VariantGroupAdminView g = selectedGroup();
        VariantMemberAdminView m = membersTable.getSelectionModel().getSelectedItem();
        if (g == null || m == null) {
            return;
        }
        kick(() -> active ? vm.reactivateMember(g.id(), m.id()) : vm.deactivateMember(g.id(), m.id()));
    }

    /** Run a boolean-returning VM mutation off-thread; reload on success. */
    private void kick(java.util.function.BooleanSupplier work) {
        final boolean[] holder = {false};
        FxTasks.run(() -> holder[0] = work.getAsBoolean(),
                () -> { if (holder[0]) reload(); },
                err -> LOG.log(System.Logger.Level.ERROR, "Variant mutation failed", err));
    }
}
```

> **FX-threading note:** `addMember` is the one action that needs a fresh product list. It uses a nested `FxTasks.run` to load products off-thread, then opens the dialog chain in `onDone` (FX thread). `showAndWait()` is called in `onDone` (allowed — it's UI, not I/O). The inner `kick(...)` is then called in `onDone` of the label dialog's result — a chain of two sequential FxTasks. No blocking VM/HTTP call is made inside any `onDone`.

- [ ] **Step 4: Create variant-builder.fxml**

Create `pos-terminal/src/main/resources/fxml/variant-builder.fxml` (all 20 fx:ids must match the controller table above exactly):

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.scene.control.Button?>
<?import javafx.scene.control.Label?>
<?import javafx.scene.control.TableColumn?>
<?import javafx.scene.control.TableView?>
<?import javafx.scene.control.TextField?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.layout.Pane?>
<?import javafx.scene.layout.VBox?>

<VBox styleClass="screen" spacing="16" xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml">
  <HBox spacing="16" alignment="CENTER_LEFT">
    <Label text="Variant builder" styleClass="title"/>
    <Pane HBox.hgrow="ALWAYS"/>
    <Label fx:id="errorLabel" styleClass="error-text"/>
    <TextField fx:id="searchField" promptText="Search group"/>
    <Button fx:id="backButton" text="Back" styleClass="btn-secondary"/>
  </HBox>

  <TableView fx:id="groupsTable" VBox.vgrow="ALWAYS">
    <columns>
      <TableColumn fx:id="gNameCol" text="Group" prefWidth="260"/>
      <TableColumn fx:id="gMemberCol" text="Members" prefWidth="100"/>
      <TableColumn fx:id="gStatusCol" text="Status" prefWidth="120"/>
    </columns>
  </TableView>
  <HBox spacing="12" alignment="CENTER_LEFT">
    <Button fx:id="newGroupButton" text="New group" styleClass="btn-primary"/>
    <Button fx:id="editGroupButton" text="Rename"/>
    <Button fx:id="deactivateGroupButton" text="Deactivate"/>
    <Button fx:id="reactivateGroupButton" text="Reactivate"/>
  </HBox>

  <Label fx:id="detailTitle" text="Select a group" styleClass="subtitle"/>

  <TableView fx:id="membersTable" VBox.vgrow="ALWAYS">
    <columns>
      <TableColumn fx:id="mSkuCol" text="SKU" prefWidth="160"/>
      <TableColumn fx:id="mLabelCol" text="Label" prefWidth="240"/>
      <TableColumn fx:id="mStatusCol" text="Status" prefWidth="120"/>
    </columns>
  </TableView>
  <HBox spacing="12" alignment="CENTER_LEFT">
    <Button fx:id="addMemberButton" text="Add member" styleClass="btn-primary"/>
    <Button fx:id="editMemberButton" text="Relabel"/>
    <Button fx:id="deactivateMemberButton" text="Deactivate"/>
    <Button fx:id="reactivateMemberButton" text="Reactivate"/>
  </HBox>
</VBox>
```

- [ ] **Step 5: Add Variants tile to AdminController**

In `pos-terminal/src/main/java/com/company/pos/terminal/view/AdminController.java`:

Add field (after `menuButton`):

```java
    @FXML private Button variantsButton;
```

Add to `initialize()` after the `menuButton` block:

```java
        variantsButton.setVisible(manager);
        variantsButton.setManaged(manager);
        variantsButton.setOnAction(e -> navigator.toVariants());
```

- [ ] **Step 6: Relabel modifier tile + add Variants tile in admin.fxml**

In `pos-terminal/src/main/resources/fxml/admin.fxml`:

Change the existing modifier tile text from "Menu" to "Modifiers":

Old line:
```xml
      <Button fx:id="menuButton" text="Menu" styleClass="home-tile"/>
```

New line:
```xml
      <Button fx:id="menuButton" text="Modifiers" styleClass="home-tile"/>
```

Then add the Variants tile immediately after:
```xml
      <Button fx:id="variantsButton" text="Variants" styleClass="home-tile"/>
```

The full updated `admin.fxml` tiles HBox after the edit:

```xml
    <HBox spacing="32" alignment="CENTER">
      <Button fx:id="staffButton" text="Staff" styleClass="home-tile"/>
      <Button fx:id="productsButton" text="Products" styleClass="home-tile"/>
      <Button fx:id="kitchenButton" text="Kitchen" styleClass="home-tile"/>
      <Button fx:id="tablesButton" text="Tables" styleClass="home-tile"/>
      <Button fx:id="settingsButton" text="Settings" styleClass="home-tile"/>
      <Button fx:id="menuButton" text="Modifiers" styleClass="home-tile"/>
      <Button fx:id="variantsButton" text="Variants" styleClass="home-tile"/>
    </HBox>
```

- [ ] **Step 7: Run the full terminal suite**

Run: `./mvnw -f pos-terminal/pom.xml clean test`

Expected: **PASS** — full suite green including all new variant tests. Note: `QuoteApiTest` StubServer-403 is a known unrelated flake; if it is the only failure, rerun in isolation (`./mvnw -f pos-terminal/pom.xml test -Dtest=QuoteApiTest`) to confirm.

- [ ] **Step 8: Run backend ModularityTests as a final sanity check**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=ModularityTests`

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/VariantBuilderController.java \
        pos-terminal/src/main/resources/fxml/variant-builder.fxml \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/AdminController.java \
        pos-terminal/src/main/resources/fxml/admin.fxml
git commit -m "feat(terminal): variant builder screen + Variants tile (MANAGER/ADMIN); relabel modifier tile to Modifiers"
```

---

## Self-Review

**Spec coverage checklist:**

| Spec item | Covered in | Status |
|---|---|---|
| V35 migration `variant_member.active` | T1 Step 1 | ✓ |
| `VariantGroup.rename(String)` mutator | T1 Step 2 | ✓ |
| `VariantMember.active` field + `isActive/relabel/deactivate/reactivate` | T1 Step 3 | ✓ |
| SKU immutable (no setter) | T1 Step 3 — no setter added | ✓ |
| `UpdateVariantGroupCommand(name)` + `UpdateVariantMemberCommand(displayLabel)` | T1 Step 4 | ✓ |
| `VariantGroupAdminView(id,name,active,members)` + `VariantMemberAdminView(id,sku,displayLabel,active)` | T1 Step 4 | ✓ |
| 8 new `MenuChangeType` values | T1 Step 5 | ✓ |
| 5 new `MenuService` methods | T1 Step 6 | ✓ |
| Retro-audit existing 3 variant writes | T1 Step 7b/7c/7d | ✓ |
| Dup-active-SKU guard in `addVariantMember` | T1 Step 7c | ✓ |
| `listVariantGroups()` filters active members | T1 Step 7e + toVariantView | ✓ |
| `loadMember` helper verifies belongs-to-group | T1 Step 7g | ✓ |
| `listVariantGroupsAdmin()` returns all (no filter) | T1 Step 7f | ✓ |
| `VariantAdminServiceTest` 13 cases incl. group-reactivate + member-reactivate | T1 Step 8 | ✓ |
| 6 new gated controller endpoints | T2 Step 1 | ✓ |
| 12 controller tests (6 happy + 6 cashier-403) | T2 Step 2 | ✓ |
| `ModularityTests` after controller change | T2 Step 4 | ✓ |
| 8 new `AuditAction` constants | T3 Step 1 | ✓ |
| Exhaustive switch extension (no `default`) | T3 Step 2 | ✓ |
| `audit/package-info.java` unchanged | T3 — no edit | ✓ |
| `MenuChangedVariantAuditTest` E2E (all 8 types, Awaitility, genuinely async) | T3 Step 3 | ✓ |
| `VariantAdminApi` non-final, 8 methods, void→null TypeReference | T4 Step 2 | ✓ |
| `VariantMemberLabelRequest` for relabel (label-only) | T4 Step 1 | ✓ |
| `VariantRows.memberLabel` = "sku — displayLabel"; `statusLabel` | T4 Step 3 | ✓ |
| `VariantBuilderViewModel` sync, mutations→boolean, validation before API | T4 Step 5 | ✓ |
| Async-dispatcher regression test (deferred `ArrayDeque::add`) | T4 Step 6 | ✓ |
| Validation-not-calling-API asserts | T4 Step 6 | ✓ |
| `VariantGroupFormDialog` (name-only; static validate) | T5 Step 1 | ✓ |
| `VariantMemberFormDialog` (label-only; static validate) | T5 Step 2 | ✓ |
| `SkuPickerDialog` reused as-is | T6 Step 3 (addMember) | ✓ |
| Static dialog tests | T5 Steps 3-4 | ✓ |
| `VariantBuilderController` master/detail, detail-from-cache, no per-selection fetch | T6 Step 3 | ✓ |
| `kick(BooleanSupplier)` re-kicks fresh FxTasks per mutation | T6 Step 3 | ✓ |
| No blocking VM/HTTP call in any `onDone` | T6 Step 3 — only navigation/dialogs/holder reads in onDone | ✓ |
| `variant-builder.fxml` 20 fx:ids exactly matching controller | T6 Steps 3-4 | ✓ |
| `Navigator.toVariants()` | T6 Step 2 | ✓ |
| `Services.variantAdminApi` | T6 Step 1 | ✓ |
| Variants tile gated on `manager` boolean | T6 Steps 5-6 | ✓ |
| Modifier tile relabelled "Menu" → "Modifiers" | T6 Step 6 | ✓ |
| Screen does NOT implement `Navigator.Screen` | T6 Step 3 (class signature) | ✓ |
| Full terminal suite green | T6 Step 7 | ✓ |
| `ModularityTests` green as final backend check | T6 Step 8 | ✓ |

**Placeholder scan:** None — every step carries full verbatim code or a precise, unique edit target. No "similar to X", no "TBD", no unresolved references.

**Type consistency check:**

- `MenuChanged(entityRef, type, actor, detail, oldPrice, newPrice)` — produced in T1 `DefaultMenuService` variant methods (oldPrice/newPrice null); consumed in T3 `MenuChangedAuditListener` switch. Record signature unchanged.
- `MenuChangeType` — 18 values total (10 modifier + 8 variant); switch in `MenuChangedAuditListener` is exhaustive (no `default`) so the compiler catches any mismatch.
- `VariantGroupAdminView(id,name,active,members)` server record (T1) → mirrored by terminal `dto.VariantGroupAdminView(id,name,active,members)` (T4 Step 1). Component order matches.
- `VariantMemberAdminView(id,sku,displayLabel,active)` server record (T1) → mirrored by terminal `dto.VariantMemberAdminView(id,sku,displayLabel,active)` (T4 Step 1). Component order matches.
- `VariantBuilderViewModel` boolean mutations consumed by `kick(BooleanSupplier)` in controller (T6). Return type: `boolean`.
- `VariantGroupFormDialog.GroupResult(name)` — produced T5 Step 1, consumed T6 Step 3 (`newGroup`, `editGroup`).
- `VariantMemberFormDialog.MemberResult(displayLabel)` — produced T5 Step 2, consumed T6 Step 3 (`addMember`, `editMember`).
- `VariantAdminApi` method signatures match `VariantBuilderViewModel` call sites exactly (`createGroup(name)`, `updateGroup(groupId, name)`, `deactivateGroup(groupId)`, `reactivateGroup(groupId)`, `addMember(groupId, sku, displayLabel)`, `updateMember(groupId, memberId, displayLabel)`, `deactivateMember(groupId, memberId)`, `reactivateMember(groupId, memberId)`).
- `AuditAction` enum: 8 new constants added (T3 Step 1), all 8 referenced in the switch (T3 Step 2). No orphan constants.
- `Services.variantAdminApi` type `VariantAdminApi` — used in `VariantBuilderController` constructor (T6 Step 3). Field is `public final`, matching the existing Services pattern.

**Notes:**
1. `VariantMemberRepository.findById(UUID)` is inherited from `JpaRepository<VariantMember, UUID>` — no new finder needed.
2. `VariantMemberLabelRequest(displayLabel)` is a third request record in T4 (separate from `VariantMemberRequest(sku, displayLabel)`) — relabel sends only the label, not the SKU.
3. The `addMember` flow in `VariantBuilderController` uses a nested `FxTasks.run` to load products before opening the dialog — a safe pattern since the inner `showAndWait()` is called in `onDone` (FX thread), and the resulting `kick(...)` spawns a new task rather than calling a VM method inline in `onDone`.
4. The QuoteApiTest StubServer-403 flake is known and unrelated to this slice; treat it as a rerun-to-confirm case, not a real failure.

