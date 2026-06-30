package com.company.pos.sales.api;

import com.company.pos.common.events.DomainEvent;
import java.math.BigDecimal;
import java.util.UUID;

/** A manager applied a discount exceeding the cashier cap. {@code sku} is null for a
 *  transaction-level discount. */
public record DiscountOverridden(UUID saleId, String actor, String sku, BigDecimal discountAmount,
        String discountType, String reasonCode) implements DomainEvent {
}
