package com.company.pos.sales.application;

import com.company.pos.sales.api.DiscountType;
import java.math.BigDecimal;

/** A discount a manager applied that exceeds the cashier cap. {@code sku} is null for a
 *  transaction-level discount. Surfaced so the orchestrator can publish a DiscountOverridden fact. */
record DiscountOverride(String sku, BigDecimal amount, DiscountType type, String reasonCode) {
}
