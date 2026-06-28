CREATE TABLE cart (
    id            VARCHAR(36) PRIMARY KEY,
    status        VARCHAR(16) NOT NULL,
    currency_code VARCHAR(3),
    created_at    TIMESTAMP NOT NULL
);

CREATE TABLE cart_line (
    id            VARCHAR(36) PRIMARY KEY,
    cart_id       VARCHAR(36) NOT NULL REFERENCES cart (id),
    line_no       INTEGER NOT NULL,
    sku           VARCHAR(64) NOT NULL,
    name          VARCHAR(300) NOT NULL,
    quantity      NUMERIC(19, 3) NOT NULL,
    unit_price    NUMERIC(19, 4) NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    CONSTRAINT uq_cart_line_sku UNIQUE (cart_id, sku)
);

CREATE INDEX idx_cart_line_cart ON cart_line (cart_id);
