package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.QuoteView;
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
class SalesQuoteTest {

    @Autowired SalesService sales;
    @Autowired CartService carts;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    @Test
    void quoteReturnsPricedTotalsWithoutCreatingASale() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "BURGER", new BigDecimal("2")); // 2 × 30.00, VAT 15% exclusive

        QuoteView q = sales.quote(cart);

        assertThat(q.subtotal()).isEqualByComparingTo("60.00");
        assertThat(q.taxTotal()).isEqualByComparingTo("9.00");
        assertThat(q.grandTotal()).isEqualByComparingTo("69.00");
        // the cart is left OPEN — quote neither pays nor closes it
        assertThat(carts.getCart(cart).status()).isEqualTo("OPEN");
    }

    @Test
    void quoteMatchesTheTotalsCheckoutProduces() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "BURGER", new BigDecimal("2"));

        QuoteView q = sales.quote(cart);
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, new BigDecimal("69.00"),
                        new BigDecimal("69.00")))), "cashier");

        assertThat(q.grandTotal()).isEqualByComparingTo(sale.grandTotal());
        assertThat(q.subtotal()).isEqualByComparingTo(sale.subtotal());
        assertThat(q.taxTotal()).isEqualByComparingTo(sale.taxTotal());
    }
}
