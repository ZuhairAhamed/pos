package com.company.pos.cart.api;

import java.math.BigDecimal;

public record CartLineView(String sku, String name, BigDecimal quantity,
        BigDecimal unitPrice, String currencyCode) {
}
