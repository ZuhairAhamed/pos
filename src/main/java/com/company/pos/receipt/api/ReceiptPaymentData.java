package com.company.pos.receipt.api;

import java.math.BigDecimal;

public record ReceiptPaymentData(String method, BigDecimal amount, BigDecimal amountTendered,
        BigDecimal changeDue, String maskedPan) {
}
