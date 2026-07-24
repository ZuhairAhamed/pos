package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SalesSummaryReport(LocalDate from, LocalDate to, String currencyCode,
        long saleCount, BigDecimal subtotal, BigDecimal lineDiscounts, BigDecimal txnDiscounts,
        BigDecimal taxTotal, BigDecimal grossSales, long returnCount, BigDecimal refundTotal,
        BigDecimal netSales) {
}
