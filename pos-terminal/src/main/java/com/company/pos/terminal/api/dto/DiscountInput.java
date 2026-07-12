package com.company.pos.terminal.api.dto;

import java.math.BigDecimal;

/**
 * Mirrors the server's sales DiscountInput field-for-field. {@code type} is the server enum
 * name: "PERCENT" (value = percentage, e.g. 10) or "AMOUNT" (value = absolute deduction).
 * {@code reasonCode} is required and must be one of the policy's reason codes.
 */
public record DiscountInput(String type, BigDecimal value, String reasonCode) {
}
