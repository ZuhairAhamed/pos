package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.CheckoutRequest;
import com.company.pos.terminal.api.dto.DiscountInput;
import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.api.dto.TenderInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SalesApiTest {

    private static final UUID CART_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final String SALE_JSON =
            "{\"id\":\"55555555-5555-5555-5555-555555555555\",\"receiptNumber\":\"S01-T01-9\","
            + "\"status\":\"COMPLETED\",\"currencyCode\":\"SAR\",\"subtotal\":25.00,\"taxTotal\":3.75,"
            + "\"grandTotal\":28.75,\"discountTotal\":0.00,\"serviceChargeAmount\":0.00,"
            + "\"lines\":[],\"payments\":[]}";

    @Test
    void checkoutPostsCartIdAndTendersAndParsesSale() throws Exception {
        try (StubServer stub = new StubServer(201, SALE_JSON, "application/json")) {
            SalesApi api = new SalesApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            CheckoutRequest req = new CheckoutRequest(CART_ID,
                    List.of(new TenderInput("CASH", new BigDecimal("28.75"), new BigDecimal("30.00"))),
                    Map.of(), null, false);
            SaleView s = api.checkout(req);
            assertEquals("S01-T01-9", s.receiptNumber());
            assertEquals("POST", stub.lastMethod);
            assertEquals("/sales", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"cartId\":\"11111111-1111-1111-1111-111111111111\""));
            assertTrue(stub.lastBody.contains("\"method\":\"CASH\""));
            assertTrue(stub.lastBody.contains("tenders"));
        }
    }

    @Test
    void checkoutSerializesTypedTransactionDiscount() throws Exception {
        try (StubServer stub = new StubServer(201, SALE_JSON, "application/json")) {
            SalesApi api = new SalesApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            api.checkout(new CheckoutRequest(CART_ID,
                    List.of(new TenderInput("CARD", new BigDecimal("62.10"), null)),
                    Map.of(),
                    new DiscountInput("PERCENT", new BigDecimal("10"), "LOYALTY"),
                    false));
            assertTrue(stub.lastBody.contains("\"transactionDiscount\":{"));
            assertTrue(stub.lastBody.contains("\"reasonCode\":\"LOYALTY\""));
        }
    }

    @Test
    void emailReceiptPostsAddressToSendReceiptEndpoint() throws Exception {
        UUID saleId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        try (StubServer stub = new StubServer(204, "", "application/json")) {
            SalesApi api = new SalesApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            api.emailReceipt(saleId, "guest@example.com");
            assertEquals("POST", stub.lastMethod);
            assertEquals("/sales/" + saleId + "/send-receipt", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"email\":\"guest@example.com\""));
        }
    }

    @Test
    void getByReceiptHitsPathAndParsesLineNo() throws Exception {
        String saleJson = "{\"id\":\"11111111-1111-1111-1111-111111111111\","
                + "\"receiptNumber\":\"S01-T01-9\",\"currencyCode\":\"SAR\",\"subtotal\":4.50,"
                + "\"taxTotal\":0.68,\"serviceChargeAmount\":0.00,\"grandTotal\":5.18,"
                + "\"discountTotal\":0.00,\"payments\":[],"
                + "\"lines\":[{\"lineNo\":1,\"sku\":\"COLA\",\"name\":\"Cola Can\",\"quantity\":1,"
                + "\"unitPrice\":4.50,\"lineTotal\":5.18,\"modifiers\":[]}]}";
        try (StubServer stub = new StubServer(200, saleJson, "application/json")) {
            SalesApi api = new SalesApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            SaleView s = api.getSaleByReceipt("S01-T01-9");
            assertEquals("S01-T01-9", s.receiptNumber());
            assertEquals(1, s.lines().get(0).lineNo());
            assertEquals(0, new BigDecimal("4.50").compareTo(s.lines().get(0).unitPrice()));
            assertEquals("GET", stub.lastMethod);
            assertEquals("/sales/by-receipt/S01-T01-9", stub.lastPath);
        }
    }
}
