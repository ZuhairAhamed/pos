package com.company.pos.terminal.api;

import java.math.BigDecimal;

public record CreateProductRequest(String sku, String name, String categoryCode, String categoryName,
        BigDecimal unitPrice, String currencyCode, String unitOfMeasure, String barcode) {
}
