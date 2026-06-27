package com.company.pos.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.util.Identifiers;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Transactional
class PinLoginTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void seed() {
        User u = new User(Identifiers.newId(), "bob", "Bob", encoder.encode("pw"), Set.of(Role.CASHIER));
        u.setCashierCode("2002");
        u.setPinHash(encoder.encode("4321"));
        users.save(u);
    }

    @Test
    void pinLoginWithValidPinReturnsToken() throws Exception {
        mvc.perform(post("/auth/pin-login").contentType("application/json")
                        .content("{\"cashierCode\":\"2002\",\"pin\":\"4321\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void pinLoginWithWrongPinIsRejected() throws Exception {
        mvc.perform(post("/auth/pin-login").contentType("application/json")
                        .content("{\"cashierCode\":\"2002\",\"pin\":\"0000\"}"))
                .andExpect(status().isBadRequest());
    }
}
