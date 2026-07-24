package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SaleLineView(int lineNo, String sku, String name, BigDecimal quantity,
        BigDecimal unitPrice, BigDecimal lineTotal, List<SaleLineModifierView> modifiers) {
}
