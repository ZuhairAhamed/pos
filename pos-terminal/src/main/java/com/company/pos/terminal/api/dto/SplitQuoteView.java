package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

/**
 * Mirrors the server's dining SplitQuoteView. BY_ITEM: {@code bills} holds one authoritative
 * quote per bill in request order ({@code order}/{@code shares} null). EVEN: {@code order} is
 * the whole-order quote and {@code shares} the per-guest amounts, the LAST absorbing the
 * rounding remainder ({@code bills} null).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SplitQuoteView(List<QuoteView> bills, QuoteView order, List<BigDecimal> shares) {
}
