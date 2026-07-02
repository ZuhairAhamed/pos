package com.company.pos.reporting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PaymentBreakdownReport(LocalDate from, LocalDate to, String currencyCode,
        List<PaymentLine> lines, BigDecimal totalCollected, BigDecimal totalRefunded) {

    public record PaymentLine(String method, long count, BigDecimal collected, BigDecimal refunded) {
    }
}
