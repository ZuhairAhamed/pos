package com.company.pos.device.infrastructure;

import com.company.pos.device.api.PaymentRequest;
import com.company.pos.device.api.PaymentResult;
import com.company.pos.device.api.PaymentTerminal;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Default {@link PaymentTerminal} adapter: simulates a semi-integrated terminal in memory.
 * Returns only a masked PAN and an opaque token — never a real card number — mirroring the
 * P2PE contract a real terminal SDK will fulfil later. Approval is controllable for tests.
 */
@Component
public class InMemoryPaymentTerminal implements PaymentTerminal {

    private volatile boolean approve = true;
    private volatile PaymentRequest lastRequest;

    @Override
    public PaymentResult requestPayment(PaymentRequest request) {
        this.lastRequest = request;
        if (!approve) {
            return new PaymentResult(false, null, null);
        }
        return new PaymentResult(true, "**** **** **** 4242", "tok_" + UUID.randomUUID());
    }

    /** Test control: set {@code false} to make the next requests decline. */
    public void setApprove(boolean approve) {
        this.approve = approve;
    }

    /** Test assertion helper: the most recent request the terminal saw. */
    public PaymentRequest lastRequest() {
        return lastRequest;
    }
}
