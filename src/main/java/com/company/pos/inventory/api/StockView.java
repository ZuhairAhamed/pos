package com.company.pos.inventory.api;

import java.math.BigDecimal;

public record StockView(String sku, BigDecimal quantityOnHand) {
}
