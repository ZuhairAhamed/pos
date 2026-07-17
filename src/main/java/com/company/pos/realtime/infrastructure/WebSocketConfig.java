package com.company.pos.realtime.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the floor push channel at {@code /ws/floor}. The handshake is a plain HTTP GET, so it
 * is authenticated by the existing OAuth2 resource-server JWT filter (SecurityConfig authenticates
 * every non-/auth request). Origins are unrestricted — terminals are LAN native apps, not browsers.
 */
@Configuration
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {

    private final FloorWebSocketHandler handler;

    WebSocketConfig(FloorWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/floor").setAllowedOriginPatterns("*");
    }
}
