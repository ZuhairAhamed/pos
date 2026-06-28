package com.company.pos.cart;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Transactional
class CartControllerTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
    }

    @Test
    void createAddAndReadCart() throws Exception {
        String created = mvc.perform(post("/carts").with(jwt().jwt(j -> j.subject("cashier"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String cartId = JsonPath.read(created, "$.cartId");

        mvc.perform(post("/carts/" + cartId + "/lines").with(jwt().jwt(j -> j.subject("cashier")))
                        .contentType("application/json")
                        .content("{\"sku\":\"COLA\",\"quantity\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].sku").value("COLA"))
                .andExpect(jsonPath("$.lines[0].quantity").value(3));

        mvc.perform(get("/carts/" + cartId).with(jwt().jwt(j -> j.subject("cashier"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(post("/carts")).andExpect(status().isUnauthorized());
    }
}
