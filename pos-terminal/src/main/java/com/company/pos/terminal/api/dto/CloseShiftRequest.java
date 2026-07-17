package com.company.pos.terminal.api.dto;

import java.math.BigDecimal;

/** Body for {@code POST /shifts/{id}/close}. */
public record CloseShiftRequest(BigDecimal countedCash) {
}
