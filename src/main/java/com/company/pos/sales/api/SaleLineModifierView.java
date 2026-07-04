package com.company.pos.sales.api;

import java.math.BigDecimal;
import java.util.UUID;

public record SaleLineModifierView(UUID optionId, String name, BigDecimal priceDelta) {
}
