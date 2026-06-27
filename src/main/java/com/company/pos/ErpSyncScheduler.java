package com.company.pos;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "pos.sync.erp", name = "scheduled", havingValue = "true")
class ErpSyncScheduler {

    private final ErpSyncCoordinator coordinator;

    ErpSyncScheduler(ErpSyncCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Scheduled(fixedDelayString = "${pos.sync.erp.fixed-delay-ms:60000}",
            initialDelayString = "${pos.sync.erp.fixed-delay-ms:60000}")
    void runScheduledSync() {
        coordinator.syncAll();
    }
}
