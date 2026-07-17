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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

    /**
     * Updates a line. The server's endpoint is a FULL REPLACE — it overwrites qty, note, and
     * course on every call — so callers must pass the line's current note/course to preserve them.
     * qty/note/course are query params ({@code note} is URL-encoded; null note/course are omitted).
     */
    public OrderView updateLine(UUID orderId, UUID lineId, BigDecimal qty, String note, String course) {
        StringBuilder path = new StringBuilder("/dining/orders/").append(orderId)
                .append("/lines/").append(lineId).append("?qty=").append(qty);
        if (note != null) {
            path.append("&note=").append(URLEncoder.encode(note, StandardCharsets.UTF_8));
        }
        if (course != null) {
            path.append("&course=").append(URLEncoder.encode(course, StandardCharsets.UTF_8));
        }
        return client.put(path.toString(), null, new TypeReference<OrderView>() {});
    }

    /** DELETE returns the updated order server-side, but {@link ApiClient#delete} is void, so re-fetch. */
    public OrderView removeLine(UUID orderId, UUID lineId) {
        client.delete("/dining/orders/" + orderId + "/lines/" + lineId);
        return order(orderId);
    }

    public void fire(UUID orderId) {
        client.post("/dining/orders/" + orderId + "/fire", null, new TypeReference<OrderView>() {});
    }

    /** POST /dining/orders/{orderId}/transfer?targetTableId=... — relocates the order to a free table. */
    public OrderView transferOrder(UUID orderId, UUID targetTableId) {
        return client.post("/dining/orders/" + orderId + "/transfer?targetTableId=" + targetTableId,
                null, new TypeReference<OrderView>() {});
    }

    /** POST /dining/orders/{survivorOrderId}/merge?absorbedOrderId=... — folds another order in. */
    public OrderView mergeOrders(UUID survivorOrderId, UUID absorbedOrderId) {
        return client.post("/dining/orders/" + survivorOrderId + "/merge?absorbedOrderId=" + absorbedOrderId,
                null, new TypeReference<OrderView>() {});
    }

    /**
     * Voids the whole order (MANAGER-gated on the server). {@code reason} is optional (may be
     * blank) and URL-encoded. Authenticated with a one-shot manager {@code bearerToken}; a 401
     * on this overridden call never clears the cashier session. 204 No Content.
     */
    public void voidOrder(UUID orderId, String reason, String bearerToken) {
        String path = "/dining/orders/" + orderId + "/void?reason="
                + URLEncoder.encode(reason == null ? "" : reason, StandardCharsets.UTF_8);
        client.post(path, null, new TypeReference<Void>() {}, bearerToken);
    }

    /** GET /dining/orders/{id}/quote — authoritative totals for a dine-in order (incl service charge). */
    public QuoteView quoteOrder(UUID orderId) {
        return client.get("/dining/orders/" + orderId + "/quote", new TypeReference<QuoteView>() {});
    }

    /** POST /dining/orders/{id}/quote — quote priced with a whole-sale discount ({@code null} = none). */
    public QuoteView quoteOrder(UUID orderId, DiscountInput transactionDiscount) {
        return quoteOrder(orderId, transactionDiscount, false);
    }

    /** As above, with the service charge waived when {@code waiveServiceCharge} is true (manager-approved). */
    public QuoteView quoteOrder(UUID orderId, DiscountInput transactionDiscount,
            boolean waiveServiceCharge) {
        return client.post("/dining/orders/" + orderId + "/quote",
                new QuoteOrderRequest(Map.of(), transactionDiscount, waiveServiceCharge),
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

    /** As {@link #closeSplit(UUID, SplitCloseRequest)} but authenticated with a one-shot manager
     *  token ({@code null} = the signed-in cashier's session token). */
    public List<SaleView> closeSplit(UUID orderId, SplitCloseRequest req, String bearerToken) {
        return client.post("/dining/orders/" + orderId + "/close-split", req,
                new TypeReference<List<SaleView>>() {}, bearerToken);
    }
}
