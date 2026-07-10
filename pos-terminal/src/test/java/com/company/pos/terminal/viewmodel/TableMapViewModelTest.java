package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.DiningApi;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.dto.OpenOrderView;
import com.company.pos.terminal.api.dto.OrderView;
import com.company.pos.terminal.api.dto.TableView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TableMapViewModelTest {

    private final UUID t1 = UUID.randomUUID();
    private final UUID t2 = UUID.randomUUID();
    private final UUID openOrderId = UUID.randomUUID();

    /** Two active tables; t1 has one OPEN dining order, t2 is free. */
    private DiningApi diningWithOneOccupied() {
        return new DiningApi(null) {
            @Override
            public List<TableView> tables() {
                return List.of(new TableView(t1, "T1", 4, true), new TableView(t2, "T2", 2, true));
            }

            @Override
            public List<OpenOrderView> openOrders() {
                return List.of(new OpenOrderView(openOrderId, t1, "T1", Instant.now(), 0));
            }
        };
    }

    @Test
    void refreshMarksOccupiedTables() {
        TableMapViewModel vm = new TableMapViewModel(diningWithOneOccupied());
        vm.refresh();
        assertEquals(2, vm.cells().size());
        TableCell c1 =
                vm.cells().stream().filter(c -> c.tableId().equals(t1)).findFirst().orElseThrow();
        TableCell c2 =
                vm.cells().stream().filter(c -> c.tableId().equals(t2)).findFirst().orElseThrow();
        assertTrue(c1.occupied());
        assertEquals(openOrderId, c1.orderId());
        assertFalse(c2.occupied());
        assertNull(c2.orderId());
    }

    @Test
    void openOrResumeReturnsExistingOrderIdForOccupied() {
        TableMapViewModel vm = new TableMapViewModel(diningWithOneOccupied());
        vm.refresh();
        TableCell occupied =
                vm.cells().stream().filter(TableCell::occupied).findFirst().orElseThrow();
        assertEquals(openOrderId, vm.openOrResume(occupied));
    }

    /**
     * Regression: occupied-table fast-path must clear a stale error set by a prior action.
     *
     * <p>Arrange: plant a stale error via a failing openOrResume on a free table (t2), while t1 is
     * occupied. Act: call openOrResume on the occupied t1 cell. Assert: errorMessage is cleared and
     * the existing order id is returned.
     */
    @Test
    void openOrResumeClearsStaleErrorOnOccupiedTable() {
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public List<TableView> tables() {
                        return List.of(
                                new TableView(t1, "T1", 4, true),
                                new TableView(t2, "T2", 2, true));
                    }

                    @Override
                    public List<OpenOrderView> openOrders() {
                        return List.of(new OpenOrderView(openOrderId, t1, "T1", Instant.now(), 0));
                    }

                    @Override
                    public OrderView openOrder(UUID tableId) {
                        // Always fails — used only to plant the stale error on the free table.
                        throw new ApiException(
                                503,
                                new ProblemDetail("Unavailable", 503, "Server busy"),
                                "HTTP 503");
                    }
                };
        TableMapViewModel vm = new TableMapViewModel(dining);
        vm.refresh(); // t1 occupied, t2 free

        // Plant stale error: attempt to open the free table, which fails.
        TableCell freeCell =
                vm.cells().stream().filter(c -> c.tableId().equals(t2)).findFirst().orElseThrow();
        assertNull(vm.openOrResume(freeCell));
        assertEquals("Server busy", vm.errorMessage().get()); // stale error is set

        // Act: tap the occupied table.
        TableCell occupiedCell =
                vm.cells().stream().filter(TableCell::occupied).findFirst().orElseThrow();
        UUID returnedId = vm.openOrResume(occupiedCell);

        // Assert: stale error cleared, correct order id returned.
        assertEquals("", vm.errorMessage().get());
        assertEquals(openOrderId, returnedId);
    }

    @Test
    void openOrResumeOpensNewOrderForFreeTable() {
        UUID newId = UUID.randomUUID();
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public List<TableView> tables() {
                        return List.of(new TableView(t2, "T2", 2, true));
                    }

                    @Override
                    public List<OpenOrderView> openOrders() {
                        return List.of();
                    }

                    @Override
                    public OrderView openOrder(UUID tableId) {
                        return new OrderView(
                                newId, tableId, "DINE_IN", "OPEN", "clerk", Instant.now(), null,
                                null, List.of());
                    }
                };
        TableMapViewModel vm = new TableMapViewModel(dining);
        vm.refresh();
        assertEquals(newId, vm.openOrResume(vm.cells().get(0)));
    }

    @Test
    void refreshSurfacesErrorMessage() {
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public List<OpenOrderView> openOrders() {
                        throw new ApiException(0, null, "Cannot reach store server");
                    }
                };
        TableMapViewModel vm = new TableMapViewModel(dining);
        vm.refresh();
        assertEquals("Cannot reach store server", vm.errorMessage().get());
        assertTrue(vm.cells().isEmpty());
    }

    @Test
    void openOrResumeSurfacesErrorAndReturnsNull() {
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public List<TableView> tables() {
                        return List.of(new TableView(t2, "T2", 2, true));
                    }

                    @Override
                    public List<OpenOrderView> openOrders() {
                        return List.of();
                    }

                    @Override
                    public OrderView openOrder(UUID tableId) {
                        throw new ApiException(
                                409,
                                new ProblemDetail("Conflict", 409, "Table already open"),
                                "HTTP 409");
                    }
                };
        TableMapViewModel vm = new TableMapViewModel(dining);
        vm.refresh();
        assertNull(vm.openOrResume(vm.cells().get(0)));
        assertEquals("Table already open", vm.errorMessage().get());
    }
}
