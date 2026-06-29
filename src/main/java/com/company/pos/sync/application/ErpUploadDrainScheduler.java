package com.company.pos.sync.application;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically resubmits outbox publications that failed to complete (e.g. ERP uploads attempted
 * while the link was down), once they are older than a minimum age so an in-flight retry is not
 * re-fired. Disabled by default; enabled on the store-server profile.
 */
@Component
@ConditionalOnProperty(prefix = "pos.sync.erp.upload", name = "scheduled", havingValue = "true")
class ErpUploadDrainScheduler {

    private final IncompleteEventPublications incomplete;
    private final Duration minAge;

    ErpUploadDrainScheduler(IncompleteEventPublications incomplete,
            @org.springframework.beans.factory.annotation.Value("${pos.sync.erp.upload.min-age-ms:10000}") long minAgeMs) {
        this.incomplete = incomplete;
        this.minAge = Duration.ofMillis(minAgeMs);
    }

    @Scheduled(fixedDelayString = "${pos.sync.erp.upload.fixed-delay-ms:30000}",
            initialDelayString = "${pos.sync.erp.upload.fixed-delay-ms:30000}")
    void drain() {
        incomplete.resubmitIncompletePublicationsOlderThan(minAge);
    }
}
