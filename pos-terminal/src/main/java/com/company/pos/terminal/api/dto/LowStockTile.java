package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LowStockTile(String sku, String name, BigDecimal onHand, BigDecimal reorderLevel) {
}
