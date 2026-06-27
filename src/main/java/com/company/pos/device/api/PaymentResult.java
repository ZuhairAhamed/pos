package com.company.pos.device.api;

public record PaymentResult(boolean approved, String maskedPan, String token) {
}
