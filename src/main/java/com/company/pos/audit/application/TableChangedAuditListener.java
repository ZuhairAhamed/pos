package com.company.pos.audit.application;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.dining.api.TableChangeType;
import com.company.pos.dining.api.TableChanged;
import java.util.Map;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Records dining-table admin actions into the audit trail. Runs async AFTER the publishing
 * transaction commits (the outbox redelivers on failure), mirroring {@code ProductChangedAuditListener}.
 * The actor rides on the event (captured on the request thread), so the real user is recorded.
 */
@Component
class TableChangedAuditListener {

    private final DefaultAuditService audit;

    TableChangedAuditListener(DefaultAuditService audit) {
        this.audit = audit;
    }

    @ApplicationModuleListener
    void on(TableChanged event) {
        audit.append(actionFor(event.type()), event.actor(), event.entityRef(),
                Map.of("label", event.label(),
                        "seats", String.valueOf(event.seats()),
                        "active", String.valueOf(event.active())));
    }

    private static AuditAction actionFor(TableChangeType type) {
        return switch (type) {
            case CREATED -> AuditAction.TABLE_CREATED;
            case UPDATED -> AuditAction.TABLE_UPDATED;
            case DEACTIVATED -> AuditAction.TABLE_DEACTIVATED;
            case REACTIVATED -> AuditAction.TABLE_REACTIVATED;
        };
    }
}
