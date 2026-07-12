package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.*;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Typed client for the store server's {@code /auth} endpoints. On a successful
 * login it stores the JWT and the caller's username+roles into the
 * {@link SessionManager}, so {@link ApiClient} can send the bearer token and
 * {@link SessionManager#isManager()} reflects the server's role claims.
 */
public class AuthApi {
    private final ApiClient client;
    private final SessionManager session;

    public AuthApi(ApiClient client, SessionManager session) {
        this.client = client;
        this.session = session;
    }

    public void login(String username, String password) {
        TokenResponse t = client.post("/auth/login", new LoginRequest(username, password),
                new TypeReference<TokenResponse>() {});
        completeLogin(t);
    }

    public void pinLogin(String cashierCode, String pin) {
        TokenResponse t = client.post("/auth/pin-login", new PinLoginRequest(cashierCode, pin),
                new TypeReference<TokenResponse>() {});
        completeLogin(t);
    }

    /**
     * One-shot PIN login for manager approval: exchanges credentials for the manager's token +
     * roles WITHOUT touching the {@link SessionManager} — the signed-in cashier stays signed in.
     * The caller attaches the returned token to exactly one request and discards it. A wrong PIN
     * is HTTP 400 from the server (surfaced as ApiException), never a session-clearing 401.
     */
    public ManagerAuth pinLoginForToken(String cashierCode, String pin) {
        TokenResponse t = client.post("/auth/pin-login", new PinLoginRequest(cashierCode, pin),
                new TypeReference<TokenResponse>() {});
        MeResponse me = client.get("/auth/me", new TypeReference<MeResponse>() {}, t.token());
        return new ManagerAuth(t.token(), me.username(), me.roles());
    }

    private void completeLogin(TokenResponse t) {
        session.setToken(t.token());
        MeResponse me = client.get("/auth/me", new TypeReference<MeResponse>() {});
        session.setUser(me.username(), me.roles());
    }
}
