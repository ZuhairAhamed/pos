package com.company.pos.sales;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
class SalesQuoteControllerTest {

    @Autowired MockMvc mvc;
    @Autowired CartService carts;
    @Autowired FakeErpClient fake;
    @Autowired ProductSync productSync;

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        productSync.sync();
    }

    @Test
    void quoteReturnsAuthoritativeTaxInclusiveTotal() throws Exception {
        UUID cart = carts.createCart();
        carts.addLine(cart, "BURGER", new BigDecimal("2")); // 60.00 net, 15% VAT

        mvc.perform(post("/sales/quote").with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"cartId\":\"" + cart + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(60.00))
                .andExpect(jsonPath("$.taxTotal").value(9.00))
                .andExpect(jsonPath("$.serviceChargeAmount").value(0.00))
                .andExpect(jsonPath("$.grandTotal").value(69.00));
    }

    @Test
    void anonymousQuoteRejected() throws Exception {
        mvc.perform(post("/sales/quote").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void quoteAppliesTransactionDiscountFromBody() throws Exception {
        UUID cart = carts.createCart();
        carts.addLine(cart, "BURGER", new BigDecimal("2"));

        mvc.perform(post("/sales/quote").with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"cartId\":\"" + cart + "\",\"transactionDiscount\":"
                                + "{\"type\":\"PERCENT\",\"value\":10,\"reasonCode\":\"LOYALTY\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountTotal").value(6.00))
                .andExpect(jsonPath("$.grandTotal").value(62.10));
    }
}
