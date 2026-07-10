package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

/**
 * Terminal-side mirror of a modifier group and its priced options. A group is
 * forced when {@code minSelections >= 1}. Used by the modifier picker and the
 * subtotal estimate in later tasks.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModifierGroupView(UUID id, String name, int minSelections, int maxSelections,
                                List<ModifierOptionView> options) {
}
