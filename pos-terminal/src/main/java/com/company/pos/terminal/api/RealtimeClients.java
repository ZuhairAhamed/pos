package com.company.pos.terminal.api;

import com.company.pos.terminal.config.TerminalConfig;

/**
 * Builds a {@link RealtimeClient} per call. Each screen owns its own instance (connect on enter,
 * close on leave), because {@link WebSocketRealtimeClient#close()} is one-shot — a shared instance
 * dies after the first screen leaves. Returns a {@link NoopRealtimeClient} when push is disabled.
 */
public final class RealtimeClients {

    private RealtimeClients() {
    }

    public static RealtimeClient create(TerminalConfig config, SessionManager session) {
        if (!config.realtimeEnabled()) {
            return new NoopRealtimeClient();
        }
        return new WebSocketRealtimeClient(config.serverBaseUrl(), config.realtimePath(), session);
    }
}
