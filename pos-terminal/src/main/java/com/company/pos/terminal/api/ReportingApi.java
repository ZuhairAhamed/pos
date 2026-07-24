package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.CashierReport;
import com.company.pos.terminal.api.dto.PaymentBreakdownReport;
import com.company.pos.terminal.api.dto.ProductPerformanceReport;
import com.company.pos.terminal.api.dto.SalesSummaryReport;
import com.company.pos.terminal.api.dto.TaxSummaryReport;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.LocalDate;

/** Typed client for the reporting endpoints. Non-final so view-model tests subclass it. */
public class ReportingApi {

    private final ApiClient client;

    public ReportingApi(ApiClient client) {
        this.client = client;
    }

    public SalesSummaryReport salesReport(LocalDate from, LocalDate to) {
        return client.get(range("/reports/sales", from, to), new TypeReference<SalesSummaryReport>() {});
    }

    public PaymentBreakdownReport paymentsReport(LocalDate from, LocalDate to) {
        return client.get(range("/reports/payments", from, to),
                new TypeReference<PaymentBreakdownReport>() {});
    }

    public TaxSummaryReport taxReport(LocalDate from, LocalDate to) {
        return client.get(range("/reports/tax", from, to), new TypeReference<TaxSummaryReport>() {});
    }

    public CashierReport cashiersReport(LocalDate from, LocalDate to) {
        return client.get(range("/reports/cashiers", from, to), new TypeReference<CashierReport>() {});
    }

    public ProductPerformanceReport productsReport(LocalDate from, LocalDate to, int limit) {
        return client.get(range("/reports/products", from, to) + "&limit=" + limit,
                new TypeReference<ProductPerformanceReport>() {});
    }

    /** Fetch a report as CSV text (backend text/csv). {@code limit} applies to PRODUCTS only. */
    public String exportCsv(ReportType type, LocalDate from, LocalDate to, Integer limit) {
        String path = range("/reports/" + type.path(), from, to) + "&format=csv";
        if (limit != null) {
            path += "&limit=" + limit;
        }
        return client.getText(path);
    }

    private static String range(String base, LocalDate from, LocalDate to) {
        return base + "?from=" + from + "&to=" + to;
    }
}
