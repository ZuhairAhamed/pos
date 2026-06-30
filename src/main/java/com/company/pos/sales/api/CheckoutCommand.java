package com.company.pos.sales.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CheckoutCommand(UUID cartId, List<TenderInput> tenders,
        Map<String, DiscountInput> lineDiscounts, DiscountInput transactionDiscount) {

    public CheckoutCommand {
        if (lineDiscounts == null) {
            lineDiscounts = Map.of();
        }
    }

    /** Convenience: a checkout with no discounts (used by existing callers and tests). */
    public CheckoutCommand(UUID cartId, List<TenderInput> tenders) {
        this(cartId, tenders, Map.of(), null);
    }
}
