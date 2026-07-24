package com.company.pos.kitchen.application;

import com.company.pos.common.events.DomainEvents;
import com.company.pos.common.util.Identifiers;
import com.company.pos.dining.api.KitchenTicketsFired;
import com.company.pos.kitchen.api.KitchenService;
import com.company.pos.kitchen.api.KitchenTicketChanged;
import com.company.pos.kitchen.domain.KitchenTicket;
import com.company.pos.kitchen.infrastructure.KitchenTicketRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Persists fired lines as one {@link KitchenTicket} per station (after-commit, async, own tx).
 * Idempotent on {@code (orderId, station, firedAt)} so at-least-once outbox redelivery is safe.
 * Runs alongside {@code KitchenTicketsFiredListener} (which prints) — this one only records.
 */
@Component
class KitchenTicketRecordingListener {

    private final KitchenService routing;
    private final KitchenTicketRepository repo;
    private final DomainEvents events;

    KitchenTicketRecordingListener(KitchenService routing, KitchenTicketRepository repo,
            DomainEvents events) {
        this.routing = routing;
        this.repo = repo;
        this.events = events;
    }

    @ApplicationModuleListener
    void on(KitchenTicketsFired event) {
        Map<String, List<KitchenTicketsFired.FiredLine>> byStation = new LinkedHashMap<>();
        for (KitchenTicketsFired.FiredLine line : event.lines()) {
            byStation.computeIfAbsent(routing.stationFor(line.sku()), s -> new ArrayList<>()).add(line);
        }
        for (Map.Entry<String, List<KitchenTicketsFired.FiredLine>> entry : byStation.entrySet()) {
            String station = entry.getKey();
            if (repo.findByOrderIdAndStationAndFiredAt(event.orderId(), station, event.firedAt())
                    .isPresent()) {
                continue; // idempotent: this fire already recorded for this station
            }
            KitchenTicket ticket = new KitchenTicket(Identifiers.newId(), event.orderId(),
                    event.tableLabel(), station, event.firedAt());
            for (KitchenTicketsFired.FiredLine line : entry.getValue()) {
                ticket.addLine(line.sku(), line.name(), line.qty(), line.note(),
                        line.course() == null ? null : line.course().name(),
                        line.modifiers().stream().map(KitchenTicketsFired.FiredModifier::name).toList());
            }
            repo.save(ticket);
            events.publish(new KitchenTicketChanged(ticket.getId(), ticket.getOrderId(), Instant.now()));
        }
    }
}
