package com.company.pos.terminal.order;

import com.company.pos.terminal.api.dto.OrderLineModifierView;
import com.company.pos.terminal.api.dto.OrderLineView;
import com.company.pos.terminal.api.dto.OrderView;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure client-side estimator for the order subtotal. It is a PRE-tax, PRE-service-charge
 * estimate only: the authoritative total is computed by the server at checkout. The estimate is
 * {@code Σ over lines of (basePrice(sku) + Σ modifier.priceDelta) × qty}, scaled to 2dp HALF_UP.
 * Money is {@link BigDecimal}, never double.
 */
public final class SubtotalCalculator {

    private SubtotalCalculator() {
    }

    public static BigDecimal estimate(OrderView order, MenuCache cache) {
        BigDecimal total = BigDecimal.ZERO;
        if (order != null && order.lines() != null) {
            for (OrderLineView line : order.lines()) {
                if (line == null) {
                    continue;
                }
                BigDecimal unit = cache.basePrice(line.sku());
                if (line.modifiers() != null) {
                    for (OrderLineModifierView m : line.modifiers()) {
                        if (m != null && m.priceDelta() != null) {
                            unit = unit.add(m.priceDelta());
                        }
                    }
                }
                BigDecimal qty = line.qty() == null ? BigDecimal.ZERO : line.qty();
                total = total.add(unit.multiply(qty));
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }
}
