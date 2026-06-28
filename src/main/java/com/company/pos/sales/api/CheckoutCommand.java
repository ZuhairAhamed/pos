package com.company.pos.sales.api;

import java.math.BigDecimal;
import java.util.UUID;

public record CheckoutCommand(UUID cartId, BigDecimal amountTendered) {
}
