package com.company.pos.tax.api;

import java.math.BigDecimal;
import java.util.List;

public record TaxedCart(List<TaxedLine> lines, BigDecimal subtotal, BigDecimal taxTotal,
        BigDecimal grandTotal, String currencyCode, BigDecimal taxRate, boolean taxInclusive) {
}
