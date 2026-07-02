package com.company.pos.customer.api;

import java.time.Instant;
import java.util.UUID;

public record CustomerView(UUID id, String name, String phone, String email,
        String address, String notes, boolean active, Instant createdAt) {
}
