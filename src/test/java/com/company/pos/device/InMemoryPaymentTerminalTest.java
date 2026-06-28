package com.company.pos.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.common.util.Monies;
import com.company.pos.device.api.PaymentRequest;
import com.company.pos.device.api.PaymentResult;
import com.company.pos.device.api.PaymentTerminal;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class InMemoryPaymentTerminalTest {

    @Autowired
    PaymentTerminal terminal;
    @Autowired
    InMemoryPaymentTerminal fakeTerminal;

    @AfterEach
    void reset() {
        // shared application context — leave the bean approving for other tests
        fakeTerminal.setApprove(true);
    }

    @Test
    void approvesByDefaultAndReturnsMaskedPanAndToken() {
        PaymentRequest request = new PaymentRequest(Monies.of(new BigDecimal("50.00"), "SAR"), "ref-1");

        PaymentResult result = terminal.requestPayment(request);

        assertThat(result.approved()).isTrue();
        assertThat(result.maskedPan()).isEqualTo("**** **** **** 4242");
        assertThat(result.token()).startsWith("tok_");
        assertThat(fakeTerminal.lastRequest().reference()).isEqualTo("ref-1");
    }

    @Test
    void declinesWhenConfigured() {
        fakeTerminal.setApprove(false);

        PaymentResult result = terminal.requestPayment(
                new PaymentRequest(Monies.of(new BigDecimal("50.00"), "SAR"), "ref-2"));

        assertThat(result.approved()).isFalse();
        assertThat(result.maskedPan()).isNull();
        assertThat(result.token()).isNull();
    }
}
