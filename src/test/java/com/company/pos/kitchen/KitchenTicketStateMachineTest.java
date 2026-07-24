package com.company.pos.kitchen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.kitchen.api.KitchenTicketState;
import com.company.pos.kitchen.domain.KitchenTicket;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KitchenTicketStateMachineTest {

    private final Instant t0 = Instant.parse("2026-07-24T10:00:00Z");
    private final Instant t1 = Instant.parse("2026-07-24T10:01:00Z");
    private final Instant t2 = Instant.parse("2026-07-24T10:02:00Z");

    private KitchenTicket fired() {
        return new KitchenTicket(UUID.randomUUID(), UUID.randomUUID(), "5", "Grill", t0);
    }

    @Test
    void newTicketStartsFired() {
        assertThat(fired().getState()).isEqualTo(KitchenTicketState.FIRED);
    }

    @Test
    void advanceWalksTheHappyPathStampingTimestamps() {
        KitchenTicket t = fired();
        t.advance(KitchenTicketState.FIRED, t1);
        assertThat(t.getState()).isEqualTo(KitchenTicketState.PREPARING);
        assertThat(t.getPreparingAt()).isEqualTo(t1);
        t.advance(KitchenTicketState.PREPARING, t2);
        assertThat(t.getState()).isEqualTo(KitchenTicketState.READY);
        assertThat(t.getReadyAt()).isEqualTo(t2);
        t.advance(KitchenTicketState.READY, t2);
        assertThat(t.getState()).isEqualTo(KitchenTicketState.BUMPED);
        assertThat(t.getBumpedAt()).isEqualTo(t2);
    }

    @Test
    void advanceWithWrongExpectedStateIsRejected() {
        KitchenTicket t = fired();
        assertThatThrownBy(() -> t.advance(KitchenTicketState.READY, t1))
                .isInstanceOf(DomainException.class);
        assertThat(t.getState()).isEqualTo(KitchenTicketState.FIRED);
    }

    @Test
    void advancingABumpedTicketIsRejected() {
        KitchenTicket t = fired();
        t.advance(KitchenTicketState.FIRED, t1);
        t.advance(KitchenTicketState.PREPARING, t1);
        t.advance(KitchenTicketState.READY, t1);
        assertThatThrownBy(() -> t.advance(KitchenTicketState.BUMPED, t2))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void recallStepsBackAndClearsLaterTimestamps() {
        KitchenTicket t = fired();
        t.advance(KitchenTicketState.FIRED, t1);
        t.advance(KitchenTicketState.PREPARING, t2);   // READY
        t.recall(KitchenTicketState.READY);
        assertThat(t.getState()).isEqualTo(KitchenTicketState.PREPARING);
        assertThat(t.getReadyAt()).isNull();
    }

    @Test
    void recallFromFiredIsRejected() {
        KitchenTicket t = fired();
        assertThatThrownBy(() -> t.recall(KitchenTicketState.FIRED))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void cancelIsTerminalFromAnyLiveState() {
        KitchenTicket t = fired();
        t.advance(KitchenTicketState.FIRED, t1);
        t.cancel();
        assertThat(t.getState()).isEqualTo(KitchenTicketState.CANCELLED);
    }
}
