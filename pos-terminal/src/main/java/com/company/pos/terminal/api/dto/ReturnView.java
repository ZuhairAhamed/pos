package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReturnView(UUID id, String creditNoteNumber, UUID originalSaleId, String status,
        String currencyCode, BigDecimal refundSubtotal, BigDecimal refundTaxTotal,
        BigDecimal refundGrandTotal, Instant createdAt, List<SaleReturnLineView> lines,
        List<ReturnPaymentView> refunds) {
}
