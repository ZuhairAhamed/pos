CREATE TABLE category (
    id          VARCHAR(36) PRIMARY KEY,
    code        VARCHAR(50) NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    erp_version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE product (
    id              VARCHAR(36) PRIMARY KEY,
    sku             VARCHAR(64) NOT NULL UNIQUE,
    name            VARCHAR(300) NOT NULL,
    category_id     VARCHAR(36),
    category_name   VARCHAR(200),
    barcode         VARCHAR(64),
    unit_of_measure VARCHAR(16),
    unit_price      NUMERIC(19, 4),
    currency_code   VARCHAR(3),
    erp_version     BIGINT NOT NULL DEFAULT 0,
    active          BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_product_name ON product (name);
