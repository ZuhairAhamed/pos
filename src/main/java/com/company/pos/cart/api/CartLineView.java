package com.company.pos.cart.api;

import java.math.BigDecimal;
import java.util.UUID;

public record CartLineView(UUID lineId, String sku, String name, BigDecimal quantity,
        BigDecimal unitPrice, String currencyCode) {
}
