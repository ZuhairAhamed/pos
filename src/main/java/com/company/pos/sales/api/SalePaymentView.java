package com.company.pos.sales.api;

import java.math.BigDecimal;

public record SalePaymentView(String method, BigDecimal amount, BigDecimal amountTendered,
        BigDecimal changeDue) {
}
