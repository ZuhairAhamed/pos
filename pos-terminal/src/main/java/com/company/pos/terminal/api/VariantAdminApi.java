package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.VariantGroupAdminView;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.UUID;

/**
 * Typed client for the store server's variant-admin endpoints (MANAGER/ADMIN-gated).
 * Non-final so view-model tests can subclass with fakes. Void endpoints pass a null TypeReference.
 */
public class VariantAdminApi {

    private final ApiClient client;

    public VariantAdminApi(ApiClient client) {
        this.client = client;
    }

    public List<VariantGroupAdminView> listAdmin() {
        return client.get("/menu/variant-groups/admin",
                new TypeReference<List<VariantGroupAdminView>>() {});
    }

    public void createGroup(String name) {
        client.post("/menu/variant-groups", new VariantGroupRequest(name), null);
    }

    public void updateGroup(UUID groupId, String name) {
        client.put("/menu/variant-groups/" + groupId, new VariantGroupRequest(name), null);
    }

    public void deactivateGroup(UUID groupId) {
        client.delete("/menu/variant-groups/" + groupId);
    }

    public void reactivateGroup(UUID groupId) {
        client.post("/menu/variant-groups/" + groupId + "/reactivate", null, null);
    }

    public void addMember(UUID groupId, String sku, String displayLabel) {
        client.post("/menu/variant-groups/" + groupId + "/members",
                new VariantMemberRequest(sku, displayLabel), null);
    }

    public void updateMember(UUID groupId, UUID memberId, String displayLabel) {
        client.put("/menu/variant-groups/" + groupId + "/members/" + memberId,
                new VariantMemberLabelRequest(displayLabel), null);
    }

    public void deactivateMember(UUID groupId, UUID memberId) {
        client.delete("/menu/variant-groups/" + groupId + "/members/" + memberId);
    }

    public void reactivateMember(UUID groupId, UUID memberId) {
        client.post("/menu/variant-groups/" + groupId + "/members/" + memberId + "/reactivate",
                null, null);
    }
}
