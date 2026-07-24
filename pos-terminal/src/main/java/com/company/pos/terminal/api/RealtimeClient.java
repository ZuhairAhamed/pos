package com.company.pos.terminal.api;

import java.util.function.Consumer;

/**
 * A server→terminal push channel. {@link #connect(Runnable)} fires on every frame (topic ignored);
 * {@link #connect(Consumer)} receives the frame's {@code topic} field (or {@code null}) so a screen
 * can react selectively. Callbacks run on a transport thread — marshal UI work yourself.
 */
public interface RealtimeClient {

    void connect(Runnable onMessage);

    /** Topic-aware variant; default adapts the plain callback (topic always {@code null}). */
    default void connect(Consumer<String> onTopic) {
        connect(() -> onTopic.accept(null));
    }

    void close();
}
