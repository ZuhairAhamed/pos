package com.company.pos.tax.api;

import java.math.BigDecimal;

public record TaxLineInput(String sku, String name, BigDecimal quantity, BigDecimal unitPrice,
        BigDecimal extendedPrice, String currencyCode) {
}
