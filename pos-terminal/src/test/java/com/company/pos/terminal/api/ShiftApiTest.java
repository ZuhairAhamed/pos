package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.ShiftView;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ShiftApiTest {

    private static final String SHIFT_JSON =
            "{\"shiftId\":\"88888888-8888-8888-8888-888888888888\",\"terminalId\":\"T01\","
            + "\"openedBy\":\"manager\",\"status\":\"OPEN\",\"currencyCode\":\"SAR\","
            + "\"openedAt\":\"2026-07-12T06:02:00Z\",\"closedAt\":null}";

    @Test
    void findOpenShiftParsesShift() throws Exception {
        try (StubServer stub = new StubServer(200, SHIFT_JSON, "application/json")) {
            ShiftApi api = new ShiftApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            ShiftView v = api.findOpenShift();
            assertNotNull(v);
            assertEquals("T01", v.terminalId());
            assertEquals(java.util.UUID.fromString("88888888-8888-8888-8888-888888888888"), v.shiftId());
            assertNotNull(v.openedAt());
            assertEquals("OPEN", v.status());
            assertEquals("SAR", v.currencyCode());
            assertEquals("GET", stub.lastMethod);
            assertEquals("/shifts/open", stub.lastPath);
        }
    }

    @Test
    void findOpenShiftReturnsNullWhenNoShiftIsOpen() throws Exception {
        // The server 404s when the terminal has no open shift — that is a normal state, not an error.
        try (StubServer stub = new StubServer(404,
                "{\"title\":\"Not Found\",\"detail\":\"No open shift for this terminal\"}",
                "application/json")) {
            ShiftApi api = new ShiftApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            assertNull(api.findOpenShift());
        }
    }

    @Test
    void findOpenShiftRethrowsNon404Failures() throws Exception {
        try (StubServer stub = new StubServer(500, null, null)) {
            ShiftApi api = new ShiftApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            ApiException ex = assertThrows(ApiException.class, api::findOpenShift);
            assertEquals(500, ex.status());
        }
    }

    @Test
    void openShiftPostsOpeningFloatAndParsesShift() throws Exception {
        try (StubServer stub = new StubServer(201, SHIFT_JSON, "application/json")) {
            ShiftApi api = new ShiftApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            ShiftView v = api.openShift(new BigDecimal("500.00"));
            assertEquals("OPEN", v.status());
            assertEquals("POST", stub.lastMethod);
            assertEquals("/shifts", stub.lastPath);
            assertTrue(stub.lastBody.contains("openingFloat"));
            assertTrue(stub.lastBody.contains("500.00"));
        }
    }
}
