CREATE TABLE dining_order (
    id           VARCHAR(36) PRIMARY KEY,
    table_id     VARCHAR(36) NOT NULL,
    service_type VARCHAR(20) NOT NULL,
    status       VARCHAR(20) NOT NULL,
    opened_by    VARCHAR(100) NOT NULL,
    opened_at    TIMESTAMP   NOT NULL,
    closed_at    TIMESTAMP,
    sale_id      VARCHAR(36),
    version      BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT fk_dining_order_table FOREIGN KEY (table_id) REFERENCES dining_table (id)
);
CREATE INDEX idx_dining_order_status ON dining_order (status);
