package com.company.pos.dining;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.ServiceType;
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
class DiningQuoteSplitControllerTest {

    @Autowired MockMvc mvc;
    @Autowired DiningService dining;
    @Autowired FakeErpClient fake;
    @Autowired ProductSync productSync;

    private UUID orderId;
    private UUID burgerLine;
    private UUID waterLine;

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("WATER", "Water", "BEV", "Beverages", "bcWATER",
                "EA", new BigDecimal("5.00"), "SAR", 1, true));
        productSync.sync();
        UUID tableId = dining.registerTable(new RegisterTableCommand("L" + UUID.randomUUID(), 4)).id();
        orderId = dining.openOrder(new OpenOrderCommand(tableId, ServiceType.DINE_IN), "alice").id();
        dining.addLine(orderId, new AddLineCommand("BURGER", BigDecimal.ONE, null, null), "alice");
        dining.addLine(orderId, new AddLineCommand("WATER", BigDecimal.ONE, null, null), "alice");
        burgerLine = dining.getOrder(orderId).lines().stream()
                .filter(l -> l.sku().equals("BURGER")).findFirst().orElseThrow().id();
        waterLine = dining.getOrder(orderId).lines().stream()
                .filter(l -> l.sku().equals("WATER")).findFirst().orElseThrow().id();
    }

    @Test
    void quoteSplitByItemReturnsOneQuotePerBill() throws Exception {
        // No service charge configured: BURGER 30.00*1.15=34.50, WATER 5.00*1.15=5.75
        mvc.perform(post("/dining/orders/" + orderId + "/quote-split")
                        .with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"mode\":\"BY_ITEM\",\"bills\":["
                                + "{\"lineIds\":[\"" + burgerLine + "\"]},"
                                + "{\"lineIds\":[\"" + waterLine + "\"]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bills[0].grandTotal").value(34.50))
                .andExpect(jsonPath("$.bills[1].grandTotal").value(5.75))
                .andExpect(jsonPath("$.order").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.shares").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void quoteSplitEvenReturnsOrderQuoteAndShares() throws Exception {
        // (30+5)*1.15 = 40.25; /2 -> 20.13, last absorbs -> 20.12
        mvc.perform(post("/dining/orders/" + orderId + "/quote-split")
                        .with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"mode\":\"EVEN\",\"even\":{\"ways\":2}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.grandTotal").value(40.25))
                .andExpect(jsonPath("$.shares[0]").value(20.13))
                .andExpect(jsonPath("$.shares[1]").value(20.12))
                .andExpect(jsonPath("$.bills").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void invalidPartitionIsRejectedAsValidation() throws Exception {
        mvc.perform(post("/dining/orders/" + orderId + "/quote-split")
                        .with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"mode\":\"BY_ITEM\",\"bills\":["
                                + "{\"lineIds\":[\"" + burgerLine + "\"]}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anonymousQuoteSplitRejected() throws Exception {
        mvc.perform(post("/dining/orders/" + UUID.randomUUID() + "/quote-split")
                        .contentType("application/json")
                        .content("{\"mode\":\"EVEN\",\"even\":{\"ways\":2}}"))
                .andExpect(status().isUnauthorized());
    }
}
