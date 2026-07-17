package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

/** Result of {@code POST /shifts/{id}/close}. Mirrors the server {@code shift.api.ShiftSummary}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShiftSummary(UUID shiftId, String terminalId, String openedBy, String closedBy,
        String status, Instant openedAt, Instant closedAt, DrawerReconciliation cash) {
}
