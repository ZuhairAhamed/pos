package com.company.pos.dining.api;

import com.company.pos.common.events.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@code dining} after any mutating write, so terminals can invalidate their floor
 * map. Deliberately carries NO floor state — only the change kind and the affected table/order ids
 * (either may be null where not applicable). Delivered through the outbox (after-commit, async);
 * the {@code realtime} module fans it out to connected terminals, which re-fetch the authoritative
 * state via REST. Over-refresh is harmless; under-refresh leaves stale tiles — so we publish on
 * every write rather than trying to predict which writes change a tile.
 */
public record DiningFloorChanged(FloorChangeType change, UUID tableId, UUID orderId, Instant at)
        implements DomainEvent {
}
