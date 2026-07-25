package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.AvailabilityApi;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.dto.ProductView;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class AvailabilityViewModelTest {

    private ProductView p(String sku, Boolean active, Boolean available) {
        return new ProductView(sku, sku, "Mains", null, new BigDecimal("10.00"), active, available);
    }

    @Test
    void loadReturnsActiveProductsOnly() {
        AvailabilityApi api = new AvailabilityApi(null) {
            @Override public List<ProductView> list() {
                return List.of(p("SALMON", true, true), p("OLD", false, true), p("NULLACT", null, true));
            }
        };
        AvailabilityViewModel vm = new AvailabilityViewModel(api, Runnable::run);
        List<ProductView> rows = vm.load();
        assertEquals(2, rows.size()); // SALMON + NULLACT (null active treated as active); OLD filtered out
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void setAvailabilityReturnsUpdatedRow() {
        AvailabilityApi api = new AvailabilityApi(null) {
            @Override public ProductView setAvailability(String sku, boolean available) {
                return p(sku, true, available);
            }
        };
        AvailabilityViewModel vm = new AvailabilityViewModel(api, Runnable::run);
        ProductView v = vm.setAvailability("SALMON", false);
        assertNotNull(v);
        assertEquals(Boolean.FALSE, v.available());
    }

    @Test
    void loadSurfacesErrorAndReturnsNull() {
        AvailabilityApi api = new AvailabilityApi(null) {
            @Override public List<ProductView> list() {
                throw new ApiException(500, new ProblemDetail("Error", 500, "boom"), "HTTP 500");
            }
        };
        AvailabilityViewModel vm = new AvailabilityViewModel(api, Runnable::run);
        assertNull(vm.load());
        assertEquals("boom", vm.errorMessage().get());
    }

    @Test
    void deferredDispatcherHoldsErrorUntilDrained() {
        AvailabilityApi api = new AvailabilityApi(null) {
            @Override public ProductView setAvailability(String sku, boolean available) {
                throw new ApiException(409, new ProblemDetail("Conflict", 409, "Item is 86'd"), "HTTP 409");
            }
        };
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        AvailabilityViewModel vm = new AvailabilityViewModel(api, queue::add);
        ProductView r = vm.setAvailability("SALMON", false);
        assertNull(r);                               // synchronous return
        assertEquals("", vm.errorMessage().get());   // deferred: not applied yet
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals("Item is 86'd", vm.errorMessage().get());
    }
}
