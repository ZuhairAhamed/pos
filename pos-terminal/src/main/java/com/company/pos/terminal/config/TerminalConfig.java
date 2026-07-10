package com.company.pos.terminal.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class TerminalConfig {
    private final String serverBaseUrl;
    private final String terminalId;
    private final String storeId;
    private final int pollIntervalSeconds;

    private TerminalConfig(Properties p) {
        this.serverBaseUrl = p.getProperty("server.base-url", "http://localhost:8080");
        this.terminalId = p.getProperty("terminal.id", "T01");
        this.storeId = p.getProperty("store.id", "S01");
        String rawInterval = p.getProperty("poll.interval.seconds", "5");
        try {
            this.pollIntervalSeconds = Integer.parseInt(rawInterval);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "poll.interval.seconds must be an integer, got: " + rawInterval, e);
        }
    }

    public static TerminalConfig from(Properties p) {
        return new TerminalConfig(p);
    }

    /** Load bundled defaults, then overlay any matching JVM system properties. */
    public static TerminalConfig load() {
        Properties p = new Properties();
        try (InputStream in = TerminalConfig.class.getResourceAsStream("/pos-terminal.properties")) {
            if (in != null) p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read pos-terminal.properties", e);
        }
        for (String key : new String[]{"server.base-url", "terminal.id", "store.id", "poll.interval.seconds"}) {
            String override = System.getProperty(key);
            if (override != null) p.setProperty(key, override);
        }
        return new TerminalConfig(p);
    }

    public String serverBaseUrl() { return serverBaseUrl; }
    public String terminalId() { return terminalId; }
    public String storeId() { return storeId; }
    public int pollIntervalSeconds() { return pollIntervalSeconds; }
}
