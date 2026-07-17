package com.company.pos.terminal.api.dto;

import java.util.Map;

/** POST /dining/orders/{id}/quote body — the discount-aware dine-in quote. */
public record QuoteOrderRequest(Map<String, DiscountInput> lineDiscounts,
        DiscountInput transactionDiscount, boolean waiveServiceCharge) {

    /** No-waiver convenience (keeps existing callers compiling). */
    public QuoteOrderRequest(Map<String, DiscountInput> lineDiscounts,
            DiscountInput transactionDiscount) {
        this(lineDiscounts, transactionDiscount, false);
    }
}
