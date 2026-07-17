package com.company.pos.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.pos.realtime.infrastructure.FloorWebSocketHandler;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class FloorWebSocketHandlerTest {

    @Test
    void broadcastReachesEveryOpenSession() throws Exception {
        FloorWebSocketHandler handler = new FloorWebSocketHandler();
        WebSocketSession a = openSession();
        WebSocketSession b = openSession();
        handler.afterConnectionEstablished(a);
        handler.afterConnectionEstablished(b);

        handler.broadcast("{\"type\":\"FLOOR_CHANGED\"}");

        verify(a).sendMessage(any(TextMessage.class));
        verify(b).sendMessage(any(TextMessage.class));
    }

    @Test
    void aFailingSessionDoesNotStopTheOthers() throws Exception {
        FloorWebSocketHandler handler = new FloorWebSocketHandler();
        WebSocketSession bad = openSession();
        WebSocketSession good = openSession();
        doThrow(new IOException("dead")).when(bad).sendMessage(any(TextMessage.class));
        handler.afterConnectionEstablished(bad);
        handler.afterConnectionEstablished(good);

        handler.broadcast("ping");

        verify(good).sendMessage(any(TextMessage.class));
    }

    @Test
    void closedSessionIsRemovedAndNoLongerReceives() throws Exception {
        FloorWebSocketHandler handler = new FloorWebSocketHandler();
        WebSocketSession s = openSession();
        handler.afterConnectionEstablished(s);
        handler.afterConnectionClosed(s, CloseStatus.NORMAL);

        handler.broadcast("ping");

        verify(s, never()).sendMessage(any(TextMessage.class));
    }

    private WebSocketSession openSession() {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.isOpen()).thenReturn(true);
        return s;
    }
}
