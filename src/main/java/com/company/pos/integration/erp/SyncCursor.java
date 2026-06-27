package com.company.pos.integration.erp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sync_cursor")
public class SyncCursor {

    @Id
    @Column(name = "stream", length = 64)
    private String stream;

    @Column(name = "cursor_value", nullable = false)
    private long value;

    protected SyncCursor() {
        // JPA
    }

    public SyncCursor(String stream, long value) {
        this.stream = stream;
        this.value = value;
    }

    public String getStream() {
        return stream;
    }

    public long getValue() {
        return value;
    }

    public void setValue(long value) {
        this.value = value;
    }
}
