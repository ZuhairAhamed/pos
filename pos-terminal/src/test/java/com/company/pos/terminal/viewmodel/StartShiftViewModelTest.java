package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.ShiftApi;
import com.company.pos.terminal.api.dto.ShiftView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StartShiftViewModelTest {

    private static ShiftView openShiftView() {
        return new ShiftView(UUID.randomUUID(), "T01", "manager", "OPEN", "SAR",
                Instant.parse("2026-07-12T06:02:00Z"), null);
    }

    /** Records the float it was asked to open with and returns a fixed shift. */
    private static final class RecordingShiftApi extends ShiftApi {
        BigDecimal received;
        int calls;
        RecordingShiftApi() { super(null); }
        @Override public ShiftView openShift(BigDecimal openingFloat) {
            received = openingFloat;
            calls++;
            return openShiftView();
        }
    }

    @Test
    void denominationTotalSumsNotesTimesCounts() {
        Map<BigDecimal, Integer> counts = Map.of(
                new BigDecimal("500"), 1,
                new BigDecimal("50"), 2,
                new BigDecimal("5"), 0);
        assertEquals(new BigDecimal("600.00"), StartShiftViewModel.total(counts));
    }

    @Test
    void denominationTotalTreatsNullAndNegativeCountsAsZero() {
        Map<BigDecimal, Integer> counts = new java.util.HashMap<>();
        counts.put(new BigDecimal("100"), null);
        counts.put(new BigDecimal("10"), -3);
        counts.put(new BigDecimal("5"), 2);
        assertEquals(new BigDecimal("10.00"), StartShiftViewModel.total(counts));
    }

    @Test
    void negativeFloatRejectedWithoutApiCall() {
        RecordingShiftApi api = new RecordingShiftApi();
        StartShiftViewModel vm = new StartShiftViewModel(api, Runnable::run);
        assertFalse(vm.openShift(new BigDecimal("-1")));
        assertEquals(0, api.calls, "must not call the server with a negative float");
        assertTrue(vm.errorMessage().get().toLowerCase().contains("zero or more"));
        assertNull(vm.shift().get());
    }

    @Test
    void nullFloatRejectedWithoutApiCall() {
        RecordingShiftApi api = new RecordingShiftApi();
        StartShiftViewModel vm = new StartShiftViewModel(api, Runnable::run);
        assertFalse(vm.openShift(null));
        assertEquals(0, api.calls);
        assertTrue(vm.errorMessage().get().toLowerCase().contains("zero or more"));
    }

    @Test
    void openShiftScalesTheFloatAndExposesTheShift() {
        RecordingShiftApi api = new RecordingShiftApi();
        StartShiftViewModel vm = new StartShiftViewModel(api, Runnable::run);
        assertTrue(vm.openShift(new BigDecimal("500")));
        assertEquals(new BigDecimal("500.00"), api.received);
        assertNotNull(vm.shift().get());
        assertEquals("OPEN", vm.shift().get().status());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void zeroFloatIsLegal() {
        RecordingShiftApi api = new RecordingShiftApi();
        StartShiftViewModel vm = new StartShiftViewModel(api, Runnable::run);
        assertTrue(vm.openShift(BigDecimal.ZERO), "an empty till is a valid opening float");
        assertEquals(1, api.calls);
    }

    @Test
    void apiFailureSurfacesErrorAndReturnsFalse() {
        ShiftApi failing = new ShiftApi(null) {
            @Override public ShiftView openShift(BigDecimal openingFloat) {
                throw new ApiException(409, null, "Shift already open");
            }
        };
        StartShiftViewModel vm = new StartShiftViewModel(failing, Runnable::run);
        assertFalse(vm.openShift(new BigDecimal("100")));
        assertEquals("Shift already open", vm.errorMessage().get());
        assertNull(vm.shift().get());
    }

    @Test
    void denominationsAreSarNotesLargestFirst() {
        assertEquals(java.util.List.of(
                new BigDecimal("500"), new BigDecimal("200"), new BigDecimal("100"),
                new BigDecimal("50"), new BigDecimal("10"), new BigDecimal("5")),
                StartShiftViewModel.DENOMINATIONS);
    }
}
