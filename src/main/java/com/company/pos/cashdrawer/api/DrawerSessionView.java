package com.company.pos.cashdrawer.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DrawerSessionView(UUID sessionId, String terminalId, String status,
        BigDecimal openingFloat, String currencyCode, String openedBy, Instant openedAt) {
}
