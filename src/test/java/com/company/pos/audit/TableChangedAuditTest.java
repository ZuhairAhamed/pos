package com.company.pos.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.audit.api.AuditRecordView;
import com.company.pos.audit.application.DefaultAuditService;
import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.util.Identifiers;
import com.company.pos.support.DatabaseCleaner;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.Set;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class TableChangedAuditTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired DefaultAuditService audit;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        users.save(new User(Identifiers.newId(), "boss", "Boss User",
                encoder.encode("pw"), Set.of(Role.MANAGER)));
    }

    @AfterEach
    void tearDown() {
        cleaner.clean();
    }

    @Test
    void tableLifecycleIsAudited() throws Exception {
        String token = login("boss");

        String created = mvc.perform(post("/dining/tables")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content("{\"label\":\"AUD-1\",\"seats\":4}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String tableId = JsonPath.read(created, "$.id");

        mvc.perform(put("/dining/tables/" + tableId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content("{\"label\":\"AUD-1b\",\"seats\":6}"))
                .andExpect(status().isOk());

        mvc.perform(delete("/dining/tables/" + tableId)
                        .header("Authorization", token))
                .andExpect(status().isNoContent());

        mvc.perform(post("/dining/tables/" + tableId + "/reactivate")
                        .header("Authorization", token))
                .andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<AuditRecordView> recent = audit.recent(50);
            assertThat(recent).anySatisfy(r -> {
                assertThat(r.action()).isEqualTo("TABLE_CREATED");
                assertThat(r.entityRef()).isEqualTo(tableId);
                assertThat(r.actor()).isEqualTo("boss");
            });
            assertThat(recent).anyMatch(r -> r.action().equals("TABLE_UPDATED"));
            assertThat(recent).anyMatch(r -> r.action().equals("TABLE_DEACTIVATED"));
            assertThat(recent).anyMatch(r -> r.action().equals("TABLE_REACTIVATED"));
        });

        assertThat(audit.verify().intact()).isTrue();
    }

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }
}
