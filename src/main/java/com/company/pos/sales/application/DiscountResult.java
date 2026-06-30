package com.company.pos.sales.application;

import com.company.pos.sales.api.DiscountType;
import java.math.BigDecimal;
import java.util.List;

/** The outcome of applying manual discounts: per-line results plus the transaction-level
 *  discount summary, the grand discount total, and any manager cap overrides. */
record DiscountResult(List<DiscountedLine> lines, BigDecimal txnDiscountAmount,
        DiscountType txnDiscountType, String txnDiscountReason, BigDecimal discountTotal,
        List<DiscountOverride> overrides) {
}
