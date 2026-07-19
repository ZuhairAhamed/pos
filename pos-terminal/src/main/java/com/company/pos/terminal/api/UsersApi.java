package com.company.pos.terminal.api;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.UUID;

/** Typed client for the store server's ADMIN-gated {@code /users} endpoints.
 *  Methods are non-final so view-model tests can subclass with fakes. */
public class UsersApi {

    private final ApiClient client;

    public UsersApi(ApiClient client) {
        this.client = client;
    }

    /** GET /users?includeDisabled= — all staff users (optionally including deactivated ones). */
    public List<UserView> list(boolean includeDisabled) {
        return client.get("/users?includeDisabled=" + includeDisabled,
                new TypeReference<List<UserView>>() {});
    }

    /** POST /users — create a staff user. */
    public UserView create(CreateUserRequest req) {
        return client.post("/users", req, new TypeReference<UserView>() {});
    }

    /** PUT /users/{id} — edit display name + roles. */
    public UserView update(UUID id, UpdateUserRequest req) {
        return client.put("/users/" + id, req, new TypeReference<UserView>() {});
    }

    /** POST /users/{id}/reset-password. */
    public void resetPassword(UUID id, String value) {
        client.post("/users/" + id + "/reset-password", new ResetCredentialRequest(value), null);
    }

    /** POST /users/{id}/reset-pin — blank value clears the PIN. */
    public void resetPin(UUID id, String value) {
        client.post("/users/" + id + "/reset-pin", new ResetCredentialRequest(value), null);
    }

    /** POST /users/{id}/deactivate. */
    public void deactivate(UUID id) {
        client.post("/users/" + id + "/deactivate", null, null);
    }

    /** POST /users/{id}/reactivate. */
    public void reactivate(UUID id) {
        client.post("/users/" + id + "/reactivate", null, null);
    }
}
