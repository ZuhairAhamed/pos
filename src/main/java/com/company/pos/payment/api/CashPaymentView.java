package com.company.pos.payment.api;

import java.math.BigDecimal;
import java.util.UUID;

public record CashPaymentView(UUID saleId, BigDecimal amount, BigDecimal amountTendered,
        BigDecimal changeDue, String currencyCode) {
}
