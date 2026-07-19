package com.company.pos.product;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class ProductAdminControllerTest {

    @Autowired MockMvc mvc;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("root")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor cashier() {
        return jwt().jwt(j -> j.subject("cashier")).authorities(new SimpleGrantedAuthority("ROLE_CASHIER"));
    }

    private static RequestPostProcessor manager() {
        return jwt().jwt(j -> j.subject("mgr")).authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    private static final String COLA = "{\"sku\":\"COLA\",\"name\":\"Cola\","
            + "\"categoryName\":\"Beverages\",\"unitPrice\":5.00,"
            + "\"currencyCode\":\"SAR\",\"unitOfMeasure\":\"EA\"}";

    @Test
    void adminCreatesProduct() throws Exception {
        mvc.perform(post("/products").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(COLA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("COLA"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void cashierCannotCreateProduct() throws Exception {
        mvc.perform(post("/products").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON).content(COLA))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotCreateProduct() throws Exception {
        mvc.perform(post("/products").with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content(COLA))
                .andExpect(status().isForbidden());
    }

    @Test
    void cashierCannotListCategories() throws Exception {
        mvc.perform(get("/categories").with(cashier())).andExpect(status().isForbidden());
    }

    @Test
    void adminListsCategoriesAfterCreate() throws Exception {
        mvc.perform(post("/products").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(COLA))
                .andExpect(status().isCreated());
        mvc.perform(get("/categories").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Beverages"));
    }
}
