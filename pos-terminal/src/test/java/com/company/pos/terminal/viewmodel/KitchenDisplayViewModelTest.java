package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.KitchenTicketApi;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.dto.KitchenTicketView;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class KitchenDisplayViewModelTest {

    private final Instant base = Instant.parse("2026-07-24T12:00:00Z");
    private final Supplier<Instant> clock = () -> base;

    private KitchenTicketView ticketFiredAt(Instant firedAt) {
        return new KitchenTicketView(UUID.randomUUID(), UUID.randomUUID(), "5", "Grill", "FIRED",
                firedAt, null, null, null, List.of());
    }

    private KitchenTicketApi apiReturning(List<KitchenTicketView> list) {
        return new KitchenTicketApi(null) {
            @Override public List<KitchenTicketView> list() { return list; }
        };
    }

    @Test
    void refreshPopulatesTickets() {
        KitchenDisplayViewModel vm = new KitchenDisplayViewModel(
                apiReturning(List.of(ticketFiredAt(base))));
        vm.refresh();
        assertEquals(1, vm.tickets().size());
    }

    @Test
    void agingBucketsFollowThresholds() {
        KitchenDisplayViewModel vm = new KitchenDisplayViewModel(
                apiReturning(List.of()), Runnable::run, clock,
                Duration.ofSeconds(300), Duration.ofSeconds(600));
        assertEquals(KitchenDisplayViewModel.Aging.GREEN,
                vm.agingOf(ticketFiredAt(base.minusSeconds(100))));
        assertEquals(KitchenDisplayViewModel.Aging.AMBER,
                vm.agingOf(ticketFiredAt(base.minusSeconds(400))));
        assertEquals(KitchenDisplayViewModel.Aging.RED,
                vm.agingOf(ticketFiredAt(base.minusSeconds(700))));
    }

    @Test
    void advanceSurfacesConflictUnderDeferredDispatcher() {
        KitchenTicketApi api = new KitchenTicketApi(null) {
            @Override public KitchenTicketView advance(UUID id, String expectedState) {
                throw new ApiException(409, new ProblemDetail("Conflict", 409,
                        "Ticket state changed"), "HTTP 409");
            }
        };
        Deque<Runnable> queue = new ArrayDeque<>();
        Consumer<Runnable> deferred = queue::add;
        KitchenDisplayViewModel vm = new KitchenDisplayViewModel(api, deferred, clock,
                Duration.ofSeconds(300), Duration.ofSeconds(600));

        boolean ok = vm.advance(UUID.randomUUID(), "FIRED");

        assertFalse(ok);                                  // synchronous truth: failed
        assertEquals("", vm.errorMessage().get());        // deferred: not applied yet
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals("Ticket state changed", vm.errorMessage().get());
    }
}
