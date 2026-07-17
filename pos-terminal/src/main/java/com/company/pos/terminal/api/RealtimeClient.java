package com.company.pos.terminal.api;

/**
 * A server→terminal push channel. {@link #connect} opens it and invokes {@code onMessage} on every
 * frame (on a background/transport thread — callers must marshal any UI work themselves). An
 * abstraction so the floor screen can depend on it and tests can inject a fake (a real socket
 * cannot be stood up in the terminal's headless test scope).
 */
public interface RealtimeClient {
    void connect(Runnable onMessage);
    void close();
}
