package com.company.pos.sales.api;

import com.company.pos.common.events.DomainEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record SaleCompleted(UUID saleId, String receiptNumber, String locationCode,
        String currencyCode, BigDecimal grandTotal, List<SoldLine> lines) implements DomainEvent {

    public record SoldLine(String sku, BigDecimal quantity) {
    }
}
