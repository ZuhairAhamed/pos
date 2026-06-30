package com.company.pos.audit.application;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.product.api.ProductPriceChanged;
import java.util.Map;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class ProductPriceChangedAuditListener {

    private final DefaultAuditService audit;

    ProductPriceChangedAuditListener(DefaultAuditService audit) {
        this.audit = audit;
    }

    @ApplicationModuleListener
    void on(ProductPriceChanged event) {
        audit.append(AuditAction.PRICE_CHANGED, "system", event.sku(),
                Map.of("oldPrice", event.oldPrice().toPlainString(),
                        "newPrice", event.newPrice().toPlainString(),
                        "erpVersion", Long.toString(event.erpVersion())));
    }
}
