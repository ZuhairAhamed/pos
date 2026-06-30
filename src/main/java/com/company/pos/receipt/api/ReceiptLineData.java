package com.company.pos.receipt.api;

import java.math.BigDecimal;

public record ReceiptLineData(String name, BigDecimal quantity, BigDecimal unitPrice,
        BigDecimal lineTotal, BigDecimal grossAmount, BigDecimal lineDiscountAmount) {

    /** Convenience for lines without a discount (e.g. credit notes): gross == lineTotal. */
    public ReceiptLineData(String name, BigDecimal quantity, BigDecimal unitPrice,
            BigDecimal lineTotal) {
        this(name, quantity, unitPrice, lineTotal, lineTotal, BigDecimal.ZERO);
    }
}
