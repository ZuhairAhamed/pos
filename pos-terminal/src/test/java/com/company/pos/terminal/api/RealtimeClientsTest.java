package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import com.company.pos.terminal.config.TerminalConfig;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class RealtimeClientsTest {

    private TerminalConfig config(boolean enabled) {
        Properties p = new Properties();
        p.setProperty("realtime.enabled", Boolean.toString(enabled));
        return TerminalConfig.from(p);
    }

    @Test
    void enabledConfigYieldsAFreshWebSocketClientEachCall() {
        TerminalConfig c = config(true);
        SessionManager session = new SessionManager();
        RealtimeClient a = RealtimeClients.create(c, session);
        RealtimeClient b = RealtimeClients.create(c, session);
        assertInstanceOf(WebSocketRealtimeClient.class, a);
        assertNotSame(a, b, "each screen must get its own client instance");
    }

    @Test
    void disabledConfigYieldsANoopClient() {
        RealtimeClient client = RealtimeClients.create(config(false), new SessionManager());
        assertInstanceOf(NoopRealtimeClient.class, client);
        // Safe no-ops: neither call throws.
        client.connect(() -> {});
        client.close();
    }
}
