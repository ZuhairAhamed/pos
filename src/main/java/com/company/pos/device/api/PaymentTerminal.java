package com.company.pos.device.api;

public interface PaymentTerminal {

    PaymentResult requestPayment(PaymentRequest request);

    /** Refunds a previously-captured amount (a card/wallet credit). */
    PaymentResult refund(PaymentRequest request);
}
