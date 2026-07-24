package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductPerformanceReport(LocalDate from, LocalDate to, String currencyCode,
        List<ProductLine> lines) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductLine(String sku, String name, BigDecimal quantitySold, BigDecimal revenue,
            BigDecimal discounts) {
    }
}
