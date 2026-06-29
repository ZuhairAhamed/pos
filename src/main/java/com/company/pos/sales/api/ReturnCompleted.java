package com.company.pos.sales.api;

import com.company.pos.common.events.DomainEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Published after a return commits; inventory/cashdrawer/sync reverse their sale effects. */
public record ReturnCompleted(UUID returnId, UUID originalSaleId, String creditNoteNumber,
        String terminalId, String locationCode, String currencyCode, BigDecimal refundGrandTotal,
        BigDecimal cashRefundTotal, List<ReturnedLine> lines) implements DomainEvent {

    public record ReturnedLine(String sku, BigDecimal quantity) {
    }
}
