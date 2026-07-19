package com.company.pos.auth;

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
class UsersControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("root"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor cashier() {
        return jwt().jwt(j -> j.subject("cashier"))
                .authorities(new SimpleGrantedAuthority("ROLE_CASHIER"));
    }

    private static final String ALICE =
            "{\"username\":\"alice\",\"displayName\":\"Alice\",\"password\":\"pw\",\"roles\":[\"CASHIER\"]}";

    @Test
    void cashierCannotCreateUser() throws Exception {
        mvc.perform(post("/users").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON).content(ALICE))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCreatesUserAndHashesAreNeverExposed() throws Exception {
        mvc.perform(post("/users").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(ALICE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.pinHash").doesNotExist());
    }

    @Test
    void adminListsUsers() throws Exception {
        mvc.perform(post("/users").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(ALICE)).andExpect(status().isCreated());
        mvc.perform(get("/users").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("alice"));
    }

    @Test
    void cashierCannotListUsers() throws Exception {
        mvc.perform(get("/users").with(cashier())).andExpect(status().isForbidden());
    }
}
