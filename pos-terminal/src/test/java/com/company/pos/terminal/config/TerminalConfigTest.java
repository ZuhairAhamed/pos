package com.company.pos.terminal.config;

import org.junit.jupiter.api.Test;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class TerminalConfigTest {

    @Test
    void readsValuesFromProperties() {
        Properties p = new Properties();
        p.setProperty("server.base-url", "http://store:9000");
        p.setProperty("terminal.id", "T07");
        p.setProperty("store.id", "S02");
        p.setProperty("poll.interval.seconds", "3");

        TerminalConfig cfg = TerminalConfig.from(p);

        assertEquals("http://store:9000", cfg.serverBaseUrl());
        assertEquals("T07", cfg.terminalId());
        assertEquals("S02", cfg.storeId());
        assertEquals(3, cfg.pollIntervalSeconds());
    }

    @Test
    void pollIntervalFallsBackToFiveWhenMissing() {
        TerminalConfig cfg = TerminalConfig.from(new Properties());
        assertEquals(5, cfg.pollIntervalSeconds());
    }
}
