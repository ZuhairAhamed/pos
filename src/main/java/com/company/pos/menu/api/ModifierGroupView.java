package com.company.pos.menu.api;

import java.util.List;
import java.util.UUID;

public record ModifierGroupView(UUID id, String name, int minSelections, int maxSelections,
        List<ModifierOptionView> options) {
}
