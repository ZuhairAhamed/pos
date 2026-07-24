package com.company.pos.kitchen.api;

import com.company.pos.common.events.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Thin invalidation event: a kitchen ticket's state changed (fired, advanced, recalled, cancelled).
 * Carries NO domain state and NO change-type enum — consumed only by {@code realtime} to push a
 * WebSocket invalidation ping. Deliberately not audited.
 */
public record KitchenTicketChanged(UUID ticketId, UUID orderId, Instant at) implements DomainEvent {
}
