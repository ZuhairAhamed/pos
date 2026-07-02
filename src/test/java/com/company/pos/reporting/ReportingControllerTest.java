package com.company.pos.reporting;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class ReportingControllerTest {

    @Autowired MockMvc mvc;

    private static RequestPostProcessor role(String r) {
        return jwt().jwt(j -> j.subject("u")).authorities(new SimpleGrantedAuthority("ROLE_" + r));
    }

    @Test
    void cashierIsForbidden() throws Exception {
        mvc.perform(get("/reports/sales").param("from", "2000-01-01").param("to", "2100-01-01")
                        .with(role("CASHIER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerGetsSalesSummary() throws Exception {
        mvc.perform(get("/reports/sales").param("from", "2000-01-01").param("to", "2100-01-01")
                        .with(role("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencyCode").value("SAR"))
                .andExpect(jsonPath("$.saleCount").exists())
                .andExpect(jsonPath("$.netSales").exists());
    }

    @Test
    void adminGetsAllReportEndpoints() throws Exception {
        for (String path : new String[] { "/reports/sales", "/reports/payments", "/reports/tax",
                "/reports/cashiers", "/reports/products" }) {
            mvc.perform(get(path).param("from", "2000-01-01").param("to", "2100-01-01")
                            .with(role("ADMIN")))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void invertedRangeIs400() throws Exception {
        mvc.perform(get("/reports/sales").param("from", "2100-01-01").param("to", "2000-01-01")
                        .with(role("MANAGER")))
                .andExpect(status().isBadRequest());
    }
}
