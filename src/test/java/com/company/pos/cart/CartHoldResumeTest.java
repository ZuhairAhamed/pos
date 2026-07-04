package com.company.pos.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.cart.api.CartService;
import com.company.pos.cart.api.CartView;
import com.company.pos.common.exception.DomainException;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
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
class CartHoldResumeTest {

    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("CHIP", "Chips", "SNK", "Snacks", "bcCHIP",
                "EA", new BigDecimal("3.00"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("MILK", "Milk Carton", "DRY", "Dairy", "bcMILK",
                "EA", new BigDecimal("6.00"), "SAR", 1, true));
        productSync.sync();
    }

    @Test
    void holdThenResumeRoundTrips() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));

        assertThat(carts.hold(cart).status()).isEqualTo("HELD");
        assertThat(carts.getCart(cart).status()).isEqualTo("HELD");
        assertThat(carts.resume(cart).status()).isEqualTo("OPEN");
    }

    @Test
    void cannotAddToHeldCart() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("1"));
        carts.hold(cart);
        assertThatThrownBy(() -> carts.addLine(cart, "COLA", new BigDecimal("1")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void resumingAnOpenCartIsRejected() {
        UUID cart = carts.createCart();
        assertThatThrownBy(() -> carts.resume(cart)).isInstanceOf(DomainException.class);
    }

    @Test
    void listHeldReturnsHeldCartsForTerminal() {
        UUID held = carts.createCart();
        carts.addLine(held, "COLA", new BigDecimal("1"));
        carts.hold(held);
        UUID open = carts.createCart();
        carts.addLine(open, "COLA", new BigDecimal("1"));

        // createCart() stamps the configured terminal id (default T01)
        assertThat(carts.listHeld("T01")).extracting(CartView::cartId).contains(held).doesNotContain(open);
        // terminal isolation: the held cart must NOT appear for a different terminal
        assertThat(carts.listHeld("T99")).extracting(CartView::cartId).doesNotContain(held);
    }

    @Test
    void voidLineRemovesItAndRenumbersRemaining() {
        // Three-line cart: add CHIP (line 1), COLA (line 2), MILK (line 3) in insertion order.
        // Remove the first line (CHIP) to exercise the renumber path across multiple survivors.
        // Note: lineNo renumbering is internal and not surfaced on CartLineView (lineId, sku,
        // name, quantity, unitPrice, currencyCode), so we use sku ordering as the observable
        // proxy that removal + reindex keep ordering coherent.
        UUID cart = carts.createCart();
        CartView afterChip = carts.addLine(cart, "CHIP", new BigDecimal("1"));
        carts.addLine(cart, "COLA", new BigDecimal("1"));
        carts.addLine(cart, "MILK", new BigDecimal("1"));
        UUID chipLineId = afterChip.lines().get(0).lineId();

        CartView view = carts.removeLine(cart, chipLineId);
        assertThat(view.lines()).hasSize(2);
        assertThat(view.lines()).extracting(l -> l.sku())
                .containsExactly("COLA", "MILK");
    }

    @Test
    void voidingAMissingLineIsRejected() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("1"));
        assertThatThrownBy(() -> carts.removeLine(cart, UUID.randomUUID()))
                .isInstanceOf(DomainException.class);
    }
}
