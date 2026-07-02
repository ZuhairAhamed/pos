package com.company.pos.reporting.api;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TaxSummaryReport(LocalDate from, LocalDate to, String currencyCode,
        BigDecimal taxableAmount, BigDecimal taxCollected, BigDecimal refundTax, BigDecimal netTax) {
}
