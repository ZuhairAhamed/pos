package com.company.pos.terminal.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * {@link RealtimeClient} over a JDK 21 {@link WebSocket}. Attaches the cashier's bearer token to
 * the handshake (re-read on every reconnect), routes each text frame to the {@code onTopic}
 * callback (with the parsed {@code topic} field, or {@code null} if absent/malformed), and
 * reconnects with a fixed backoff after a drop/error so a backend restart re-arms push. The floor
 * screen's fallback poller bridges any gap while disconnected.
 *
 * <p>Deliberately thin: only {@link #wsUri}, {@link #parseTopic}, and {@link FrameListener} carry
 * logic and are unit tested; the socket round-trip is exercised by the backend end-to-end test and
 * manual E2E (no WebSocket server exists in the terminal's headless test scope).
 */
public final class WebSocketRealtimeClient implements RealtimeClient {

    private static final System.Logger LOG = System.getLogger(WebSocketRealtimeClient.class.getName());
    private static final int RECONNECT_SECONDS = 5;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String url;
    private final SessionManager session;
    private final HttpClient http;
    private final ScheduledExecutorService reconnect =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-reconnect");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile WebSocket socket;
    private volatile Consumer<String> onTopic;

    public WebSocketRealtimeClient(String httpBaseUrl, String path, SessionManager session) {
        this(wsUri(httpBaseUrl, path), session,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    WebSocketRealtimeClient(String wsUrl, SessionManager session, HttpClient http) {
        this.url = wsUrl;
        this.session = session;
        this.http = http;
    }

    /** Maps an http(s) base URL to a ws(s) URL and appends {@code path} (trailing slash safe). */
    static String wsUri(String httpBaseUrl, String path) {
        String base = httpBaseUrl.endsWith("/")
                ? httpBaseUrl.substring(0, httpBaseUrl.length() - 1) : httpBaseUrl;
        if (base.startsWith("https://")) {
            base = "wss://" + base.substring("https://".length());
        } else if (base.startsWith("http://")) {
            base = "ws://" + base.substring("http://".length());
        }
        return base + path;
    }

    /** Extracts the {@code topic} field from a ping frame, or {@code null} if absent/malformed. */
    static String parseTopic(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            return node.hasNonNull("topic") ? node.get("topic").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void connect(Runnable onMessage) {
        connect((Consumer<String>) topic -> onMessage.run());
    }

    @Override
    public void connect(Consumer<String> onTopic) {
        this.onTopic = onTopic;
        openSocket();
    }

    private void openSocket() {
        if (closed.get()) {
            return;
        }
        WebSocket.Builder builder = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5));
        String token = session.token();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        builder.buildAsync(URI.create(url), new FrameListener(onTopic, this))
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        LOG.log(System.Logger.Level.DEBUG, "Floor socket connect failed; retrying", err);
                        scheduleReconnect();
                    } else {
                        this.socket = ws;
                    }
                });
    }

    void scheduleReconnect() {
        if (closed.get()) {
            return;
        }
        try {
            reconnect.schedule(this::openSocket, RECONNECT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // close() shut the scheduler down between the guard and the schedule — nothing to do.
        }
    }

    @Override
    public void close() {
        closed.set(true);
        WebSocket s = socket;
        if (s != null) {
            s.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
        reconnect.shutdownNow();
    }

    /** Package-visible for unit testing: routes each text frame to the callback (with parsed topic),
     *  then asks for one more. Reconnects via {@code owner} on close/error ({@code owner} may be
     *  null in tests). */
    static final class FrameListener implements WebSocket.Listener {
        private final Consumer<String> onTopic;
        private final WebSocketRealtimeClient owner;

        FrameListener(Consumer<String> onTopic, WebSocketRealtimeClient owner) {
            this.onTopic = onTopic;
            this.owner = owner;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            onTopic.accept(parseTopic(data.toString()));
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (owner != null) {
                owner.scheduleReconnect();
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (owner != null) {
                owner.scheduleReconnect();
            }
            return null;
        }
    }
}
