package com.company.pos.reporting;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
class ReportingCsvTest {

    @Autowired MockMvc mvc;

    private static RequestPostProcessor manager() {
        return jwt().jwt(j -> j.subject("u")).authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    @Test
    void salesCsvHasHeaderAndContentType() throws Exception {
        mvc.perform(get("/reports/sales").param("from", "2000-01-01").param("to", "2100-01-01")
                        .param("format", "csv").with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "from,to,currencyCode,saleCount,subtotal,lineDiscounts,txnDiscounts,taxTotal,grossSales,returnCount,refundTotal,netSales")));
    }

    @Test
    void productsCsvHasHeaderRow() throws Exception {
        mvc.perform(get("/reports/products").param("from", "2000-01-01").param("to", "2100-01-01")
                        .param("format", "csv").with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "sku,name,quantitySold,revenue,discounts")));
    }
}
