package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A single cash-drawer movement returned by the pay-in / pay-out endpoints. Mirrors the server
 *  {@code cashdrawer.api.CashMovementView}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CashMovementView(UUID id, UUID sessionId, String type, BigDecimal amount,
        String reference, Instant createdAt) {
}
