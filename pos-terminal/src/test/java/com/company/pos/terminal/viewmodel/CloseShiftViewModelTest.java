package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.ShiftApi;
import com.company.pos.terminal.api.dto.DrawerReconciliation;
import com.company.pos.terminal.api.dto.ShiftSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CloseShiftViewModelTest {

    private final UUID shiftId = UUID.randomUUID();

    private ShiftSummary sampleSummary() {
        DrawerReconciliation cash = new DrawerReconciliation(UUID.randomUUID(),
                new BigDecimal("500.00"), new BigDecimal("1200.00"), 37, BigDecimal.ZERO,
                new BigDecimal("50.00"), new BigDecimal("1650.00"), new BigDecimal("1640.00"),
                new BigDecimal("-10.00"), "SAR");
        return new ShiftSummary(shiftId, "T01", "manager", "manager", "CLOSED",
                Instant.now(), Instant.now(), cash);
    }

    @Test
    void closeShiftStoresSummaryAndReturnsTrue() {
        ShiftApi api = new ShiftApi(null) {
            @Override
            public ShiftSummary closeShift(UUID id, BigDecimal countedCash) {
                return sampleSummary();
            }
        };
        CloseShiftViewModel vm = new CloseShiftViewModel(api, Runnable::run);
        assertTrue(vm.closeShift(shiftId, new BigDecimal("1640.00")));
        assertNotNull(vm.summary().get());
        assertEquals(0, new BigDecimal("-10.00").compareTo(vm.summary().get().cash().variance()));
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void closeShiftSurfacesErrorAndReturnsFalse() {
        ShiftApi api = new ShiftApi(null) {
            @Override
            public ShiftSummary closeShift(UUID id, BigDecimal countedCash) {
                throw new ApiException(409, new ProblemDetail("Conflict", 409, "Shift is not open"),
                        "HTTP 409");
            }
        };
        CloseShiftViewModel vm = new CloseShiftViewModel(api, Runnable::run);
        assertFalse(vm.closeShift(shiftId, new BigDecimal("100.00")));
        assertNull(vm.summary().get());
        assertEquals("Shift is not open", vm.errorMessage().get());
    }

    @Test
    void deferredDispatcherHoldsSummaryUntilDrained() {
        ShiftApi api = new ShiftApi(null) {
            @Override
            public ShiftSummary closeShift(UUID id, BigDecimal countedCash) {
                return sampleSummary();
            }
        };
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        CloseShiftViewModel vm = new CloseShiftViewModel(api, queue::add);
        boolean result = vm.closeShift(shiftId, new BigDecimal("1640.00"));
        assertTrue(result);                    // synchronous return
        assertNull(vm.summary().get());        // deferred: summary not applied yet
        while (!queue.isEmpty()) queue.poll().run();
        assertNotNull(vm.summary().get());
    }
}
