package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.dto.DiscountInput;
import com.company.pos.terminal.api.dto.DiscountPolicyView;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Client-side mirror of the server's cashier discount cap, used only to decide WHEN to ask for
 * manager approval — never authoritative (the server re-enforces the cap at checkout).
 * Mirrors DiscountCalculator: the cap is exceeded when the resolved amount is over the absolute
 * cap OR its effective percentage of the pre-discount base is over the percent cap.
 */
public final class DiscountRules {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private DiscountRules() {}

    /** The monetary amount {@code d} resolves to against {@code base} (the pre-discount subtotal).
     *  AMOUNT discounts cap at the base, matching the server. */
    public static BigDecimal amountOf(DiscountInput d, BigDecimal base) {
        if (d == null || d.value() == null || base == null) {
            return BigDecimal.ZERO;
        }
        if ("PERCENT".equals(d.type())) {
            return base.multiply(d.value()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        }
        return d.value().setScale(2, RoundingMode.HALF_UP).min(base);
    }

    /** True when the discount exceeds the cashier cap and the signed-in user is not a manager.
     *  A null policy (fetch failed) yields false — the server's checkout rejection then drives
     *  the controller's defensive approval path. */
    public static boolean needsApproval(DiscountInput d, BigDecimal base, DiscountPolicyView policy,
            boolean isManager) {
        if (d == null || policy == null || isManager) {
            return false;
        }
        BigDecimal amount = amountOf(d, base);
        if (amount.compareTo(policy.cashierMaxAmount()) > 0) {
            return true;
        }
        if (base != null && base.signum() > 0) {
            BigDecimal effectivePercent = amount.multiply(HUNDRED)
                    .divide(base, 2, RoundingMode.HALF_UP);
            return effectivePercent.compareTo(policy.cashierMaxPercent()) > 0;
        }
        return false;
    }
}
