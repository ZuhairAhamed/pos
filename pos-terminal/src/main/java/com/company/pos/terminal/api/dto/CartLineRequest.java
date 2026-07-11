package com.company.pos.terminal.api.dto;

import java.math.BigDecimal;
import java.util.List;

/** POST /carts/{id}/lines body. modifierOptionIds may be empty. */
public record CartLineRequest(String sku, BigDecimal quantity, List<java.util.UUID> modifierOptionIds) {
}
