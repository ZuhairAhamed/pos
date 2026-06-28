CREATE TABLE sale (
    id               VARCHAR(36) PRIMARY KEY,
    receipt_number   VARCHAR(40) NOT NULL UNIQUE,
    store_id         VARCHAR(16) NOT NULL,
    terminal_id      VARCHAR(16) NOT NULL,
    cashier_username VARCHAR(100) NOT NULL,
    location_code    VARCHAR(32) NOT NULL,
    status           VARCHAR(16) NOT NULL,
    currency_code    VARCHAR(3) NOT NULL,
    subtotal         NUMERIC(19, 2) NOT NULL,
    tax_total        NUMERIC(19, 2) NOT NULL,
    grand_total      NUMERIC(19, 2) NOT NULL,
    created_at       TIMESTAMP NOT NULL
);

CREATE TABLE sale_line (
    id            VARCHAR(36) PRIMARY KEY,
    sale_id       VARCHAR(36) NOT NULL REFERENCES sale (id),
    line_no       INTEGER NOT NULL,
    sku           VARCHAR(64) NOT NULL,
    name          VARCHAR(300) NOT NULL,
    quantity      NUMERIC(19, 3) NOT NULL,
    unit_price    NUMERIC(19, 4) NOT NULL,
    net_amount    NUMERIC(19, 2) NOT NULL,
    tax_amount    NUMERIC(19, 2) NOT NULL,
    line_total    NUMERIC(19, 2) NOT NULL,
    currency_code VARCHAR(3) NOT NULL
);

CREATE INDEX idx_sale_line_sale ON sale_line (sale_id);

CREATE TABLE sale_number_sequence (
    id         VARCHAR(64) PRIMARY KEY,
    next_value BIGINT NOT NULL
);
