package com.company.pos.notification.api;

import java.time.Instant;

public record Alert(AlertType type, String message, Instant occurredAt) {
}
