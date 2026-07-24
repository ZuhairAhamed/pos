package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CashierReport(LocalDate from, LocalDate to, String currencyCode,
        List<CashierLine> lines) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CashierLine(String cashierUsername, long saleCount, BigDecimal totalSales,
            BigDecimal totalDiscounts) {
    }
}
