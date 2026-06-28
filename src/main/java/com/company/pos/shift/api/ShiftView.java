package com.company.pos.shift.api;

import java.time.Instant;
import java.util.UUID;

public record ShiftView(UUID shiftId, String terminalId, String openedBy, String status,
        String currencyCode, Instant openedAt, Instant closedAt) {
}
