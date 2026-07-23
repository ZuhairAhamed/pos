package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VariantGroupAdminView(UUID id, String name, boolean active,
        List<VariantMemberAdminView> members) {
}
