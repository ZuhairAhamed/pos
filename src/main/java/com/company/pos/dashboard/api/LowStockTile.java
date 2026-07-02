package com.company.pos.dashboard.api;

import java.math.BigDecimal;

public record LowStockTile(String sku, String name, BigDecimal onHand, BigDecimal reorderLevel) {
}
