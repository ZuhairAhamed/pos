package com.company.pos.cashdrawer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.cashdrawer.api.CashDrawerService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Transactional
class CashDrawerControllerTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    CashDrawerService drawer;

    @BeforeEach
    void openSession() {
        // default terminal is T01 (SettingKey.TERMINAL_ID default)
        drawer.openSession("T01", new BigDecimal("100.00"), "SAR", "cashier");
    }

    @Test
    void payInThenReconciliationReflectsIt() throws Exception {
        mvc.perform(post("/cash-drawer/pay-in").with(jwt().jwt(j -> j.subject("cashier")))
                        .contentType("application/json")
                        .content("{\"amount\":25.00,\"reason\":\"change fund\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("PAY_IN"))
                .andExpect(jsonPath("$.amount").value(25.00));

        mvc.perform(get("/cash-drawer/reconciliation").with(jwt().jwt(j -> j.subject("cashier"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payIns").value(25.00))
                .andExpect(jsonPath("$.expectedCash").value(125.00));
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(post("/cash-drawer/pay-in").contentType("application/json")
                        .content("{\"amount\":5.00,\"reason\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }
}
