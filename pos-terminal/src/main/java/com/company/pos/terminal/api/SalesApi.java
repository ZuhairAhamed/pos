package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.CheckoutRequest;
import com.company.pos.terminal.api.dto.SaleView;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.UUID;

/** Typed client for the store server's {@code /sales} endpoints used by the terminal. */
public class SalesApi {

    private final ApiClient client;

    public SalesApi(ApiClient client) {
        this.client = client;
    }

    /** POST /sales — retail checkout of a cart; returns the authoritative SaleView. */
    public SaleView checkout(CheckoutRequest req) {
        return client.post("/sales", req, new TypeReference<SaleView>() {});
    }

    /** {@code POST /sales/{id}/reprint} — 204 No Content. */
    public void reprint(UUID saleId) {
        client.post("/sales/" + saleId + "/reprint", null, new TypeReference<Void>() {});
    }
}
