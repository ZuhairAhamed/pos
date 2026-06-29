package com.company.pos.notification.application;

import com.company.pos.notification.api.Alert;
import com.company.pos.notification.api.AlertType;
import com.company.pos.notification.api.Notifier;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.stereotype.Component;

/**
 * Polls the transactional outbox for publications that have stayed incomplete longer than the
 * configured min-age (e.g. ERP uploads that keep failing because the link is down) and raises a
 * SYNC_ERROR alert. Deduped by publication id so a single stuck row alerts once, not every scan.
 */
@Component
public class StuckUploadMonitor {

    private final EventPublicationRegistry registry;
    private final Notifier notifier;
    private final long minAgeMs;
    private final Set<UUID> alerted = ConcurrentHashMap.newKeySet();

    StuckUploadMonitor(EventPublicationRegistry registry, Notifier notifier,
            @Value("${pos.notification.stuck-upload.min-age-ms:300000}") long minAgeMs) {
        this.registry = registry;
        this.notifier = notifier;
        this.minAgeMs = minAgeMs;
    }

    public void scan() {
        Instant cutoff = Instant.now().minusMillis(minAgeMs);
        for (TargetEventPublication publication : registry.findIncompletePublications()) {
            if (publication.getPublicationDate().isBefore(cutoff)
                    && alerted.add(publication.getIdentifier())) {
                String message = "Outbox publication %s stuck since %s (not delivered)".formatted(
                        publication.getIdentifier(), publication.getPublicationDate());
                notifier.send(new Alert(AlertType.SYNC_ERROR, message, Instant.now()));
            }
        }
    }
}
