package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuthApiTest {

    @Test
    void loginStoresTokenAndRoles() throws Exception {
        // Stub returns a token for POST /auth/login AND the same body for GET /auth/me.
        // The MeResponse fields are ignored by the token parse and vice-versa (ignore-unknown).
        String body = "{\"token\":\"jwt-xyz\",\"username\":\"alice\",\"roles\":[\"MANAGER\"]}";
        try (StubServer stub = new StubServer(200, body, "application/json")) {
            SessionManager session = new SessionManager();
            AuthApi auth = new AuthApi(new ApiClient(stub.baseUrl(), session), session);

            auth.login("alice", "pw");

            assertEquals("jwt-xyz", session.token());
            assertEquals("alice", session.username());
            assertTrue(session.isAuthenticated());
            assertTrue(session.isManager());
        }
    }

    @Test
    void loginSendsUsernamePasswordToLoginEndpoint() throws Exception {
        String body = "{\"token\":\"jwt-xyz\",\"username\":\"alice\",\"roles\":[\"CASHIER\"]}";
        try (StubServer stub = new StubServer(200, body, "application/json")) {
            SessionManager session = new SessionManager();
            AuthApi auth = new AuthApi(new ApiClient(stub.baseUrl(), session), session);

            auth.login("alice", "pw");

            // Final recorded request is GET /auth/me carrying the freshly-stored bearer token.
            assertEquals("GET", stub.lastMethod);
            assertEquals("/auth/me", stub.lastPath);
            assertEquals("Bearer jwt-xyz", stub.lastAuth);
            assertTrue(stub.pathsHit().contains("/auth/login"));
            assertFalse(session.isManager());
        }
    }

    @Test
    void pinLoginSendsCashierCodePinToPinEndpoint() throws Exception {
        String body = "{\"token\":\"jwt-pin\",\"username\":\"bob\",\"roles\":[\"CASHIER\"]}";
        try (StubServer stub = new StubServer(200, body, "application/json")) {
            SessionManager session = new SessionManager();
            AuthApi auth = new AuthApi(new ApiClient(stub.baseUrl(), session), session);

            auth.pinLogin("C01", "1234");

            assertEquals("jwt-pin", session.token());
            assertEquals("bob", session.username());
            assertTrue(session.isAuthenticated());
            assertTrue(stub.pathsHit().contains("/auth/pin-login"));
            assertEquals("/auth/me", stub.lastPath);
        }
    }
}
