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
                    public OrderView updateLine(UUID oid, UUID lineId, BigDecimal qty) {
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
}
