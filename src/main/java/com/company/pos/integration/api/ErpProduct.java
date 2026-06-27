package com.company.pos.integration.api;

import java.math.BigDecimal;

public record ErpProduct(
        String sku,
        String name,
        String categoryCode,
        String categoryName,
        String barcode,
        String unitOfMeasure,
        BigDecimal unitPrice,
        String currencyCode,
        long version,
        boolean active) {
}
