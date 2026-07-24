package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TaxSummaryReport(LocalDate from, LocalDate to, String currencyCode,
        BigDecimal taxableAmount, BigDecimal taxCollected, BigDecimal refundTax, BigDecimal netTax) {
}
