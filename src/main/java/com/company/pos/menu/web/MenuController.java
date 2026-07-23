package com.company.pos.menu.web;

import com.company.pos.menu.api.AddOptionCommand;
import com.company.pos.menu.api.AddVariantMemberCommand;
import com.company.pos.menu.api.CreateModifierGroupCommand;
import com.company.pos.menu.api.CreateVariantGroupCommand;
import com.company.pos.menu.api.MenuService;
import com.company.pos.menu.api.ModifierGroupAdminView;
import com.company.pos.menu.api.ModifierGroupView;
import com.company.pos.menu.api.ModifierOptionView;
import com.company.pos.menu.api.UpdateModifierGroupCommand;
import com.company.pos.menu.api.UpdateOptionCommand;
import com.company.pos.menu.api.VariantGroupView;
import com.company.pos.menu.api.VariantMemberView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MenuController {

    private final MenuService menu;

    MenuController(MenuService menu) {
        this.menu = menu;
    }

    // --- modifier admin (MANAGER/ADMIN) ---
    @PostMapping("/menu/modifier-groups")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    ModifierGroupView createGroup(@RequestBody CreateModifierGroupCommand body) {
        return menu.createModifierGroup(body);
    }

    @PostMapping("/menu/modifier-groups/{groupId}/options")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    ModifierOptionView addOption(@PathVariable UUID groupId, @RequestBody AddOptionCommand body) {
        return menu.addOption(groupId, body);
    }

    @PostMapping("/menu/modifier-groups/{groupId}/assignments")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    void assign(@PathVariable UUID groupId, @RequestParam String sku) {
        menu.assignGroupToSku(groupId, sku);
    }

    @DeleteMapping("/menu/modifier-groups/{groupId}/assignments")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    void unassign(@PathVariable UUID groupId, @RequestParam String sku) {
        menu.unassignGroupFromSku(groupId, sku);
    }

    @DeleteMapping("/menu/modifier-groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    void deactivateGroup(@PathVariable UUID groupId) {
        menu.deactivateModifierGroup(groupId);
    }

    @GetMapping("/menu/modifier-groups")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    List<ModifierGroupAdminView> listGroups() {
        return menu.listModifierGroups();
    }

    @PutMapping("/menu/modifier-groups/{groupId}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    ModifierGroupView updateGroup(@PathVariable UUID groupId, @RequestBody UpdateModifierGroupCommand body) {
        return menu.updateModifierGroup(groupId, body);
    }

    @PostMapping("/menu/modifier-groups/{groupId}/reactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    void reactivateGroup(@PathVariable UUID groupId) {
        menu.reactivateModifierGroup(groupId);
    }

    @PutMapping("/menu/modifier-groups/{groupId}/options/{optionId}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    ModifierOptionView updateOption(@PathVariable UUID groupId, @PathVariable UUID optionId,
            @RequestBody UpdateOptionCommand body) {
        return menu.updateOption(groupId, optionId, body);
    }

    @DeleteMapping("/menu/modifier-groups/{groupId}/options/{optionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    void deactivateOption(@PathVariable UUID groupId, @PathVariable UUID optionId) {
        menu.deactivateOption(groupId, optionId);
    }

    @PostMapping("/menu/modifier-groups/{groupId}/options/{optionId}/reactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    void reactivateOption(@PathVariable UUID groupId, @PathVariable UUID optionId) {
        menu.reactivateOption(groupId, optionId);
    }

    // --- variant admin (MANAGER/ADMIN) ---
    @PostMapping("/menu/variant-groups")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    VariantGroupView createVariantGroup(@RequestBody CreateVariantGroupCommand body) {
        return menu.createVariantGroup(body);
    }

    @PostMapping("/menu/variant-groups/{groupId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    VariantMemberView addMember(@PathVariable UUID groupId, @RequestBody AddVariantMemberCommand body) {
        return menu.addVariantMember(groupId, body);
    }

    @DeleteMapping("/menu/variant-groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    void deactivateVariantGroup(@PathVariable UUID groupId) {
        menu.deactivateVariantGroup(groupId);
    }

    // --- reads (any authenticated caller) ---
    @GetMapping("/menu/products/{sku}/modifier-groups")
    List<ModifierGroupView> groupsForSku(@PathVariable String sku) {
        return menu.groupsForSku(sku);
    }

    @GetMapping("/menu/variant-groups")
    List<VariantGroupView> variantGroups() {
        return menu.listVariantGroups();
    }
}
