package com.company.pos.dashboard.web;

import com.company.pos.dashboard.api.DashboardService;
import com.company.pos.dashboard.api.DashboardSnapshot;
import com.company.pos.dashboard.api.LowStockTile;
import com.company.pos.dashboard.api.OpenShifts;
import com.company.pos.dashboard.api.RevenueSummary;
import com.company.pos.reporting.api.ProductPerformanceReport;
import com.company.pos.reporting.api.SalesSummaryReport;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
class DashboardController {

    private final DashboardService dashboard;

    DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/dashboard")
    DashboardSnapshot snapshot() {
        return dashboard.snapshot();
    }

    @GetMapping("/dashboard/sales-today")
    SalesSummaryReport salesToday() {
        return dashboard.salesToday();
    }

    @GetMapping("/dashboard/revenue")
    RevenueSummary revenue() {
        return dashboard.revenue();
    }

    @GetMapping("/dashboard/best-sellers")
    List<ProductPerformanceReport.ProductLine> bestSellers(@RequestParam(defaultValue = "5") int limit) {
        return dashboard.bestSellers(limit);
    }

    @GetMapping("/dashboard/low-stock")
    List<LowStockTile> lowStock() {
        return dashboard.lowStock();
    }

    @GetMapping("/dashboard/open-shifts")
    OpenShifts openShifts() {
        return dashboard.openShifts();
    }
}
