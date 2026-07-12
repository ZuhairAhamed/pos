package com.company.pos.terminal.api.dto;

import java.util.Map;
import java.util.UUID;

/** POST /sales/quote body. lineDiscounts stays empty in this slice (transaction discount only). */
public record QuoteRequest(UUID cartId, Map<String, DiscountInput> lineDiscounts,
        DiscountInput transactionDiscount) {
}
