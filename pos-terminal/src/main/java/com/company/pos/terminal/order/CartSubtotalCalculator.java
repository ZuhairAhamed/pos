package com.company.pos.terminal.order;

import com.company.pos.terminal.api.dto.CartLineView;
import com.company.pos.terminal.api.dto.CartView;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure client-side estimator for the retail cart subtotal. PRE-tax only: the server owns the
 * authoritative total at checkout. Unlike the dining estimate, {@link CartLineView#unitPrice()}
 * already includes modifier deltas, so the estimate is {@code Σ unitPrice × quantity}, scale 2.
 */
public final class CartSubtotalCalculator {

    private CartSubtotalCalculator() {
    }

    public static BigDecimal estimate(CartView cart) {
        BigDecimal total = BigDecimal.ZERO;
        if (cart != null && cart.lines() != null) {
            for (CartLineView line : cart.lines()) {
                if (line == null) {
                    continue;
                }
                BigDecimal unit = line.unitPrice() == null ? BigDecimal.ZERO : line.unitPrice();
                BigDecimal qty = line.quantity() == null ? BigDecimal.ZERO : line.quantity();
                total = total.add(unit.multiply(qty));
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }
}
