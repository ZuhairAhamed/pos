package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class WebSocketRealtimeClientTest {

    @Test
    void wsUriMapsHttpToWsAndAppendsPath() {
        assertEquals("ws://localhost:8080/ws/floor",
                WebSocketRealtimeClient.wsUri("http://localhost:8080", "/ws/floor"));
    }

    @Test
    void wsUriMapsHttpsToWssAndTrimsTrailingSlash() {
        assertEquals("wss://store.example.com/ws/floor",
                WebSocketRealtimeClient.wsUri("https://store.example.com/", "/ws/floor"));
    }

    @Test
    void onTextRunsCallbackOnceAndRequestsAnotherFrame() {
        AtomicInteger fired = new AtomicInteger();
        WebSocketRealtimeClient.FrameListener listener =
                new WebSocketRealtimeClient.FrameListener(t -> fired.incrementAndGet(), null);
        CountingWebSocket socket = new CountingWebSocket();

        listener.onText(socket, "{\"type\":\"KITCHEN_CHANGED\",\"topic\":\"KITCHEN\"}", true);

        assertEquals(1, fired.get());
        assertEquals(1, socket.requested);
    }

    @Test
    void parseTopicReadsTopicField() {
        assertEquals("KITCHEN",
                WebSocketRealtimeClient.parseTopic("{\"type\":\"KITCHEN_CHANGED\",\"topic\":\"KITCHEN\"}"));
        assertEquals("FLOOR",
                WebSocketRealtimeClient.parseTopic("{\"type\":\"FLOOR_CHANGED\",\"topic\":\"FLOOR\"}"));
    }

    @Test
    void parseTopicReturnsNullWhenAbsentOrMalformed() {
        assertEquals(null, WebSocketRealtimeClient.parseTopic("{\"type\":\"X\"}"));
        assertEquals(null, WebSocketRealtimeClient.parseTopic("not json"));
    }

    /** Minimal no-op {@link WebSocket} that counts request(n) calls. */
    private static final class CountingWebSocket implements WebSocket {
        int requested;
        @Override public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) { return null; }
        @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) { return null; }
        @Override public CompletableFuture<WebSocket> sendPing(ByteBuffer message) { return null; }
        @Override public CompletableFuture<WebSocket> sendPong(ByteBuffer message) { return null; }
        @Override public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) { return null; }
        @Override public void request(long n) { requested += (int) n; }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isOutputClosed() { return false; }
        @Override public boolean isInputClosed() { return false; }
        @Override public void abort() { }
    }
}
