package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ApiClientGetTextTest {

    @Test
    void getTextReturnsRawBody() throws Exception {
        try (StubServer stub = new StubServer(200, "from,to\n2026-07-01,2026-07-24\n", "text/csv")) {
            ApiClient client = new ApiClient(stub.baseUrl(), new SessionManager());
            String body = client.getText("/reports/sales?from=2026-07-01&to=2026-07-24&format=csv");
            assertEquals("from,to\n2026-07-01,2026-07-24\n", body);
            assertEquals("/reports/sales", stub.lastPath);
            assertTrue(stub.lastQuery.contains("from=2026-07-01"), "query should contain from param");
            assertTrue(stub.lastQuery.contains("format=csv"), "query should contain format param");
        }
    }

    @Test
    void getTextThrowsOnServerError() throws Exception {
        try (StubServer stub = new StubServer(500, "boom", "text/plain")) {
            ApiClient client = new ApiClient(stub.baseUrl(), new SessionManager());
            assertThrows(ApiException.class,
                    () -> client.getText("/reports/sales?from=2026-07-01&to=2026-07-24&format=csv"));
        }
    }
}
