package com.company.pos.auth;

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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class RbacTest {

    @Autowired
    MockMvc mvc;

    @Test
    void meIsUnauthorizedWithoutToken() throws Exception {
        mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsPrincipalForAuthenticatedUser() throws Exception {
        mvc.perform(get("/auth/me").with(jwt().jwt(j -> j.subject("alice").claim("roles", java.util.List.of("CASHIER")))
                        .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CASHIER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.roles[0]").value("CASHIER"));
    }

    @Test
    void managerCheckForbiddenForCashier() throws Exception {
        mvc.perform(get("/auth/manager-check").with(jwt()
                        .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CASHIER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCheckAllowedForManager() throws Exception {
        mvc.perform(get("/auth/manager-check").with(jwt()
                        .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isOk());
    }
}
