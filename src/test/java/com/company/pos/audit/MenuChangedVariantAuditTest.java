package com.company.pos.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class MenuChangedVariantAuditTest {

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
        fake.addProduct(new ErpProduct("BEER-S", "Small Beer", "DRINKS", "Drinks", "bcBEERS",
                "EA", new BigDecimal("10.00"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("BEER-L", "Large Beer", "DRINKS", "Drinks", "bcBEERL",
                "EA", new BigDecimal("14.00"), "SAR", 1, true));
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
    void variantLifecycleIsAudited() throws Exception {
        String token = login("boss");

        // 1. Create group → VARIANT_GROUP_CREATED
        String groupJson = mvc.perform(post("/menu/variant-groups")
                        .header("Authorization", token).contentType("application/json")
                        .content("{\"name\":\"Sizes\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String groupId = JsonPath.read(groupJson, "$.id");

        // 2. Add member → VARIANT_MEMBER_ADDED
        String memberJson = mvc.perform(post("/menu/variant-groups/" + groupId + "/members")
                        .header("Authorization", token).contentType("application/json")
                        .content("{\"sku\":\"BEER-S\",\"displayLabel\":\"Small\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        // 3. Get admin list to retrieve the member id
        String adminJson = mvc.perform(get("/menu/variant-groups/admin")
                        .header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // JsonPath: first group's first member id
        String memberId = JsonPath.read(adminJson, "$[0].members[0].id");

        // 4. Relabel member → VARIANT_MEMBER_UPDATED
        mvc.perform(put("/menu/variant-groups/" + groupId + "/members/" + memberId)
                        .header("Authorization", token).contentType("application/json")
                        .content("{\"displayLabel\":\"Sm\"}"))
                .andExpect(status().isNoContent());

        // 5. Rename group → VARIANT_GROUP_UPDATED
        mvc.perform(put("/menu/variant-groups/" + groupId)
                        .header("Authorization", token).contentType("application/json")
                        .content("{\"name\":\"Beer sizes\"}"))
                .andExpect(status().isOk());

        // 6. Deactivate member → VARIANT_MEMBER_DEACTIVATED
        mvc.perform(delete("/menu/variant-groups/" + groupId + "/members/" + memberId)
                        .header("Authorization", token))
                .andExpect(status().isNoContent());

        // 7. Reactivate member → VARIANT_MEMBER_REACTIVATED
        mvc.perform(post("/menu/variant-groups/" + groupId + "/members/" + memberId + "/reactivate")
                        .header("Authorization", token))
                .andExpect(status().isNoContent());

        // 8. Deactivate group → VARIANT_GROUP_DEACTIVATED
        mvc.perform(delete("/menu/variant-groups/" + groupId)
                        .header("Authorization", token))
                .andExpect(status().isNoContent());

        // 9. Reactivate group → VARIANT_GROUP_REACTIVATED
        mvc.perform(post("/menu/variant-groups/" + groupId + "/reactivate")
                        .header("Authorization", token))
                .andExpect(status().isNoContent());

        // Wait for async listener (outbox + @ApplicationModuleListener fires post-commit)
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<AuditRecordView> recent = audit.recent(100);
            assertThat(recent).anySatisfy(r -> {
                assertThat(r.action()).isEqualTo("MENU_VARIANT_GROUP_CREATED");
                assertThat(r.entityRef()).isEqualTo(groupId);
                assertThat(r.actor()).isEqualTo("boss");
            });
            assertThat(recent).anyMatch(r -> r.action().equals("MENU_VARIANT_MEMBER_ADDED"));
            assertThat(recent).anyMatch(r -> r.action().equals("MENU_VARIANT_MEMBER_UPDATED"));
            assertThat(recent).anyMatch(r -> r.action().equals("MENU_VARIANT_GROUP_UPDATED"));
            assertThat(recent).anyMatch(r -> r.action().equals("MENU_VARIANT_MEMBER_DEACTIVATED"));
            assertThat(recent).anyMatch(r -> r.action().equals("MENU_VARIANT_MEMBER_REACTIVATED"));
            assertThat(recent).anyMatch(r -> r.action().equals("MENU_VARIANT_GROUP_DEACTIVATED"));
            assertThat(recent).anyMatch(r -> r.action().equals("MENU_VARIANT_GROUP_REACTIVATED"));
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
