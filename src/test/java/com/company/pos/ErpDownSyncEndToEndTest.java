package com.company.pos;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.util.Identifiers;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
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
class ErpDownSyncEndToEndTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    FakeErpClient fake;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void setUp() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("20"), 1));
        users.save(new User(Identifiers.newId(), "manager", "Store Manager",
                encoder.encode("pw"), Set.of(Role.MANAGER)));
    }

    @Test
    void authenticateThenSyncThenSeeCatalogueAndStock() throws Exception {
        // 1. Authenticate
        String body = mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"username\":\"manager\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bearer = "Bearer " + JsonPath.read(body, "$.token");

        // 2. Trigger ERP down-sync
        mvc.perform(post("/sync/erp").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products").value(1))
                .andExpect(jsonPath("$.stock").value(1));

        // 3. See the synced catalogue
        mvc.perform(get("/products/COLA").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Cola Can"))
                .andExpect(jsonPath("$.categoryName").value("Beverages"))
                .andExpect(jsonPath("$.unitPrice").value(4.50));

        // 4. See the synced stock
        mvc.perform(get("/inventory/COLA").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(20));
    }
}
