package com.company.pos.receipt.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ReceiptData(String receiptNumber, String cashierName, Instant timestamp,
        List<ReceiptLineData> lines, BigDecimal subtotal, BigDecimal taxTotal,
        BigDecimal grandTotal, List<ReceiptPaymentData> payments, String currencyCode,
        BigDecimal discountTotal, BigDecimal txnDiscountAmount, String txnDiscountReason,
        BigDecimal serviceChargeAmount) {

    /** Convenience for receipts without discounts (e.g. credit notes). */
    public ReceiptData(String receiptNumber, String cashierName, Instant timestamp,
            List<ReceiptLineData> lines, BigDecimal subtotal, BigDecimal taxTotal,
            BigDecimal grandTotal, List<ReceiptPaymentData> payments, String currencyCode) {
        this(receiptNumber, cashierName, timestamp, lines, subtotal, taxTotal, grandTotal, payments,
                currencyCode, BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO);
    }
}
