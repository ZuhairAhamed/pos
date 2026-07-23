package com.company.pos.terminal.api;

import java.math.BigDecimal;

public record ModifierOptionRequest(String name, BigDecimal priceDelta) {
}
