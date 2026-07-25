package com.company.pos.terminal.api.dto;

/** Body for PUT /products/{sku}/availability. */
public record AvailabilityRequest(boolean available) {
}
