package com.company.pos.integration.api;

import java.math.BigDecimal;

public record ErpStockLevel(String sku, String locationCode, BigDecimal quantityOnHand, long version) {
}
