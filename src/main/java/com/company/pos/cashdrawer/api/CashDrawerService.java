package com.company.pos.cashdrawer.api;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface CashDrawerService {

    DrawerSessionView openSession(String terminalId, BigDecimal openingFloat, String currencyCode,
            String openedBy);

    void recordCashSale(String terminalId, BigDecimal amount, String reference);

    CashMovementView payIn(String terminalId, BigDecimal amount, String reason, String performedBy);

    CashMovementView payOut(String terminalId, BigDecimal amount, String reason, String performedBy);

    DrawerReconciliation reconcile(UUID sessionId);

    DrawerReconciliation closeSession(UUID sessionId, BigDecimal countedAmount);

    Optional<DrawerSessionView> findOpenSession(String terminalId);
}
