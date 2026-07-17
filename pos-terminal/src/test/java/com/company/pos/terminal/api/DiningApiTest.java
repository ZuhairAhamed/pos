package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DiningApiTest {

    private static final UUID ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID TABLE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID LINE_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private static final String ORDER_JSON =
            "{\"id\":\"33333333-3333-3333-3333-333333333333\","
            + "\"tableId\":\"44444444-4444-4444-4444-444444444444\","
            + "\"serviceType\":\"DINE_IN\",\"status\":\"OPEN\","
            + "\"openedBy\":\"cashier\",\"openedAt\":\"2026-07-11T10:00:00Z\","
            + "\"closedAt\":null,\"saleId\":null,"
            + "\"lines\":[{\"id\":\"66666666-6666-6666-6666-666666666666\",\"sku\":\"BURGER\","
            + "\"qty\":2,\"note\":\"no onion\",\"course\":\"MAIN\","
            + "\"firedAt\":\"2026-07-11T10:05:00Z\",\"modifiers\":["
            + "{\"optionId\":\"77777777-7777-7777-7777-777777777777\",\"name\":\"Cheese\",\"priceDelta\":2.00}]}]}";

    @Test
    void listTablesParsesTables() throws Exception {
        String json = "[{\"id\":\"44444444-4444-4444-4444-444444444444\",\"label\":\"T1\","
                + "\"seats\":4,\"active\":true}]";
        try (StubServer stub = new StubServer(200, json, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            List<TableView> tables = api.tables();
            assertEquals(1, tables.size());
            assertEquals("T1", tables.get(0).label());
            assertEquals(4, tables.get(0).seats());
            assertTrue(tables.get(0).active());
            assertEquals("GET", stub.lastMethod);
            assertEquals("/dining/tables", stub.lastPath);
        }
    }

    @Test
    void openOrdersParsesOpenOrderSummaries() throws Exception {
        String json = "[{\"orderId\":\"33333333-3333-3333-3333-333333333333\","
                + "\"tableId\":\"44444444-4444-4444-4444-444444444444\",\"tableLabel\":\"T1\","
                + "\"openedAt\":\"2026-07-11T10:00:00Z\",\"lineCount\":3,"
                + "\"serviceType\":\"QUICK_SERVICE\"}]";
        try (StubServer stub = new StubServer(200, json, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            List<OpenOrderView> orders = api.openOrders();
            assertEquals(1, orders.size());
            assertEquals(ORDER_ID, orders.get(0).orderId());
            assertEquals("T1", orders.get(0).tableLabel());
            assertEquals(3, orders.get(0).lineCount());
            assertEquals("QUICK_SERVICE", orders.get(0).serviceType());
            assertEquals("GET", stub.lastMethod);
            assertEquals("/dining/orders", stub.lastPath);
        }
    }

    @Test
    void openOrderPostsQuickServiceWhenRequested() throws Exception {
        try (StubServer stub = new StubServer(201, ORDER_JSON, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            api.openOrder(TABLE_ID, "QUICK_SERVICE");
            assertEquals("POST", stub.lastMethod);
            assertEquals("/dining/orders", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"serviceType\":\"QUICK_SERVICE\""));
            assertTrue(stub.lastBody.contains("tableId"));
        }
    }

    @Test
    void orderParsesFullOrderWithLinesAndModifiers() throws Exception {
        try (StubServer stub = new StubServer(200, ORDER_JSON, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            OrderView v = api.order(ORDER_ID);
            assertEquals("OPEN", v.status());
            assertEquals("DINE_IN", v.serviceType());
            assertEquals(1, v.lines().size());
            OrderLineView line = v.lines().get(0);
            assertEquals("BURGER", line.sku());
            assertEquals("MAIN", line.course());
            assertTrue(line.fired());
            assertEquals(1, line.modifiers().size());
            assertEquals("Cheese", line.modifiers().get(0).name());
            assertEquals("GET", stub.lastMethod);
            assertEquals("/dining/orders/33333333-3333-3333-3333-333333333333", stub.lastPath);
        }
    }

    @Test
    void openOrderPostsDineInAndParsesOrder() throws Exception {
        try (StubServer stub = new StubServer(201, ORDER_JSON, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            OrderView v = api.openOrder(TABLE_ID);
            assertEquals("OPEN", v.status());
            assertEquals("POST", stub.lastMethod);
            assertEquals("/dining/orders", stub.lastPath);
            assertTrue(stub.lastBody.contains("DINE_IN"));
            assertTrue(stub.lastBody.contains("tableId"));
            assertTrue(stub.lastBody.contains("serviceType"));
        }
    }

    @Test
    void addLinePostsCommandFields() throws Exception {
        try (StubServer stub = new StubServer(200, ORDER_JSON, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            AddLineRequest req = new AddLineRequest("BURGER", new BigDecimal("2"), "no onion", "MAIN",
                    List.of(UUID.fromString("77777777-7777-7777-7777-777777777777")));
            api.addLine(ORDER_ID, req);
            assertEquals("POST", stub.lastMethod);
            assertEquals("/dining/orders/33333333-3333-3333-3333-333333333333/lines", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"sku\":\"BURGER\""));
            assertTrue(stub.lastBody.contains("\"qty\":2"));
            assertTrue(stub.lastBody.contains("\"note\":\"no onion\""));
            assertTrue(stub.lastBody.contains("\"course\":\"MAIN\""));
            assertTrue(stub.lastBody.contains("modifierOptionIds"));
        }
    }

    @Test
    void updateLineSendsQtyNoteAndCourseAsQueryParams() throws Exception {
        try (StubServer stub = new StubServer(200, ORDER_JSON, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            api.updateLine(ORDER_ID, LINE_ID, new BigDecimal("3"), "no onion", "STARTER");
            assertEquals("PUT", stub.lastMethod);
            assertEquals("/dining/orders/33333333-3333-3333-3333-333333333333/lines/"
                    + "66666666-6666-6666-6666-666666666666", stub.lastPath);
            assertTrue(stub.lastQuery.contains("qty=3"), stub.lastQuery);
            assertTrue(stub.lastQuery.contains("note=no+onion"), stub.lastQuery);
            assertTrue(stub.lastQuery.contains("course=STARTER"), stub.lastQuery);
        }
    }

    @Test
    void updateLineOmitsNullNoteAndCourse() throws Exception {
        try (StubServer stub = new StubServer(200, ORDER_JSON, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            api.updateLine(ORDER_ID, LINE_ID, new BigDecimal("3"), null, null);
            assertEquals("PUT", stub.lastMethod);
            assertEquals("qty=3", stub.lastQuery);
        }
    }

    @Test
    void removeLineDeletesAndReturnsOrder() throws Exception {
        try (StubServer stub = new StubServer(200, ORDER_JSON, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            OrderView v = api.removeLine(ORDER_ID, LINE_ID);
            assertEquals("OPEN", v.status());
            String linePath = "/dining/orders/33333333-3333-3333-3333-333333333333/lines/"
                    + "66666666-6666-6666-6666-666666666666";
            // removeLine issues DELETE on the line, then re-fetches the order via GET.
            assertNotNull(stub.requestTo("DELETE", linePath));
            assertNotNull(stub.requestTo("GET", "/dining/orders/33333333-3333-3333-3333-333333333333"));
        }
    }

    @Test
    void firePostsToFireEndpoint() throws Exception {
        try (StubServer stub = new StubServer(200, ORDER_JSON, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            api.fire(ORDER_ID);
            assertEquals("POST", stub.lastMethod);
            assertEquals("/dining/orders/33333333-3333-3333-3333-333333333333/fire", stub.lastPath);
        }
    }

    @Test
    void closeSendsTendersAndParsesSale() throws Exception {
        String sale = "{\"id\":\"55555555-5555-5555-5555-555555555555\",\"receiptNumber\":\"S01-T01-1\","
                + "\"status\":\"COMPLETED\",\"currencyCode\":\"SAR\","
                + "\"subtotal\":25.00,\"taxTotal\":3.75,\"grandTotal\":28.75,"
                + "\"createdAt\":\"2026-07-11T10:30:00Z\",\"lines\":[],\"payments\":[],"
                + "\"discountTotal\":0.00,\"txnDiscountAmount\":null,\"txnDiscountType\":null,"
                + "\"txnDiscountReason\":null,\"serviceChargeAmount\":0.00}";
        try (StubServer stub = new StubServer(201, sale, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            CloseOrderRequest req = new CloseOrderRequest(
                    List.of(new TenderInput("CASH", new BigDecimal("28.75"), new BigDecimal("30.00"))),
                    Map.of(), null, false);
            SaleView s = api.close(ORDER_ID, req);
            assertEquals("S01-T01-1", s.receiptNumber());
            assertEquals(0, new BigDecimal("25.00").compareTo(s.subtotal()));
            assertEquals(0, new BigDecimal("3.75").compareTo(s.taxTotal()));
            assertEquals(0, new BigDecimal("0.00").compareTo(s.serviceChargeAmount()));
            assertEquals(0, new BigDecimal("28.75").compareTo(s.grandTotal()));
            assertEquals("SAR", s.currencyCode());
            assertEquals("POST", stub.lastMethod);
            assertEquals("/dining/orders/33333333-3333-3333-3333-333333333333/close", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"method\":\"CASH\""));
            assertTrue(stub.lastBody.contains("tenders"));
            assertTrue(stub.lastBody.contains("lineDiscounts"));
            assertTrue(stub.lastBody.contains("waiveServiceCharge"));
        }
    }

    @Test
    void reprintPostsToReprintEndpoint() throws Exception {
        try (StubServer stub = new StubServer(204, null, null)) {
            SalesApi api = new SalesApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            api.reprint(UUID.fromString("55555555-5555-5555-5555-555555555555"));
            assertEquals("POST", stub.lastMethod);
            assertEquals("/sales/55555555-5555-5555-5555-555555555555/reprint", stub.lastPath);
        }
    }

    @Test
    void quoteSplitByItemPostsModeAndBills() throws Exception {
        String json = "{\"bills\":[{\"currencyCode\":\"SAR\",\"subtotal\":30.00,\"discountTotal\":0,"
                + "\"serviceChargeAmount\":0,\"taxTotal\":4.50,\"grandTotal\":34.50}],"
                + "\"order\":null,\"shares\":null}";
        try (StubServer stub = new StubServer(200, json, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            SplitQuoteView v = api.quoteSplit(ORDER_ID, new QuoteSplitRequest("BY_ITEM",
                    List.of(new QuoteBillInput(List.of(LINE_ID))), null));
            assertEquals(1, v.bills().size());
            assertEquals(0, new BigDecimal("34.50").compareTo(v.bills().get(0).grandTotal()));
            assertNull(v.order());
            assertNull(v.shares());
            assertEquals("POST", stub.lastMethod);
            assertEquals("/dining/orders/33333333-3333-3333-3333-333333333333/quote-split", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"mode\":\"BY_ITEM\""));
            assertTrue(stub.lastBody.contains("\"lineIds\":[\"66666666-6666-6666-6666-666666666666\"]"));
        }
    }

    @Test
    void quoteSplitEvenPostsWaysAndParsesShares() throws Exception {
        String json = "{\"bills\":null,"
                + "\"order\":{\"currencyCode\":\"SAR\",\"subtotal\":35.00,\"discountTotal\":0,"
                + "\"serviceChargeAmount\":0,\"taxTotal\":5.25,\"grandTotal\":40.25},"
                + "\"shares\":[13.42,13.42,13.41]}";
        try (StubServer stub = new StubServer(200, json, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            SplitQuoteView v = api.quoteSplit(ORDER_ID,
                    new QuoteSplitRequest("EVEN", null, new QuoteEvenInput(3)));
            assertEquals(0, new BigDecimal("40.25").compareTo(v.order().grandTotal()));
            assertEquals(3, v.shares().size());
            assertEquals(0, new BigDecimal("13.41").compareTo(v.shares().get(2)));
            assertTrue(stub.lastBody.contains("\"mode\":\"EVEN\""));
            assertTrue(stub.lastBody.contains("\"ways\":3"));
        }
    }

    @Test
    void closeSplitPostsBillsAndParsesSaleList() throws Exception {
        String sales = "[{\"id\":\"55555555-5555-5555-5555-555555555555\",\"receiptNumber\":\"S01-T01-1\","
                + "\"currencyCode\":\"SAR\",\"subtotal\":30.00,\"taxTotal\":4.50,\"grandTotal\":34.50,"
                + "\"discountTotal\":0.00,\"serviceChargeAmount\":0.00,\"lines\":[],\"payments\":[]},"
                + "{\"id\":\"88888888-8888-8888-8888-888888888888\",\"receiptNumber\":\"S01-T01-2\","
                + "\"currencyCode\":\"SAR\",\"subtotal\":17.00,\"taxTotal\":2.55,\"grandTotal\":19.55,"
                + "\"discountTotal\":0.00,\"serviceChargeAmount\":0.00,\"lines\":[],\"payments\":[]}]";
        try (StubServer stub = new StubServer(201, sales, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            SplitCloseRequest req = new SplitCloseRequest("BY_ITEM", List.of(
                    new BillRequest(List.of(LINE_ID),
                            List.of(new TenderInput("CASH", new BigDecimal("34.50"), new BigDecimal("50.00"))),
                            Map.of(), null)),
                    null, false);
            List<SaleView> result = api.closeSplit(ORDER_ID, req);
            assertEquals(2, result.size());
            assertEquals("S01-T01-2", result.get(1).receiptNumber());
            assertEquals("POST", stub.lastMethod);
            assertEquals("/dining/orders/33333333-3333-3333-3333-333333333333/close-split", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"mode\":\"BY_ITEM\""));
            assertTrue(stub.lastBody.contains("\"method\":\"CASH\""));
            assertTrue(stub.lastBody.contains("\"tendered\":50.00"));
            assertTrue(stub.lastBody.contains("\"waiveServiceCharge\":false"));
        }
    }

    @Test
    void closeSplitEvenPostsWaysAndMethods() throws Exception {
        String sales = "[{\"id\":\"55555555-5555-5555-5555-555555555555\",\"receiptNumber\":\"S01-T01-1\","
                + "\"currencyCode\":\"SAR\",\"subtotal\":35.00,\"taxTotal\":5.25,\"grandTotal\":40.25,"
                + "\"discountTotal\":0.00,\"serviceChargeAmount\":0.00,\"lines\":[],\"payments\":[]}]";
        try (StubServer stub = new StubServer(201, sales, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            SplitCloseRequest req = new SplitCloseRequest("EVEN", null,
                    new EvenSplitRequest(3, List.of("CASH", "CARD", "CASH")), false);
            List<SaleView> result = api.closeSplit(ORDER_ID, req);
            assertEquals(1, result.size());
            assertTrue(stub.lastBody.contains("\"mode\":\"EVEN\""));
            assertTrue(stub.lastBody.contains("\"ways\":3"));
            assertTrue(stub.lastBody.contains("\"methods\":[\"CASH\",\"CARD\",\"CASH\"]"));
        }
    }

    @Test
    void voidOrderPostsToVoidEndpointWithReasonAndBearer() throws Exception {
        try (StubServer stub = new StubServer(204, null, null)) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            api.voidOrder(ORDER_ID, "walk out", "mgr-token-123");
            assertEquals("POST", stub.lastMethod);
            assertEquals("/dining/orders/33333333-3333-3333-3333-333333333333/void", stub.lastPath);
            assertTrue(stub.lastQuery.contains("reason=walk+out"), stub.lastQuery);
            assertEquals("Bearer mgr-token-123", stub.lastAuth);
        }
    }
}
