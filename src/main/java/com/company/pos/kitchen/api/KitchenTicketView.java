package com.company.pos.kitchen.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record KitchenTicketView(UUID id, UUID orderId, String tableLabel, String station,
        KitchenTicketState state, Instant firedAt, Instant preparingAt, Instant readyAt,
        Instant bumpedAt, List<KitchenTicketLineView> lines) {
}
