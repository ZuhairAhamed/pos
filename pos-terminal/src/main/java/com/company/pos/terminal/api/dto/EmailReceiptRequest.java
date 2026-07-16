package com.company.pos.terminal.api.dto;

/** Request body for POST /sales/{saleId}/send-receipt. */
public record EmailReceiptRequest(String email) {
}
