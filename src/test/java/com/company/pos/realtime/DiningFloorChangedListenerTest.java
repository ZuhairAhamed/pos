package com.company.pos.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.company.pos.dining.api.DiningFloorChanged;
import com.company.pos.dining.api.FloorChangeType;
import com.company.pos.realtime.application.DiningFloorChangedListener;
import com.company.pos.realtime.infrastructure.FloorWebSocketHandler;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DiningFloorChangedListenerTest {

    @Test
    void broadcastsANonEmptyPingCarryingTheChangeType() {
        FloorWebSocketHandler handler = mock(FloorWebSocketHandler.class);
        DiningFloorChangedListener listener = new DiningFloorChangedListener(handler);

        listener.on(new DiningFloorChanged(FloorChangeType.ORDER_OPENED,
                UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-07-17T10:00:00Z")));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(handler).broadcast(json.capture());
        assertThat(json.getValue()).contains("FLOOR_CHANGED").contains("ORDER_OPENED");
    }
}
