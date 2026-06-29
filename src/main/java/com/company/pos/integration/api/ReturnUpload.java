package com.company.pos.integration.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReturnUpload(UUID returnId, String creditNoteNumber, UUID originalSaleId,
        String terminalId, String locationCode, String currencyCode, BigDecimal refundSubtotal,
        BigDecimal refundTaxTotal, BigDecimal refundGrandTotal, Instant createdAt, List<Line> lines) {

    public record Line(int lineNo, int originalLineNo, String sku, String name, BigDecimal quantity,
            BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxAmount, BigDecimal lineTotal) {
    }
}
