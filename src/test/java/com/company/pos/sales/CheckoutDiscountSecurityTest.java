package com.company.pos.sales;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.cart.api.CartService;
import com.company.pos.common.util.Identifiers;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.support.DatabaseCleaner;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The cashier discount cap is enforced from the authenticated principal's role: a cashier applying
 * an over-cap discount gets 400 (validation), while a manager applying the same discount succeeds
 * (201). Proves the controller derives callerIsManager from ROLE_MANAGER end-to-end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class CheckoutDiscountSecurityTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
        users.save(new User(Identifiers.newId(), "cashier", "Cashier One",
                encoder.encode("pw"), Set.of(Role.CASHIER)));
        users.save(new User(Identifiers.newId(), "manager", "Store Manager",
                encoder.encode("pw"), Set.of(Role.MANAGER)));
    }

    @AfterEach
    void cleanup() {
        databaseCleaner.clean();
        fake.clear();
    }

    // gross 9.00, 20% off -> 1.80; discounted 7.20, tax 1.08, total 8.28
    private String body(UUID cartId) {
        return "{\"cartId\":\"" + cartId + "\","
                + "\"tenders\":[{\"method\":\"CASH\",\"amount\":null,\"tendered\":10.00}],"
                + "\"lineDiscounts\":{\"COLA\":{\"type\":\"PERCENT\",\"value\":20,\"reasonCode\":\"LOYALTY\"}},"
                + "\"transactionDiscount\":null}";
    }

    private UUID cartWithCola() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        return cart;
    }

    private String login(String username) throws Exception {
        String resp = mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(resp, "$.token");
    }

    @Test
    void cashierOverCapDiscountIsRejected() throws Exception {
        String token = login("cashier");
        mvc.perform(post("/sales").header("Authorization", token)
                        .contentType("application/json").content(body(cartWithCola())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void managerOverCapDiscountSucceeds() throws Exception {
        String token = login("manager");
        mvc.perform(post("/sales").header("Authorization", token)
                        .contentType("application/json").content(body(cartWithCola())))
                .andExpect(status().isCreated());
    }
}
