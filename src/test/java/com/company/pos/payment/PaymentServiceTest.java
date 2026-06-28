package com.company.pos.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.payment.api.PaymentService;
import com.company.pos.payment.api.PaymentView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class PaymentServiceTest {

    @Autowired
    PaymentService payments;
    @Autowired
    InMemoryPaymentTerminal terminal;

    @AfterEach
    void reset() {
        terminal.setApprove(true);
    }

    @Test
    void recordsCashAndComputesChange() {
        UUID saleId = Identifiers.newId();
        PaymentView view = payments.recordCash(saleId, "SAR",
                new BigDecimal("10.35"), new BigDecimal("20.00"));

        assertThat(view.saleId()).isEqualTo(saleId);
        assertThat(view.method()).isEqualTo("CASH");
        assertThat(view.amount()).isEqualByComparingTo("10.35");
        assertThat(view.changeDue()).isEqualByComparingTo("9.65");
        assertThat(view.maskedPan()).isNull();
    }

    @Test
    void insufficientCashTenderIsRejected() {
        assertThatThrownBy(() -> payments.recordCash(Identifiers.newId(), "SAR",
                new BigDecimal("10.35"), new BigDecimal("5.00")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void recordsApprovedCardWithMaskedPanAndNoChange() {
        UUID saleId = Identifiers.newId();
        PaymentView view = payments.recordTerminalPayment(saleId, "SAR",
                new BigDecimal("50.00"), PaymentMethod.CARD, saleId.toString());

        assertThat(view.method()).isEqualTo("CARD");
        assertThat(view.amount()).isEqualByComparingTo("50.00");
        assertThat(view.amountTendered()).isEqualByComparingTo("50.00");
        assertThat(view.changeDue()).isEqualByComparingTo("0.00");
        assertThat(view.maskedPan()).isEqualTo("**** **** **** 4242");
    }

    @Test
    void recordsWalletTender() {
        PaymentView view = payments.recordTerminalPayment(Identifiers.newId(), "SAR",
                new BigDecimal("12.00"), PaymentMethod.WALLET, "ref");
        assertThat(view.method()).isEqualTo("WALLET");
        assertThat(view.amount()).isEqualByComparingTo("12.00");
    }

    @Test
    void declinedTerminalPaymentIsRejected() {
        terminal.setApprove(false);
        assertThatThrownBy(() -> payments.recordTerminalPayment(Identifiers.newId(), "SAR",
                new BigDecimal("50.00"), PaymentMethod.CARD, "ref"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void cashMethodRejectedByTerminalPath() {
        assertThatThrownBy(() -> payments.recordTerminalPayment(Identifiers.newId(), "SAR",
                new BigDecimal("50.00"), PaymentMethod.CASH, "ref"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void findBySaleReturnsAllTenders() {
        UUID saleId = Identifiers.newId();
        payments.recordTerminalPayment(saleId, "SAR", new BigDecimal("40.00"), PaymentMethod.CARD, "ref");
        payments.recordCash(saleId, "SAR", new BigDecimal("10.35"), new BigDecimal("20.00"));

        List<PaymentView> found = payments.findBySale(saleId);
        assertThat(found).hasSize(2);
        assertThat(found).extracting(PaymentView::method).containsExactlyInAnyOrder("CARD", "CASH");
    }
}
