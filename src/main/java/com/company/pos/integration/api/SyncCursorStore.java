package com.company.pos.integration.api;

public interface SyncCursorStore {

    long get(String stream);

    void set(String stream, long value);
}
