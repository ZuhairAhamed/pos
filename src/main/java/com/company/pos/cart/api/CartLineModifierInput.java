package com.company.pos.cart.api;

import java.math.BigDecimal;
import java.util.UUID;

/** A pre-resolved modifier supplied by a caller (e.g. dining close), folded into the line
 *  without re-resolving against the menu. */
public record CartLineModifierInput(UUID optionId, String name, BigDecimal priceDelta) {
}
