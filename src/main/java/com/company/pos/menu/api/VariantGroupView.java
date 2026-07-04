package com.company.pos.menu.api;

import java.util.List;
import java.util.UUID;

public record VariantGroupView(UUID id, String name, List<VariantMemberView> members) {
}
