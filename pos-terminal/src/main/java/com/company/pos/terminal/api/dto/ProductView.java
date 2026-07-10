package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * Terminal-side mirror of the store server's product read model. Only the fields
 * the terminal uses are declared; extra wire fields (barcode, unitOfMeasure,
 * currencyCode, active) are ignored. Money is {@link BigDecimal}, never double.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductView(String sku, String name, String categoryName, BigDecimal unitPrice) {
}
