package com.company.pos.terminal.api.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * POST /sales body. Mirrors the backend CheckoutCommand. As of slice 5 the transaction
 * discount is typed; lineDiscounts stays empty (per-line discounts are a later slice).
 * applyServiceCharge is forced false server-side for retail; we send false for honesty.
 */
public record CheckoutRequest(UUID cartId, List<TenderInput> tenders,
        Map<String, DiscountInput> lineDiscounts, DiscountInput transactionDiscount,
        boolean applyServiceCharge) {
}
