package com.company.pos.reporting.application;

import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.reporting.api.CashierReport;
import com.company.pos.reporting.api.PaymentBreakdownReport;
import com.company.pos.reporting.api.ProductPerformanceReport;
import com.company.pos.reporting.api.ReportingService;
import com.company.pos.reporting.api.SalesSummaryReport;
import com.company.pos.reporting.api.TaxSummaryReport;
import com.company.pos.reporting.infrastructure.ReportingQueries;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultReportingService implements ReportingService {

    private final ReportingQueries queries;
    private final ConfigurationService config;

    DefaultReportingService(ReportingQueries queries, ConfigurationService config) {
        this.queries = queries;
        this.config = config;
    }

    @Override
    public SalesSummaryReport salesSummary(LocalDate from, LocalDate to) {
        ReportRanges.requireValid(from, to);
        String currency = config.getString(SettingKey.CURRENCY_CODE);
        return queries.salesSummary(from, to, ReportRanges.startOf(from), ReportRanges.endOf(to), currency);
    }

    @Override
    public PaymentBreakdownReport paymentBreakdown(LocalDate from, LocalDate to) {
        ReportRanges.requireValid(from, to);
        String currency = config.getString(SettingKey.CURRENCY_CODE);
        return queries.paymentBreakdown(from, to, ReportRanges.startOf(from), ReportRanges.endOf(to), currency);
    }

    @Override
    public TaxSummaryReport taxSummary(LocalDate from, LocalDate to) {
        ReportRanges.requireValid(from, to);
        String currency = config.getString(SettingKey.CURRENCY_CODE);
        return queries.taxSummary(from, to, ReportRanges.startOf(from), ReportRanges.endOf(to), currency);
    }

    @Override
    public CashierReport cashierReport(LocalDate from, LocalDate to) {
        ReportRanges.requireValid(from, to);
        String currency = config.getString(SettingKey.CURRENCY_CODE);
        return queries.cashierReport(from, to, ReportRanges.startOf(from), ReportRanges.endOf(to), currency);
    }

    @Override
    public ProductPerformanceReport productPerformance(LocalDate from, LocalDate to, int limit) {
        ReportRanges.requireValid(from, to);
        int clamped = Math.max(1, Math.min(limit, 500));
        String currency = config.getString(SettingKey.CURRENCY_CODE);
        return queries.productPerformance(from, to, ReportRanges.startOf(from), ReportRanges.endOf(to),
                currency, clamped);
    }
}
