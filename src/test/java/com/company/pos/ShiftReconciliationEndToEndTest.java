package com.company.pos;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.cashdrawer.api.CashDrawerService;
import com.company.pos.cashdrawer.infrastructure.CashMovementRepository;
import com.company.pos.cashdrawer.infrastructure.DrawerSessionRepository;
import com.company.pos.common.util.Identifiers;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.shift.infrastructure.ShiftRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * NOT @Transactional: the cash sale must commit so the after-commit cashdrawer listener captures
 * it before the shift is closed. We poll the open session's reconciliation until the cash sale
 * lands, then close. @AfterEach clears shift/drawer/user rows (FK order: cash_movement,
 * shift, drawer_session) so the next run opens a clean session; sale and stock rows are harmless
 * (stock is reset to its absolute ERP value by inventorySync in @BeforeEach).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
// Commits to the shared in-memory DB; rebuild the context after this class so its committed rows (sync_cursor, product, …) don't leak into other tests.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ShiftReconciliationEndToEndTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    FakeErpClient fake;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    CashDrawerService drawer;
    @Autowired
    CashMovementRepository cashMovements;
    @Autowired
    DrawerSessionRepository drawerSessions;
    @Autowired
    ShiftRepository shifts;

    @BeforeEach
    void seed() {
        cashMovements.deleteAll();
        shifts.deleteAll();
        drawerSessions.deleteAll();
        users.deleteAll();
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
        cashMovements.deleteAll();
        shifts.deleteAll();
        drawerSessions.deleteAll();
        users.deleteAll();
    }

    @Test
    void openShiftSellCashPayOutThenCloseReportsVariance() throws Exception {
        String managerToken = login("manager");
        mvc.perform(post("/sync/erp").header("Authorization", managerToken))
                .andExpect(status().isOk());
        String token = login("cashier");

        // 1. Open a shift with a 100.00 float
        String openedShift = mvc.perform(post("/shifts").header("Authorization", token)
                        .contentType("application/json").content("{\"openingFloat\":100.00}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String shiftId = JsonPath.read(openedShift, "$.shiftId");
        var sessionId = drawer.findOpenSession("T01").orElseThrow().sessionId();

        // 2. Ring up 2 colas (total 10.35) and pay cash
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

        // 2b. Wait for the after-commit cashdrawer listener to capture the cash sale.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(drawer.reconcile(sessionId).cashSales()).isEqualByComparingTo("10.35"));

        // 3. Pay out 15.00 (e.g. a supplier cash payment)
        mvc.perform(post("/cash-drawer/pay-out").header("Authorization", token)
                        .contentType("application/json")
                        .content("{\"amount\":15.00,\"reason\":\"supplier\"}"))
                .andExpect(status().isOk());

        // 4. Close the shift. Expected = 100 + 10.35 (cash sale) - 15.00 (pay-out) = 95.35.
        //    Count 95.35 -> variance 0.00.
        mvc.perform(post("/shifts/" + shiftId + "/close").header("Authorization", token)
                        .contentType("application/json").content("{\"countedCash\":95.35}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.cash.cashSales").value(closeTo(10.35, 0.001)))
                .andExpect(jsonPath("$.cash.payOuts").value(closeTo(15.00, 0.001)))
                .andExpect(jsonPath("$.cash.expectedCash").value(closeTo(95.35, 0.001)))
                .andExpect(jsonPath("$.cash.variance").value(closeTo(0.00, 0.001)));
    }

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }
}
