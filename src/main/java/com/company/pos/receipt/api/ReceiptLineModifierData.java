package com.company.pos.receipt.api;

import java.math.BigDecimal;

public record ReceiptLineModifierData(String name, BigDecimal priceDelta) {
}
