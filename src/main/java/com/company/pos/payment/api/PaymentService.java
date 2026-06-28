package com.company.pos.payment.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface PaymentService {

    PaymentView recordCash(UUID saleId, String currencyCode, BigDecimal amount,
            BigDecimal amountTendered);

    PaymentView recordTerminalPayment(UUID saleId, String currencyCode, BigDecimal amount,
            PaymentMethod method, String reference);

    List<PaymentView> findBySale(UUID saleId);
}
