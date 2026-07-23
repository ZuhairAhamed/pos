package com.company.pos.audit.application;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.menu.api.MenuChangeType;
import com.company.pos.menu.api.MenuChanged;
import java.util.HashMap;
import java.util.Map;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Records menu modifier-admin actions into the audit trail. Runs async AFTER the publishing
 * transaction commits (the outbox redelivers on failure), mirroring {@code ProductChangedAuditListener}.
 * The actor rides on the event, so the real user is recorded.
 */
@Component
class MenuChangedAuditListener {

    private final DefaultAuditService audit;

    MenuChangedAuditListener(DefaultAuditService audit) {
        this.audit = audit;
    }

    @ApplicationModuleListener
    void on(MenuChanged event) {
        Map<String, String> details = new HashMap<>();
        if (event.detail() != null) {
            details.put("sku", event.detail());
        }
        if (event.oldPrice() != null) {
            details.put("oldPrice", event.oldPrice().toPlainString());
        }
        if (event.newPrice() != null) {
            details.put("newPrice", event.newPrice().toPlainString());
        }
        audit.append(actionFor(event.type()), event.actor(), event.entityRef(), details);
    }

    private static AuditAction actionFor(MenuChangeType type) {
        return switch (type) {
            case GROUP_CREATED -> AuditAction.MENU_GROUP_CREATED;
            case GROUP_UPDATED -> AuditAction.MENU_GROUP_UPDATED;
            case GROUP_DEACTIVATED -> AuditAction.MENU_GROUP_DEACTIVATED;
            case GROUP_REACTIVATED -> AuditAction.MENU_GROUP_REACTIVATED;
            case OPTION_ADDED -> AuditAction.MENU_OPTION_ADDED;
            case OPTION_UPDATED -> AuditAction.MENU_OPTION_UPDATED;
            case OPTION_DEACTIVATED -> AuditAction.MENU_OPTION_DEACTIVATED;
            case OPTION_REACTIVATED -> AuditAction.MENU_OPTION_REACTIVATED;
            case GROUP_ASSIGNED -> AuditAction.MENU_GROUP_ASSIGNED;
            case GROUP_UNASSIGNED -> AuditAction.MENU_GROUP_UNASSIGNED;
            case VARIANT_GROUP_CREATED -> AuditAction.MENU_VARIANT_GROUP_CREATED;
            case VARIANT_GROUP_UPDATED -> AuditAction.MENU_VARIANT_GROUP_UPDATED;
            case VARIANT_GROUP_DEACTIVATED -> AuditAction.MENU_VARIANT_GROUP_DEACTIVATED;
            case VARIANT_GROUP_REACTIVATED -> AuditAction.MENU_VARIANT_GROUP_REACTIVATED;
            case VARIANT_MEMBER_ADDED -> AuditAction.MENU_VARIANT_MEMBER_ADDED;
            case VARIANT_MEMBER_UPDATED -> AuditAction.MENU_VARIANT_MEMBER_UPDATED;
            case VARIANT_MEMBER_DEACTIVATED -> AuditAction.MENU_VARIANT_MEMBER_DEACTIVATED;
            case VARIANT_MEMBER_REACTIVATED -> AuditAction.MENU_VARIANT_MEMBER_REACTIVATED;
        };
    }
}
