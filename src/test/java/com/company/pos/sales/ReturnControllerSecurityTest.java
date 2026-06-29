package com.company.pos.sales;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.util.Identifiers;
import com.company.pos.support.DatabaseCleaner;
import com.jayway.jsonpath.JsonPath;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A cashier is forbidden from POST /returns; a manager is authorized (passes the security layer —
 * here the body references a non-existent sale, so a manager gets 404, NOT 403). Proves the
 * MANAGER-only guard without needing a full sale fixture.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class ReturnControllerSecurityTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
        users.save(new User(Identifiers.newId(), "cashier", "Cashier One",
                encoder.encode("pw"), Set.of(Role.CASHIER)));
        users.save(new User(Identifiers.newId(), "manager", "Store Manager",
                encoder.encode("pw"), Set.of(Role.MANAGER)));
    }

    @AfterEach
    void cleanup() {
        databaseCleaner.clean();
    }

    @Test
    void cashierIsForbiddenFromProcessingReturns() throws Exception {
        String token = login("cashier");
        mvc.perform(post("/returns").header("Authorization", token)
                        .contentType("application/json")
                        .content(body(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerPassesSecurity() throws Exception {
        String token = login("manager");
        // Non-existent sale id -> service throws notFound -> 404 (NOT 403): the guard let the manager in.
        mvc.perform(post("/returns").header("Authorization", token)
                        .contentType("application/json")
                        .content(body(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    private String body(UUID saleId) {
        return "{\"originalSaleId\":\"" + saleId + "\",\"lines\":[{\"lineNo\":1,\"quantity\":1}]}";
    }

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }
}
