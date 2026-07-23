package com.company.pos.menu.api;

import java.util.List;
import java.util.UUID;

public record VariantGroupAdminView(UUID id, String name, boolean active,
        List<VariantMemberAdminView> members) {
}
