package com.company.pos.reporting.web;

import com.company.pos.reporting.api.CashierReport;
import com.company.pos.reporting.api.PaymentBreakdownReport;
import com.company.pos.reporting.api.ProductPerformanceReport;
import com.company.pos.reporting.api.ReportingService;
import com.company.pos.reporting.api.SalesSummaryReport;
import com.company.pos.reporting.api.TaxSummaryReport;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
class ReportingController {

    private final ReportingService reports;

    ReportingController(ReportingService reports) {
        this.reports = reports;
    }

    @GetMapping("/reports/sales")
    SalesSummaryReport sales(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.salesSummary(from, to);
    }

    @GetMapping("/reports/payments")
    PaymentBreakdownReport payments(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.paymentBreakdown(from, to);
    }

    @GetMapping("/reports/tax")
    TaxSummaryReport tax(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.taxSummary(from, to);
    }

    @GetMapping("/reports/cashiers")
    CashierReport cashiers(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.cashierReport(from, to);
    }

    @GetMapping("/reports/products")
    ProductPerformanceReport products(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "50") int limit) {
        return reports.productPerformance(from, to, limit);
    }
}
