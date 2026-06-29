package com.company.pos.sales.api;

import java.math.BigDecimal;

public record SaleReturnLineView(int lineNo, int originalLineNo, String sku, String name,
        BigDecimal quantity, BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxAmount,
        BigDecimal lineTotal, String currencyCode) {
}
