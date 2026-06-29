package com.company.pos.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.payment.api.PaymentService;
import com.company.pos.payment.api.PaymentView;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Refund payments are recorded keyed by returnId, retrievable via findByReturn, and do NOT appear
 * in the original sale's payment list. Non-@Transactional (commits) + DatabaseCleaner.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class RefundPaymentTest {

    @Autowired
    PaymentService payments;
    @Autowired
    InMemoryPaymentTerminal terminal;
    @Autowired
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void clean() {
        databaseCleaner.clean();
        terminal.setApprove(true);
    }

    @AfterEach
    void cleanup() {
        databaseCleaner.clean();
        terminal.setApprove(true);
    }

    @Test
    void cashRefundIsRecordedUnderTheReturn() {
        UUID returnId = Identifiers.newId();

        PaymentView pv = payments.refundCash(returnId, "SAR", new BigDecimal("9.00"));

        assertThat(pv.method()).isEqualTo("CASH");
        assertThat(pv.amount()).isEqualByComparingTo("9.00");
        List<PaymentView> byReturn = payments.findByReturn(returnId);
        assertThat(byReturn).hasSize(1);
        // Refund rows are keyed by returnId, so the original sale's query stays clean.
        assertThat(payments.findBySale(returnId)).isEmpty();
    }

    @Test
    void terminalRefundGoesThroughTheTerminal() {
        UUID returnId = Identifiers.newId();

        PaymentView pv = payments.refundTerminalPayment(returnId, "SAR", new BigDecimal("4.50"),
                PaymentMethod.CARD, returnId.toString());

        assertThat(pv.method()).isEqualTo("CARD");
        assertThat(pv.maskedPan()).isNotBlank();
        assertThat(payments.findByReturn(returnId)).hasSize(1);
    }

    @Test
    void terminalRefundDeclinedThrows() {
        terminal.setApprove(false);
        UUID returnId = Identifiers.newId();

        assertThatThrownBy(() -> payments.refundTerminalPayment(returnId, "SAR",
                new BigDecimal("4.50"), PaymentMethod.CARD, returnId.toString()))
                .isInstanceOf(DomainException.class);
    }
}
