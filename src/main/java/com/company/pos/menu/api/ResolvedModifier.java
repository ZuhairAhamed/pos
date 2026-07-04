package com.company.pos.menu.api;

import java.math.BigDecimal;
import java.util.UUID;

public record ResolvedModifier(UUID optionId, String name, BigDecimal priceDelta) {
}
