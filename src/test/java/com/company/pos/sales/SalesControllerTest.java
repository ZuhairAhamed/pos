package com.company.pos.sales;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import java.math.BigDecimal;
import java.util.UUID;
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
class SalesControllerTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    CartService carts;
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
    void checkoutViaRestReturnsSale() throws Exception {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));

        mvc.perform(post("/sales").with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"cartId\":\"" + cart + "\",\"tenders\":[{\"method\":\"CASH\",\"tendered\":20.00}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.grandTotal").value(10.35))
                .andExpect(jsonPath("$.payments[0].changeDue").value(9.65))
                .andExpect(jsonPath("$.receiptNumber", matchesPattern("S01-T01-\\d{6}")));
    }

    @Test
    void reprintReturnsNoContent() throws Exception {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("1"));
        String body = mvc.perform(post("/sales").with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"cartId\":\"" + cart + "\",\"tenders\":[{\"method\":\"CASH\",\"tendered\":10.00}]}"))
                .andReturn().getResponse().getContentAsString();
        String saleId = com.jayway.jsonpath.JsonPath.read(body, "$.id");

        mvc.perform(post("/sales/" + saleId + "/reprint").with(jwt().jwt(j -> j.subject("cashier1"))))
                .andExpect(status().isNoContent());
    }

    @Test
    void anonymousCheckoutRejected() throws Exception {
        mvc.perform(post("/sales").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void emailReceiptReturnsNoContent() throws Exception {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("1"));
        String body = mvc.perform(post("/sales").with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"cartId\":\"" + cart + "\",\"tenders\":[{\"method\":\"CASH\",\"tendered\":10.00}]}"))
                .andReturn().getResponse().getContentAsString();
        String saleId = com.jayway.jsonpath.JsonPath.read(body, "$.id");

        mvc.perform(post("/sales/" + saleId + "/send-receipt").with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"email\":\"guest@example.com\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void emailReceiptRejectsMalformedAddress() throws Exception {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("1"));
        String body = mvc.perform(post("/sales").with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"cartId\":\"" + cart + "\",\"tenders\":[{\"method\":\"CASH\",\"tendered\":10.00}]}"))
                .andReturn().getResponse().getContentAsString();
        String saleId = com.jayway.jsonpath.JsonPath.read(body, "$.id");

        mvc.perform(post("/sales/" + saleId + "/send-receipt").with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emailReceiptUnknownSaleReturnsNotFound() throws Exception {
        mvc.perform(post("/sales/" + UUID.randomUUID() + "/send-receipt").with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"email\":\"guest@example.com\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getByReceiptReturnsSale() throws Exception {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("1"));
        String body = mvc.perform(post("/sales").with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"cartId\":\"" + cart + "\",\"tenders\":[{\"method\":\"CASH\",\"tendered\":10.00}]}"))
                .andReturn().getResponse().getContentAsString();
        String receipt = com.jayway.jsonpath.JsonPath.read(body, "$.receiptNumber");
        String saleId = com.jayway.jsonpath.JsonPath.read(body, "$.id");

        mvc.perform(get("/sales/by-receipt/" + receipt).with(jwt().jwt(j -> j.subject("cashier1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(saleId))
                .andExpect(jsonPath("$.receiptNumber").value(receipt))
                .andExpect(jsonPath("$.lines[0].lineNo").exists());
    }

    @Test
    void getByReceiptUnknownReturnsNotFound() throws Exception {
        mvc.perform(get("/sales/by-receipt/NOPE-404-0").with(jwt().jwt(j -> j.subject("cashier1"))))
                .andExpect(status().isNotFound());
    }
}
