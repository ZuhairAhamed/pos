package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SaleView(UUID id, String receiptNumber, BigDecimal subtotal, BigDecimal taxTotal,
        BigDecimal serviceChargeAmount, BigDecimal grandTotal, String currencyCode,
        BigDecimal discountTotal, List<SaleLineView> lines, List<SalePaymentView> payments) {
}
