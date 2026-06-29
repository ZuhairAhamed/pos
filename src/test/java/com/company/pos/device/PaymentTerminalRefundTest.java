package com.company.pos.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.device.api.PaymentRequest;
import com.company.pos.device.api.PaymentResult;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import com.company.pos.common.util.Monies;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PaymentTerminalRefundTest {

    private final InMemoryPaymentTerminal terminal = new InMemoryPaymentTerminal();

    @Test
    void refundIsApprovedByDefaultAndReturnsMaskedPan() {
        PaymentResult result = terminal.refund(
                new PaymentRequest(Monies.of(new BigDecimal("9.00"), "SAR"), "ret-1"));

        assertThat(result.approved()).isTrue();
        assertThat(result.maskedPan()).isNotBlank();
        assertThat(result.token()).isNotBlank();
    }

    @Test
    void refundCanBeDeclined() {
        terminal.setApprove(false);

        PaymentResult result = terminal.refund(
                new PaymentRequest(Monies.of(new BigDecimal("9.00"), "SAR"), "ret-1"));

        assertThat(result.approved()).isFalse();
    }
}
