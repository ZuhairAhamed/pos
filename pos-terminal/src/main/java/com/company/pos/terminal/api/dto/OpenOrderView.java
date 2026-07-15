package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

/**
 * Summary of an open dining order, as returned by {@code GET /dining/orders}.
 * {@code serviceType} mirrors the server's {@code ServiceType} ({@code DINE_IN} /
 * {@code QUICK_SERVICE}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenOrderView(UUID orderId, UUID tableId, String tableLabel, Instant openedAt,
        int lineCount, String serviceType) {
}
