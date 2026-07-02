package com.company.pos.sales.api;

import com.company.pos.common.events.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SaleCompleted(UUID saleId, String receiptNumber, String terminalId, String locationCode,
        String currencyCode, BigDecimal grandTotal, BigDecimal cashTotal, List<SoldLine> lines,
        UUID customerId, Instant occurredAt)
        implements DomainEvent {

    public record SoldLine(String sku, BigDecimal quantity) {
    }
}
