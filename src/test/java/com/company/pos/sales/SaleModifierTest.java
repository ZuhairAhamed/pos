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
