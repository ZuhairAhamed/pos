package com.company.pos.dining;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
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
class DiningLineControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private static RequestPostProcessor cashier() {
        return jwt().jwt(j -> j.subject("cashier"))
                .authorities(new SimpleGrantedAuthority("ROLE_CASHIER"));
    }

    private static RequestPostProcessor manager() {
        return jwt().jwt(j -> j.subject("manager"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    private String openOrderWithLine() throws Exception {
        String table = mvc.perform(post("/dining/tables").with(manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"CT1\",\"seats\":4}"))
                .andReturn().getResponse().getContentAsString();
        String tableId = com.jayway.jsonpath.JsonPath.read(table, "$.id");

        String order = mvc.perform(post("/dining/orders").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":\"" + tableId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = com.jayway.jsonpath.JsonPath.read(order, "$.id");

        mvc.perform(post("/dining/orders/" + orderId + "/lines").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"BURGER\",\"qty\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].sku").value("BURGER"));
        return orderId;
    }

    @Test
    void cashierCannotRemoveLine() throws Exception {
        String orderId = openOrderWithLine();
        String lineBody = mvc.perform(post("/dining/orders/" + orderId + "/lines").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"BURGER\",\"qty\":1}"))
                .andReturn().getResponse().getContentAsString();
        String lineId = com.jayway.jsonpath.JsonPath.read(lineBody, "$.lines[1].id");

        mvc.perform(delete("/dining/orders/" + orderId + "/lines/" + lineId).with(cashier()))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanRemoveLine() throws Exception {
        String orderId = openOrderWithLine();
        String lineBody = mvc.perform(post("/dining/orders/" + orderId + "/lines").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"BURGER\",\"qty\":1}"))
                .andReturn().getResponse().getContentAsString();
        String lineId = com.jayway.jsonpath.JsonPath.read(lineBody, "$.lines[1].id");

        mvc.perform(delete("/dining/orders/" + orderId + "/lines/" + lineId).with(manager()))
                .andExpect(status().isOk());
    }
}
