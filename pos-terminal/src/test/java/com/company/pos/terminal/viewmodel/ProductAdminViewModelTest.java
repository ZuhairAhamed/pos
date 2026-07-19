package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.CreateProductRequest;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.ProductAdminApi;
import com.company.pos.terminal.api.ProductAdminView;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductAdminViewModelTest {

    private ProductAdminView sample() {
        return new ProductAdminView("COLA", "Cola", "Beverages", "bcCOLA", "EA",
                new BigDecimal("5.00"), "SAR", true);
    }

    @Test
    void loadReturnsProductsAndClearsError() {
        ProductAdminApi api = new ProductAdminApi(null) {
            @Override public List<ProductAdminView> list() { return List.of(sample()); }
        };
        ProductAdminViewModel vm = new ProductAdminViewModel(api, Runnable::run);
        List<ProductAdminView> r = vm.load();
        assertEquals(1, r.size());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void createSurfacesConflictAndReturnsNull() {
        ProductAdminApi api = new ProductAdminApi(null) {
            @Override public ProductAdminView create(CreateProductRequest req) {
                throw new ApiException(409, new ProblemDetail("Conflict", 409, "SKU already in use"),
                        "HTTP 409");
            }
        };
        ProductAdminViewModel vm = new ProductAdminViewModel(api, Runnable::run);
        assertNull(vm.create(new CreateProductRequest("COLA", "Cola", null, null,
                new BigDecimal("5.00"), "SAR", "EA", null)));
        assertEquals("SKU already in use", vm.errorMessage().get());
    }

    @Test
    void deactivateReturnsTrueOnSuccess() {
        ProductAdminApi api = new ProductAdminApi(null) {
            @Override public void deactivate(String sku) { /* ok */ }
        };
        ProductAdminViewModel vm = new ProductAdminViewModel(api, Runnable::run);
        assertTrue(vm.deactivate("COLA"));
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void deferredDispatcherHoldsErrorUntilDrained() {
        ProductAdminApi api = new ProductAdminApi(null) {
            @Override public List<ProductAdminView> list() {
                throw new ApiException(500, new ProblemDetail("Error", 500, "boom"), "HTTP 500");
            }
        };
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        ProductAdminViewModel vm = new ProductAdminViewModel(api, queue::add);
        assertNull(vm.load());
        assertEquals("", vm.errorMessage().get());   // deferred: not applied yet
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
        assertEquals("boom", vm.errorMessage().get());
    }
}
