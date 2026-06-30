package com.company.pos.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Transactional
class ConfigurationControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ConfigurationService config;

    @Test
    void adminCanUpdateASetting() throws Exception {
        mvc.perform(put("/config/STORE_NAME")
                        .with(jwt().jwt(j -> j.subject("admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json")
                        .content("{\"value\":\"Downtown Branch\"}"))
                .andExpect(status().isNoContent());
        assertThat(config.getString(SettingKey.STORE_NAME)).isEqualTo("Downtown Branch");
    }

    @Test
    void managerCannotUpdateASetting() throws Exception {
        mvc.perform(put("/config/STORE_NAME")
                        .with(jwt().jwt(j -> j.subject("mgr"))
                                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER")))
                        .contentType("application/json")
                        .content("{\"value\":\"Nope\"}"))
                .andExpect(status().isForbidden());
    }
}
