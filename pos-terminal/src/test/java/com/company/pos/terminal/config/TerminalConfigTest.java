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

    @Test
    void nonNumericPollIntervalThrowsIllegalArgumentExceptionWithBadValue() {
        Properties p = new Properties();
        p.setProperty("poll.interval.seconds", "five");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> TerminalConfig.from(p));
        assertTrue(ex.getMessage().contains("five"),
                "Exception message should contain the bad value 'five'");
    }

    @Test
    void loadRespectsJvmSystemPropertyOverlay() {
        System.setProperty("terminal.id", "OVERRIDE1");
        try {
            TerminalConfig cfg = TerminalConfig.load();
            assertEquals("OVERRIDE1", cfg.terminalId());
        } finally {
            System.clearProperty("terminal.id");
        }
    }

    @Test
    void reducedMotionDefaultsFalse() {
        java.util.Properties p = new java.util.Properties();
        assertFalse(com.company.pos.terminal.config.TerminalConfig.from(p).reducedMotion());
    }

    @Test
    void reducedMotionReadsTrue() {
        java.util.Properties p = new java.util.Properties();
        p.setProperty("ui.reduced-motion", "true");
        assertTrue(com.company.pos.terminal.config.TerminalConfig.from(p).reducedMotion());
    }

    @Test
    void takeawayPrefixAndDwellDefaults() {
        TerminalConfig cfg = TerminalConfig.from(new Properties());
        assertEquals("Counter ", cfg.takeawayLabelPrefix());
        assertEquals(java.time.Duration.ofMinutes(45), cfg.dwellAttention());
    }

    @Test
    void takeawayPrefixAndDwellFromProperties() {
        Properties p = new Properties();
        p.setProperty("dining.takeaway.label-prefix", "TA-");
        p.setProperty("dining.dwell.attention.minutes", "20");
        TerminalConfig cfg = TerminalConfig.from(p);
        assertEquals("TA-", cfg.takeawayLabelPrefix());
        assertEquals(java.time.Duration.ofMinutes(20), cfg.dwellAttention());
    }

    @Test
    void nonNumericDwellThrowsWithBadValue() {
        Properties p = new Properties();
        p.setProperty("dining.dwell.attention.minutes", "soon");
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> TerminalConfig.from(p));
        assertTrue(ex.getMessage().contains("soon"));
    }
}
