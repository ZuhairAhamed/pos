CREATE TABLE sales_return (
    id                 VARCHAR(36) PRIMARY KEY,
    credit_note_number VARCHAR(40) NOT NULL UNIQUE,
    original_sale_id   VARCHAR(36) NOT NULL,
    store_id           VARCHAR(16) NOT NULL,
    terminal_id        VARCHAR(16) NOT NULL,
    manager_username   VARCHAR(100) NOT NULL,
    location_code      VARCHAR(32) NOT NULL,
    status             VARCHAR(16) NOT NULL,
    currency_code      VARCHAR(3) NOT NULL,
    refund_subtotal    NUMERIC(19, 2) NOT NULL,
    refund_tax_total   NUMERIC(19, 2) NOT NULL,
    refund_grand_total NUMERIC(19, 2) NOT NULL,
    created_at         TIMESTAMP NOT NULL
);

CREATE TABLE sales_return_line (
    id               VARCHAR(36) PRIMARY KEY,
    sales_return_id  VARCHAR(36) NOT NULL REFERENCES sales_return (id),
    line_no          INTEGER NOT NULL,
    original_line_no INTEGER NOT NULL,
    sku              VARCHAR(64) NOT NULL,
    name             VARCHAR(300) NOT NULL,
    quantity         NUMERIC(19, 3) NOT NULL,
    unit_price       NUMERIC(19, 4) NOT NULL,
    net_amount       NUMERIC(19, 2) NOT NULL,
    tax_amount       NUMERIC(19, 2) NOT NULL,
    line_total       NUMERIC(19, 2) NOT NULL,
    currency_code    VARCHAR(3) NOT NULL
);
