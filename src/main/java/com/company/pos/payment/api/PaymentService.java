package com.company.pos.payment.api;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentService {

    CashPaymentView recordCash(UUID saleId, String currencyCode, BigDecimal amountDue,
            BigDecimal amountTendered);
}
