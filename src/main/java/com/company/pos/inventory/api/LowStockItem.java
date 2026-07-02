package com.company.pos.inventory.api;

import java.math.BigDecimal;

public record LowStockItem(String sku, BigDecimal onHand, BigDecimal reorderLevel) {
}
