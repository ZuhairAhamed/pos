package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.CashMovementRequest;
import com.company.pos.terminal.api.dto.CashMovementView;
import com.company.pos.terminal.api.dto.DrawerReconciliation;
import com.fasterxml.jackson.core.type.TypeReference;
import java.math.BigDecimal;

/** Typed client for the store server's {@code /cash-drawer} endpoints (cashier-level). */
public class CashDrawerApi {

    private final ApiClient client;

    public CashDrawerApi(ApiClient client) {
        this.client = client;
    }

    /** GET /cash-drawer/reconciliation — the open drawer session's current reconciliation. */
    public DrawerReconciliation reconciliation() {
        return client.get("/cash-drawer/reconciliation", new TypeReference<DrawerReconciliation>() {});
    }

    /** POST /cash-drawer/pay-in — records cash added to the drawer. */
    public CashMovementView payIn(BigDecimal amount, String reason) {
        return client.post("/cash-drawer/pay-in", new CashMovementRequest(amount, reason),
                new TypeReference<CashMovementView>() {});
    }

    /** POST /cash-drawer/pay-out — records cash removed from the drawer. */
    public CashMovementView payOut(BigDecimal amount, String reason) {
        return client.post("/cash-drawer/pay-out", new CashMovementRequest(amount, reason),
                new TypeReference<CashMovementView>() {});
    }
}
