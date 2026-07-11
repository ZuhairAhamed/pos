package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CartView(UUID cartId, String status, String currencyCode, UUID customerId,
        List<CartLineView> lines) {
}
