package com.company.pos.receipt.api;

import java.math.BigDecimal;
import java.util.List;

public record ReceiptLineData(String name, BigDecimal quantity, BigDecimal unitPrice,
        BigDecimal lineTotal, BigDecimal grossAmount, BigDecimal lineDiscountAmount,
        List<ReceiptLineModifierData> modifiers) {

    /** Convenience for lines without a discount (e.g. credit notes): gross == lineTotal, no modifiers. */
    public ReceiptLineData(String name, BigDecimal quantity, BigDecimal unitPrice,
            BigDecimal lineTotal) {
        this(name, quantity, unitPrice, lineTotal, lineTotal, BigDecimal.ZERO, List.of());
    }

    /** Convenience for lines with discount amounts but no modifiers. */
    public ReceiptLineData(String name, BigDecimal quantity, BigDecimal unitPrice,
            BigDecimal lineTotal, BigDecimal grossAmount, BigDecimal lineDiscountAmount) {
        this(name, quantity, unitPrice, lineTotal, grossAmount, lineDiscountAmount, List.of());
    }
}
