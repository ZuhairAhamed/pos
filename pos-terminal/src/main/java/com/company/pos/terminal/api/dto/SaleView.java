package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Sale result of a close/checkout. Mirrors the server's {@code SaleView} totals fields
 * ({@code subtotal}, {@code taxTotal}, {@code serviceChargeAmount}, {@code grandTotal}); the
 * server's line/payment/discount detail fields are ignored here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SaleView(UUID id, String receiptNumber, BigDecimal subtotal, BigDecimal taxTotal,
        BigDecimal serviceChargeAmount, BigDecimal grandTotal, String currencyCode) {
}
