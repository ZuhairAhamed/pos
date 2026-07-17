package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.DiningApi;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.dto.AddLineRequest;
import com.company.pos.terminal.api.dto.OrderLineView;
import com.company.pos.terminal.api.dto.OrderView;
import com.company.pos.terminal.api.dto.ProductView;
import com.company.pos.terminal.order.MenuCache;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderViewModelTest {

    private final UUID orderId = UUID.randomUUID();
    private final UUID tableId = UUID.randomUUID();
    private final UUID firedLineId = UUID.randomUUID();

    private MenuCache cache() {
        return new MenuCache(
                List.of(new ProductView("BURGER", "Burger", "Mains", null, new BigDecimal("25.00"))));
    }

    /** Real OrderView is 9-arg: (id, tableId, serviceType, status, openedBy, openedAt, closedAt, saleId, lines). */
    private OrderView orderWith(List<OrderLineView> lines) {
        return new OrderView(
                orderId, tableId, "DINE_IN", "OPEN", "clerk", Instant.now(), null, null, lines);
    }

    @Test
    void addLineRefreshesLinesAndSubtotal() {
        OrderLineView added =
                new OrderLineView(
                        UUID.randomUUID(), "BURGER", new BigDecimal("2"), null, "MAIN", null,
                        List.of());
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of());
                    }

                    @Override
                    public OrderView addLine(UUID oid, AddLineRequest req) {
                        return orderWith(List.of(added));
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        assertEquals(0, vm.lines().size());
        vm.addLine("BURGER", new BigDecimal("2"), null, "MAIN", List.of());
        assertEquals(1, vm.lines().size());
        assertTrue(vm.subtotalText().get().contains("50.00"));
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void firedLineCannotBeEdited() {
        OrderLineView fired =
                new OrderLineView(
                        firedLineId, "BURGER", new BigDecimal("1"), null, "MAIN", Instant.now(),
                        List.of());
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(fired));
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        assertFalse(vm.canEdit(fired));
        vm.updateQty(fired, new BigDecimal("5"));
        assertEquals("Fired lines cannot be changed", vm.errorMessage().get());
    }

    @Test
    void firedLineCannotBeRemoved() {
        OrderLineView fired =
                new OrderLineView(
                        firedLineId, "BURGER", new BigDecimal("1"), null, "MAIN", Instant.now(),
                        List.of());
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(fired));
                    }

                    @Override
                    public OrderView removeLine(UUID oid, UUID lineId) {
                        throw new AssertionError("removeLine must not be called for a fired line");
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        vm.removeLine(fired);
        assertEquals("Fired lines cannot be changed", vm.errorMessage().get());
        assertEquals(1, vm.lines().size());
    }

    @Test
    void updateQtyRefreshesState() {
        OrderLineView before =
                new OrderLineView(
                        firedLineId, "BURGER", new BigDecimal("1"), null, "MAIN", null, List.of());
        OrderLineView after =
                new OrderLineView(
                        firedLineId, "BURGER", new BigDecimal("3"), null, "MAIN", null, List.of());
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(before));
                    }

                    @Override
                    public OrderView updateLine(UUID oid, UUID lineId, BigDecimal qty, String note, String course) {
                        return orderWith(List.of(after));
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        assertTrue(vm.subtotalText().get().contains("25.00"));
        vm.updateQty(before, new BigDecimal("3"));
        assertEquals(new BigDecimal("3"), vm.lines().get(0).qty());
        assertTrue(vm.subtotalText().get().contains("75.00"));
    }

    @Test
    void removeLineRefreshesState() {
        OrderLineView line =
                new OrderLineView(
                        firedLineId, "BURGER", new BigDecimal("2"), null, "MAIN", null, List.of());
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(line));
                    }

                    @Override
                    public OrderView removeLine(UUID oid, UUID lineId) {
                        return orderWith(List.of());
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        assertEquals(1, vm.lines().size());
        vm.removeLine(line);
        assertEquals(0, vm.lines().size());
        assertTrue(vm.subtotalText().get().contains("0.00"));
    }

    @Test
    void apiExceptionSurfacesErrorAndLeavesStateIntact() {
        OrderLineView existing =
                new OrderLineView(
                        firedLineId, "BURGER", new BigDecimal("2"), null, "MAIN", null, List.of());
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(existing));
                    }

                    @Override
                    public OrderView addLine(UUID oid, AddLineRequest req) {
                        throw new ApiException(
                                409, new ProblemDetail("Conflict", 409, "Kitchen closed"),
                                "HTTP 409");
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        vm.addLine("BURGER", new BigDecimal("1"), null, "MAIN", List.of());
        assertEquals("Kitchen closed", vm.errorMessage().get());
        // State untouched by the failed add.
        assertEquals(1, vm.lines().size());
        assertTrue(vm.subtotalText().get().contains("50.00"));
    }

    @Test
    void updateQtyPreservesNoteAndCourse() {
        OrderLineView line =
                new OrderLineView(
                        firedLineId, "BURGER", new BigDecimal("1"), "no onion", "STARTER", null,
                        List.of());
        String[] captured = new String[2];
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(line));
                    }

                    @Override
                    public OrderView updateLine(UUID oid, UUID lineId, BigDecimal qty, String note,
                            String course) {
                        captured[0] = note;
                        captured[1] = course;
                        return orderWith(List.of(new OrderLineView(
                                lineId, "BURGER", qty, note, course, null, List.of())));
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        vm.updateQty(line, new BigDecimal("4"));
        assertEquals("no onion", captured[0]);
        assertEquals("STARTER", captured[1]);
    }

    @Test
    void updateCourseChangesCoursePreservingQtyAndNote() {
        OrderLineView line =
                new OrderLineView(
                        firedLineId, "BURGER", new BigDecimal("2"), "no onion", "MAIN", null,
                        List.of());
        BigDecimal[] capturedQty = new BigDecimal[1];
        String[] capturedNote = new String[1];
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(line));
                    }

                    @Override
                    public OrderView updateLine(UUID oid, UUID lineId, BigDecimal qty, String note,
                            String course) {
                        capturedQty[0] = qty;
                        capturedNote[0] = note;
                        return orderWith(List.of(new OrderLineView(
                                lineId, "BURGER", qty, note, course, null, List.of())));
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        vm.updateCourse(line, "DESSERT");
        assertEquals(new BigDecimal("2"), capturedQty[0]);
        assertEquals("no onion", capturedNote[0]);
        assertEquals("DESSERT", vm.lines().get(0).course());
    }

    @Test
    void updateCourseOnFiredLineIsNoOp() {
        OrderLineView fired =
                new OrderLineView(
                        firedLineId, "BURGER", new BigDecimal("1"), null, "MAIN", Instant.now(),
                        List.of());
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(fired));
                    }

                    @Override
                    public OrderView updateLine(UUID oid, UUID lineId, BigDecimal qty, String note,
                            String course) {
                        throw new AssertionError("updateLine must not be called for a fired line");
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        vm.updateCourse(fired, "DESSERT");
        assertEquals("Fired lines cannot be changed", vm.errorMessage().get());
        assertEquals("MAIN", vm.lines().get(0).course());
    }

    @Test
    void deferredDispatcherHoldsCourseWriteUntilDrained() {
        OrderLineView before =
                new OrderLineView(
                        firedLineId, "BURGER", new BigDecimal("1"), null, "MAIN", null, List.of());
        OrderLineView after =
                new OrderLineView(
                        firedLineId, "BURGER", new BigDecimal("1"), null, "DESSERT", null, List.of());
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(before));
                    }

                    @Override
                    public OrderView updateLine(UUID oid, UUID lineId, BigDecimal qty, String note,
                            String course) {
                        return orderWith(List.of(after));
                    }
                };
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        OrderViewModel vm = new OrderViewModel(dining, cache(), queue::add);
        vm.load(orderId);
        while (!queue.isEmpty()) queue.poll().run();       // drain load → baseline
        assertEquals("MAIN", vm.lines().get(0).course());
        vm.updateCourse(before, "DESSERT");
        assertEquals("MAIN", vm.lines().get(0).course());  // deferred: not yet applied
        while (!queue.isEmpty()) queue.poll().run();       // drain the course write
        assertEquals("DESSERT", vm.lines().get(0).course());
    }

    @Test
    void voidOrderReturnsTrueOnSuccess() {
        OrderLineView line =
                new OrderLineView(firedLineId, "BURGER", new BigDecimal("1"), null, "MAIN", null, List.of());
        boolean[] called = {false};
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(line));
                    }

                    @Override
                    public void voidOrder(UUID orderId, String reason, String bearerToken) {
                        called[0] = true;
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        vm.setError("stale");
        assertTrue(vm.voidOrder("walkout", "tok"));
        assertTrue(called[0]);
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void voidOrderSurfacesErrorAndReturnsFalse() {
        OrderLineView line =
                new OrderLineView(firedLineId, "BURGER", new BigDecimal("1"), null, "MAIN", null, List.of());
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(line));
                    }

                    @Override
                    public void voidOrder(UUID orderId, String reason, String bearerToken) {
                        throw new ApiException(
                                403, new ProblemDetail("Forbidden", 403, "Manager role required"), "HTTP 403");
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        assertFalse(vm.voidOrder("x", "tok"));
        assertEquals("Manager role required", vm.errorMessage().get());
    }

    @Test
    void deferredDispatcherHoldsVoidErrorUntilDrained() {
        OrderLineView line =
                new OrderLineView(firedLineId, "BURGER", new BigDecimal("1"), null, "MAIN", null, List.of());
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(line));
                    }

                    @Override
                    public void voidOrder(UUID orderId, String reason, String bearerToken) {
                        throw new ApiException(
                                403, new ProblemDetail("Forbidden", 403, "Manager role required"), "HTTP 403");
                    }
                };
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        OrderViewModel vm = new OrderViewModel(dining, cache(), queue::add);
        vm.load(orderId);
        while (!queue.isEmpty()) queue.poll().run();       // drain load → baseline
        boolean result = vm.voidOrder("x", "tok");
        assertFalse(result);                               // synchronous return
        assertEquals("", vm.errorMessage().get());         // deferred: error not applied yet
        while (!queue.isEmpty()) queue.poll().run();       // drain the error write
        assertEquals("Manager role required", vm.errorMessage().get());
    }

    @Test
    void setErrorSurfacesMessage() {
        OrderViewModel vm = new OrderViewModel(new DiningApi(null), cache());
        vm.setError("Manager approval failed");
        assertEquals("Manager approval failed", vm.errorMessage().get());
    }

    @Test
    void deferredDispatcherHoldsSetErrorUntilDrained() {
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        OrderViewModel vm = new OrderViewModel(new DiningApi(null), cache(), queue::add);
        vm.setError("boom");
        assertEquals("", vm.errorMessage().get());   // deferred: write not applied yet
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals("boom", vm.errorMessage().get());
    }

    @Test
    void transferReturnsTrueOnSuccess() {
        OrderLineView line =
                new OrderLineView(firedLineId, "BURGER", new BigDecimal("1"), null, "MAIN", null, List.of());
        UUID[] captured = new UUID[1];
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(line));
                    }

                    @Override
                    public OrderView transferOrder(UUID oid, UUID targetTableId) {
                        captured[0] = targetTableId;
                        return orderWith(List.of(line));
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        UUID target = UUID.randomUUID();
        assertTrue(vm.transfer(target));
        assertEquals(target, captured[0]);
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void transferSurfacesErrorAndReturnsFalse() {
        OrderLineView line =
                new OrderLineView(firedLineId, "BURGER", new BigDecimal("1"), null, "MAIN", null, List.of());
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(line));
                    }

                    @Override
                    public OrderView transferOrder(UUID oid, UUID targetTableId) {
                        throw new ApiException(409,
                                new ProblemDetail("Conflict", 409, "Table T2 already has an open order"),
                                "HTTP 409");
                    }
                };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        assertFalse(vm.transfer(UUID.randomUUID()));
        assertEquals("Table T2 already has an open order", vm.errorMessage().get());
    }

    @Test
    void deferredDispatcherHoldsTransferErrorUntilDrained() {
        OrderLineView line =
                new OrderLineView(firedLineId, "BURGER", new BigDecimal("1"), null, "MAIN", null, List.of());
        DiningApi dining =
                new DiningApi(null) {
                    @Override
                    public OrderView order(UUID id) {
                        return orderWith(List.of(line));
                    }

                    @Override
                    public OrderView transferOrder(UUID oid, UUID targetTableId) {
                        throw new ApiException(409,
                                new ProblemDetail("Conflict", 409, "Table T2 already has an open order"),
                                "HTTP 409");
                    }
                };
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        OrderViewModel vm = new OrderViewModel(dining, cache(), queue::add);
        vm.load(orderId);
        while (!queue.isEmpty()) queue.poll().run();       // drain load → baseline
        boolean result = vm.transfer(UUID.randomUUID());
        assertFalse(result);                               // synchronous return
        assertEquals("", vm.errorMessage().get());         // deferred: error not applied yet
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals("Table T2 already has an open order", vm.errorMessage().get());
    }
}
