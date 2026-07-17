package com.company.pos.terminal.api;

/** A {@link RealtimeClient} that does nothing — returned when {@code realtime.enabled=false} so
 *  consumers can call {@code connect}/{@code close} unconditionally without a feature-flag check. */
public final class NoopRealtimeClient implements RealtimeClient {

    @Override
    public void connect(Runnable onMessage) {
        // no live push when disabled
    }

    @Override
    public void close() {
        // nothing to release
    }
}
