package com.company.pos.payment.api;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentView(UUID saleId, String method, BigDecimal amount, BigDecimal amountTendered,
        BigDecimal changeDue, String maskedPan, String currencyCode) {
}
