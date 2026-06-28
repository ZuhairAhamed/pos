package com.company.pos.receipt.api;

import java.math.BigDecimal;

public record ReceiptLineData(String name, BigDecimal quantity, BigDecimal unitPrice,
        BigDecimal lineTotal) {
}
