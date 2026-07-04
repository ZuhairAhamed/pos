package com.company.pos.dining.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderView(UUID id, UUID tableId, ServiceType serviceType, OrderStatus status,
        String openedBy, Instant openedAt, Instant closedAt, UUID saleId,
        List<OrderLineView> lines) {
}
