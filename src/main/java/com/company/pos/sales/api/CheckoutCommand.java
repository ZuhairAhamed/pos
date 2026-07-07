package com.company.pos.sales.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CheckoutCommand(UUID cartId, List<TenderInput> tenders,
        Map<String, DiscountInput> lineDiscounts, DiscountInput transactionDiscount,
        boolean applyServiceCharge) {

    public CheckoutCommand {
        if (lineDiscounts == null) {
            lineDiscounts = Map.of();
        }
    }

    /** Convenience: discounts but no service charge (used by existing callers). */
    public CheckoutCommand(UUID cartId, List<TenderInput> tenders,
            Map<String, DiscountInput> lineDiscounts, DiscountInput transactionDiscount) {
        this(cartId, tenders, lineDiscounts, transactionDiscount, false);
    }

    /** Convenience: a checkout with no discounts and no service charge. */
    public CheckoutCommand(UUID cartId, List<TenderInput> tenders) {
        this(cartId, tenders, Map.of(), null, false);
    }
}
