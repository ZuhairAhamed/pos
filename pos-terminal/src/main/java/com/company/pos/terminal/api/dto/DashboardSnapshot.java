package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DashboardSnapshot(LocalDate asOfDate, String currencyCode,
        SalesSummaryReport todaysSales, RevenueSummary revenue,
        List<ProductPerformanceReport.ProductLine> bestSellers, List<LowStockTile> lowStock,
        List<ShiftView> openShifts, List<String> activeCashiers) {
}
