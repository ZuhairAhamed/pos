package com.company.pos.menu.api;

import java.util.List;
import java.util.UUID;

public record ModifierGroupAdminView(UUID id, String name, int minSelections, int maxSelections,
        boolean active, List<ModifierOptionAdminView> options, List<String> assignedSkus) {
}
