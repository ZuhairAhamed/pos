package com.company.pos.audit.application;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.sales.api.ReturnCompleted;
import java.util.Map;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class ReturnCompletedAuditListener {

    private final DefaultAuditService audit;

    ReturnCompletedAuditListener(DefaultAuditService audit) {
        this.audit = audit;
    }

    @ApplicationModuleListener
    void on(ReturnCompleted event) {
        audit.append(AuditAction.RETURN_COMPLETED, event.terminalId(), event.creditNoteNumber(),
                Map.of("originalSaleId", event.originalSaleId().toString(),
                        "refundGrandTotal", event.refundGrandTotal().toPlainString(),
                        "currency", event.currencyCode()));
    }
}
