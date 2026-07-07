package com.company.pos.sales;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.cart.api.CartService;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Guard test: retail POST /sales must never apply a service charge even when
 * the request body contains {@code "applyServiceCharge": true} and the config
 * has SERVICE_CHARGE_ENABLED=true. The service charge is a DINE_IN-only concept.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class SalesControllerServiceChargeTest {

    @Autowired MockMvc mvc;
    @Autowired CartService carts;
    @Autowired ConfigurationService config;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        // BURGER @ 30.00; 2 units -> net 60.00, VAT 15% -> grandTotal 69.00 (no charge)
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        productSync.sync();
        // Enable service charge so the guard is non-trivial
        config.put(SettingKey.SERVICE_CHARGE_ENABLED, "true");
        config.put(SettingKey.SERVICE_CHARGE_PERCENT, "10");
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private static RequestPostProcessor cashier() {
        return jwt().jwt(j -> j.subject("cashier"))
                .authorities(new SimpleGrantedAuthority("ROLE_CASHIER"));
    }

    /**
     * Even when the caller sends {@code "applyServiceCharge": true} and the store
     * config has the charge enabled (10 %), the retail controller must strip the
     * flag and return {@code serviceChargeAmount == 0}.
     *
     * <p>Grand total WITHOUT charge: 60.00 net + 15% VAT = 69.00.
     * Grand total WITH charge would be: 60.00 + 6.00 (SC) + 9.90 (VAT on both) = 75.90.
     * The tender is 69.00, which exactly covers the no-charge total; the test
     * asserts HTTP 201 (tender matched) and serviceChargeAmount == 0.
     */
    @Test
    void retailCheckoutNeverCarriesServiceChargeEvenIfRequestBodySaysTrue() throws Exception {
        UUID cart = carts.createCart();
        carts.addLine(cart, "BURGER", new BigDecimal("2")); // net 60.00

        String body = "{"
                + "\"cartId\":\"" + cart + "\","
                + "\"tenders\":[{\"method\":\"CASH\",\"tendered\":69.00}],"
                + "\"applyServiceCharge\":true"
                + "}";

        mvc.perform(post("/sales").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.serviceChargeAmount").value(0))
                .andExpect(jsonPath("$.grandTotal").value(69.00));
    }
}
