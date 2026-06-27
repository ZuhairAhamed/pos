package com.company.pos.product;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.common.util.Identifiers;
import com.company.pos.product.domain.Product;
import com.company.pos.product.infrastructure.ProductRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
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
class ProductControllerTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ProductRepository products;

    @BeforeEach
    void seed() {
        Product p = new Product(Identifiers.newId(), "COLA", "Cola Can");
        p.setUnitPrice(new BigDecimal("4.50"));
        p.setCurrencyCode("SAR");
        products.save(p);
    }

    @Test
    void listRequiresAuthentication() throws Exception {
        mvc.perform(get("/products")).andExpect(status().isUnauthorized());
    }

    @Test
    void listReturnsProductsForAuthenticatedCaller() throws Exception {
        mvc.perform(get("/products").with(jwt()
                        .authorities(new SimpleGrantedAuthority("ROLE_CASHIER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sku").value("COLA"));
    }

    @Test
    void getBySkuReturnsProduct() throws Exception {
        mvc.perform(get("/products/COLA").with(jwt()
                        .authorities(new SimpleGrantedAuthority("ROLE_CASHIER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Cola Can"));
    }

    @Test
    void getByUnknownSkuReturns404() throws Exception {
        mvc.perform(get("/products/NOPE").with(jwt()
                        .authorities(new SimpleGrantedAuthority("ROLE_CASHIER"))))
                .andExpect(status().isNotFound());
    }
}
