package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ModifierGroupAdminView(UUID id, String name, int minSelections, int maxSelections,
        boolean active, List<ModifierOptionAdminView> options, List<String> assignedSkus) {
}
