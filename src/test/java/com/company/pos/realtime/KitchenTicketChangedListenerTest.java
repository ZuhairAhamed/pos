package com.company.pos.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.company.pos.kitchen.api.KitchenTicketChanged;
import com.company.pos.realtime.application.KitchenTicketChangedListener;
import com.company.pos.realtime.infrastructure.FloorWebSocketHandler;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KitchenTicketChangedListenerTest {

    @Test
    void broadcastsKitchenTopicPing() {
        FloorWebSocketHandler handler = mock(FloorWebSocketHandler.class);
        KitchenTicketChangedListener listener = new KitchenTicketChangedListener(handler);

        listener.on(new KitchenTicketChanged(UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-07-24T10:00:00Z")));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(handler).broadcast(json.capture());
        assertThat(json.getValue()).contains("\"topic\":\"KITCHEN\"");
        assertThat(json.getValue()).contains("\"type\":\"KITCHEN_CHANGED\"");
    }
}
