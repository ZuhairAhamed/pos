package com.company.pos.terminal.api.dto;

import java.math.BigDecimal;

/** PUT /carts/{id}/lines/{lineId} body. */
public record QuantityRequest(BigDecimal quantity) {
}
