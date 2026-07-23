package com.company.pos.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.audit.api.AuditRecordView;
import com.company.pos.audit.application.DefaultAuditService;
import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.util.Identifiers;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.support.DatabaseCleaner;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
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
class MenuChangedAuditTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired DefaultAuditService audit;
    @Autowired DatabaseCleaner cleaner;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("STEAK", "Ribeye", "FOOD", "Food", "bcSTEAK",
                "EA", new BigDecimal("80.00"), "SAR", 1, true));
        productSync.sync();
        users.save(new User(Identifiers.newId(), "boss", "Boss User",
                encoder.encode("pw"), Set.of(Role.MANAGER)));
    }

    @AfterEach
    void tearDown() {
        cleaner.clean();
        fake.clear();
    }

    @Test
    void modifierLifecycleIsAudited() throws Exception {
        String token = login("boss");

        String created = mvc.perform(post("/menu/modifier-groups")
                        .header("Authorization", token).contentType("application/json")
                        .content("{\"name\":\"Add-ons\",\"minSelections\":0,\"maxSelections\":2}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String groupId = JsonPath.read(created, "$.id");

        String opt = mvc.perform(post("/menu/modifier-groups/" + groupId + "/options")
                        .header("Authorization", token).contentType("application/json")
                        .content("{\"name\":\"Cheese\",\"priceDelta\":2.00}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String optionId = JsonPath.read(opt, "$.id");

        mvc.perform(put("/menu/modifier-groups/" + groupId + "/options/" + optionId)
                        .header("Authorization", token).contentType("application/json")
                        .content("{\"name\":\"Extra cheese\",\"priceDelta\":2.50}"))
                .andExpect(status().isOk());

        mvc.perform(post("/menu/modifier-groups/" + groupId + "/assignments?sku=STEAK")
                        .header("Authorization", token))
                .andExpect(status().isNoContent());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<AuditRecordView> recent = audit.recent(50);
            assertThat(recent).anySatisfy(r -> {
                assertThat(r.action()).isEqualTo("MENU_GROUP_CREATED");
                assertThat(r.entityRef()).isEqualTo(groupId);
                assertThat(r.actor()).isEqualTo("boss");
            });
            assertThat(recent).anyMatch(r -> r.action().equals("MENU_OPTION_ADDED"));
            assertThat(recent).anyMatch(r -> r.action().equals("MENU_OPTION_UPDATED"));
            assertThat(recent).anyMatch(r -> r.action().equals("MENU_GROUP_ASSIGNED"));
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
