package com.company.pos.menu.api;

import java.math.BigDecimal;
import java.util.List;

/** The validated modifier selection for one line: the resolved options and their summed delta. */
public record ModifierResolution(List<ResolvedModifier> modifiers, BigDecimal totalDelta) {
}
