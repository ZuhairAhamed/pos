package com.company.pos.dashboard.api;

import java.math.BigDecimal;

public record RevenueSummary(BigDecimal today, int windowDays, BigDecimal window) {
}
