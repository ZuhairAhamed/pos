package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.CartView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CartApiTest {

    private static final UUID CART_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LINE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OPT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final String CART_JSON =
            "{\"cartId\":\"11111111-1111-1111-1111-111111111111\",\"status\":\"OPEN\","
            + "\"currencyCode\":\"SAR\",\"customerId\":null,\"lines\":["
            + "{\"lineId\":\"22222222-2222-2222-2222-222222222222\",\"sku\":\"LATTE\",\"name\":\"Latte\","
            + "\"quantity\":2,\"basePrice\":12.00,\"unitPrice\":14.00,\"currencyCode\":\"SAR\","
            + "\"modifiers\":[{\"optionId\":\"33333333-3333-3333-3333-333333333333\","
            + "\"name\":\"Oat milk\",\"priceDelta\":2.00}]}]}";

    @Test
    void createCartParsesCartId() throws Exception {
        try (StubServer stub = new StubServer(201,
                "{\"cartId\":\"11111111-1111-1111-1111-111111111111\"}", "application/json")) {
            CartApi api = new CartApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            assertEquals(CART_ID, api.createCart());
            assertEquals("POST", stub.lastMethod);
            assertEquals("/carts", stub.lastPath);
        }
    }

    @Test
    void addLinePostsSkuQtyAndModifiers() throws Exception {
        try (StubServer stub = new StubServer(200, CART_JSON, "application/json")) {
            CartApi api = new CartApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            CartView v = api.addLine(CART_ID, "LATTE", new BigDecimal("2"), List.of(OPT_ID));
            assertEquals(1, v.lines().size());
            assertEquals("Latte", v.lines().get(0).name());
            assertEquals(0, new BigDecimal("14.00").compareTo(v.lines().get(0).unitPrice()));
            assertEquals("Oat milk", v.lines().get(0).modifiers().get(0).name());
            assertEquals("POST", stub.lastMethod);
            assertEquals("/carts/11111111-1111-1111-1111-111111111111/lines", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"sku\":\"LATTE\""));
            assertTrue(stub.lastBody.contains("\"quantity\":2"));
            assertTrue(stub.lastBody.contains("modifierOptionIds"));
        }
    }

    @Test
    void updateLinePutsQuantityAsBody() throws Exception {
        try (StubServer stub = new StubServer(200, CART_JSON, "application/json")) {
            CartApi api = new CartApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            api.updateLine(CART_ID, LINE_ID, new BigDecimal("3"));
            assertEquals("PUT", stub.lastMethod);
            assertEquals("/carts/11111111-1111-1111-1111-111111111111/lines/"
                    + "22222222-2222-2222-2222-222222222222", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"quantity\":3"));
        }
    }

    @Test
    void removeLineDeletesThenRefetches() throws Exception {
        try (StubServer stub = new StubServer(200, CART_JSON, "application/json")) {
            CartApi api = new CartApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            CartView v = api.removeLine(CART_ID, LINE_ID);
            assertEquals("OPEN", v.status());
            String linePath = "/carts/11111111-1111-1111-1111-111111111111/lines/"
                    + "22222222-2222-2222-2222-222222222222";
            assertNotNull(stub.requestTo("DELETE", linePath));
            assertNotNull(stub.requestTo("GET", "/carts/11111111-1111-1111-1111-111111111111"));
        }
    }
}
