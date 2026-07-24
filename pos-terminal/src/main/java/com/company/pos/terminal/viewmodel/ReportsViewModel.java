package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.ReportType;
import com.company.pos.terminal.api.ReportingApi;
import com.company.pos.terminal.api.dto.CashierReport;
import com.company.pos.terminal.api.dto.PaymentBreakdownReport;
import com.company.pos.terminal.api.dto.ProductPerformanceReport;
import com.company.pos.terminal.api.dto.SalesSummaryReport;
import com.company.pos.terminal.api.dto.TaxSummaryReport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the reports screen. Synchronous (the controller runs it off the FX thread via
 * FxTasks); the only off-thread observable write is {@code errorMessage} inside {@code ui}. Date
 * presets resolve against the injected {@code clock} (never inline Instant.now()).
 */
public class ReportsViewModel {

    /** Quick-pick ranges. CUSTOM is not here — the controller supplies custom dates directly. */
    public enum Preset { TODAY, YESTERDAY, LAST_7_DAYS, THIS_MONTH }

    private final ReportingApi api;
    private final Consumer<Runnable> ui;
    private final Supplier<Instant> clock;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public ReportsViewModel(ReportingApi api) {
        this(api, Runnable::run, Instant::now);
    }

    public ReportsViewModel(ReportingApi api, Consumer<Runnable> ui, Supplier<Instant> clock) {
        this.api = api;
        this.ui = ui;
        this.clock = clock;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    /** Resolve a preset to {from, to} inclusive, against today (system zone) from the clock. */
    public LocalDate[] resolve(Preset preset) {
        LocalDate today = LocalDate.ofInstant(clock.get(), ZoneId.systemDefault());
        return switch (preset) {
            case TODAY -> new LocalDate[] {today, today};
            case YESTERDAY -> new LocalDate[] {today.minusDays(1), today.minusDays(1)};
            case LAST_7_DAYS -> new LocalDate[] {today.minusDays(6), today};
            case THIS_MONTH -> new LocalDate[] {today.withDayOfMonth(1), today};
        };
    }

    public SalesSummaryReport sales(LocalDate from, LocalDate to) {
        return fetch(() -> api.salesReport(from, to));
    }

    public PaymentBreakdownReport payments(LocalDate from, LocalDate to) {
        return fetch(() -> api.paymentsReport(from, to));
    }

    public TaxSummaryReport tax(LocalDate from, LocalDate to) {
        return fetch(() -> api.taxReport(from, to));
    }

    public CashierReport cashiers(LocalDate from, LocalDate to) {
        return fetch(() -> api.cashiersReport(from, to));
    }

    public ProductPerformanceReport products(LocalDate from, LocalDate to, int limit) {
        return fetch(() -> api.productsReport(from, to, limit));
    }

    /** Fetch a report as CSV; null on failure (error surfaced via errorMessage). */
    public String exportCsv(ReportType type, LocalDate from, LocalDate to, Integer limit) {
        return fetch(() -> api.exportCsv(type, from, to, limit));
    }

    private <T> T fetch(Supplier<T> call) {
        try {
            T result = call.get();
            ui.accept(() -> errorMessage.set(""));
            return result;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return null;
        }
    }

    /** Prefer the server's ProblemDetail (detail, then title), else the exception message. */
    private String messageOf(ApiException e) {
        if (e.problem() != null) {
            if (e.problem().detail() != null && !e.problem().detail().isBlank()) {
                return e.problem().detail();
            }
            if (e.problem().title() != null && !e.problem().title().isBlank()) {
                return e.problem().title();
            }
        }
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        return "Request failed";
    }
}
