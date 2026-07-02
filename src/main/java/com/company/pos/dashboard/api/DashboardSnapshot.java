package com.company.pos.dashboard.api;

import com.company.pos.reporting.api.ProductPerformanceReport;
import com.company.pos.reporting.api.SalesSummaryReport;
import com.company.pos.shift.api.ShiftView;
import java.time.LocalDate;
import java.util.List;

public record DashboardSnapshot(
        LocalDate asOfDate,
        String currencyCode,
        SalesSummaryReport todaysSales,
        RevenueSummary revenue,
        List<ProductPerformanceReport.ProductLine> bestSellers,
        List<LowStockTile> lowStock,
        List<ShiftView> openShifts,
        List<String> activeCashiers) {
}
