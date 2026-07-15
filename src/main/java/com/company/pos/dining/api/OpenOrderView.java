package com.company.pos.dining.api;

import java.time.Instant;
import java.util.UUID;

public record OpenOrderView(UUID orderId, UUID tableId, String tableLabel, Instant openedAt,
        int lineCount, ServiceType serviceType) {
}
