package com.company.pos.dining.api;

import com.company.pos.sales.api.QuoteView;
import java.math.BigDecimal;
import java.util.List;

/**
 * Authoritative pricing for a proposed split, priced exactly as {@code closeOrderSplit} would
 * charge it. BY_ITEM populates {@code bills} (one quote per bill, in request order; {@code order}
 * and {@code shares} are null). EVEN populates {@code order} (the whole-order quote) and
 * {@code shares} (per-share amounts, the LAST absorbing the rounding remainder; {@code bills}
 * is null).
 */
public record SplitQuoteView(List<QuoteView> bills, QuoteView order, List<BigDecimal> shares) {
}
