package com.company.pos.terminal.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record KitchenTicketView(UUID id, UUID orderId, String tableLabel, String station,
        String state, Instant firedAt, Instant preparingAt, Instant readyAt, Instant bumpedAt,
        List<KitchenTicketLineView> lines) {
}
