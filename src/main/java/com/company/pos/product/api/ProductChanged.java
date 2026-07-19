package com.company.pos.product.api;

import com.company.pos.common.events.DomainEvent;
import java.math.BigDecimal;

/**
 * Published by {@code ProductAdminService} for every catalogue admin action. Consumed by the
 * {@code audit} module's {@code ProductChangedAuditListener}. {@code entityRef} is the product SKU
 * (or the category code for {@code CATEGORY_CREATED}); {@code actor} is the admin who made the
 * change (captured on the request thread so the async listener records the real user, not
 * "system"); {@code oldPrice}/{@code newPrice} are non-null only for {@code PRICE_CHANGED}.
 */
public record ProductChanged(String entityRef, ProductChangeType type, String actor,
        BigDecimal oldPrice, BigDecimal newPrice) implements DomainEvent {
}
