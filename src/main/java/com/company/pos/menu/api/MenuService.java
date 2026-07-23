package com.company.pos.menu.api;

import java.util.List;
import java.util.UUID;

public interface MenuService {

    // --- modifier admin (MANAGER/ADMIN) ---
    ModifierGroupView createModifierGroup(CreateModifierGroupCommand command);

    ModifierOptionView addOption(UUID groupId, AddOptionCommand command);

    void assignGroupToSku(UUID groupId, String sku);

    void unassignGroupFromSku(UUID groupId, String sku);

    void deactivateModifierGroup(UUID groupId);

    ModifierGroupView updateModifierGroup(UUID groupId, UpdateModifierGroupCommand command);

    void reactivateModifierGroup(UUID groupId);

    ModifierOptionView updateOption(UUID groupId, UUID optionId, UpdateOptionCommand command);

    void deactivateOption(UUID groupId, UUID optionId);

    void reactivateOption(UUID groupId, UUID optionId);

    List<ModifierGroupAdminView> listModifierGroups();

    // --- variant admin (MANAGER/ADMIN) ---
    VariantGroupView createVariantGroup(CreateVariantGroupCommand command);

    VariantMemberView addVariantMember(UUID variantGroupId, AddVariantMemberCommand command);

    void deactivateVariantGroup(UUID variantGroupId);

    // --- queries (any authenticated caller / used by cart + dining) ---
    List<ModifierGroupView> groupsForSku(String sku);

    /**
     * Validate {@code selectedOptionIds} against every modifier group assigned to {@code sku}
     * (each option must belong to an assigned+active group; each group's min/max enforced),
     * and return the resolved names + deltas with their sum. Throws
     * {@code DomainException.validation} on any rule violation.
     */
    ModifierResolution resolveSelections(String sku, List<UUID> selectedOptionIds);

    List<VariantGroupView> listVariantGroups();
}
