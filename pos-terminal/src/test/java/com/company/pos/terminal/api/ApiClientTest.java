package com.company.pos.terminal.api;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiClientTest {

    record Echo(String token) {}

    @Test
    void getDeserializesJsonAndSendsBearerToken() throws Exception {
        try (StubServer stub = new StubServer(200, "{\"token\":\"abc\"}", "application/json")) {
            SessionManager session = new SessionManager();
            session.setToken("jwt-123");
            ApiClient client = new ApiClient(stub.baseUrl(), session);

            Echo body = client.get("/auth/me", new TypeReference<Echo>() {});

            assertEquals("abc", body.token());
            assertEquals("GET", stub.lastMethod);
            assertEquals("/auth/me", stub.lastPath);
            assertEquals("Bearer jwt-123", stub.lastAuth);
        }
    }

    @Test
    void postSerializesBody() throws Exception {
        try (StubServer stub = new StubServer(200, "{\"token\":\"t\"}", "application/json")) {
            ApiClient client = new ApiClient(stub.baseUrl(), new SessionManager());
            client.post("/auth/login", new Echo("pw"), new TypeReference<Echo>() {});
            assertEquals("POST", stub.lastMethod);
            assertTrue(stub.lastBody.contains("\"token\":\"pw\""));
        }
    }

    @Test
    void nonSuccessThrowsApiExceptionWithProblemDetail() throws Exception {
        String problem = "{\"title\":\"Bad Request\",\"status\":400,\"detail\":\"reason required\"}";
        try (StubServer stub = new StubServer(400, problem, "application/problem+json")) {
            ApiClient client = new ApiClient(stub.baseUrl(), new SessionManager());
            ApiException ex = assertThrows(ApiException.class,
                    () -> client.get("/x", new TypeReference<Echo>() {}));
            assertEquals(400, ex.status());
            assertNotNull(ex.problem());
            assertEquals("reason required", ex.problem().detail());
        }
    }

    @Test
    void sessionManagerRolesDriveIsManager() {
        SessionManager s = new SessionManager();
        s.setUser("m", java.util.Set.of("MANAGER"));
        assertTrue(s.isManager());
        s.setUser("c", java.util.Set.of("CASHIER"));
        assertFalse(s.isManager());
        s.clear();
        assertFalse(s.isAuthenticated());
    }
}
