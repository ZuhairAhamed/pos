package com.company.pos.dining.api;

import com.company.pos.common.events.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by {@code dining} when order lines are fired to the kitchen. Self-contained snapshot
 * (like {@code SaleCompleted}) — carries product name, quantity, modifiers, note and course, but no
 * money (cooks don't need price). The {@code kitchen} module listens and routes each line to its
 * station.
 */
public record KitchenTicketsFired(UUID orderId, String tableLabel, Instant firedAt,
        List<FiredLine> lines) implements DomainEvent {

    public record FiredLine(String sku, String name, BigDecimal qty, String note,
            CourseTag course, List<FiredModifier> modifiers) {
    }

    public record FiredModifier(String name) {
    }
}
