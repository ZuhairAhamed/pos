package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.QuoteView;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QuoteApiTest {

    private static final String QUOTE_JSON =
            "{\"currencyCode\":\"SAR\",\"subtotal\":60.00,\"discountTotal\":0.00,"
            + "\"serviceChargeAmount\":0.00,\"taxTotal\":9.00,\"grandTotal\":69.00}";

    @Test
    void salesQuotePostsCartIdAndParsesGrandTotal() throws Exception {
        UUID cart = UUID.fromString("11111111-1111-1111-1111-111111111111");
        try (StubServer stub = new StubServer(200, QUOTE_JSON, "application/json")) {
            SalesApi api = new SalesApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            QuoteView q = api.quote(cart);
            assertEquals(0, new BigDecimal("69.00").compareTo(q.grandTotal()));
            assertEquals(0, new BigDecimal("9.00").compareTo(q.taxTotal()));
            assertEquals("POST", stub.lastMethod);
            assertEquals("/sales/quote", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"cartId\":\"11111111-1111-1111-1111-111111111111\""));
        }
    }

    @Test
    void diningQuoteOrderGetsOrderQuote() throws Exception {
        UUID order = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String json = "{\"currencyCode\":\"SAR\",\"subtotal\":60.00,\"discountTotal\":0.00,"
                + "\"serviceChargeAmount\":6.00,\"taxTotal\":9.90,\"grandTotal\":75.90}";
        try (StubServer stub = new StubServer(200, json, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            QuoteView q = api.quoteOrder(order);
            assertEquals(0, new BigDecimal("75.90").compareTo(q.grandTotal()));
            assertEquals(0, new BigDecimal("6.00").compareTo(q.serviceChargeAmount()));
            assertEquals("GET", stub.lastMethod);
            assertEquals("/dining/orders/22222222-2222-2222-2222-222222222222/quote", stub.lastPath);
        }
    }
}
