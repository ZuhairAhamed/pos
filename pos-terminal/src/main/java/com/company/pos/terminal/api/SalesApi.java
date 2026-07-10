package com.company.pos.terminal.api;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.UUID;

/** Typed client for the store server's {@code /sales} endpoints used by the terminal. */
public class SalesApi {

    private final ApiClient client;

    public SalesApi(ApiClient client) {
        this.client = client;
    }

    /** {@code POST /sales/{id}/reprint} — 204 No Content. */
    public void reprint(UUID saleId) {
        client.post("/sales/" + saleId + "/reprint", null, new TypeReference<Void>() {});
    }
}
