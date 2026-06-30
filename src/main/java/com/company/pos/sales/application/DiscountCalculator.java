package com.company.pos.sales.application;

import com.company.pos.common.exception.DomainException;
import com.company.pos.pricing.api.PricedLine;
import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.DiscountType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Pure manual-discount math. Resolves each discount to an amount, caps a fixed amount to its base,
 * enforces the cashier cap by role, and allocates the transaction discount proportionally across
 * lines (last line absorbs the rounding remainder — the same pattern as Phase 4 refund allocation).
 * Discounts reduce the line extended amount before tax, so VAT is computed on the discounted base.
 */
@Component
class DiscountCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    DiscountResult apply(List<PricedLine> priced, Map<String, DiscountInput> lineDiscounts,
            DiscountInput txnDiscount, boolean callerIsManager, BigDecimal cashierMaxPercent,
            BigDecimal cashierMaxAmount, Set<String> reasonCodes) {

        List<DiscountOverride> overrides = new ArrayList<>();
        Map<String, DiscountInput> lineMap = lineDiscounts == null ? Map.of() : lineDiscounts;
        Set<String> skus = new HashSet<>();
        for (PricedLine p : priced) {
            skus.add(p.sku());
        }
        for (String sku : lineMap.keySet()) {
            if (!skus.contains(sku)) {
                throw DomainException.validation("Line discount references unknown sku " + sku);
            }
        }

        // Stage 1: line-level discounts.
        List<BigDecimal> gross = new ArrayList<>();
        List<BigDecimal> lineDiscAmt = new ArrayList<>();
        List<DiscountType> lineDiscType = new ArrayList<>();
        List<String> lineDiscReason = new ArrayList<>();
        List<BigDecimal> postLine = new ArrayList<>();
        for (PricedLine p : priced) {
            BigDecimal g = p.extendedPrice().setScale(2, RoundingMode.HALF_UP);
            DiscountInput in = lineMap.get(p.sku());
            BigDecimal d = zero();
            DiscountType type = null;
            String reason = null;
            if (in != null) {
                d = resolve(in, g, reasonCodes);
                enforceCap(d, g, callerIsManager, cashierMaxPercent, cashierMaxAmount);
                if (callerIsManager && exceedsCashierCap(d, g, cashierMaxPercent, cashierMaxAmount)) {
                    overrides.add(new DiscountOverride(p.sku(), d, in.type(), in.reasonCode()));
                }
                type = in.type();
                reason = in.reasonCode();
            }
            gross.add(g);
            lineDiscAmt.add(d);
            lineDiscType.add(type);
            lineDiscReason.add(reason);
            postLine.add(g.subtract(d));
        }

        // Stage 2: transaction discount, allocated across the post-line extended amounts.
        BigDecimal cartBase = postLine.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal txnAmt = zero();
        DiscountType txnType = null;
        String txnReason = null;
        List<BigDecimal> shares = new ArrayList<>();
        for (int i = 0; i < priced.size(); i++) {
            shares.add(zero());
        }
        if (txnDiscount != null) {
            if (cartBase.signum() <= 0) {
                throw DomainException.validation("No discountable amount for a transaction discount");
            }
            txnAmt = resolve(txnDiscount, cartBase, reasonCodes);
            enforceCap(txnAmt, cartBase, callerIsManager, cashierMaxPercent, cashierMaxAmount);
            if (callerIsManager && exceedsCashierCap(txnAmt, cartBase, cashierMaxPercent, cashierMaxAmount)) {
                overrides.add(new DiscountOverride(null, txnAmt, txnDiscount.type(), txnDiscount.reasonCode()));
            }
            txnType = txnDiscount.type();
            txnReason = txnDiscount.reasonCode();
            BigDecimal allocated = zero();
            for (int i = 0; i < priced.size(); i++) {
                BigDecimal share = (i == priced.size() - 1)
                        ? txnAmt.subtract(allocated)
                        : txnAmt.multiply(postLine.get(i)).divide(cartBase, 2, RoundingMode.HALF_UP);
                allocated = allocated.add(share);
                shares.set(i, share);
            }
        }

        // Stage 3: assemble per-line results.
        List<DiscountedLine> lines = new ArrayList<>();
        BigDecimal discountTotal = zero();
        for (int i = 0; i < priced.size(); i++) {
            PricedLine p = priced.get(i);
            BigDecimal discountedExtended = postLine.get(i).subtract(shares.get(i));
            if (discountedExtended.signum() < 0) {
                throw DomainException.validation("Discount exceeds line value for sku " + p.sku());
            }
            discountTotal = discountTotal.add(lineDiscAmt.get(i));
            lines.add(new DiscountedLine(p.sku(), p.name(), p.quantity(), p.unitPrice(),
                    p.currencyCode(), gross.get(i), lineDiscAmt.get(i), lineDiscType.get(i),
                    lineDiscReason.get(i), discountedExtended));
        }
        discountTotal = discountTotal.add(txnAmt);

        return new DiscountResult(lines, txnAmt, txnType, txnReason, discountTotal, overrides);
    }

    private BigDecimal resolve(DiscountInput in, BigDecimal base, Set<String> reasonCodes) {
        if (in.reasonCode() == null || !reasonCodes.contains(in.reasonCode())) {
            throw DomainException.validation("Unknown discount reason code " + in.reasonCode());
        }
        if (in.value() == null || in.value().signum() <= 0) {
            throw DomainException.validation("Discount value must be positive");
        }
        if (in.type() == DiscountType.PERCENT) {
            if (in.value().compareTo(HUNDRED) > 0) {
                throw DomainException.validation("Percentage discount cannot exceed 100");
            }
            return base.multiply(in.value()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        }
        // AMOUNT: cap to the base so a fixed discount can never exceed its target.
        return in.value().setScale(2, RoundingMode.HALF_UP).min(base);
    }

    private boolean exceedsCashierCap(BigDecimal d, BigDecimal base, BigDecimal maxPercent,
            BigDecimal maxAmount) {
        if (d.compareTo(maxAmount) > 0) {
            return true;
        }
        if (base.signum() > 0) {
            BigDecimal effectivePercent = d.multiply(HUNDRED).divide(base, 2, RoundingMode.HALF_UP);
            return effectivePercent.compareTo(maxPercent) > 0;
        }
        return false;
    }

    private void enforceCap(BigDecimal d, BigDecimal base, boolean callerIsManager,
            BigDecimal maxPercent, BigDecimal maxAmount) {
        if (callerIsManager) {
            return;
        }
        if (exceedsCashierCap(d, base, maxPercent, maxAmount)) {
            throw DomainException.validation("Discount exceeds cashier limit; manager approval required");
        }
    }
}
