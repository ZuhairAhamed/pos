package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.VariantAdminApi;
import com.company.pos.terminal.api.dto.VariantGroupAdminView;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VariantBuilderViewModelTest {

    private VariantBuilderViewModel vm(VariantAdminApi api) {
        return new VariantBuilderViewModel(api, Runnable::run);
    }

    @Test
    void loadReturnsListAndClearsError() {
        VariantAdminApi api = new VariantAdminApi(null) {
            @Override public List<VariantGroupAdminView> listAdmin() {
                return List.of(new VariantGroupAdminView(UUID.randomUUID(), "Sizes", true, List.of()));
            }
        };
        VariantBuilderViewModel vm = vm(api);
        assertEquals(1, vm.load().size());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void loadReturnsNullOnApiError() {
        VariantAdminApi api = new VariantAdminApi(null) {
            @Override public List<VariantGroupAdminView> listAdmin() {
                throw new ApiException(500, new ProblemDetail("Error", 500, "boom"), "HTTP 500");
            }
        };
        assertNull(vm(api).load());
    }

    @Test
    void createGroupRejectsBlankNameWithoutCallingApi() {
        boolean[] called = {false};
        VariantAdminApi api = new VariantAdminApi(null) {
            @Override public void createGroup(String name) { called[0] = true; }
        };
        VariantBuilderViewModel vm = vm(api);
        assertFalse(vm.createGroup("  "));
        assertFalse(called[0]);
        assertEquals("Group name is required", vm.errorMessage().get());
    }

    @Test
    void addMemberRejectsBlankSkuWithoutCallingApi() {
        boolean[] called = {false};
        VariantAdminApi api = new VariantAdminApi(null) {
            @Override public void addMember(UUID g, String sku, String label) { called[0] = true; }
        };
        VariantBuilderViewModel vm = vm(api);
        assertFalse(vm.addMember(UUID.randomUUID(), "  ", "Small"));
        assertFalse(called[0]);
        assertEquals("SKU is required", vm.errorMessage().get());
    }

    @Test
    void addMemberRejectsBlankLabelWithoutCallingApi() {
        boolean[] called = {false};
        VariantAdminApi api = new VariantAdminApi(null) {
            @Override public void addMember(UUID g, String sku, String label) { called[0] = true; }
        };
        VariantBuilderViewModel vm = vm(api);
        assertFalse(vm.addMember(UUID.randomUUID(), "BEER-S", "  "));
        assertFalse(called[0]);
        assertEquals("Display label is required", vm.errorMessage().get());
    }

    @Test
    void updateMemberRejectsBlankLabelWithoutCallingApi() {
        boolean[] called = {false};
        VariantAdminApi api = new VariantAdminApi(null) {
            @Override public void updateMember(UUID g, UUID m, String label) { called[0] = true; }
        };
        VariantBuilderViewModel vm = vm(api);
        assertFalse(vm.updateMember(UUID.randomUUID(), UUID.randomUUID(), "  "));
        assertFalse(called[0]);
        assertEquals("Display label is required", vm.errorMessage().get());
    }

    @Test
    void createGroupSuccessReturnsTrue() {
        VariantAdminApi api = new VariantAdminApi(null) {
            @Override public void createGroup(String name) { /* ok */ }
        };
        assertTrue(vm(api).createGroup("Sizes"));
    }

    @Test
    void deferredDispatcherHoldsErrorUntilDrained() {
        VariantAdminApi api = new VariantAdminApi(null) {
            @Override public List<VariantGroupAdminView> listAdmin() {
                throw new ApiException(500, new ProblemDetail("Error", 500, "boom"), "HTTP 500");
            }
        };
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        VariantBuilderViewModel vm = new VariantBuilderViewModel(api, queue::add);
        assertNull(vm.load());
        assertEquals("", vm.errorMessage().get());   // deferred: not applied yet
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
        assertEquals("boom", vm.errorMessage().get());
    }
}
