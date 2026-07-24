package com.company.pos.realtime.application;

import com.company.pos.dining.api.DiningFloorChanged;
import com.company.pos.realtime.infrastructure.FloorWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Fans a {@link DiningFloorChanged} fact out to every connected terminal (after-commit, async, own
 * tx — a dead socket never rolls back or blocks a dining write). The ping is intentionally tiny:
 * the terminal ignores the body and simply re-fetches the authoritative floor state over REST. The
 * fields are for logs and future filtering. At-least-once outbox replay may re-send a ping; a
 * duplicate re-fetch is harmless.
 */
@Component
public class DiningFloorChangedListener {

    private final FloorWebSocketHandler handler;
    private final ObjectMapper mapper = new ObjectMapper();

    public DiningFloorChangedListener(FloorWebSocketHandler handler) {
        this.handler = handler;
    }

    @ApplicationModuleListener
    public void on(DiningFloorChanged event) {
        ObjectNode ping = mapper.createObjectNode();
        ping.put("type", "FLOOR_CHANGED");
        ping.put("topic", "FLOOR");
        ping.put("change", event.change().name());
        if (event.at() != null) {
            ping.put("at", event.at().toString());
        }
        handler.broadcast(ping.toString());
    }
}
