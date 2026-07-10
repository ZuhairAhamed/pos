package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Terminal-side mirror of a priced modifier option. {@code priceDelta} is the
 * amount this option adds to (or subtracts from) the line price; it is
 * {@link BigDecimal}, never double.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModifierOptionView(UUID id, String name, BigDecimal priceDelta) {
}
