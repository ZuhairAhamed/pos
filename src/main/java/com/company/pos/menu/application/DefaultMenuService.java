package com.company.pos.menu.application;

import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.menu.api.AddOptionCommand;
import com.company.pos.menu.api.AddVariantMemberCommand;
import com.company.pos.menu.api.CreateModifierGroupCommand;
import com.company.pos.menu.api.CreateVariantGroupCommand;
import com.company.pos.menu.api.MenuService;
import com.company.pos.menu.api.ModifierGroupView;
import com.company.pos.menu.api.ModifierOptionView;
import com.company.pos.menu.api.ModifierResolution;
import com.company.pos.menu.api.ResolvedModifier;
import com.company.pos.menu.api.VariantGroupView;
import com.company.pos.menu.api.VariantMemberView;
import com.company.pos.menu.domain.ModifierGroup;
import com.company.pos.menu.domain.ModifierGroupAssignment;
import com.company.pos.menu.domain.ModifierOption;
import com.company.pos.menu.domain.VariantGroup;
import com.company.pos.menu.domain.VariantMember;
import com.company.pos.menu.infrastructure.ModifierGroupAssignmentRepository;
import com.company.pos.menu.infrastructure.ModifierGroupRepository;
import com.company.pos.menu.infrastructure.ModifierOptionRepository;
import com.company.pos.menu.infrastructure.VariantGroupRepository;
import com.company.pos.menu.infrastructure.VariantMemberRepository;
import com.company.pos.product.api.ProductCatalog;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultMenuService implements MenuService {

    private final ModifierGroupRepository groups;
    private final ModifierOptionRepository options;
    private final ModifierGroupAssignmentRepository assignments;
    private final ProductCatalog products;
    private final VariantGroupRepository variantGroups;
    private final VariantMemberRepository variantMembers;

    DefaultMenuService(ModifierGroupRepository groups, ModifierOptionRepository options,
            ModifierGroupAssignmentRepository assignments, ProductCatalog products,
            VariantGroupRepository variantGroups, VariantMemberRepository variantMembers) {
        this.groups = groups;
        this.options = options;
        this.assignments = assignments;
        this.products = products;
        this.variantGroups = variantGroups;
        this.variantMembers = variantMembers;
    }

    @Override
    public ModifierGroupView createModifierGroup(CreateModifierGroupCommand command) {
        if (command.name() == null || command.name().isBlank()) {
            throw DomainException.validation("Modifier group name is required");
        }
        if (command.minSelections() < 0 || command.maxSelections() < command.minSelections()
                || command.maxSelections() < 1) {
            throw DomainException.validation("Invalid min/max selections");
        }
        ModifierGroup g = new ModifierGroup(Identifiers.newId(), command.name().trim(),
                command.minSelections(), command.maxSelections());
        return toGroupView(groups.save(g));
    }

    @Override
    public ModifierOptionView addOption(UUID groupId, AddOptionCommand command) {
        loadGroup(groupId);
        if (command.name() == null || command.name().isBlank()) {
            throw DomainException.validation("Option name is required");
        }
        if (command.priceDelta() == null) {
            throw DomainException.validation("Option priceDelta is required (may be 0)");
        }
        ModifierOption o = new ModifierOption(Identifiers.newId(), groupId, command.name().trim(),
                command.priceDelta());
        o = options.save(o);
        return new ModifierOptionView(o.getId(), o.getName(), o.getPriceDelta());
    }

    @Override
    public void assignGroupToSku(UUID groupId, String sku) {
        loadGroup(groupId);
        products.findBySku(sku)
                .orElseThrow(() -> DomainException.validation("Unknown sku " + sku));
        if (assignments.existsByGroupIdAndSku(groupId, sku)) {
            return; // idempotent
        }
        assignments.save(new ModifierGroupAssignment(Identifiers.newId(), groupId, sku));
    }

    @Override
    public void unassignGroupFromSku(UUID groupId, String sku) {
        assignments.deleteByGroupIdAndSku(groupId, sku);
    }

    @Override
    public void deactivateModifierGroup(UUID groupId) {
        loadGroup(groupId).setActive(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModifierGroupView> groupsForSku(String sku) {
        List<ModifierGroupView> result = new ArrayList<>();
        for (ModifierGroupAssignment a : assignments.findBySku(sku)) {
            groups.findById(a.getGroupId())
                    .filter(ModifierGroup::isActive)
                    .ifPresent(g -> result.add(toGroupView(g)));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public ModifierResolution resolveSelections(String sku, List<UUID> selectedOptionIds) {
        List<UUID> selected = selectedOptionIds == null ? List.of() : selectedOptionIds;

        // The groups assigned & active for this sku, keyed by id.
        Map<UUID, ModifierGroup> assignedGroups = new HashMap<>();
        for (ModifierGroupAssignment a : assignments.findBySku(sku)) {
            groups.findById(a.getGroupId()).filter(ModifierGroup::isActive)
                    .ifPresent(g -> assignedGroups.put(g.getId(), g));
        }

        // Resolve each selected option; it must be active and belong to an assigned group.
        List<ResolvedModifier> resolved = new ArrayList<>();
        Map<UUID, Long> perGroupCount = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (UUID optionId : selected) {
            ModifierOption o = options.findById(optionId)
                    .filter(ModifierOption::isActive)
                    .orElseThrow(() -> DomainException.validation("Unknown or inactive option " + optionId));
            if (!assignedGroups.containsKey(o.getGroupId())) {
                throw DomainException.validation(
                        "Option " + o.getName() + " is not offered for sku " + sku);
            }
            perGroupCount.merge(o.getGroupId(), 1L, Long::sum);
            resolved.add(new ResolvedModifier(o.getId(), o.getName(), o.getPriceDelta()));
            total = total.add(o.getPriceDelta());
        }

        // Enforce each assigned group's min/max against the count picked from it.
        for (ModifierGroup g : assignedGroups.values()) {
            long count = perGroupCount.getOrDefault(g.getId(), 0L);
            if (count < g.getMinSelections()) {
                throw DomainException.validation(
                        "Group " + g.getName() + " requires at least " + g.getMinSelections() + " selection(s)");
            }
            if (count > g.getMaxSelections()) {
                throw DomainException.validation(
                        "Group " + g.getName() + " allows at most " + g.getMaxSelections() + " selection(s)");
            }
        }
        return new ModifierResolution(resolved, total);
    }

    // --- variant methods ---
    @Override
    public VariantGroupView createVariantGroup(CreateVariantGroupCommand command) {
        if (command.name() == null || command.name().isBlank()) {
            throw DomainException.validation("Variant group name is required");
        }
        VariantGroup g = variantGroups.save(new VariantGroup(Identifiers.newId(), command.name().trim()));
        return toVariantView(g);
    }

    @Override
    public VariantMemberView addVariantMember(UUID variantGroupId, AddVariantMemberCommand command) {
        variantGroups.findById(variantGroupId)
                .orElseThrow(() -> DomainException.notFound("No variant group " + variantGroupId));
        products.findBySku(command.sku())
                .orElseThrow(() -> DomainException.validation("Unknown sku " + command.sku()));
        VariantMember m = variantMembers.save(new VariantMember(Identifiers.newId(),
                variantGroupId, command.sku(), command.displayLabel()));
        return new VariantMemberView(m.getSku(), m.getDisplayLabel());
    }

    @Override
    public void deactivateVariantGroup(UUID variantGroupId) {
        variantGroups.findById(variantGroupId)
                .orElseThrow(() -> DomainException.notFound("No variant group " + variantGroupId))
                .setActive(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VariantGroupView> listVariantGroups() {
        return variantGroups.findAll().stream()
                .filter(VariantGroup::isActive)
                .map(this::toVariantView)
                .collect(Collectors.toList());
    }

    private VariantGroupView toVariantView(VariantGroup g) {
        List<VariantMemberView> members = variantMembers.findByVariantGroupId(g.getId()).stream()
                .map(m -> new VariantMemberView(m.getSku(), m.getDisplayLabel()))
                .collect(Collectors.toList());
        return new VariantGroupView(g.getId(), g.getName(), members);
    }

    private ModifierGroup loadGroup(UUID groupId) {
        return groups.findById(groupId)
                .orElseThrow(() -> DomainException.notFound("No modifier group " + groupId));
    }

    private ModifierGroupView toGroupView(ModifierGroup g) {
        List<ModifierOptionView> opts = options.findByGroupId(g.getId()).stream()
                .filter(ModifierOption::isActive)
                .map(o -> new ModifierOptionView(o.getId(), o.getName(), o.getPriceDelta()))
                .collect(Collectors.toList());
        return new ModifierGroupView(g.getId(), g.getName(), g.getMinSelections(),
                g.getMaxSelections(), opts);
    }
}
