package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.CheckoutRequest;
import com.company.pos.terminal.api.dto.DiscountInput;
import com.company.pos.terminal.api.dto.DiscountPolicyView;
import com.company.pos.terminal.api.dto.QuoteRequest;
import com.company.pos.terminal.api.dto.QuoteView;
import com.company.pos.terminal.api.dto.SaleView;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Map;
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

    /** POST /sales/quote — authoritative totals for a retail cart (service charge off). */
    public QuoteView quote(UUID cartId) {
        return quote(cartId, null);
    }

    /** As {@link #quote(UUID)} but priced with a whole-sale discount ({@code null} = none). */
    public QuoteView quote(UUID cartId, DiscountInput transactionDiscount) {
        return client.post("/sales/quote", new QuoteRequest(cartId, Map.of(), transactionDiscount),
                new TypeReference<QuoteView>() {});
    }

    /** GET /sales/discount-policy — cashier caps + reason codes for the discount dialog. */
    public DiscountPolicyView discountPolicy() {
        return client.get("/sales/discount-policy", new TypeReference<DiscountPolicyView>() {});
    }

    /** {@code POST /sales/{id}/reprint} — 204 No Content. */
    public void reprint(UUID saleId) {
        client.post("/sales/" + saleId + "/reprint", null, new TypeReference<Void>() {});
    }
}
