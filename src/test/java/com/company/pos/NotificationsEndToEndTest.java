package com.company.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.util.Identifiers;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.notification.api.AlertType;
import com.company.pos.notification.application.StuckUploadMonitor;
import com.company.pos.notification.infrastructure.InMemoryNotifier;
import com.company.pos.support.DatabaseCleaner;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end over REST: (1) a sale that crosses the reorder level raises a LOW_STOCK alert;
 * (2) with the ERP offline, a sale's stuck upload publication raises a SYNC_ERROR alert when the
 * monitor scans. Non-@Transactional + DatabaseCleaner + Awaitility; fakes reset around each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@TestPropertySource(properties = {
        "pos.inventory.reorder-level=19",
        "pos.notification.stuck-upload.min-age-ms=0" })
@Import(DatabaseCleaner.class)
class NotificationsEndToEndTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    FakeErpClient fake;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    InMemoryNotifier notifier;
    @Autowired
    StuckUploadMonitor monitor;
    @Autowired
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
        notifier.clear();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("20"), 1));
        users.save(new User(Identifiers.newId(), "cashier", "Cashier One",
                encoder.encode("pw"), Set.of(Role.CASHIER)));
        users.save(new User(Identifiers.newId(), "manager", "Store Manager",
                encoder.encode("pw"), Set.of(Role.MANAGER)));
    }

    @AfterEach
    void cleanup() {
        databaseCleaner.clean();
        notifier.clear();
    }

    @Test
    void crossingReorderRaisesLowStockAlert() throws Exception {
        String managerToken = login("manager");
        mvc.perform(post("/sync/erp").header("Authorization", managerToken))
                .andExpect(status().isOk());
        String token = login("cashier");

        ringUpTwoColas(token);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(notifier.alerts().stream().anyMatch(a -> a.type() == AlertType.LOW_STOCK))
                        .isTrue());
    }

    @Test
    void offlineUploadRaisesSyncErrorAlertOnScan() throws Exception {
        String managerToken = login("manager");
        mvc.perform(post("/sync/erp").header("Authorization", managerToken))
                .andExpect(status().isOk());
        String token = login("cashier");

        fake.setAvailable(false); // ERP offline -> upload publication stays incomplete
        ringUpTwoColas(token);

        monitor.scan();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(notifier.alerts().stream().anyMatch(a -> a.type() == AlertType.SYNC_ERROR))
                        .isTrue());
    }

    private void ringUpTwoColas(String token) throws Exception {
        String createdCart = mvc.perform(post("/carts").header("Authorization", token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String cartId = JsonPath.read(createdCart, "$.cartId");
        mvc.perform(post("/carts/" + cartId + "/lines").header("Authorization", token)
                        .contentType("application/json").content("{\"sku\":\"COLA\",\"quantity\":2}"))
                .andExpect(status().isOk());
        mvc.perform(post("/sales").header("Authorization", token).contentType("application/json")
                        .content("{\"cartId\":\"" + cartId + "\",\"tenders\":[{\"method\":\"CASH\",\"tendered\":20.00}]}"))
                .andExpect(status().isCreated());
    }

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }
}
