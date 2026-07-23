package com.company.pos.menu.api;

import com.company.pos.common.events.DomainEvent;
import java.math.BigDecimal;

/**
 * Published by {@code DefaultMenuService} on every modifier-admin write. Consumed by the
 * {@code audit} module (async, after commit). {@code entityRef} is the group id (or the option id
 * for OPTION_*); {@code detail} carries the sku for GROUP_ASSIGNED/UNASSIGNED (else null);
 * {@code oldPrice}/{@code newPrice} are non-null only when an option's price delta changed.
 * {@code actor} is captured on the request thread so the async listener records the real user.
 */
public record MenuChanged(String entityRef, MenuChangeType type, String actor, String detail,
        BigDecimal oldPrice, BigDecimal newPrice) implements DomainEvent {
}
