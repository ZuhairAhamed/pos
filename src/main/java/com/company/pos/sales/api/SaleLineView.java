package com.company.pos.sales.api;

import java.math.BigDecimal;
import java.util.List;

public record SaleLineView(int lineNo, String sku, String name, BigDecimal quantity,
        BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxAmount, BigDecimal lineTotal,
        String currencyCode, BigDecimal grossAmount, BigDecimal lineDiscountAmount,
        String lineDiscountType, String lineDiscountReason,
        List<SaleLineModifierView> modifiers) {
}
