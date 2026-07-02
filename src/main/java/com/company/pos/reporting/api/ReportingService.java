package com.company.pos.reporting.api;

import java.time.LocalDate;

public interface ReportingService {

    SalesSummaryReport salesSummary(LocalDate from, LocalDate to);
}
