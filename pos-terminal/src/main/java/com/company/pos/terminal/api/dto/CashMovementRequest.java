package com.company.pos.terminal.api.dto;

import java.math.BigDecimal;

/** POST body for {@code /cash-drawer/pay-in} and {@code /cash-drawer/pay-out}. Mirrors the server
 *  {@code CashMovementRequest{amount, reason}}. */
public record CashMovementRequest(BigDecimal amount, String reason) {
}
