package com.company.pos.dashboard;

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
class DashboardControllerTest {

    @Autowired MockMvc mvc;

    private static final String[] ALL = {
        "/dashboard", "/dashboard/sales-today", "/dashboard/revenue",
        "/dashboard/best-sellers", "/dashboard/low-stock", "/dashboard/open-shifts"
    };

    private static RequestPostProcessor role(String r) {
        return jwt().jwt(j -> j.subject("u")).authorities(new SimpleGrantedAuthority("ROLE_" + r));
    }

    @Test
    void cashierIsForbiddenOnEveryEndpoint() throws Exception {
        for (String path : ALL) {
            mvc.perform(get(path).with(role("CASHIER")))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void managerAndAdminGetEveryEndpoint() throws Exception {
        for (String path : ALL) {
            mvc.perform(get(path).with(role("MANAGER"))).andExpect(status().isOk());
            mvc.perform(get(path).with(role("ADMIN"))).andExpect(status().isOk());
        }
    }

    @Test
    void snapshotExposesEveryTileField() throws Exception {
        mvc.perform(get("/dashboard").with(role("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencyCode").value("SAR"))
                .andExpect(jsonPath("$.asOfDate").exists())
                .andExpect(jsonPath("$.todaysSales").exists())
                .andExpect(jsonPath("$.revenue.windowDays").value(7))
                .andExpect(jsonPath("$.bestSellers").isArray())
                .andExpect(jsonPath("$.lowStock").isArray())
                .andExpect(jsonPath("$.openShifts").isArray())
                .andExpect(jsonPath("$.activeCashiers").isArray());
    }
}
