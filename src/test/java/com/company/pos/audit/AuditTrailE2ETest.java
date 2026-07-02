package com.company.pos.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.audit.api.AuditRecordView;
import com.company.pos.audit.application.DefaultAuditService;
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
import java.time.Duration;
import java.util.List;
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
 * Capstone: a manager rings up a real cash sale with a discount that exceeds the cashier cap. The
 * completed sale and the cap override both land in the tamper-evident chain (after-commit, async),
 * and the chain verifies intact. Non-@Transactional + DatabaseCleaner + Awaitility so the sale
 * actually commits and the @ApplicationModuleListener audit writes fire.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class AuditTrailE2ETest {

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
    @Autowired
    DefaultAuditService audit;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
        users.save(new User(Identifiers.newId(), "manager", "Store Manager",
                encoder.encode("pw"), Set.of(Role.MANAGER)));
    }

    @AfterEach
    void cleanup() {
        databaseCleaner.clean();
        fake.clear();
    }

    @Test
    void completedSaleWithManagerOverrideLandsInTheChain() throws Exception {
        String token = login("manager");
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));

        // 20% line discount exceeds the 10% cashier cap; a manager may apply it (201) and it is
        // recorded as a cap override. gross 9.00 -> 1.80 off -> 7.20 net.
        String body = "{\"cartId\":\"" + cart + "\","
                + "\"tenders\":[{\"method\":\"CASH\",\"amount\":null,\"tendered\":10.00}],"
                + "\"lineDiscounts\":{\"COLA\":{\"type\":\"PERCENT\",\"value\":20,\"reasonCode\":\"LOYALTY\"}},"
                + "\"transactionDiscount\":null}";
        mvc.perform(post("/sales").header("Authorization", token)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<String> actions = audit.recent(50).stream().map(AuditRecordView::action).toList();
            assertThat(actions).contains("SALE_COMPLETED", "DISCOUNT_OVERRIDE");
        });
        assertThat(audit.verify().intact()).isTrue();
    }

    private String login(String username) throws Exception {
        String resp = mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(resp, "$.token");
    }
}
