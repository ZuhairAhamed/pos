CREATE TABLE payment (
    id              VARCHAR(36) PRIMARY KEY,
    sale_id         VARCHAR(36) NOT NULL,
    method          VARCHAR(16) NOT NULL,
    amount          NUMERIC(19, 2) NOT NULL,
    amount_tendered NUMERIC(19, 2) NOT NULL,
    change_due      NUMERIC(19, 2) NOT NULL,
    currency_code   VARCHAR(3) NOT NULL,
    created_at      TIMESTAMP NOT NULL
);

CREATE INDEX idx_payment_sale ON payment (sale_id);
