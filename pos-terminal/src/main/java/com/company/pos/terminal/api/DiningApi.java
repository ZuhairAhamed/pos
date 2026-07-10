package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.AddLineRequest;
import com.company.pos.terminal.api.dto.CloseOrderRequest;
import com.company.pos.terminal.api.dto.OpenOrderRequest;
import com.company.pos.terminal.api.dto.OpenOrderView;
import com.company.pos.terminal.api.dto.OrderView;
import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.api.dto.TableView;
import com.fasterxml.jackson.core.type.TypeReference;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Typed client for the store server's {@code /dining} seat-to-payment endpoints. */
public class DiningApi {

    private final ApiClient client;

    public DiningApi(ApiClient client) {
        this.client = client;
    }

    public List<TableView> tables() {
        return client.get("/dining/tables", new TypeReference<List<TableView>>() {});
    }

    public List<OpenOrderView> openOrders() {
        return client.get("/dining/orders", new TypeReference<List<OpenOrderView>>() {});
    }

    public OrderView order(UUID id) {
        return client.get("/dining/orders/" + id, new TypeReference<OrderView>() {});
    }

    public OrderView openOrder(UUID tableId) {
        return client.post("/dining/orders", new OpenOrderRequest(tableId, "DINE_IN"),
                new TypeReference<OrderView>() {});
    }

    public OrderView addLine(UUID orderId, AddLineRequest req) {
        return client.post("/dining/orders/" + orderId + "/lines", req,
                new TypeReference<OrderView>() {});
    }

    /** The server takes {@code qty} as a query parameter (not a body). */
    public OrderView updateLine(UUID orderId, UUID lineId, BigDecimal qty) {
        return client.put("/dining/orders/" + orderId + "/lines/" + lineId + "?qty=" + qty, null,
                new TypeReference<OrderView>() {});
    }

    /** DELETE returns the updated order server-side, but {@link ApiClient#delete} is void, so re-fetch. */
    public OrderView removeLine(UUID orderId, UUID lineId) {
        client.delete("/dining/orders/" + orderId + "/lines/" + lineId);
        return order(orderId);
    }

    public void fire(UUID orderId) {
        client.post("/dining/orders/" + orderId + "/fire", null, new TypeReference<OrderView>() {});
    }

    public SaleView close(UUID orderId, CloseOrderRequest req) {
        return client.post("/dining/orders/" + orderId + "/close", req,
                new TypeReference<SaleView>() {});
    }
}
