package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SaleLineView(String sku, String name, BigDecimal quantity, BigDecimal lineTotal,
        List<SaleLineModifierView> modifiers) {
}
