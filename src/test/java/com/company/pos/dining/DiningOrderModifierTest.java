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
