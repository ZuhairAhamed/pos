package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

/** Cash-drawer reconciliation returned inside {@link ShiftSummary} at close. Mirrors the
 *  server {@code cashdrawer.api.DrawerReconciliation}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DrawerReconciliation(UUID sessionId, BigDecimal openingFloat, BigDecimal cashSales,
        int cashSalesCount, BigDecimal payIns, BigDecimal payOuts, BigDecimal expectedCash,
        BigDecimal countedCash, BigDecimal variance, String currencyCode) {
}
