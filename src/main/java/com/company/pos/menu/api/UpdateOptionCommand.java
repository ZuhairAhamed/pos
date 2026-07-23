package com.company.pos.menu.api;

import java.math.BigDecimal;

public record UpdateOptionCommand(String name, BigDecimal priceDelta) {
}
