package com.company.pos.product.api;

import com.company.pos.common.events.DomainEvent;
import java.math.BigDecimal;

public record ProductPriceChanged(String sku, BigDecimal oldPrice, BigDecimal newPrice,
        long erpVersion) implements DomainEvent {
}
