package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.KitchenApi;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.ProductApi;
import com.company.pos.terminal.api.StationAssignmentView;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

class KitchenRoutingViewModelTest {

    private KitchenRoutingViewModel vm(KitchenApi k) {
        return new KitchenRoutingViewModel(k, new ProductApi(null), Runnable::run);
    }

    @Test
    void loadAssignmentsReturnsListAndClearsError() {
        KitchenApi k = new KitchenApi(null) {
            @Override public List<StationAssignmentView> listAssignments() {
                return List.of(new StationAssignmentView("COLA", "Bar"));
            }
        };
        KitchenRoutingViewModel vm = vm(k);
        assertEquals(1, vm.loadAssignments().size());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void assignRejectsBlankStationWithoutCallingApi() {
        boolean[] called = {false};
        KitchenApi k = new KitchenApi(null) {
            @Override public StationAssignmentView assign(String sku, String stationName) {
                called[0] = true;
                return null;
            }
        };
        KitchenRoutingViewModel vm = vm(k);
        assertNull(vm.assign("COLA", "  "));
        assertFalse(called[0]);
        assertEquals("Station name is required", vm.errorMessage().get());
    }

    @Test
    void assignTrimsAndReturnsViewOnSuccess() {
        KitchenApi k = new KitchenApi(null) {
            @Override public StationAssignmentView assign(String sku, String stationName) {
                return new StationAssignmentView(sku, stationName);
            }
        };
        KitchenRoutingViewModel vm = vm(k);
        StationAssignmentView v = vm.assign("COLA", " Bar ");
        assertEquals("COLA", v.sku());
        assertEquals("Bar", v.stationName());   // VM trims before the call
    }

    @Test
    void unassignReturnsTrueOnSuccess() {
        KitchenApi k = new KitchenApi(null) {
            @Override public void unassign(String sku) { /* ok */ }
        };
        assertTrue(vm(k).unassign("COLA"));
    }

    @Test
    void deferredDispatcherHoldsErrorUntilDrained() {
        KitchenApi k = new KitchenApi(null) {
            @Override public List<StationAssignmentView> listAssignments() {
                throw new ApiException(500, new ProblemDetail("Error", 500, "boom"), "HTTP 500");
            }
        };
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        KitchenRoutingViewModel vm = new KitchenRoutingViewModel(k, new ProductApi(null), queue::add);
        assertNull(vm.loadAssignments());
        assertEquals("", vm.errorMessage().get());   // deferred: not applied yet
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
        assertEquals("boom", vm.errorMessage().get());
    }
}
