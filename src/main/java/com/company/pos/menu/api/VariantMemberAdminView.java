package com.company.pos.menu.api;

import java.util.UUID;

public record VariantMemberAdminView(UUID id, String sku, String displayLabel, boolean active) {
}
