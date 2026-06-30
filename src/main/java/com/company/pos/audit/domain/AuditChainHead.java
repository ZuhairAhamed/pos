package com.company.pos.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The tip of a store's audit chain. A single row per store, locked while appending so concurrent
 *  terminals serialize and never claim the same prev_hash. */
@Entity
@Table(name = "audit_chain_head")
public class AuditChainHead {

    @Id
    @Column(name = "store_id")
    private String storeId;

    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    @Column(name = "last_hash", length = 64, nullable = false)
    private String lastHash;

    protected AuditChainHead() {
    }

    public AuditChainHead(String storeId, long lastSeq, String lastHash) {
        this.storeId = storeId;
        this.lastSeq = lastSeq;
        this.lastHash = lastHash;
    }

    public String getStoreId() {
        return storeId;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public String getLastHash() {
        return lastHash;
    }

    public void advance(long seq, String hash) {
        this.lastSeq = seq;
        this.lastHash = hash;
    }
}
