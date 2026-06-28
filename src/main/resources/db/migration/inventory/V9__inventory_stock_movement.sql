CREATE TABLE stock_movement (
    id             VARCHAR(36) PRIMARY KEY,
    sku            VARCHAR(64) NOT NULL,
    location_code  VARCHAR(32) NOT NULL,
    quantity_delta NUMERIC(19, 3) NOT NULL,
    reason         VARCHAR(24) NOT NULL,
    reference_id   VARCHAR(36),
    created_at     TIMESTAMP NOT NULL
);

CREATE INDEX idx_stock_movement_sku ON stock_movement (sku);
