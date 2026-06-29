package com.company.pos.sync.web;

import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual trigger to drain the ERP upload outbox now (e.g. after the link is restored), mirroring
 * the MANAGER-only down-sync trigger POST /sync/erp. Resubmission is asynchronous; this returns
 * immediately once the resubmit has been kicked off.
 */
@RestController
class ErpUploadController {

    private final IncompleteEventPublications incomplete;

    ErpUploadController(IncompleteEventPublications incomplete) {
        this.incomplete = incomplete;
    }

    @PostMapping("/sync/erp/upload")
    @PreAuthorize("hasRole('MANAGER')")
    void drain() {
        incomplete.resubmitIncompletePublications(p -> true);
    }
}
