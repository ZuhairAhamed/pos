package com.company.pos.cashdrawer.api;

import java.math.BigDecimal;
import java.util.UUID;

public record DrawerReconciliation(UUID sessionId, BigDecimal openingFloat, BigDecimal cashSales,
        int cashSalesCount, BigDecimal payIns, BigDecimal payOuts, BigDecimal expectedCash,
        BigDecimal countedCash, BigDecimal variance, String currencyCode) {
}
