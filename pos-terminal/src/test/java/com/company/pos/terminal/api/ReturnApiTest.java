package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.dto.ReturnCommand;
import com.company.pos.terminal.api.dto.ReturnLineRequest;
import com.company.pos.terminal.api.dto.ReturnView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReturnApiTest {

    private static final UUID SALE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String RETURN_JSON =
            "{\"id\":\"22222222-2222-2222-2222-222222222222\",\"creditNoteNumber\":\"CN-1\","
            + "\"originalSaleId\":\"11111111-1111-1111-1111-111111111111\",\"status\":\"COMPLETED\","
            + "\"currencyCode\":\"SAR\",\"refundSubtotal\":9.00,\"refundTaxTotal\":1.35,"
            + "\"refundGrandTotal\":10.35,\"lines\":[],"
            + "\"refunds\":[{\"method\":\"CASH\",\"amount\":10.35,\"maskedPan\":null}]}";

    @Test
    void processPostsReturnWithManagerToken() throws Exception {
        try (StubServer stub = new StubServer(201, RETURN_JSON, "application/json")) {
            ReturnApi api = new ReturnApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            ReturnView v = api.process(new ReturnCommand(SALE_ID, null,
                    List.of(new ReturnLineRequest(1, new BigDecimal("2")))), "mgr-token");
            assertEquals("CN-1", v.creditNoteNumber());
            assertEquals(0, new BigDecimal("10.35").compareTo(v.refundGrandTotal()));
            assertEquals("POST", stub.lastMethod);
            assertEquals("/returns", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"originalSaleId\":\"" + SALE_ID + "\""));
            assertTrue(stub.lastBody.contains("\"lineNo\":1"));
            assertTrue(stub.lastBody.contains("\"quantity\":2"));
            assertEquals("Bearer mgr-token", stub.lastAuth);
        }
    }
}
