package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.DiningApi;
import com.company.pos.terminal.api.KitchenTicketApi;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.dto.KitchenTicketView;
import com.company.pos.terminal.api.dto.OpenOrderView;
import com.company.pos.terminal.api.dto.OrderView;
import com.company.pos.terminal.api.dto.TableView;
import com.company.pos.terminal.viewmodel.TableCell.TableState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TableMapViewModelTest {

    private final UUID t1 = UUID.randomUUID();
    private final UUID t2 = UUID.randomUUID();
    private final UUID counter1 = UUID.randomUUID();
    private final UUID openOrderId = UUID.randomUUID();
    private final Instant base = Instant.parse("2026-07-15T12:00:00Z");
    private final Supplier<Instant> fixedClock = () -> base;

    private TableCell cell(TableMapViewModel vm, UUID tableId) {
        return vm.cells().stream().filter(c -> c.tableId().equals(tableId)).findFirst().orElseThrow();
    }

    private static final KitchenTicketApi NO_TICKETS = new KitchenTicketApi(null) {
        @Override public List<KitchenTicketView> list() { return List.of(); }
    };

    private TableMapViewModel vm(DiningApi dining) {
        return new TableMapViewModel(dining, NO_TICKETS, Runnable::run, "Counter ",
                Duration.ofMinutes(45), fixedClock);
    }

    /** Two dine-in tables; t1 has a seated (0-line) DINE_IN order opened "now", t2 is free. */
    private DiningApi diningWithOneSeated() {
        return new DiningApi(null) {
            @Override
            public List<TableView> tables() {
                return List.of(new TableView(t1, "T1", 4, true), new TableView(t2, "T2", 2, true));
            }

            @Override
            public List<OpenOrderView> openOrders() {
                return List.of(new OpenOrderView(openOrderId, t1, "T1", base, 0, "DINE_IN"));
            }
        };
    }

    @Test
    void refreshDerivesFreeAndSeatedStates() {
        TableMapViewModel vm = vm(diningWithOneSeated());
        vm.refresh();
        assertEquals(2, vm.cells().size());
        assertEquals(TableState.SEATED, cell(vm, t1).state());
        assertTrue(cell(vm, t1).occupied());
        assertEquals(openOrderId, cell(vm, t1).orderId());
        assertEquals(TableState.FREE, cell(vm, t2).state());
        assertFalse(cell(vm, t2).occupied());
        assertNull(cell(vm, t2).orderId());
    }

    @Test
    void refreshDerivesActiveStateWhenOrderHasLines() {
        DiningApi dining = new DiningApi(null) {
            @Override
            public List<TableView> tables() {
                return List.of(new TableView(t1, "T1", 4, true));
            }

            @Override
            public List<OpenOrderView> openOrders() {
                return List.of(new OpenOrderView(openOrderId, t1, "T1", base, 3, "DINE_IN"));
            }
        };
        TableMapViewModel vm = vm(dining);
        vm.refresh();
        assertEquals(TableState.ACTIVE, cell(vm, t1).state());
    }

    @Test
    void attentionFlagsOrdersPastDwellThreshold() {
        // Order opened 50 minutes before the clock; threshold is 45 → attention, openMinutes 50.
        Instant opened = base.minus(Duration.ofMinutes(50));
        DiningApi dining = new DiningApi(null) {
            @Override
            public List<TableView> tables() {
                return List.of(new TableView(t1, "T1", 4, true));
            }

            @Override
            public List<OpenOrderView> openOrders() {
                return List.of(new OpenOrderView(openOrderId, t1, "T1", opened, 2, "DINE_IN"));
            }
        };
        TableMapViewModel vm = vm(dining);
        vm.refresh();
        assertTrue(cell(vm, t1).attention());
        assertEquals(50, cell(vm, t1).openMinutes());
    }

    @Test
    void noAttentionJustUnderThreshold() {
        Instant opened = base.minus(Duration.ofMinutes(44));
        DiningApi dining = new DiningApi(null) {
            @Override
            public List<TableView> tables() {
                return List.of(new TableView(t1, "T1", 4, true));
            }

            @Override
            public List<OpenOrderView> openOrders() {
                return List.of(new OpenOrderView(openOrderId, t1, "T1", opened, 2, "DINE_IN"));
            }
        };
        TableMapViewModel vm = vm(dining);
        vm.refresh();
        assertFalse(cell(vm, t1).attention());
    }

    @Test
    void counterTablesExcludedFromGridAndTakeawayBucketed() {
        UUID takeawayOrder = UUID.randomUUID();
        DiningApi dining = new DiningApi(null) {
            @Override
            public List<TableView> tables() {
                return List.of(new TableView(t1, "T1", 4, true),
                        new TableView(counter1, "Counter 1", 1, true));
            }

            @Override
            public List<OpenOrderView> openOrders() {
                return List.of(
                        new OpenOrderView(openOrderId, t1, "T1", base, 1, "DINE_IN"),
                        new OpenOrderView(takeawayOrder, counter1, "Counter 1", base, 2,
                                "QUICK_SERVICE"));
            }
        };
        TableMapViewModel vm = vm(dining);
        vm.refresh();
        // Grid: only the dine-in table.
        assertEquals(1, vm.cells().size());
        assertEquals(t1, vm.cells().get(0).tableId());
        // Takeaway list: only the QUICK_SERVICE order.
        assertEquals(1, vm.takeawayOrders().size());
        TakeawayRow row = vm.takeawayOrders().get(0);
        assertEquals(takeawayOrder, row.orderId());
        assertEquals("Counter 1", row.label());
        assertEquals(2, row.lineCount());
    }

    @Test
    void openTakeawayOpensFirstFreeCounter() {
        UUID newId = UUID.randomUUID();
        UUID counter2 = UUID.randomUUID();
        UUID[] openedAgainst = new UUID[1];
        DiningApi dining = new DiningApi(null) {
            @Override
            public List<TableView> tables() {
                return List.of(new TableView(counter1, "Counter 1", 1, true),
                        new TableView(counter2, "Counter 2", 1, true));
            }

            @Override
            public List<OpenOrderView> openOrders() {
                // Counter 1 is busy → openTakeaway must pick Counter 2.
                return List.of(new OpenOrderView(UUID.randomUUID(), counter1, "Counter 1", base, 0,
                        "QUICK_SERVICE"));
            }

            @Override
            public OrderView openOrder(UUID tableId, String serviceType) {
                openedAgainst[0] = tableId;
                assertEquals("QUICK_SERVICE", serviceType);
                return new OrderView(newId, tableId, "QUICK_SERVICE", "OPEN", "clerk", base, null,
                        null, List.of());
            }
        };
        TableMapViewModel vm = vm(dining);
        assertEquals(newId, vm.openTakeaway());
        assertEquals(counter2, openedAgainst[0]);
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void openTakeawaySurfacesErrorWhenAllCountersBusy() {
        DiningApi dining = new DiningApi(null) {
            @Override
            public List<TableView> tables() {
                return List.of(new TableView(counter1, "Counter 1", 1, true));
            }

            @Override
            public List<OpenOrderView> openOrders() {
                return List.of(new OpenOrderView(UUID.randomUUID(), counter1, "Counter 1", base, 0,
                        "QUICK_SERVICE"));
            }
        };
        TableMapViewModel vm = vm(dining);
        assertNull(vm.openTakeaway());
        assertEquals("All counters are busy", vm.errorMessage().get());
    }

    @Test
    void openOrResumeReturnsExistingOrderIdForOccupied() {
        TableMapViewModel vm = vm(diningWithOneSeated());
        vm.refresh();
        assertEquals(openOrderId, vm.openOrResume(cell(vm, t1)));
    }

    @Test
    void openOrResumeOpensNewDineInOrderForFreeTable() {
        UUID newId = UUID.randomUUID();
        DiningApi dining = new DiningApi(null) {
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
                return new OrderView(newId, tableId, "DINE_IN", "OPEN", "clerk", base, null, null,
                        List.of());
            }
        };
        TableMapViewModel vm = vm(dining);
        vm.refresh();
        assertEquals(newId, vm.openOrResume(vm.cells().get(0)));
    }

    @Test
    void refreshSurfacesErrorMessage() {
        DiningApi dining = new DiningApi(null) {
            @Override
            public List<OpenOrderView> openOrders() {
                throw new ApiException(0, null, "Cannot reach store server");
            }
        };
        TableMapViewModel vm = vm(dining);
        vm.refresh();
        assertEquals("Cannot reach store server", vm.errorMessage().get());
        assertTrue(vm.cells().isEmpty());
    }

    @Test
    void openOrResumeSurfacesErrorAndReturnsNull() {
        DiningApi dining = new DiningApi(null) {
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
                throw new ApiException(409, new ProblemDetail("Conflict", 409, "Table already open"),
                        "HTTP 409");
            }
        };
        TableMapViewModel vm = vm(dining);
        vm.refresh();
        assertNull(vm.openOrResume(vm.cells().get(0)));
        assertEquals("Table already open", vm.errorMessage().get());
    }

    /**
     * Regression (slice-2 lesson): with a deferred UI dispatcher, the observable results are only
     * visible after the queued runnables drain — but the plain return values are correct
     * immediately. Guards that no VM logic depends on the observable being written synchronously.
     */
    @Test
    void refreshWorksUnderDeferredDispatcher() {
        Deque<Runnable> queue = new ArrayDeque<>();
        Consumer<Runnable> deferred = queue::add;
        TableMapViewModel vm = new TableMapViewModel(diningWithOneSeated(), NO_TICKETS, deferred,
                "Counter ", Duration.ofMinutes(45), fixedClock);
        vm.refresh();
        // Nothing applied yet.
        assertTrue(vm.cells().isEmpty());
        // Drain the queued UI mutation.
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
        assertEquals(2, vm.cells().size());
        assertEquals(TableState.SEATED, cell(vm, t1).state());
    }

    @Test
    void tableWithReadyTicketIsFlaggedFoodReady() {
        Deque<Runnable> queue = new ArrayDeque<>();
        KitchenTicketApi tickets = new KitchenTicketApi(null) {
            @Override
            public List<KitchenTicketView> list() {
                return List.of(new KitchenTicketView(UUID.randomUUID(), openOrderId, "T1", "Grill",
                        "READY", base, null, base, null, List.of()));
            }
        };
        TableMapViewModel vm = new TableMapViewModel(diningWithOneSeated(), tickets, queue::add,
                "Counter ", Duration.ofMinutes(45), fixedClock);
        vm.refresh();
        while (!queue.isEmpty()) queue.poll().run();
        assertTrue(cell(vm, t1).foodReady());
        assertFalse(cell(vm, t2).foodReady());
    }

    /** Regression: kitchen KDS endpoint down must not break floor rendering (defensive catch). */
    @Test
    void kitchenFailureDoesNotBreakFloor() {
        Deque<Runnable> queue = new ArrayDeque<>();
        KitchenTicketApi failingTickets = new KitchenTicketApi(null) {
            @Override
            public List<KitchenTicketView> list() {
                throw new ApiException(503, new ProblemDetail("Unavailable", 503, "kitchen down"),
                        "HTTP 503");
            }
        };
        TableMapViewModel vm = new TableMapViewModel(diningWithOneSeated(), failingTickets,
                queue::add, "Counter ", Duration.ofMinutes(45), fixedClock);
        vm.refresh();
        while (!queue.isEmpty()) queue.poll().run();
        // Floor still renders despite kitchen failure.
        assertEquals(2, vm.cells().size());
        assertTrue(vm.cells().stream().noneMatch(TableCell::foodReady));
        // Kitchen outage must NOT surface as a floor error.
        assertEquals("", vm.errorMessage().get());
    }
}
