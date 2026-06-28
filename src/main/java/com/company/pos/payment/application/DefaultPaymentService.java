package com.company.pos.payment.application;

import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.payment.api.CashPaymentView;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.payment.api.PaymentService;
import com.company.pos.payment.domain.Payment;
import com.company.pos.payment.infrastructure.PaymentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultPaymentService implements PaymentService {

    private final PaymentRepository payments;

    DefaultPaymentService(PaymentRepository payments) {
        this.payments = payments;
    }

    @Override
    public CashPaymentView recordCash(UUID saleId, String currencyCode, BigDecimal amountDue,
            BigDecimal amountTendered) {
        BigDecimal due = amountDue.setScale(2, RoundingMode.HALF_UP);
        BigDecimal tendered = amountTendered.setScale(2, RoundingMode.HALF_UP);
        if (tendered.compareTo(due) < 0) {
            throw DomainException.validation(
                    "Tendered " + tendered + " is less than amount due " + due);
        }
        BigDecimal change = tendered.subtract(due);
        Payment payment = new Payment(Identifiers.newId(), saleId, PaymentMethod.CASH,
                due, tendered, change, currencyCode, Instant.now());
        payments.save(payment);
        return new CashPaymentView(saleId, due, tendered, change, currencyCode);
    }
}
