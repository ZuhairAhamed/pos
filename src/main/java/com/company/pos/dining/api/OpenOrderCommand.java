package com.company.pos.dining.api;

import java.util.UUID;

/** {@code serviceType} may be null — defaults to {@link ServiceType#DINE_IN}. */
public record OpenOrderCommand(UUID tableId, ServiceType serviceType) {
}
