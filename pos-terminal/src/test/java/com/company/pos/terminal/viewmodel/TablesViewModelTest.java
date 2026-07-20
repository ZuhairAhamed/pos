package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.TableAdminApi;
import com.company.pos.terminal.api.dto.TableView;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TablesViewModelTest {

    private TablesViewModel vm(TableAdminApi api) {
        return new TablesViewModel(api, Runnable::run);
    }

    @Test
    void loadTablesReturnsListAndClearsError() {
        TableAdminApi api = new TableAdminApi(null) {
            @Override public List<TableView> list() {
                return List.of(new TableView(UUID.randomUUID(), "T1", 4, true));
            }
        };
        TablesViewModel vm = vm(api);
        assertEquals(1, vm.loadTables().size());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void createRejectsBlankLabelWithoutCallingApi() {
        boolean[] called = {false};
        TableAdminApi api = new TableAdminApi(null) {
            @Override public TableView create(String label, Integer seats) {
                called[0] = true;
                return null;
            }
        };
        TablesViewModel vm = vm(api);
        assertNull(vm.create("  ", 4));
        assertFalse(called[0]);
        assertEquals("Table label is required", vm.errorMessage().get());
    }

    @Test
    void createRejectsNonPositiveSeatsWithoutCallingApi() {
        boolean[] called = {false};
        TableAdminApi api = new TableAdminApi(null) {
            @Override public TableView create(String label, Integer seats) {
                called[0] = true;
                return null;
            }
        };
        TablesViewModel vm = vm(api);
        assertNull(vm.create("T2", 0));
        assertFalse(called[0]);
        assertEquals("Seats must be a positive number", vm.errorMessage().get());
    }

    @Test
    void createTrimsAndReturnsViewOnSuccess() {
        TableAdminApi api = new TableAdminApi(null) {
            @Override public TableView create(String label, Integer seats) {
                return new TableView(UUID.randomUUID(), label, seats, true);
            }
        };
        TableView v = vm(api).create("  T3 ", 2);
        assertEquals("T3", v.label());
    }

    @Test
    void reactivateReturnsViewOnSuccess() {
        UUID id = UUID.randomUUID();
        TableAdminApi api = new TableAdminApi(null) {
            @Override public TableView reactivate(UUID tableId) {
                return new TableView(tableId, "T4", 4, true);
            }
        };
        TableView v = vm(api).reactivate(id);
        assertTrue(v.active());
    }

    @Test
    void deferredDispatcherHoldsErrorUntilDrained() {
        TableAdminApi api = new TableAdminApi(null) {
            @Override public List<TableView> list() {
                throw new ApiException(500, new ProblemDetail("Error", 500, "boom"), "HTTP 500");
            }
        };
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        TablesViewModel vm = new TablesViewModel(api, queue::add);
        assertNull(vm.loadTables());
        assertEquals("", vm.errorMessage().get());   // deferred: not applied yet
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
        assertEquals("boom", vm.errorMessage().get());
    }
}
