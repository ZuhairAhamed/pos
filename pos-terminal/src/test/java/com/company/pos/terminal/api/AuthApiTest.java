package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
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

            // Assert POST /auth/login body serializes the correct field names and values.
            StubServer.RecordedRequest loginReq = stub.requestTo("POST", "/auth/login");
            assertNotNull(loginReq, "POST /auth/login was not recorded");
            JsonNode loginBody = ApiClient.defaultMapper().readTree(loginReq.body());
            assertEquals("alice", loginBody.get("username").asText(), "login body must use field name 'username'");
            assertEquals("pw", loginBody.get("password").asText(), "login body must use field name 'password'");
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

    @Test
    void pinLoginForTokenDoesNotTouchTheSession() throws Exception {
        // Stub returns the same body for POST /auth/pin-login and GET /auth/me.
        String body = "{\"token\":\"mgr-jwt\",\"username\":\"boss\",\"roles\":[\"MANAGER\"]}";
        try (StubServer stub = new StubServer(200, body, "application/json")) {
            SessionManager session = new SessionManager();
            session.setToken("cashier-jwt");
            session.setUser("alice", java.util.Set.of("CASHIER"));
            AuthApi auth = new AuthApi(new ApiClient(stub.baseUrl(), session), session);

            com.company.pos.terminal.api.dto.ManagerAuth mgr = auth.pinLoginForToken("M01", "9999");

            assertEquals("mgr-jwt", mgr.token());
            assertTrue(mgr.isManager());
            // The cashier session is untouched:
            assertEquals("cashier-jwt", session.token());
            assertEquals("alice", session.username());
            assertFalse(session.isManager());
            // /auth/me was called with the MANAGER token, not the session token:
            StubServer.RecordedRequest me = stub.requestTo("GET", "/auth/me");
            assertNotNull(me);
            assertEquals("Bearer mgr-jwt", me.authorization());
        }
    }

    @Test
    void pinLoginForTokenReportsNonManagerRoles() throws Exception {
        String body = "{\"token\":\"jwt-c\",\"username\":\"carl\",\"roles\":[\"CASHIER\"]}";
        try (StubServer stub = new StubServer(200, body, "application/json")) {
            SessionManager session = new SessionManager();
            AuthApi auth = new AuthApi(new ApiClient(stub.baseUrl(), session), session);
            assertFalse(auth.pinLoginForToken("C01", "1234").isManager());
        }
    }
}
