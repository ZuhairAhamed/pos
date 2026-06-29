package com.company.pos.sales.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReturnView(UUID id, String creditNoteNumber, UUID originalSaleId, String status,
        String currencyCode, BigDecimal refundSubtotal, BigDecimal refundTaxTotal,
        BigDecimal refundGrandTotal, Instant createdAt, List<SaleReturnLineView> lines,
        List<ReturnPaymentView> refunds) {
}
