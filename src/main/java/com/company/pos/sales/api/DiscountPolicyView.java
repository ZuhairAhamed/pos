package com.company.pos.sales.api;

import java.math.BigDecimal;
import java.util.List;

/** The store's discount policy for terminal UIs: cashier caps + the valid reason codes. */
public record DiscountPolicyView(BigDecimal cashierMaxPercent, BigDecimal cashierMaxAmount,
        List<String> reasonCodes) {
}
