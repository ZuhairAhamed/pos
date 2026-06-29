package com.company.pos.integration.api;

import java.math.BigDecimal;

public record StockMovementUpload(String sku, String locationCode, BigDecimal quantityDelta,
        String reason) {
}
