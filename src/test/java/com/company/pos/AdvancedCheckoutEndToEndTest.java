package com.company.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.util.Identifiers;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import com.company.pos.device.infrastructure.InMemoryPrinter;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
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
class AdvancedCheckoutEndToEndTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    FakeErpClient fake;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    InMemoryPrinter printer;
    @Autowired
    InMemoryPaymentTerminal terminal;

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
        terminal.setApprove(true);
    }

    @AfterEach
    void reset() {
        terminal.setApprove(true);
    }

    @Test
    void holdResumeThenSplitCardAndCashCheckout() throws Exception {
        String managerToken = login("manager");
        mvc.perform(post("/sync/erp").header("Authorization", managerToken))
                .andExpect(status().isOk());
        String token = login("cashier");

        // Ring up 2 colas (total 10.35)
        String createdCart = mvc.perform(post("/carts").header("Authorization", token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String cartId = JsonPath.read(createdCart, "$.cartId");
        mvc.perform(post("/carts/" + cartId + "/lines").header("Authorization", token)
                        .contentType("application/json").content("{\"sku\":\"COLA\",\"quantity\":2}"))
                .andExpect(status().isOk());

        // Hold, then it appears in the terminal's held list, then resume
        mvc.perform(put("/carts/" + cartId + "/hold").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HELD"));
        mvc.perform(get("/carts/held?terminalId=T01").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cartId").value(cartId));
        mvc.perform(put("/carts/" + cartId + "/resume").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        // Split: 5.00 on card, remainder (5.35) on cash tendered 10.00 -> change 4.65
        String sale = mvc.perform(post("/sales").header("Authorization", token)
                        .contentType("application/json")
                        .content("{\"cartId\":\"" + cartId + "\",\"tenders\":["
                                + "{\"method\":\"CARD\",\"amount\":5.00},"
                                + "{\"method\":\"CASH\",\"tendered\":10.00}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.grandTotal").value(10.35))
                .andExpect(jsonPath("$.payments[0].method").value("CARD"))
                .andExpect(jsonPath("$.payments[0].maskedPan").value("**** **** **** 4242"))
                .andExpect(jsonPath("$.payments[1].method").value("CASH"))
                .andExpect(jsonPath("$.payments[1].changeDue").value(4.65))
                .andExpect(jsonPath("$.receiptNumber", matchesPattern("S01-T01-\\d{6}")))
                .andReturn().getResponse().getContentAsString();
        String receiptNumber = JsonPath.read(sale, "$.receiptNumber");

        // Receipt printed with the sale's number
        assertThat(printer.lastReceipt()).anyMatch(l -> l.text().contains(receiptNumber));

        // Stock decremented 20 -> 18
        mvc.perform(get("/inventory/COLA").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(18));
    }

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }
}
