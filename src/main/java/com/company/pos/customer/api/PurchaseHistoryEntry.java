package com.company.pos.customer.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PurchaseHistoryEntry(UUID saleId, String receiptNumber, Instant occurredAt,
        BigDecimal grandTotal, String currencyCode) {
}
