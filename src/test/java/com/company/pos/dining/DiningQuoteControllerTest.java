package com.company.pos.dining;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.ServiceType;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
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
class DiningQuoteControllerTest {

    @Autowired MockMvc mvc;
    @Autowired DiningService dining;
    @Autowired ConfigurationService config;
    @Autowired FakeErpClient fake;
    @Autowired ProductSync productSync;

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        productSync.sync();
        config.put(SettingKey.SERVICE_CHARGE_ENABLED, "true");
        config.put(SettingKey.SERVICE_CHARGE_PERCENT, "10");
    }

    @Test
    void quoteOrderViaRestReturnsAuthoritativeTotal() throws Exception {
        UUID tableId = dining.registerTable(new RegisterTableCommand("L" + UUID.randomUUID(), 4)).id();
        UUID orderId = dining.openOrder(new OpenOrderCommand(tableId, ServiceType.DINE_IN), "alice").id();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("2"), null, null), "alice");

        mvc.perform(get("/dining/orders/" + orderId + "/quote")
                        .with(jwt().jwt(j -> j.subject("cashier1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceChargeAmount").value(6.00))
                .andExpect(jsonPath("$.grandTotal").value(75.90));
    }

    @Test
    void anonymousQuoteRejected() throws Exception {
        mvc.perform(get("/dining/orders/" + UUID.randomUUID() + "/quote"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postQuoteAppliesTransactionDiscount() throws Exception {
        UUID tableId = dining.registerTable(new RegisterTableCommand("L" + UUID.randomUUID(), 4)).id();
        UUID orderId = dining.openOrder(new OpenOrderCommand(tableId, ServiceType.DINE_IN), "alice").id();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("2"), null, null), "alice");

        mvc.perform(post("/dining/orders/" + orderId + "/quote")
                        .with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"transactionDiscount\":"
                                + "{\"type\":\"PERCENT\",\"value\":10,\"reasonCode\":\"LOYALTY\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountTotal").value(6.00))
                .andExpect(jsonPath("$.serviceChargeAmount").value(5.40))
                .andExpect(jsonPath("$.grandTotal").value(68.31));
    }
}
