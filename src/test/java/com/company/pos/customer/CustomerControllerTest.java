package com.company.pos.customer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class CustomerControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor cashier() {
        return jwt().jwt(j -> j.subject("cashier"))
                .authorities(new SimpleGrantedAuthority("ROLE_CASHIER"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor manager() {
        return jwt().jwt(j -> j.subject("manager"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    private String registerAisha() throws Exception {
        String body = mvc.perform(post("/customers").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Aisha\",\"phone\":\"0501234567\",\"email\":\"aisha@x.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.id");
    }

    @Test
    void cashierCanRegisterAndFetch() throws Exception {
        String id = registerAisha();
        mvc.perform(get("/customers/" + id).with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Aisha"));
    }

    @Test
    void cashierCanSearch() throws Exception {
        registerAisha();
        mvc.perform(get("/customers").param("q", "Aisha").with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Aisha"));
    }

    @Test
    void unknownCustomerIs404() throws Exception {
        mvc.perform(get("/customers/" + java.util.UUID.randomUUID()).with(cashier()))
                .andExpect(status().isNotFound());
    }

    @Test
    void cashierCannotDeactivate() throws Exception {
        String id = registerAisha();
        mvc.perform(delete("/customers/" + id).with(cashier()))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanDeactivate() throws Exception {
        String id = registerAisha();
        mvc.perform(delete("/customers/" + id).with(manager()))
                .andExpect(status().isNoContent());
        // Soft-deleted: still fetchable, active=false.
        mvc.perform(get("/customers/" + id).with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void purchaseHistoryEmptyForNewCustomer() throws Exception {
        String id = registerAisha();
        mvc.perform(get("/customers/" + id + "/purchases").with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
