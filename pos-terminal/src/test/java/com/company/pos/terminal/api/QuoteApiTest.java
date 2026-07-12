package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.DiscountInput;
import com.company.pos.terminal.api.dto.DiscountPolicyView;
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

    @Test
    void salesQuoteSerializesTransactionDiscount() throws Exception {
        UUID cart = UUID.fromString("11111111-1111-1111-1111-111111111111");
        try (StubServer stub = new StubServer(200, QUOTE_JSON, "application/json")) {
            SalesApi api = new SalesApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            api.quote(cart, new DiscountInput("PERCENT", new BigDecimal("10"), "LOYALTY"));
            assertEquals("POST", stub.lastMethod);
            assertEquals("/sales/quote", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"cartId\":\"11111111-1111-1111-1111-111111111111\""));
            assertTrue(stub.lastBody.contains("\"type\":\"PERCENT\""));
            assertTrue(stub.lastBody.contains("\"value\":10"));
            assertTrue(stub.lastBody.contains("\"reasonCode\":\"LOYALTY\""));
        }
    }

    @Test
    void diningQuotePostsDiscountBodyToQuoteEndpoint() throws Exception {
        UUID order = UUID.fromString("22222222-2222-2222-2222-222222222222");
        try (StubServer stub = new StubServer(200, QUOTE_JSON, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            api.quoteOrder(order, new DiscountInput("AMOUNT", new BigDecimal("5.00"), "PRICE_MATCH"));
            assertEquals("POST", stub.lastMethod);
            assertEquals("/dining/orders/22222222-2222-2222-2222-222222222222/quote", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"type\":\"AMOUNT\""));
            assertTrue(stub.lastBody.contains("\"reasonCode\":\"PRICE_MATCH\""));
        }
    }

    @Test
    void discountPolicyParsesCapsAndReasonCodes() throws Exception {
        String json = "{\"cashierMaxPercent\":10,\"cashierMaxAmount\":20.00,"
                + "\"reasonCodes\":[\"DAMAGED\",\"PRICE_MATCH\",\"LOYALTY\",\"MANAGER_COMP\"]}";
        try (StubServer stub = new StubServer(200, json, "application/json")) {
            SalesApi api = new SalesApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            DiscountPolicyView p = api.discountPolicy();
            assertEquals(0, new BigDecimal("10").compareTo(p.cashierMaxPercent()));
            assertEquals(0, new BigDecimal("20.00").compareTo(p.cashierMaxAmount()));
            assertEquals(4, p.reasonCodes().size());
            assertEquals("GET", stub.lastMethod);
            assertEquals("/sales/discount-policy", stub.lastPath);
        }
    }
}
