package com.company.pos.realtime.application;

import com.company.pos.kitchen.api.KitchenTicketChanged;
import com.company.pos.realtime.infrastructure.FloorWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Broadcasts a KITCHEN-topic invalidation ping on the shared /ws/floor socket. */
@Component
public class KitchenTicketChangedListener {

    private final FloorWebSocketHandler handler;
    private final ObjectMapper mapper = new ObjectMapper();

    public KitchenTicketChangedListener(FloorWebSocketHandler handler) {
        this.handler = handler;
    }

    @ApplicationModuleListener
    public void on(KitchenTicketChanged event) {
        ObjectNode ping = mapper.createObjectNode();
        ping.put("type", "KITCHEN_CHANGED");
        ping.put("topic", "KITCHEN");
        if (event.at() != null) {
            ping.put("at", event.at().toString());
        }
        handler.broadcast(ping.toString());
    }
}
