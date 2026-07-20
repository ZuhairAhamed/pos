package com.company.pos.dining.api;

import com.company.pos.common.events.DomainEvent;

/**
 * Published by {@code DefaultDiningService} on every table-registry write. Consumed by the
 * {@code audit} module's {@code TableChangedAuditListener} (async, after commit — via the outbox).
 * {@code entityRef} is the table id; {@code actor} is the user who made the change (captured on the
 * request thread so the async listener records the real user, not "system").
 */
public record TableChanged(String entityRef, TableChangeType type, String actor,
        String label, int seats, boolean active) implements DomainEvent {
}
