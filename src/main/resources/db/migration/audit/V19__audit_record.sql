CREATE TABLE audit_record (
    id           VARCHAR(36)   NOT NULL PRIMARY KEY,
    seq          BIGINT        NOT NULL,
    store_id     VARCHAR(64)   NOT NULL,
    occurred_at  TIMESTAMP     NOT NULL,
    actor        VARCHAR(128)  NOT NULL,
    action       VARCHAR(32)   NOT NULL,
    entity_ref   VARCHAR(128),
    payload      TEXT,
    prev_hash    CHAR(64)      NOT NULL,
    hash         CHAR(64)      NOT NULL
);

CREATE UNIQUE INDEX ux_audit_record_store_seq ON audit_record (store_id, seq);
CREATE INDEX ix_audit_record_occurred_at ON audit_record (occurred_at);
CREATE INDEX ix_audit_record_action ON audit_record (action);

CREATE TABLE audit_chain_head (
    store_id   VARCHAR(64)  NOT NULL PRIMARY KEY,
    last_seq   BIGINT       NOT NULL,
    last_hash  CHAR(64)     NOT NULL
);
