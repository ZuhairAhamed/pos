package com.company.pos.product.api;

import java.math.BigDecimal;

public record UpdateProductCommand(String name, String categoryCode, String categoryName,
        BigDecimal unitPrice, String currencyCode, String unitOfMeasure, String barcode) {
}
