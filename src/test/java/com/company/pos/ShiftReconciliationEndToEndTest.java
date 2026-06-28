package com.company.pos;

import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.util.Identifiers;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Transactional
class ShiftReconciliationEndToEndTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    FakeErpClient fake;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("20"), 1));
        users.save(new User(Identifiers.newId(), "cashier", "Cashier One",
                encoder.encode("pw"), Set.of(Role.CASHIER)));
        users.save(new User(Identifiers.newId(), "manager", "Store Manager",
                encoder.encode("pw"), Set.of(Role.MANAGER)));
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
