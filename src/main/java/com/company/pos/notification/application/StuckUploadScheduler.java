package com.company.pos.notification.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically runs the stuck-upload scan. Disabled by default; enabled on store-server. */
@Component
@ConditionalOnProperty(prefix = "pos.notification.stuck-upload", name = "scheduled",
        havingValue = "true")
class StuckUploadScheduler {

    private final StuckUploadMonitor monitor;

    StuckUploadScheduler(StuckUploadMonitor monitor) {
        this.monitor = monitor;
    }

    @Scheduled(fixedDelayString = "${pos.notification.stuck-upload.fixed-delay-ms:60000}",
            initialDelayString = "${pos.notification.stuck-upload.fixed-delay-ms:60000}")
    void run() {
        monitor.scan();
    }
}
