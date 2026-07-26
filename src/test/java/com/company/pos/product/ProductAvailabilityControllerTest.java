package com.company.pos.product;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class ProductAvailabilityControllerTest {

    @Autowired MockMvc mvc;
    @Autowired DatabaseCleaner cleaner;
    @Autowired com.company.pos.audit.application.DefaultAuditService audit;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("root")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor cashier() {
        return jwt().jwt(j -> j.subject("cashier")).authorities(new SimpleGrantedAuthority("ROLE_CASHIER"));
    }

    private static final String SALMON = "{\"sku\":\"SALMON\",\"name\":\"Grilled Salmon\","
            + "\"categoryName\":\"Mains\",\"unitPrice\":42.00,"
            + "\"currencyCode\":\"SAR\",\"unitOfMeasure\":\"EA\"}";

    private void seedSalmon() throws Exception {
        mvc.perform(post("/products").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(SALMON))
                .andExpect(status().isCreated());
    }

    @Test
    void cashierCan86AndRestore() throws Exception {
        seedSalmon();
        mvc.perform(put("/products/SALMON/availability").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"available\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));
        mvc.perform(put("/products/SALMON/availability").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"available\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void unknownSkuIs404() throws Exception {
        mvc.perform(put("/products/NOPE/availability").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"available\":false}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void toggleIsAudited() throws Exception {
        seedSalmon();
        mvc.perform(put("/products/SALMON/availability").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"available\":false}"))
                .andExpect(status().isOk());

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    java.util.List<String> actions = audit.recent(50).stream()
                            .map(com.company.pos.audit.api.AuditRecordView::action).toList();
                    org.assertj.core.api.Assertions.assertThat(actions)
                            .contains("PRODUCT_MARKED_UNAVAILABLE");
                });
    }
}
