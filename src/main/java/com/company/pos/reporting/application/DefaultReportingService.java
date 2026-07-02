package com.company.pos.reporting.application;

import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.reporting.api.ReportingService;
import com.company.pos.reporting.api.SalesSummaryReport;
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
}
