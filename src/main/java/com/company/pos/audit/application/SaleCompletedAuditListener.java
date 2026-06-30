package com.company.pos.audit.application;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.sales.api.SaleCompleted;
import java.util.Map;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class SaleCompletedAuditListener {

    private final DefaultAuditService audit;

    SaleCompletedAuditListener(DefaultAuditService audit) {
        this.audit = audit;
    }

    @ApplicationModuleListener
    void on(SaleCompleted event) {
        audit.append(AuditAction.SALE_COMPLETED, event.terminalId(), event.receiptNumber(),
                Map.of("grandTotal", event.grandTotal().toPlainString(),
                        "currency", event.currencyCode(),
                        "terminalId", event.terminalId()));
    }
}
