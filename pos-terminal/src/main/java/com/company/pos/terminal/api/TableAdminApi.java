package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.TableView;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.UUID;

/** Typed client for the store server's dining-table registry endpoints (writes MANAGER-gated).
 *  Non-final so view-model tests can subclass with fakes. */
public class TableAdminApi {

    private final ApiClient client;

    public TableAdminApi(ApiClient client) {
        this.client = client;
    }

    /** GET /dining/tables — all tables, including inactive. */
    public List<TableView> list() {
        return client.get("/dining/tables", new TypeReference<List<TableView>>() {});
    }

    /** POST /dining/tables — create a table. */
    public TableView create(String label, Integer seats) {
        return client.post("/dining/tables", new TableChangeRequest(label, seats),
                new TypeReference<TableView>() {});
    }

    /** PUT /dining/tables/{id} — rename / reseat. */
    public TableView update(UUID id, String label, Integer seats) {
        return client.put("/dining/tables/" + id, new TableChangeRequest(label, seats),
                new TypeReference<TableView>() {});
    }

    /** DELETE /dining/tables/{id} — soft-delete (active=false). */
    public void deactivate(UUID id) {
        client.delete("/dining/tables/" + id);
    }

    /** POST /dining/tables/{id}/reactivate — restore a soft-deleted table. */
    public TableView reactivate(UUID id) {
        return client.post("/dining/tables/" + id + "/reactivate", null,
                new TypeReference<TableView>() {});
    }
}
