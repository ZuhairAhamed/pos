package com.company.pos.terminal.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/** Admin-side product read model — includes active/UoM/currency that {@code dto.ProductView} drops. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductAdminView(String sku, String name, String categoryName, String barcode,
        String unitOfMeasure, BigDecimal unitPrice, String currencyCode, boolean active) {
}
