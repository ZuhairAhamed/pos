package com.company.pos.sales.api;

import java.math.BigDecimal;

public record ReturnPaymentView(String method, BigDecimal amount, String maskedPan) {
}
