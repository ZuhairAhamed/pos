package com.company.pos.device.api;

import javax.money.MonetaryAmount;

public record PaymentRequest(MonetaryAmount amount, String reference) {
}
