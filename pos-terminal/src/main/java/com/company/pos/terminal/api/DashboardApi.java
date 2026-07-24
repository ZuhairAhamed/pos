package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.DashboardSnapshot;
import com.fasterxml.jackson.core.type.TypeReference;

/** Typed client for the manager dashboard snapshot. Non-final so view-model tests subclass it. */
public class DashboardApi {

    private final ApiClient client;

    public DashboardApi(ApiClient client) {
        this.client = client;
    }

    /** GET /dashboard — full snapshot (today's sales, revenue, best-sellers, low-stock, shifts). */
    public DashboardSnapshot snapshot() {
        return client.get("/dashboard", new TypeReference<DashboardSnapshot>() {});
    }
}
