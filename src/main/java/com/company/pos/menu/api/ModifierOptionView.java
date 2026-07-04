package com.company.pos.menu.api;

import java.math.BigDecimal;
import java.util.UUID;

public record ModifierOptionView(UUID id, String name, BigDecimal priceDelta) {
}
