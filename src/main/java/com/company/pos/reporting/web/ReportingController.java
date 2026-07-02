package com.company.pos.reporting.web;

import com.company.pos.reporting.api.CashierReport;
import com.company.pos.reporting.api.PaymentBreakdownReport;
import com.company.pos.reporting.api.ProductPerformanceReport;
import com.company.pos.reporting.api.ReportingService;
import com.company.pos.reporting.api.SalesSummaryReport;
import com.company.pos.reporting.api.TaxSummaryReport;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    ResponseEntity<?> sales(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "json") String format) {
        SalesSummaryReport r = reports.salesSummary(from, to);
        return "csv".equalsIgnoreCase(format)
                ? ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv")).body(ReportCsv.of(r))
                : ResponseEntity.ok(r);
    }

    @GetMapping("/reports/payments")
    ResponseEntity<?> payments(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "json") String format) {
        PaymentBreakdownReport r = reports.paymentBreakdown(from, to);
        return "csv".equalsIgnoreCase(format)
                ? ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv")).body(ReportCsv.of(r))
                : ResponseEntity.ok(r);
    }

    @GetMapping("/reports/tax")
    ResponseEntity<?> tax(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "json") String format) {
        TaxSummaryReport r = reports.taxSummary(from, to);
        return "csv".equalsIgnoreCase(format)
                ? ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv")).body(ReportCsv.of(r))
                : ResponseEntity.ok(r);
    }

    @GetMapping("/reports/cashiers")
    ResponseEntity<?> cashiers(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "json") String format) {
        CashierReport r = reports.cashierReport(from, to);
        return "csv".equalsIgnoreCase(format)
                ? ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv")).body(ReportCsv.of(r))
                : ResponseEntity.ok(r);
    }

    @GetMapping("/reports/products")
    ResponseEntity<?> products(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "json") String format) {
        ProductPerformanceReport r = reports.productPerformance(from, to, limit);
        return "csv".equalsIgnoreCase(format)
                ? ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv")).body(ReportCsv.of(r))
                : ResponseEntity.ok(r);
    }
}
