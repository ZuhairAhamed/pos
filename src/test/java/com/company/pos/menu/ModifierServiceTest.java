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
