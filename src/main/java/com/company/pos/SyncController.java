package com.company.pos;

import com.company.pos.ErpSyncCoordinator.SyncSummary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SyncController {

    private final ErpSyncCoordinator coordinator;

    SyncController(ErpSyncCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping("/sync/erp")
    @PreAuthorize("hasRole('MANAGER')")
    SyncSummary trigger() {
        return coordinator.syncAll();
    }
}
