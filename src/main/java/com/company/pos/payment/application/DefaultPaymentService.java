package com.company.pos.payment.application;

import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.common.util.Monies;
import com.company.pos.device.api.PaymentRequest;
import com.company.pos.device.api.PaymentResult;
import com.company.pos.device.api.PaymentTerminal;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.payment.api.PaymentService;
import com.company.pos.payment.api.PaymentView;
import com.company.pos.payment.domain.Payment;
import com.company.pos.payment.domain.PaymentDirection;
import com.company.pos.payment.infrastructure.PaymentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultPaymentService implements PaymentService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final PaymentRepository payments;
    private final PaymentTerminal terminal;

    DefaultPaymentService(PaymentRepository payments, PaymentTerminal terminal) {
        this.payments = payments;
        this.terminal = terminal;
    }

    @Override
    public PaymentView recordCash(UUID saleId, String currencyCode, BigDecimal amount,
            BigDecimal amountTendered) {
        BigDecimal due = amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal tendered = amountTendered.setScale(2, RoundingMode.HALF_UP);
        if (tendered.compareTo(due) < 0) {
            throw DomainException.validation(
                    "Tendered " + tendered + " is less than amount due " + due);
        }
        BigDecimal change = tendered.subtract(due);
        Payment payment = new Payment(Identifiers.newId(), saleId, null, PaymentMethod.CASH,
                due, tendered, change, currencyCode, null, null, PaymentDirection.SALE, Instant.now());
        payments.save(payment);
        return new PaymentView(saleId, PaymentMethod.CASH.name(), due, tendered, change, null,
                currencyCode);
    }

    @Override
    public PaymentView recordTerminalPayment(UUID saleId, String currencyCode, BigDecimal amount,
            PaymentMethod method, String reference) {
        if (method != PaymentMethod.CARD && method != PaymentMethod.WALLET) {
            throw DomainException.validation("Method " + method + " is not terminal-mediated");
        }
        BigDecimal due = amount.setScale(2, RoundingMode.HALF_UP);
        if (due.signum() <= 0) {
            throw DomainException.validation("Terminal payment amount must be positive");
        }
        PaymentResult result = terminal.requestPayment(
                new PaymentRequest(Monies.of(due, currencyCode), reference));
        if (!result.approved()) {
            throw DomainException.validation(method + " payment was declined");
        }
        Payment payment = new Payment(Identifiers.newId(), saleId, null, method, due, due, ZERO,
                currencyCode, result.maskedPan(), result.token(), PaymentDirection.SALE, Instant.now());
        payments.save(payment);
        return new PaymentView(saleId, method.name(), due, due, ZERO, result.maskedPan(),
                currencyCode);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentView> findBySale(UUID saleId) {
        return payments.findBySaleIdAndDirectionOrderByCreatedAtAsc(saleId, PaymentDirection.SALE)
                .stream()
                .map(p -> new PaymentView(p.getSaleId(), p.getMethod().name(), p.getAmount(),
                        p.getAmountTendered(), p.getChangeDue(), p.getMaskedPan(),
                        p.getCurrencyCode()))
                .toList();
    }

    @Override
    public PaymentView refundCash(UUID returnId, String currencyCode, BigDecimal amount) {
        BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
        if (value.signum() <= 0) {
            throw DomainException.validation("Refund amount must be positive");
        }
        Payment payment = new Payment(Identifiers.newId(), returnId, returnId, PaymentMethod.CASH,
                value, value, ZERO, currencyCode, null, null, PaymentDirection.REFUND, Instant.now());
        payments.save(payment);
        return new PaymentView(returnId, PaymentMethod.CASH.name(), value, value, ZERO, null,
                currencyCode);
    }

    @Override
    public PaymentView refundTerminalPayment(UUID returnId, String currencyCode, BigDecimal amount,
            PaymentMethod method, String reference) {
        if (method != PaymentMethod.CARD && method != PaymentMethod.WALLET) {
            throw DomainException.validation("Method " + method + " is not terminal-mediated");
        }
        BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
        if (value.signum() <= 0) {
            throw DomainException.validation("Refund amount must be positive");
        }
        PaymentResult result = terminal.refund(
                new PaymentRequest(Monies.of(value, currencyCode), reference));
        if (!result.approved()) {
            throw DomainException.validation(method + " refund was declined");
        }
        Payment payment = new Payment(Identifiers.newId(), returnId, returnId, method, value, value,
                ZERO, currencyCode, result.maskedPan(), result.token(), PaymentDirection.REFUND,
                Instant.now());
        payments.save(payment);
        return new PaymentView(returnId, method.name(), value, value, ZERO, result.maskedPan(),
                currencyCode);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentView> findByReturn(UUID returnId) {
        return payments.findByReturnIdOrderByCreatedAtAsc(returnId).stream()
                .map(p -> new PaymentView(p.getReturnId(), p.getMethod().name(), p.getAmount(),
                        p.getAmountTendered(), p.getChangeDue(), p.getMaskedPan(),
                        p.getCurrencyCode()))
                .toList();
    }
}
