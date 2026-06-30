package com.company.pos.audit.application;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.sales.api.DiscountOverridden;
import java.util.Map;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class DiscountOverriddenAuditListener {

    private final DefaultAuditService audit;

    DiscountOverriddenAuditListener(DefaultAuditService audit) {
        this.audit = audit;
    }

    @ApplicationModuleListener
    void on(DiscountOverridden event) {
        audit.append(AuditAction.DISCOUNT_OVERRIDE, event.actor(), event.saleId().toString(),
                Map.of("sku", event.sku() == null ? "TRANSACTION" : event.sku(),
                        "discountAmount", event.discountAmount().toPlainString(),
                        "discountType", event.discountType() == null ? "" : event.discountType(),
                        "reasonCode", event.reasonCode() == null ? "" : event.reasonCode()));
    }
}
