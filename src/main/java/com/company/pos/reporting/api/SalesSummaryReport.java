package com.company.pos.reporting.api;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SalesSummaryReport(LocalDate from, LocalDate to, String currencyCode,
        long saleCount, BigDecimal subtotal, BigDecimal lineDiscounts, BigDecimal txnDiscounts,
        BigDecimal taxTotal, BigDecimal grossSales, long returnCount, BigDecimal refundTotal,
        BigDecimal netSales) {
}
