package com.company.pos.terminal.api.dto;

import java.time.Instant;
import java.util.UUID;

/** A terminal shift as returned by {@code POST /shifts} and {@code GET /shifts/open}. */
public record ShiftView(UUID shiftId, String terminalId, String openedBy, String status,
        String currencyCode, Instant openedAt, Instant closedAt) {
}
