package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.KitchenTicketView;
import com.fasterxml.jackson.core.type.TypeReference;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Typed client for the KDS ticket-board endpoints. Non-final so view-model tests subclass it. */
public class KitchenTicketApi {

    private final ApiClient client;

    public KitchenTicketApi(ApiClient client) {
        this.client = client;
    }

    public List<KitchenTicketView> list() {
        return client.get("/kitchen/tickets", new TypeReference<List<KitchenTicketView>>() {});
    }

    public List<KitchenTicketView> list(String station) {
        return client.get("/kitchen/tickets?station="
                        + URLEncoder.encode(station, StandardCharsets.UTF_8),
                new TypeReference<List<KitchenTicketView>>() {});
    }

    public KitchenTicketView advance(UUID id, String expectedState) {
        return client.post("/kitchen/tickets/" + id + "/advance",
                new TransitionRequest(expectedState), new TypeReference<KitchenTicketView>() {});
    }

    public KitchenTicketView recall(UUID id, String expectedState) {
        return client.post("/kitchen/tickets/" + id + "/recall",
                new TransitionRequest(expectedState), new TypeReference<KitchenTicketView>() {});
    }

    public record TransitionRequest(String expectedState) {}
}
