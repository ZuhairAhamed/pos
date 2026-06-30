package com.company.pos.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_record")
public class AuditRecord {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "seq", nullable = false)
    private long seq;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private String action;

    @Column(name = "entity_ref")
    private String entityRef;

    @Column(columnDefinition = "text")
    private String payload;

    @Column(name = "prev_hash", length = 64, nullable = false)
    private String prevHash;

    @Column(length = 64, nullable = false)
    private String hash;

    protected AuditRecord() {
    }

    public AuditRecord(UUID id, long seq, String storeId, Instant occurredAt, String actor,
            String action, String entityRef, String payload, String prevHash, String hash) {
        this.id = id.toString();
        this.seq = seq;
        this.storeId = storeId;
        this.occurredAt = occurredAt;
        this.actor = actor;
        this.action = action;
        this.entityRef = entityRef;
        this.payload = payload;
        this.prevHash = prevHash;
        this.hash = hash;
    }

    public UUID getId() {
        return UUID.fromString(id);
    }

    public long getSeq() {
        return seq;
    }

    public String getStoreId() {
        return storeId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getEntityRef() {
        return entityRef;
    }

    public String getPayload() {
        return payload;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public String getHash() {
        return hash;
    }
}
