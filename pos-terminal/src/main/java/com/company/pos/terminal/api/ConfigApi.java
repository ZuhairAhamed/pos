package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.SettingView;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;

/** Typed client for the store server's configuration endpoints (ADMIN-gated).
 *  Non-final so view-model tests can subclass with fakes. */
public class ConfigApi {

    private final ApiClient client;

    public ConfigApi(ApiClient client) {
        this.client = client;
    }

    /** GET /config — all settings, typed, with current + default values. */
    public List<SettingView> list() {
        return client.get("/config", new TypeReference<List<SettingView>>() {});
    }

    /** PUT /config/{name} — update one setting (204, no body). */
    public void update(String name, String value) {
        client.put("/config/" + name, new SettingUpdateRequest(value), null);
    }
}
