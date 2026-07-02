package com.company.pos.reporting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CashierReport(LocalDate from, LocalDate to, String currencyCode,
        List<CashierLine> lines) {

    public record CashierLine(String cashierUsername, long saleCount, BigDecimal totalSales,
            BigDecimal totalDiscounts) {
    }
}
