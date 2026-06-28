package com.company.pos.pricing.api;

import java.math.BigDecimal;

public record PricedLine(String sku, String name, BigDecimal quantity, BigDecimal unitPrice,
        String currencyCode, BigDecimal extendedPrice) {
}
