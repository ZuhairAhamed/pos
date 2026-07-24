package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.DashboardApi;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.dto.DashboardSnapshot;
import com.company.pos.terminal.api.dto.RevenueSummary;
import com.company.pos.terminal.api.dto.SalesSummaryReport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class DashboardViewModelTest {

    private DashboardSnapshot snapshot() {
        SalesSummaryReport sales = new SalesSummaryReport(LocalDate.parse("2026-07-24"),
                LocalDate.parse("2026-07-24"), "SAR", 34, new BigDecimal("1240.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("186.00"), new BigDecimal("1240.00"),
                0, BigDecimal.ZERO, new BigDecimal("1240.00"));
        return new DashboardSnapshot(LocalDate.parse("2026-07-24"), "SAR", sales,
                new RevenueSummary(new BigDecimal("1240.00"), 7, new BigDecimal("8910.00")),
                List.of(), List.of(), List.of(), List.of("alice"));
    }

    @Test
    void loadReturnsSnapshot() {
        DashboardApi api = new DashboardApi(null) {
            @Override public DashboardSnapshot snapshot() { return DashboardViewModelTest.this.snapshot(); }
        };
        DashboardViewModel vm = new DashboardViewModel(api);
        DashboardSnapshot s = vm.load();
        assertEquals(34, s.todaysSales().saleCount());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void loadSurfacesErrorUnderDeferredDispatcher() {
        DashboardApi api = new DashboardApi(null) {
            @Override public DashboardSnapshot snapshot() {
                throw new ApiException(503, new ProblemDetail("Unavailable", 503, "server down"),
                        "HTTP 503");
            }
        };
        Deque<Runnable> queue = new ArrayDeque<>();
        Consumer<Runnable> deferred = queue::add;
        DashboardViewModel vm = new DashboardViewModel(api, deferred);

        DashboardSnapshot s = vm.load();

        assertNull(s);                              // synchronous truth: failed
        assertEquals("", vm.errorMessage().get());  // deferred: not applied yet
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals("server down", vm.errorMessage().get());
    }
}
