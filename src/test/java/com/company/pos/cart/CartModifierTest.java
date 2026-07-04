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
