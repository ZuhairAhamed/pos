package com.company.pos.pricing.api;

import java.math.BigDecimal;

public record PricingInput(String sku, String name, BigDecimal quantity,
        BigDecimal unitPrice, String currencyCode) {
}
