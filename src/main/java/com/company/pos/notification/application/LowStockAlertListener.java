package com.company.pos.notification.application;

import com.company.pos.inventory.api.LowStockDetected;
import com.company.pos.notification.api.Alert;
import com.company.pos.notification.api.AlertType;
import com.company.pos.notification.api.Notifier;
import java.time.Instant;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Turns a {@link LowStockDetected} fact into a LOW_STOCK operator alert (after-commit, async). */
@Component
class LowStockAlertListener {

    private final Notifier notifier;

    LowStockAlertListener(Notifier notifier) {
        this.notifier = notifier;
    }

    @ApplicationModuleListener
    void on(LowStockDetected event) {
        String message = "Low stock: %s at %s on-hand %s (reorder %s)".formatted(
                event.sku(), event.locationCode(), event.onHand(), event.reorderLevel());
        notifier.send(new Alert(AlertType.LOW_STOCK, message, Instant.now()));
    }
}
