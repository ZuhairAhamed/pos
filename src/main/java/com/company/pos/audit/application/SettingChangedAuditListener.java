package com.company.pos.audit.application;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.configuration.api.SettingChanged;
import java.util.Map;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class SettingChangedAuditListener {

    private final DefaultAuditService audit;

    SettingChangedAuditListener(DefaultAuditService audit) {
        this.audit = audit;
    }

    @ApplicationModuleListener
    void on(SettingChanged event) {
        audit.append(AuditAction.SETTING_CHANGED, event.actor(), event.key(),
                Map.of("old", n(event.oldValue()), "new", n(event.newValue())));
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }
}
