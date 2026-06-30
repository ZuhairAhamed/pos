package com.company.pos.audit.api;

import java.time.Instant;
import java.util.UUID;

public record AuditRecordView(UUID id, long seq, String storeId, Instant occurredAt, String actor,
        String action, String entityRef, String payload, String prevHash, String hash) {
}
