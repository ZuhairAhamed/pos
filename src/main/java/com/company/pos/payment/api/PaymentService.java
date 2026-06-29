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

    PaymentView refundCash(UUID returnId, String currencyCode, BigDecimal amount);

    PaymentView refundTerminalPayment(UUID returnId, String currencyCode, BigDecimal amount,
            PaymentMethod method, String reference);

    List<PaymentView> findByReturn(UUID returnId);
}
