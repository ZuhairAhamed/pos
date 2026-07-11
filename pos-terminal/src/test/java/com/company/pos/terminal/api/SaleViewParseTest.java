package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.SaleView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SaleViewParseTest {

    private static final String JSON =
            "{\"id\":\"55555555-5555-5555-5555-555555555555\",\"receiptNumber\":\"S01-T01-7\","
            + "\"status\":\"COMPLETED\",\"currencyCode\":\"SAR\",\"subtotal\":25.00,\"taxTotal\":3.75,"
            + "\"grandTotal\":28.75,\"createdAt\":\"2026-07-11T10:30:00Z\","
            + "\"discountTotal\":1.00,\"txnDiscountAmount\":null,\"serviceChargeAmount\":0.00,"
            + "\"lines\":[{\"lineNo\":1,\"sku\":\"LATTE\",\"name\":\"Latte\",\"quantity\":2,"
            + "\"unitPrice\":14.00,\"lineTotal\":28.00,\"currencyCode\":\"SAR\","
            + "\"modifiers\":[{\"name\":\"Oat milk\",\"priceDelta\":2.00}]}],"
            + "\"payments\":[{\"method\":\"CASH\",\"amount\":28.75,\"tendered\":30.00,\"changeGiven\":1.25}]}";

    @Test
    void parsesLinesPaymentsAndDiscountTolerantOfExtraFields() throws Exception {
        ObjectMapper mapper = ApiClient.defaultMapper();
        SaleView v = mapper.readValue(JSON, new TypeReference<SaleView>() {});
        assertEquals("S01-T01-7", v.receiptNumber());
        assertEquals(0, new java.math.BigDecimal("1.00").compareTo(v.discountTotal()));
        assertEquals(1, v.lines().size());
        assertEquals("Latte", v.lines().get(0).name());
        assertEquals("Oat milk", v.lines().get(0).modifiers().get(0).name());
        assertEquals(1, v.payments().size());
        assertEquals("CASH", v.payments().get(0).method());
        assertEquals(0, new java.math.BigDecimal("1.25").compareTo(v.payments().get(0).changeGiven()));
    }
}
