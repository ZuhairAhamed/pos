package com.company.pos.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.cart.api.CartService;
import com.company.pos.cart.api.CartView;
import com.company.pos.common.exception.DomainException;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.product.api.ProductSync;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class CartServiceTest {

    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;

    @BeforeEach
    void seedCatalogue() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
    }

    @Test
    void addLineSnapshotsCataloguePrice() {
        UUID cart = carts.createCart();
        CartView view = carts.addLine(cart, "COLA", new BigDecimal("2"));

        assertThat(view.status()).isEqualTo("OPEN");
        assertThat(view.lines()).hasSize(1);
        assertThat(view.lines().get(0).name()).isEqualTo("Cola Can");
        assertThat(view.lines().get(0).unitPrice()).isEqualByComparingTo("4.50");
        assertThat(view.lines().get(0).quantity()).isEqualByComparingTo("2");
        assertThat(view.currencyCode()).isEqualTo("SAR");
    }

    @Test
    void addingSameSkuMergesQuantity() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        CartView view = carts.addLine(cart, "COLA", new BigDecimal("3"));

        assertThat(view.lines()).hasSize(1);
        assertThat(view.lines().get(0).quantity()).isEqualByComparingTo("5");
    }

    @Test
    void unknownSkuIsRejected() {
        UUID cart = carts.createCart();
        assertThatThrownBy(() -> carts.addLine(cart, "NOPE", BigDecimal.ONE))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void nonPositiveQuantityIsRejected() {
        UUID cart = carts.createCart();
        assertThatThrownBy(() -> carts.addLine(cart, "COLA", BigDecimal.ZERO))
                .isInstanceOf(DomainException.class);
    }

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
}
