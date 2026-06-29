package com.company.pos.notification.infrastructure;

import com.company.pos.notification.api.Alert;
import com.company.pos.notification.api.Notifier;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** In-memory + log Notifier stand-in. Replaced by a real channel adapter later. */
@Component
public class InMemoryNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(InMemoryNotifier.class);

    private final List<Alert> alerts = new CopyOnWriteArrayList<>();

    @Override
    public void send(Alert alert) {
        alerts.add(alert);
        log.info("ALERT [{}] {}", alert.type(), alert.message());
    }

    public List<Alert> alerts() {
        return List.copyOf(alerts);
    }

    public void clear() {
        alerts.clear();
    }
}
