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

    @Test
    void deactivateThenReactivateGroupFlipsAndPublishes() {
        ModifierGroupView g = menu.createModifierGroup(new CreateModifierGroupCommand("Add-ons", 0, 2));
        menu.deactivateModifierGroup(g.id());
        assertThat(activeOfGroup(g.id())).isFalse();
        menu.reactivateModifierGroup(g.id());
        assertThat(activeOfGroup(g.id())).isTrue();
        assertThat(typesFor(g.id().toString()))
                .contains(MenuChangeType.GROUP_DEACTIVATED, MenuChangeType.GROUP_REACTIVATED);
    }

    private boolean activeOf(UUID groupId, UUID optionId) {
        return menu.listModifierGroups().stream()
                .filter(v -> v.id().equals(groupId)).findFirst().orElseThrow()
                .options().stream().filter(o -> o.id().equals(optionId)).findFirst().orElseThrow()
                .active();
    }

    private boolean activeOfGroup(UUID groupId) {
        return menu.listModifierGroups().stream()
                .filter(v -> v.id().equals(groupId)).findFirst().orElseThrow()
                .active();
    }

    private List<MenuChangeType> typesFor(String entityRef) {
        return events.stream(MenuChanged.class)
                .filter(e -> e.entityRef().equals(entityRef))
                .map(MenuChanged::type)
                .toList();
    }
}
