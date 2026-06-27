package com.company.pos.device.api;

public interface PaymentTerminal {

    PaymentResult requestPayment(PaymentRequest request);
}
