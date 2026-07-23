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
