package com.company.pos.reporting.api;

import java.time.LocalDate;

public interface ReportingService {

    SalesSummaryReport salesSummary(LocalDate from, LocalDate to);

    PaymentBreakdownReport paymentBreakdown(LocalDate from, LocalDate to);

    TaxSummaryReport taxSummary(LocalDate from, LocalDate to);
}
