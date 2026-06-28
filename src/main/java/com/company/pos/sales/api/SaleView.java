package com.company.pos.sales.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SaleView(UUID id, String receiptNumber, String status, String currencyCode,
        BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal, Instant createdAt,
        List<SaleLineView> lines, SalePaymentView payment) {
}
