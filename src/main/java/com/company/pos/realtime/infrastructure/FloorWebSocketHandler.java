package com.company.pos.realtime.infrastructure;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The single {@code /ws/floor} push channel. Holds every connected terminal's session and
 * fans a floor-invalidation ping out to all of them. Push-only: inbound frames are ignored.
 * A session that is closed or throws on send is dropped, so one dead terminal never blocks the
 * rest.
 */
@Component
public class FloorWebSocketHandler extends TextWebSocketHandler {

    private static final System.Logger LOG = System.getLogger(FloorWebSocketHandler.class.getName());

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    /** Sends {@code json} to every open session; drops any that is closed or fails. */
    public void broadcast(String json) {
        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }
            try {
                session.sendMessage(message);
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "Dropping unreachable floor session", e);
                sessions.remove(session);
            }
        }
    }
}
