package com.company.pos.dining;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class DiningTableControllerTest {

    @Autowired MockMvc mvc;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    private static RequestPostProcessor cashier() {
        return jwt().jwt(j -> j.subject("cashier"))
                .authorities(new SimpleGrantedAuthority("ROLE_CASHIER"));
    }

    private static RequestPostProcessor manager() {
        return jwt().jwt(j -> j.subject("manager"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    @Test
    void managerRegistersTable() throws Exception {
        mvc.perform(post("/dining/tables").with(manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"T10\",\"seats\":4}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("T10"));
    }

    @Test
    void cashierCannotRegisterTable() throws Exception {
        mvc.perform(post("/dining/tables").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"T11\",\"seats\":4}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anyCashierCanListTables() throws Exception {
        mvc.perform(get("/dining/tables").with(cashier()))
                .andExpect(status().isOk());
    }
}
