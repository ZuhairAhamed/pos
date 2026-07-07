package com.company.pos.integration.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SaleUpload(UUID saleId, String receiptNumber, String terminalId, String locationCode,
        String currencyCode, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal,
        Instant createdAt, List<Line> lines, List<Payment> payments, BigDecimal discountTotal,
        BigDecimal txnDiscountAmount, String txnDiscountType, String txnDiscountReason,
        BigDecimal serviceChargeAmount) {

    public record Line(int lineNo, String sku, String name, BigDecimal quantity,
            BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxAmount, BigDecimal lineTotal,
            BigDecimal grossAmount, BigDecimal lineDiscountAmount, String lineDiscountReason) {
    }

    public record Payment(String method, BigDecimal amount, BigDecimal amountTendered,
            BigDecimal changeDue, String maskedPan) {
    }
}
