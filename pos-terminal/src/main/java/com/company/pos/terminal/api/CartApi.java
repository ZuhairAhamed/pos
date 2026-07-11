package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.CartLineRequest;
import com.company.pos.terminal.api.dto.CartView;
import com.company.pos.terminal.api.dto.QuantityRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Typed client for the store server's {@code /carts} retail endpoints. */
public class CartApi {

    private final ApiClient client;

    public CartApi(ApiClient client) {
        this.client = client;
    }

    /** POST /carts → {@code {"cartId": ...}}; returns the new cart id. */
    public UUID createCart() {
        Map<String, UUID> res =
                client.post("/carts", null, new TypeReference<Map<String, UUID>>() {});
        return res.get("cartId");
    }

    public CartView getCart(UUID cartId) {
        return client.get("/carts/" + cartId, new TypeReference<CartView>() {});
    }

    public CartView addLine(UUID cartId, String sku, BigDecimal qty, List<UUID> optionIds) {
        CartLineRequest body = new CartLineRequest(sku, qty, optionIds == null ? List.of() : optionIds);
        return client.post("/carts/" + cartId + "/lines", body, new TypeReference<CartView>() {});
    }

    public CartView updateLine(UUID cartId, UUID lineId, BigDecimal qty) {
        return client.put("/carts/" + cartId + "/lines/" + lineId, new QuantityRequest(qty),
                new TypeReference<CartView>() {});
    }

    /** DELETE returns the cart server-side, but {@link ApiClient#delete} is void, so re-fetch. */
    public CartView removeLine(UUID cartId, UUID lineId) {
        client.delete("/carts/" + cartId + "/lines/" + lineId);
        return getCart(cartId);
    }
}
