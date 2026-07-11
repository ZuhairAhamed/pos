package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SalePaymentView(String method, BigDecimal amount, BigDecimal tendered,
        BigDecimal changeGiven) {
}
