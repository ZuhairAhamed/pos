package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/** Authoritative priced totals for a cart/order (server-computed). Money is BigDecimal. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QuoteView(String currencyCode, BigDecimal subtotal, BigDecimal discountTotal,
        BigDecimal serviceChargeAmount, BigDecimal taxTotal, BigDecimal grandTotal) {
}
