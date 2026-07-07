package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
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
class SalesServiceChargeTest {

    @Autowired SalesService sales;
    @Autowired CartService carts;
    @Autowired ConfigurationService config;
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
        config.put(SettingKey.SERVICE_CHARGE_ENABLED, "true");
        config.put(SettingKey.SERVICE_CHARGE_PERCENT, "10");
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private UUID cartWithTwoBurgers() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "BURGER", new BigDecimal("2")); // net 60.00
        return cart;
    }

    @Test
    void appliesTaxableServiceChargeWhenFlagSet() {
        // net 60.00; service charge 10% = 6.00 (net); VAT 15% on (60 + 6):
        // productTax 9.00, scTax 0.90 -> taxTotal 9.90; grand 60 + 6 + 9.90 = 75.90
        UUID cart = cartWithTwoBurgers();
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, new BigDecimal("75.90"),
                        new BigDecimal("75.90"))), java.util.Map.of(), null, true), "cashier");

        assertThat(sale.subtotal()).isEqualByComparingTo("60.00");
        assertThat(sale.serviceChargeAmount()).isEqualByComparingTo("6.00");
        assertThat(sale.taxTotal()).isEqualByComparingTo("9.90");
        assertThat(sale.grandTotal()).isEqualByComparingTo("75.90");
    }

    @Test
    void noChargeWhenFlagUnset() {
        UUID cart = cartWithTwoBurgers();
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, new BigDecimal("69.00"),
                        new BigDecimal("69.00")))), "cashier"); // 4-arg-less -> applyServiceCharge false

        assertThat(sale.serviceChargeAmount()).isEqualByComparingTo("0.00");
        assertThat(sale.grandTotal()).isEqualByComparingTo("69.00");
    }

    @Test
    void noChargeWhenConfigDisabledEvenIfFlagSet() {
        config.put(SettingKey.SERVICE_CHARGE_ENABLED, "false");
        UUID cart = cartWithTwoBurgers();
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, new BigDecimal("69.00"),
                        new BigDecimal("69.00"))), java.util.Map.of(), null, true), "cashier");

        assertThat(sale.serviceChargeAmount()).isEqualByComparingTo("0.00");
        assertThat(sale.grandTotal()).isEqualByComparingTo("69.00");
    }

    @Test
    void quoteWithChargeMatchesCheckoutGrandTotal() {
        UUID cart = cartWithTwoBurgers();
        QuoteView q = sales.quote(cart, true);
        assertThat(q.serviceChargeAmount()).isEqualByComparingTo("6.00");
        assertThat(q.grandTotal()).isEqualByComparingTo("75.90");
    }
}
