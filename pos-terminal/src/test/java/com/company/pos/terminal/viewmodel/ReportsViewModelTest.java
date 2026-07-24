package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.ReportType;
import com.company.pos.terminal.api.ReportingApi;
import com.company.pos.terminal.api.dto.SalesSummaryReport;
import com.company.pos.terminal.viewmodel.ReportsViewModel.Preset;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ReportsViewModelTest {

    // 2026-07-24 is a Friday. Clock fixed at noon UTC; presets resolve against system zone.
    private final Supplier<Instant> clock = () -> Instant.parse("2026-07-24T12:00:00Z");
    private LocalDate today() { return LocalDate.ofInstant(clock.get(), ZoneId.systemDefault()); }

    private ReportsViewModel vm(ReportingApi api, Consumer<Runnable> ui) {
        return new ReportsViewModel(api, ui, clock);
    }

    @Test
    void presetsResolveToDateRanges() {
        ReportsViewModel vm = vm(new ReportingApi(null), Runnable::run);
        LocalDate t = today();
        assertEquals(t, vm.resolve(Preset.TODAY)[0]);
        assertEquals(t, vm.resolve(Preset.TODAY)[1]);
        assertEquals(t.minusDays(1), vm.resolve(Preset.YESTERDAY)[0]);
        assertEquals(t.minusDays(1), vm.resolve(Preset.YESTERDAY)[1]);
        assertEquals(t.minusDays(6), vm.resolve(Preset.LAST_7_DAYS)[0]);   // 7 days incl. today
        assertEquals(t, vm.resolve(Preset.LAST_7_DAYS)[1]);
        assertEquals(t.withDayOfMonth(1), vm.resolve(Preset.THIS_MONTH)[0]);
        assertEquals(t, vm.resolve(Preset.THIS_MONTH)[1]);
    }

    @Test
    void salesDispatchesToApiAndReturnsReport() {
        SalesSummaryReport report = new SalesSummaryReport(LocalDate.parse("2026-07-01"),
                LocalDate.parse("2026-07-24"), "SAR", 5, BigDecimal.TEN, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.TEN, 0, BigDecimal.ZERO, BigDecimal.TEN);
        ReportingApi api = new ReportingApi(null) {
            @Override public SalesSummaryReport salesReport(LocalDate from, LocalDate to) { return report; }
        };
        ReportsViewModel vm = vm(api, Runnable::run);
        assertEquals(5, vm.sales(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-24")).saleCount());
    }

    @Test
    void exportCsvReturnsRawString() {
        ReportingApi api = new ReportingApi(null) {
            @Override public String exportCsv(ReportType t, LocalDate f, LocalDate to, Integer lim) {
                return "csv-body";
            }
        };
        ReportsViewModel vm = vm(api, Runnable::run);
        assertEquals("csv-body",
                vm.exportCsv(ReportType.SALES, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-24"), null));
    }

    @Test
    void fetchSurfacesErrorUnderDeferredDispatcher() {
        ReportingApi api = new ReportingApi(null) {
            @Override public SalesSummaryReport salesReport(LocalDate from, LocalDate to) {
                throw new ApiException(500, new ProblemDetail("Error", 500, "boom"), "HTTP 500");
            }
        };
        Deque<Runnable> queue = new ArrayDeque<>();
        ReportsViewModel vm = vm(api, queue::add);

        SalesSummaryReport r = vm.sales(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-24"));

        assertNull(r);
        assertEquals("", vm.errorMessage().get());
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals("boom", vm.errorMessage().get());
    }
}
