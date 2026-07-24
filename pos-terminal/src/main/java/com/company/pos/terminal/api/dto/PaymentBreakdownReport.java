package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentBreakdownReport(LocalDate from, LocalDate to, String currencyCode,
        List<PaymentLine> lines, BigDecimal totalCollected, BigDecimal totalRefunded) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentLine(String method, long count, BigDecimal collected, BigDecimal refunded) {
    }
}
