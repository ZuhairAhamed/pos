package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.MenuAdminApi;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.ProductApi;
import com.company.pos.terminal.api.dto.ModifierGroupAdminView;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModifierBuilderViewModelTest {

    private ModifierBuilderViewModel vm(MenuAdminApi m) {
        return new ModifierBuilderViewModel(m, new ProductApi(null), Runnable::run);
    }

    @Test
    void loadGroupsReturnsListAndClearsError() {
        MenuAdminApi m = new MenuAdminApi(null) {
            @Override public List<ModifierGroupAdminView> listGroups() {
                return List.of(new ModifierGroupAdminView(UUID.randomUUID(), "Add-ons", 0, 2, true,
                        List.of(), List.of()));
            }
        };
        ModifierBuilderViewModel vm = vm(m);
        assertEquals(1, vm.loadGroups().size());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void createGroupRejectsBlankNameWithoutCallingApi() {
        boolean[] called = {false};
        MenuAdminApi m = new MenuAdminApi(null) {
            @Override public void createGroup(String name, int min, int max) {
                called[0] = true;
            }
        };
        ModifierBuilderViewModel vm = vm(m);
        assertFalse(vm.createGroup("  ", 0, 2));
        assertFalse(called[0]);
        assertEquals("Group name is required", vm.errorMessage().get());
    }

    @Test
    void createGroupRejectsBadSelectionsWithoutCallingApi() {
        boolean[] called = {false};
        MenuAdminApi m = new MenuAdminApi(null) {
            @Override public void createGroup(String name, int min, int max) {
                called[0] = true;
            }
        };
        ModifierBuilderViewModel vm = vm(m);
        assertFalse(vm.createGroup("X", 3, 1));
        assertFalse(called[0]);
        assertEquals("Invalid min/max selections", vm.errorMessage().get());
    }

    @Test
    void addOptionRejectsNullPriceWithoutCallingApi() {
        boolean[] called = {false};
        MenuAdminApi m = new MenuAdminApi(null) {
            @Override public void addOption(UUID g, String name, BigDecimal d) {
                called[0] = true;
            }
        };
        ModifierBuilderViewModel vm = vm(m);
        assertFalse(vm.addOption(UUID.randomUUID(), "Cheese", null));
        assertFalse(called[0]);
    }

    @Test
    void updateGroupSuccessReturnsTrue() {
        MenuAdminApi m = new MenuAdminApi(null) {
            @Override public void updateGroup(UUID g, String name, int min, int max) { /* ok */ }
        };
        assertTrue(vm(m).updateGroup(UUID.randomUUID(), "Extras", 1, 3));
    }

    @Test
    void deferredDispatcherHoldsErrorUntilDrained() {
        MenuAdminApi m = new MenuAdminApi(null) {
            @Override public List<ModifierGroupAdminView> listGroups() {
                throw new ApiException(500, new ProblemDetail("Error", 500, "boom"), "HTTP 500");
            }
        };
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        ModifierBuilderViewModel vm = new ModifierBuilderViewModel(m, new ProductApi(null), queue::add);
        assertNull(vm.loadGroups());
        assertEquals("", vm.errorMessage().get());   // deferred: not applied yet
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
        assertEquals("boom", vm.errorMessage().get());
    }
}
