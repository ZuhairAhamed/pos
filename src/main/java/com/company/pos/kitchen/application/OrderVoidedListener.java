package com.company.pos.kitchen.application;

import com.company.pos.common.events.DomainEvents;
import com.company.pos.dining.api.DiningFloorChanged;
import com.company.pos.dining.api.FloorChangeType;
import com.company.pos.kitchen.api.KitchenTicketChanged;
import com.company.pos.kitchen.api.KitchenTicketState;
import com.company.pos.kitchen.domain.KitchenTicket;
import com.company.pos.kitchen.infrastructure.KitchenTicketRepository;
import java.time.Instant;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Cancels a voided order's live tickets so killed food clears the board. */
@Component
class OrderVoidedListener {

    private final KitchenTicketRepository repo;
    private final DomainEvents events;

    OrderVoidedListener(KitchenTicketRepository repo, DomainEvents events) {
        this.repo = repo;
        this.events = events;
    }

    @ApplicationModuleListener
    void on(DiningFloorChanged event) {
        if (event.change() != FloorChangeType.ORDER_VOIDED || event.orderId() == null) {
            return;
        }
        for (KitchenTicket t : repo.findByOrderId(event.orderId())) {
            if (t.getState() != KitchenTicketState.BUMPED
                    && t.getState() != KitchenTicketState.CANCELLED) {
                t.cancel();
                repo.save(t);
                events.publish(new KitchenTicketChanged(t.getId(), t.getOrderId(), Instant.now()));
            }
        }
    }
}
