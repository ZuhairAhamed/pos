package com.company.pos.inventory.api;

import com.company.pos.common.events.DomainEvent;
import java.math.BigDecimal;

/** Published when an on-hand quantity crosses downward through its reorder level. */
public record LowStockDetected(String sku, String locationCode, BigDecimal onHand,
        BigDecimal reorderLevel) implements DomainEvent {
}
