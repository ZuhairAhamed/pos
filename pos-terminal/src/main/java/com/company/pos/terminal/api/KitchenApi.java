package com.company.pos.terminal.api;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;

/** Typed client for the store server's kitchen-routing endpoints (writes MANAGER/ADMIN-gated).
 *  Non-final so view-model tests can subclass with fakes. */
public class KitchenApi {

    private final ApiClient client;

    public KitchenApi(ApiClient client) {
        this.client = client;
    }

    /** GET /kitchen/stations/assignments — all explicit SKU→station assignments. */
    public List<StationAssignmentView> listAssignments() {
        return client.get("/kitchen/stations/assignments",
                new TypeReference<List<StationAssignmentView>>() {});
    }

    /** POST /kitchen/stations/assignments — assign or change a SKU's station (server upserts). */
    public StationAssignmentView assign(String sku, String stationName) {
        return client.post("/kitchen/stations/assignments",
                new AssignStationRequest(sku, stationName),
                new TypeReference<StationAssignmentView>() {});
    }

    /** DELETE /kitchen/stations/assignments/{sku} — clear a SKU's routing. */
    public void unassign(String sku) {
        client.delete("/kitchen/stations/assignments/" + sku);
    }
}
