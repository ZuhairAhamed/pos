package com.company.pos.terminal.api.dto;

import java.util.UUID;

/** Body for {@code POST /dining/orders}. Mirrors the server's {@code OpenOrderCommand}. */
public record OpenOrderRequest(UUID tableId, String serviceType) {
}
