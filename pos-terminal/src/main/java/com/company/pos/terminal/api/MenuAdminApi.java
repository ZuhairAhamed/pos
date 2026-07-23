package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.ModifierGroupAdminView;
import com.fasterxml.jackson.core.type.TypeReference;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Typed client for the store server's menu modifier-admin endpoints (MANAGER/ADMIN-gated).
 *  Non-final so view-model tests can subclass with fakes. Void endpoints pass a null TypeReference. */
public class MenuAdminApi {

    private final ApiClient client;

    public MenuAdminApi(ApiClient client) {
        this.client = client;
    }

    public List<ModifierGroupAdminView> listGroups() {
        return client.get("/menu/modifier-groups", new TypeReference<List<ModifierGroupAdminView>>() {});
    }

    public void createGroup(String name, int min, int max) {
        client.post("/menu/modifier-groups", new ModifierGroupRequest(name, min, max), null);
    }

    public void updateGroup(UUID groupId, String name, int min, int max) {
        client.put("/menu/modifier-groups/" + groupId, new ModifierGroupRequest(name, min, max), null);
    }

    public void deactivateGroup(UUID groupId) {
        client.delete("/menu/modifier-groups/" + groupId);
    }

    public void reactivateGroup(UUID groupId) {
        client.post("/menu/modifier-groups/" + groupId + "/reactivate", null, null);
    }

    public void addOption(UUID groupId, String name, BigDecimal priceDelta) {
        client.post("/menu/modifier-groups/" + groupId + "/options",
                new ModifierOptionRequest(name, priceDelta), null);
    }

    public void updateOption(UUID groupId, UUID optionId, String name, BigDecimal priceDelta) {
        client.put("/menu/modifier-groups/" + groupId + "/options/" + optionId,
                new ModifierOptionRequest(name, priceDelta), null);
    }

    public void deactivateOption(UUID groupId, UUID optionId) {
        client.delete("/menu/modifier-groups/" + groupId + "/options/" + optionId);
    }

    public void reactivateOption(UUID groupId, UUID optionId) {
        client.post("/menu/modifier-groups/" + groupId + "/options/" + optionId + "/reactivate", null, null);
    }

    public void assignSku(UUID groupId, String sku) {
        client.post("/menu/modifier-groups/" + groupId + "/assignments?sku=" + sku, null, null);
    }

    public void unassignSku(UUID groupId, String sku) {
        client.delete("/menu/modifier-groups/" + groupId + "/assignments?sku=" + sku);
    }
}
