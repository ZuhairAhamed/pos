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
