package com.company.pos.audit;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.audit.api.AuditService;
import com.company.pos.support.DatabaseCleaner;
import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class AuditControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AuditService auditService;

    @Autowired
    DatabaseCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner.clean();
    }

    @AfterEach
    void tearDown() {
        cleaner.clean();
    }

    @Test
    void adminCanQueryAndVerify() throws Exception {
        auditService.record(AuditAction.LOGIN_FAILED, "mallory", "mallory", Map.of("reason", "x"));

        mvc.perform(get("/audit").param("action", "LOGIN_FAILED")
                        .with(jwt().jwt(j -> j.subject("admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("LOGIN_FAILED"));

        mvc.perform(post("/audit/verify")
                        .with(jwt().jwt(j -> j.subject("admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(true));
    }

    @Test
    void managerCannotReadAudit() throws Exception {
        mvc.perform(get("/audit")
                        .with(jwt().jwt(j -> j.subject("mgr"))
                                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isForbidden());

        mvc.perform(post("/audit/verify")
                        .with(jwt().jwt(j -> j.subject("mgr"))
                                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isForbidden());

        mvc.perform(get("/audit/" + UUID.randomUUID())
                        .with(jwt().jwt(j -> j.subject("mgr"))
                                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanFetchAuditRecordById() throws Exception {
        auditService.record(AuditAction.LOGIN_FAILED, "charlie", "charlie", Map.of("attempt", "1"));

        MvcResult listResult = mvc.perform(get("/audit").param("action", "LOGIN_FAILED")
                        .with(jwt().jwt(j -> j.subject("admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = listResult.getResponse().getContentAsString();
        String recordIdStr = JsonPath.read(responseBody, "$[0].id");
        UUID recordId = UUID.fromString(recordIdStr);

        mvc.perform(get("/audit/" + recordId)
                        .with(jwt().jwt(j -> j.subject("admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(recordId.toString()))
                .andExpect(jsonPath("$.action").value("LOGIN_FAILED"));
    }

    @Test
    void getAuditByIdReturns404ForNonexistent() throws Exception {
        UUID randomId = UUID.randomUUID();

        mvc.perform(get("/audit/" + randomId)
                        .with(jwt().jwt(j -> j.subject("admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound());
    }
}
