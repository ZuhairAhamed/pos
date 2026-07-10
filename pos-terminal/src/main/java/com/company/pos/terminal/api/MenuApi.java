package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.ModifierGroupView;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;

/** Typed client for the store server's {@code /menu} read endpoints. */
public class MenuApi {
    private final ApiClient client;

    public MenuApi(ApiClient client) {
        this.client = client;
    }

    public List<ModifierGroupView> modifierGroupsForSku(String sku) {
        return client.get("/menu/products/" + sku + "/modifier-groups",
                new TypeReference<List<ModifierGroupView>>() {});
    }
}
