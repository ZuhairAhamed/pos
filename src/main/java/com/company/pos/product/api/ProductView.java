package com.company.pos.product.api;

import java.math.BigDecimal;

public record ProductView(
        String sku,
        String name,
        String categoryName,
        String barcode,
        String unitOfMeasure,
        BigDecimal unitPrice,
        String currencyCode,
        boolean active) {
}
