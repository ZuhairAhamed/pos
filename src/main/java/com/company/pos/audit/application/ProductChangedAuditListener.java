package com.company.pos.audit.application;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.product.api.ProductChangeType;
import com.company.pos.product.api.ProductChanged;
import java.util.HashMap;
import java.util.Map;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Records catalogue admin actions into the audit trail. Runs async AFTER the publishing
 * transaction commits (the outbox redelivers on failure), mirroring
 * {@link ProductPriceChangedAuditListener}. The actor rides on the event (captured on the request
 * thread), so the real admin is recorded rather than "system".
 */
@Component
class ProductChangedAuditListener {

    private final DefaultAuditService audit;

    ProductChangedAuditListener(DefaultAuditService audit) {
        this.audit = audit;
    }

    @ApplicationModuleListener
    void on(ProductChanged event) {
        audit.append(actionFor(event.type()), event.actor(), event.entityRef(), detailsFor(event));
    }

    private static AuditAction actionFor(ProductChangeType type) {
        return switch (type) {
            case CREATED -> AuditAction.PRODUCT_CREATED;
            case UPDATED -> AuditAction.PRODUCT_UPDATED;
            case PRICE_CHANGED -> AuditAction.PRICE_CHANGED;
            case DEACTIVATED -> AuditAction.PRODUCT_DEACTIVATED;
            case REACTIVATED -> AuditAction.PRODUCT_REACTIVATED;
            case CATEGORY_CREATED -> AuditAction.CATEGORY_CREATED;
            case MARKED_UNAVAILABLE -> AuditAction.PRODUCT_MARKED_UNAVAILABLE;
            case MARKED_AVAILABLE -> AuditAction.PRODUCT_MARKED_AVAILABLE;
        };
    }

    private static Map<String, String> detailsFor(ProductChanged event) {
        if (event.type() != ProductChangeType.PRICE_CHANGED) {
            return Map.of();
        }
        Map<String, String> d = new HashMap<>();
        d.put("oldPrice", event.oldPrice() == null ? "" : event.oldPrice().toPlainString());
        d.put("newPrice", event.newPrice() == null ? "" : event.newPrice().toPlainString());
        return d;
    }
}
