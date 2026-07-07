package com.company.pos.kitchen;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class KitchenControllerTest {

    @Autowired MockMvc mvc;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    private static RequestPostProcessor cashier() {
        return jwt().jwt(j -> j.subject("cashier")).authorities(new SimpleGrantedAuthority("ROLE_CASHIER"));
    }

    private static RequestPostProcessor manager() {
        return jwt().jwt(j -> j.subject("manager")).authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    @Test
    void managerAssignsStation() throws Exception {
        mvc.perform(post("/kitchen/stations/assignments").with(manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"BURGER\",\"stationName\":\"Grill\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void cashierCannotAssignStation() throws Exception {
        mvc.perform(post("/kitchen/stations/assignments").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"BURGER\",\"stationName\":\"Grill\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anyCashierCanListAssignments() throws Exception {
        mvc.perform(get("/kitchen/stations/assignments").with(cashier()))
                .andExpect(status().isOk());
    }
}
