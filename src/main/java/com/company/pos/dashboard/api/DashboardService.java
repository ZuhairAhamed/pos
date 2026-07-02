package com.company.pos.dashboard.api;

import com.company.pos.reporting.api.ProductPerformanceReport;
import com.company.pos.reporting.api.SalesSummaryReport;
import java.util.List;

public interface DashboardService {

    DashboardSnapshot snapshot();

    SalesSummaryReport salesToday();

    RevenueSummary revenue();

    List<ProductPerformanceReport.ProductLine> bestSellers(int limit);

    List<LowStockTile> lowStock();

    OpenShifts openShifts();
}
