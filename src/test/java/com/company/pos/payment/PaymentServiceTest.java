package com.company.pos.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.payment.api.CashPaymentView;
import com.company.pos.payment.api.PaymentService;
import java.math.BigDecimal;
import java.util.UUID;
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

    @Test
    void recordsCashAndComputesChange() {
        UUID saleId = Identifiers.newId();
        CashPaymentView view = payments.recordCash(saleId, "SAR",
                new BigDecimal("10.35"), new BigDecimal("20.00"));

        assertThat(view.saleId()).isEqualTo(saleId);
        assertThat(view.amount()).isEqualByComparingTo("10.35");
        assertThat(view.changeDue()).isEqualByComparingTo("9.65");
    }

    @Test
    void exactTenderGivesZeroChange() {
        CashPaymentView view = payments.recordCash(Identifiers.newId(), "SAR",
                new BigDecimal("10.35"), new BigDecimal("10.35"));
        assertThat(view.changeDue()).isEqualByComparingTo("0.00");
    }

    @Test
    void insufficientTenderIsRejected() {
        assertThatThrownBy(() -> payments.recordCash(Identifiers.newId(), "SAR",
                new BigDecimal("10.35"), new BigDecimal("5.00")))
                .isInstanceOf(DomainException.class);
    }
}
