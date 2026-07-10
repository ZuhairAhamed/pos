package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full dining order. {@code serviceType} mirrors the server's {@code ServiceType} enum
 * ({@code DINE_IN}/{@code QUICK_SERVICE}); {@code status} mirrors {@code OrderStatus}
 * ({@code OPEN}/{@code CLOSED}/{@code VOIDED}). Lines carry no prices by design.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderView(UUID id, UUID tableId, String serviceType, String status, String openedBy,
        Instant openedAt, Instant closedAt, UUID saleId, List<OrderLineView> lines) {
}
