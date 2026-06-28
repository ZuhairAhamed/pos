package com.company.pos.cashdrawer.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CashMovementView(UUID id, UUID sessionId, String type, BigDecimal amount,
        String reference, Instant createdAt) {
}
