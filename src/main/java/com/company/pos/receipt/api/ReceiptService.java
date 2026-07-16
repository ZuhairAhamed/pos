package com.company.pos.receipt.api;

public interface ReceiptService {

    void print(ReceiptData data);

    /** Renders {@code data} as a plain-text body and emails it to {@code to}. */
    void emailReceipt(String to, ReceiptData data);
}
