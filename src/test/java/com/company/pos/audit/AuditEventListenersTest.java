package com.company.pos.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.audit.api.AuditRecordView;
import com.company.pos.audit.application.DefaultAuditService;
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

/**
 * Verifies that domain events fired inside committed transactions are picked up by the five
 * @ApplicationModuleListener audit listeners.
 *
 * The key constraint: @ApplicationModuleListener only fires *after* a transaction commits.
 * Publishing via DomainEvents.publish() from a non-transactional test method leaves no
 * committing transaction, so no event_publication row is written and the listener never fires.
 *
 * Solution: trigger SettingChanged by calling the real PUT /config/{key} HTTP endpoint (which
 * commits its own transaction) and then Awaitility-await the async listener, mirroring
 * NotificationsEndToEndTest / ErpUpSyncEndToEndTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class AuditEventListenersTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    DefaultAuditService audit;

    @Autowired
    DatabaseCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        users.save(new User(Identifiers.newId(), "admin", "Admin User",
                encoder.encode("pw"), Set.of(Role.ADMIN)));
    }

    @AfterEach
    void tearDown() {
        cleaner.clean();
    }

    @Test
    void settingChangedEventIsAudited() throws Exception {
        // PUT /config/{key} commits a transaction and publishes SettingChanged inside it,
        // which the @ApplicationModuleListener picks up after commit (async).
        String adminToken = login("admin");
        mvc.perform(put("/config/STORE_NAME")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content("{\"value\":\"New Store Name\"}"))
                .andExpect(status().isNoContent());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<AuditRecordView> recent = audit.recent(20);
            assertThat(recent).anySatisfy(r -> {
                assertThat(r.action()).isEqualTo("SETTING_CHANGED");
                assertThat(r.entityRef()).isEqualTo("store.name");
                assertThat(r.actor()).isEqualTo("admin");
            });
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
