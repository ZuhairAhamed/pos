package com.company.pos.menu.api;

import java.math.BigDecimal;

public record AddOptionCommand(String name, BigDecimal priceDelta) {
}
