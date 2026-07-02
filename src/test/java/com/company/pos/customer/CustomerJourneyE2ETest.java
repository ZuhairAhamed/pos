package com.company.pos.customer;

import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Duration;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class CustomerJourneyE2ETest {

    @Autowired
    MockMvc mvc;
    @Autowired
    DatabaseCleaner cleaner;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    InMemoryPaymentTerminal terminal;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
        terminal.setApprove(true);
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

    @Test
    void registerAttachCheckoutThenHistory() throws Exception {
        // 1. Register a customer.
        String customerBody = mvc.perform(post("/customers").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Aisha\",\"phone\":\"0501234567\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andReturn().getResponse().getContentAsString();
        String customerId = com.jayway.jsonpath.JsonPath.read(customerBody, "$.id");

        // 2. Create a cart.
        String cartBody = mvc.perform(post("/carts").with(cashier()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String cartId = com.jayway.jsonpath.JsonPath.read(cartBody, "$.cartId");

        // 3. Add a COLA line (2 units × 4.50 SAR = 9.00 net, 1.35 VAT at 15%, grand total 10.35).
        mvc.perform(post("/carts/" + cartId + "/lines").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"COLA\",\"quantity\":2}"))
                .andExpect(status().isOk());

        // 4. Attach the customer to the cart.
        mvc.perform(post("/customers/" + customerId + "/cart/" + cartId).with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(customerId));

        // 5. Checkout: tender 20.00 cash against the 10.35 total.
        mvc.perform(post("/sales").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cartId\":\"" + cartId + "\",\"tenders\":[{\"method\":\"CASH\",\"tendered\":20.00}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.grandTotal").value(10.35));

        // 6. Purchase history shows the sale (the projection runs after-commit, asynchronously).
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/customers/" + customerId + "/purchases").with(cashier()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(1))
                        .andExpect(jsonPath("$[0].grandTotal").value(10.35)));
    }
}
