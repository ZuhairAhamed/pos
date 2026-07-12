package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.cart.api.CartService;
import com.company.pos.common.exception.DomainException;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.DiscountType;
import com.company.pos.sales.api.QuoteView;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The discount-aware quote overload: a pure calculator that prices any discount (no cashier
 * cap — checkout is the enforcement point) but still validates reason codes.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class SalesQuoteDiscountTest {

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

    private UUID cartWithTwoBurgers() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "BURGER", new BigDecimal("2")); // 60.00 net, 15% VAT exclusive
        return cart;
    }

    @Test
    void percentDiscountQuotesDiscountedTotals() {
        UUID cart = cartWithTwoBurgers();
        QuoteView q = sales.quote(cart, Map.of(),
                new DiscountInput(DiscountType.PERCENT, new BigDecimal("10"), "LOYALTY"), false);
        assertThat(q.discountTotal()).isEqualByComparingTo("6.00");
        assertThat(q.taxTotal()).isEqualByComparingTo("8.10");
        assertThat(q.grandTotal()).isEqualByComparingTo("62.10");
        assertThat(carts.getCart(cart).status()).isEqualTo("OPEN"); // quote commits nothing
    }

    @Test
    void amountDiscountQuotesDiscountedTotals() {
        UUID cart = cartWithTwoBurgers();
        QuoteView q = sales.quote(cart, Map.of(),
                new DiscountInput(DiscountType.AMOUNT, new BigDecimal("5.00"), "PRICE_MATCH"), false);
        assertThat(q.discountTotal()).isEqualByComparingTo("5.00");
        assertThat(q.grandTotal()).isEqualByComparingTo("63.25");
    }

    @Test
    void overCapDiscountStillQuotes() {
        // 50% is far over the 10%/20.00 cashier caps — quote is a pure calculator and prices it.
        UUID cart = cartWithTwoBurgers();
        QuoteView q = sales.quote(cart, Map.of(),
                new DiscountInput(DiscountType.PERCENT, new BigDecimal("50"), "MANAGER_COMP"), false);
        assertThat(q.discountTotal()).isEqualByComparingTo("30.00");
        assertThat(q.grandTotal()).isEqualByComparingTo("34.50");
    }

    @Test
    void unknownReasonCodeRejectedAtQuoteTime() {
        UUID cart = cartWithTwoBurgers();
        assertThatThrownBy(() -> sales.quote(cart, Map.of(),
                new DiscountInput(DiscountType.PERCENT, new BigDecimal("10"), "NOT_A_CODE"), false))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("reason code");
    }

    @Test
    void quoteWithDiscountMatchesManagerCheckoutTotals() {
        UUID cart = cartWithTwoBurgers();
        DiscountInput d = new DiscountInput(DiscountType.PERCENT, new BigDecimal("10"), "LOYALTY");
        QuoteView q = sales.quote(cart, Map.of(), d, false);
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, q.grandTotal(), q.grandTotal())),
                Map.of(), d, false), "manager1", true);
        assertThat(q.grandTotal()).isEqualByComparingTo(sale.grandTotal());
        assertThat(q.discountTotal()).isEqualByComparingTo(sale.discountTotal());
        assertThat(q.taxTotal()).isEqualByComparingTo(sale.taxTotal());
    }
}
