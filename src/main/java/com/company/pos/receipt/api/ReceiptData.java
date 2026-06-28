package com.company.pos.receipt.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ReceiptData(String receiptNumber, String cashierName, Instant timestamp,
        List<ReceiptLineData> lines, BigDecimal subtotal, BigDecimal taxTotal,
        BigDecimal grandTotal, BigDecimal amountTendered, BigDecimal changeDue,
        String currencyCode) {
}
