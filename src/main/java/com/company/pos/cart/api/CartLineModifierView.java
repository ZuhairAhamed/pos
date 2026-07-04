package com.company.pos.cart.api;

import java.math.BigDecimal;
import java.util.UUID;

public record CartLineModifierView(UUID optionId, String name, BigDecimal priceDelta) {
}
