package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

/** Server discount policy (GET /sales/discount-policy): cashier caps + valid reason codes.
 *  Advisory only — the server re-enforces the cap at checkout. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DiscountPolicyView(BigDecimal cashierMaxPercent, BigDecimal cashierMaxAmount,
        List<String> reasonCodes) {
}
