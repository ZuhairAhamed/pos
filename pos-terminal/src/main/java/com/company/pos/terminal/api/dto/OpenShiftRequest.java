package com.company.pos.terminal.api.dto;

import java.math.BigDecimal;

/** Body for {@code POST /shifts}: the opening cash float counted into the drawer. */
public record OpenShiftRequest(BigDecimal openingFloat) {
}
