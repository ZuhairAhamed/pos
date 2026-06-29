package com.company.pos.sync;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

// Auth-stamping mirrors the existing SyncControllerTest exactly (jwt().authorities(ROLE_*)).
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class ErpUploadControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void managerCanTriggerDrain() throws Exception {
        mvc.perform(post("/sync/erp/upload")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isOk());
    }

    @Test
    void cashierIsForbidden() throws Exception {
        mvc.perform(post("/sync/erp/upload")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CASHIER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(post("/sync/erp/upload"))
                .andExpect(status().isUnauthorized());
    }
}
