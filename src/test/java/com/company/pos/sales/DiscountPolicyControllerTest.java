package com.company.pos.sales;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class DiscountPolicyControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void policyExposesCapsAndReasonCodes() throws Exception {
        mvc.perform(get("/sales/discount-policy").with(jwt().jwt(j -> j.subject("cashier1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashierMaxPercent").value(10))
                .andExpect(jsonPath("$.cashierMaxAmount").value(20.00))
                .andExpect(jsonPath("$.reasonCodes.length()").value(4))
                .andExpect(jsonPath("$.reasonCodes[0]").value("DAMAGED"))
                .andExpect(jsonPath("$.reasonCodes[2]").value("LOYALTY"));
    }

    @Test
    void anonymousPolicyRejected() throws Exception {
        mvc.perform(get("/sales/discount-policy")).andExpect(status().isUnauthorized());
    }
}
