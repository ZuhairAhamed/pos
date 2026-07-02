CREATE TABLE customer (
    id           VARCHAR(36)   NOT NULL PRIMARY KEY,
    name         VARCHAR(200)  NOT NULL,
    phone        VARCHAR(40),
    email        VARCHAR(200),
    address      VARCHAR(300),
    notes        TEXT,
    active       BOOLEAN       NOT NULL,
    external_id  VARCHAR(64),
    erp_version  BIGINT        NOT NULL,
    created_at   TIMESTAMP     NOT NULL,
    updated_at   TIMESTAMP     NOT NULL
);

CREATE INDEX ix_customer_phone ON customer (phone);
CREATE INDEX ix_customer_email ON customer (email);

CREATE TABLE customer_purchase (
    id             VARCHAR(36)   NOT NULL PRIMARY KEY,
    customer_id    VARCHAR(36)   NOT NULL,
    sale_id        VARCHAR(36)   NOT NULL,
    receipt_number VARCHAR(64),
    occurred_at    TIMESTAMP     NOT NULL,
    grand_total    NUMERIC(19,4) NOT NULL,
    currency_code  VARCHAR(3)    NOT NULL
);

CREATE UNIQUE INDEX ux_customer_purchase_sale ON customer_purchase (sale_id);
CREATE INDEX ix_customer_purchase_customer ON customer_purchase (customer_id);
