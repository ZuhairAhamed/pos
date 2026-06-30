package com.company.pos.sales.api;

import java.math.BigDecimal;

/** A manual discount as entered: a percentage or a fixed amount, with a required reason code. */
public record DiscountInput(DiscountType type, BigDecimal value, String reasonCode) {
}
