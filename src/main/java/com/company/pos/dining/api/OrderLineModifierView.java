package com.company.pos.dining.api;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderLineModifierView(UUID optionId, String name, BigDecimal priceDelta) {
}
