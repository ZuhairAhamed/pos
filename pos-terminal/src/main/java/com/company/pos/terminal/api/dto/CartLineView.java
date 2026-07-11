package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CartLineView(UUID lineId, String sku, String name, BigDecimal quantity,
        BigDecimal basePrice, BigDecimal unitPrice, String currencyCode,
        List<CartLineModifierView> modifiers) {
}
