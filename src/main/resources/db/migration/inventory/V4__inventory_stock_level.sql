CREATE TABLE stock_level (
    id               VARCHAR(36) PRIMARY KEY,
    sku              VARCHAR(64) NOT NULL,
    location_code    VARCHAR(32) NOT NULL,
    quantity_on_hand NUMERIC(19, 3) NOT NULL DEFAULT 0,
    erp_version      BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_stock_sku_location UNIQUE (sku, location_code)
);

CREATE INDEX idx_stock_sku ON stock_level (sku);
