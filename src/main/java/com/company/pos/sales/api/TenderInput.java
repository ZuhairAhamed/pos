package com.company.pos.sales.api;

import com.company.pos.payment.api.PaymentMethod;
import java.math.BigDecimal;

/**
 * One tender in a checkout. For {@link PaymentMethod#CASH}, {@code amount} may be null (it
 * defaults to the outstanding balance) and {@code tendered} is the cash received. For
 * {@link PaymentMethod#CARD}/{@link PaymentMethod#WALLET}, {@code amount} is required and
 * {@code tendered} is ignored.
 */
public record TenderInput(PaymentMethod method, BigDecimal amount, BigDecimal tendered) {
}
