package com.company.pos.dining.api;

import java.util.UUID;

public record TableView(UUID id, String label, int seats, boolean active) {
}
