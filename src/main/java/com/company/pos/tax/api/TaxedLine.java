package com.company.pos.tax.api;

import java.math.BigDecimal;

public record TaxedLine(String sku, String name, BigDecimal quantity, BigDecimal unitPrice,
        BigDecimal netAmount, BigDecimal taxAmount, BigDecimal lineTotal, String currencyCode) {
}
