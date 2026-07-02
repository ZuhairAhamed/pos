package com.company.pos.dashboard.application;

import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.dashboard.api.DashboardService;
import com.company.pos.dashboard.api.DashboardSnapshot;
import com.company.pos.dashboard.api.LowStockTile;
import com.company.pos.dashboard.api.OpenShifts;
import com.company.pos.dashboard.api.RevenueSummary;
import com.company.pos.inventory.api.InventoryService;
import com.company.pos.product.api.ProductCatalog;
import com.company.pos.reporting.api.ProductPerformanceReport;
import com.company.pos.reporting.api.ReportingService;
import com.company.pos.reporting.api.SalesSummaryReport;
import com.company.pos.shift.api.ShiftService;
import com.company.pos.shift.api.ShiftView;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultDashboardService implements DashboardService {

    private static final int DEFAULT_BEST_SELLERS = 5;

    private final ReportingService reports;
    private final InventoryService inventory;
    private final ShiftService shifts;
    private final ProductCatalog products;
    private final ConfigurationService config;

    DefaultDashboardService(ReportingService reports, InventoryService inventory, ShiftService shifts,
            ProductCatalog products, ConfigurationService config) {
        this.reports = reports;
        this.inventory = inventory;
        this.shifts = shifts;
        this.products = products;
        this.config = config;
    }

    @Override
    public DashboardSnapshot snapshot() {
        LocalDate today = today();
        String currency = config.getString(SettingKey.CURRENCY_CODE);
        List<ShiftView> open = shifts.listOpenShifts();
        SalesSummaryReport todayReport = reports.salesSummary(today, today);
        return new DashboardSnapshot(
                today,
                currency,
                todayReport,
                revenue(todayReport),
                bestSellers(DEFAULT_BEST_SELLERS),
                lowStock(),
                open,
                activeCashiers(open));
    }

    @Override
    public SalesSummaryReport salesToday() {
        LocalDate today = today();
        return reports.salesSummary(today, today);
    }

    @Override
    public RevenueSummary revenue() {
        LocalDate today = today();
        SalesSummaryReport todayReport = reports.salesSummary(today, today);
        return revenue(todayReport);
    }

    private RevenueSummary revenue(SalesSummaryReport todayReport) {
        LocalDate today = today();
        int windowDays = Math.max(1, config.getInt(SettingKey.DASHBOARD_REVENUE_WINDOW_DAYS));
        SalesSummaryReport windowReport = reports.salesSummary(today.minusDays(windowDays - 1L), today);
        return new RevenueSummary(todayReport.netSales(), windowDays, windowReport.netSales());
    }

    @Override
    public List<ProductPerformanceReport.ProductLine> bestSellers(int limit) {
        LocalDate today = today();
        int clamped = Math.max(1, Math.min(limit, 50));
        return reports.productPerformance(today, today, clamped).lines();
    }

    @Override
    public List<LowStockTile> lowStock() {
        return inventory.listLowStock().stream()
                .map(item -> new LowStockTile(item.sku(), nameOf(item.sku()),
                        item.onHand(), item.reorderLevel()))
                .toList();
    }

    @Override
    public OpenShifts openShifts() {
        List<ShiftView> open = shifts.listOpenShifts();
        return new OpenShifts(open, activeCashiers(open));
    }

    private String nameOf(String sku) {
        return products.findBySku(sku).map(p -> p.name()).orElse(sku);
    }

    private List<String> activeCashiers(List<ShiftView> open) {
        return open.stream()
                .map(ShiftView::openedBy)
                .distinct()
                .sorted()
                .toList();
    }

    private LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
