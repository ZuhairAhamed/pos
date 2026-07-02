package com.company.pos.reporting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ProductPerformanceReport(LocalDate from, LocalDate to, String currencyCode,
        List<ProductLine> lines) {

    public record ProductLine(String sku, String name, BigDecimal quantitySold, BigDecimal revenue,
            BigDecimal discounts) {
    }
}
