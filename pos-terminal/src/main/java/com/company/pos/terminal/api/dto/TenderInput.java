package com.company.pos.terminal.api.dto;

import java.math.BigDecimal;

/**
 * One tender in a close/checkout. {@code method} mirrors the server's {@code PaymentMethod} enum
 * ({@code CASH}/{@code CARD}/{@code WALLET}). For {@code CASH}, {@code tendered} is the cash
 * received; for {@code CARD}/{@code WALLET}, {@code amount} is required and {@code tendered} is
 * ignored.
 */
public record TenderInput(String method, BigDecimal amount, BigDecimal tendered) {
}
