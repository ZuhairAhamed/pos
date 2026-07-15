package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.AddLineRequest;
import com.company.pos.terminal.api.dto.CloseOrderRequest;
import com.company.pos.terminal.api.dto.DiscountInput;
import com.company.pos.terminal.api.dto.OpenOrderRequest;
import com.company.pos.terminal.api.dto.OpenOrderView;
import com.company.pos.terminal.api.dto.OrderView;
import com.company.pos.terminal.api.dto.QuoteOrderRequest;
import com.company.pos.terminal.api.dto.QuoteView;
import com.company.pos.terminal.api.dto.QuoteSplitRequest;
import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.api.dto.SplitCloseRequest;
import com.company.pos.terminal.api.dto.SplitQuoteView;
import com.company.pos.terminal.api.dto.TableView;
import com.fasterxml.jackson.core.type.TypeReference;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
        return openOrder(tableId, "DINE_IN");
    }

    /** Opens a dining order of the given service type ({@code "DINE_IN"} or {@code "QUICK_SERVICE"}). */
    public OrderView openOrder(UUID tableId, String serviceType) {
        return client.post("/dining/orders", new OpenOrderRequest(tableId, serviceType),
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

    /** GET /dining/orders/{id}/quote — authoritative totals for a dine-in order (incl service charge). */
    public QuoteView quoteOrder(UUID orderId) {
        return client.get("/dining/orders/" + orderId + "/quote", new TypeReference<QuoteView>() {});
    }

    /** POST /dining/orders/{id}/quote — quote priced with a whole-sale discount ({@code null} = none). */
    public QuoteView quoteOrder(UUID orderId, DiscountInput transactionDiscount) {
        return client.post("/dining/orders/" + orderId + "/quote",
                new QuoteOrderRequest(Map.of(), transactionDiscount),
                new TypeReference<QuoteView>() {});
    }

    public SaleView close(UUID orderId, CloseOrderRequest req) {
        return client.post("/dining/orders/" + orderId + "/close", req,
                new TypeReference<SaleView>() {});
    }

    /** As {@link #close(UUID, CloseOrderRequest)} but authenticated with a one-shot manager token
     *  ({@code null} = the signed-in cashier's session token). */
    public SaleView close(UUID orderId, CloseOrderRequest req, String bearerToken) {
        return client.post("/dining/orders/" + orderId + "/close", req,
                new TypeReference<SaleView>() {}, bearerToken);
    }

    /** POST /dining/orders/{id}/quote-split — authoritative per-bill / per-share amounts for a
     *  proposed split. Pure calculator: the order stays OPEN. */
    public SplitQuoteView quoteSplit(UUID orderId, QuoteSplitRequest req) {
        return client.post("/dining/orders/" + orderId + "/quote-split", req,
                new TypeReference<SplitQuoteView>() {});
    }

    /** POST /dining/orders/{id}/close-split — closes ALL bills in one atomic call; any failing
     *  bill rolls back the whole split and the order stays OPEN. */
    public List<SaleView> closeSplit(UUID orderId, SplitCloseRequest req) {
        return client.post("/dining/orders/" + orderId + "/close-split", req,
                new TypeReference<List<SaleView>>() {});
    }
}
