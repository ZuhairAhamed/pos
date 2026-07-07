package com.company.pos.sales.api;

import java.math.BigDecimal;

public record QuoteView(String currencyCode, BigDecimal subtotal, BigDecimal discountTotal,
        BigDecimal serviceChargeAmount, BigDecimal taxTotal, BigDecimal grandTotal) {
}
