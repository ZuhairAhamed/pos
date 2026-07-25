package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * Terminal-side mirror of the store server's product read model. Deserialized by field name;
 * {@code active}/{@code available} are boxed so an older server that omits them yields null
 * (treated as active/available by callers). Money is {@link BigDecimal}, never double.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductView(String sku, String name, String categoryName, String barcode,
        BigDecimal unitPrice, Boolean active, Boolean available) {
}
