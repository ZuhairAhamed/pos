package com.company.pos.shift.api;

import com.company.pos.cashdrawer.api.DrawerReconciliation;
import java.time.Instant;
import java.util.UUID;

public record ShiftSummary(UUID shiftId, String terminalId, String openedBy, String closedBy,
        String status, Instant openedAt, Instant closedAt, DrawerReconciliation cash) {
}
