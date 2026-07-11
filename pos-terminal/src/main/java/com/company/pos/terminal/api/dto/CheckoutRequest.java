package com.company.pos.terminal.api.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * POST /sales body. Mirrors the backend CheckoutCommand. Discounts are opaque here
 * (this slice sends {@code Map.of()} / {@code null}); applyServiceCharge is forced false
 * server-side for retail regardless, but we send false for honesty.
 */
public record CheckoutRequest(UUID cartId, List<TenderInput> tenders,
        Map<String, Object> lineDiscounts, Object transactionDiscount, boolean applyServiceCharge) {
}
