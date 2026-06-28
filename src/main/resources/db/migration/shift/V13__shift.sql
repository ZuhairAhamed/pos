CREATE TABLE shift (
    id                VARCHAR(36) PRIMARY KEY,
    terminal_id       VARCHAR(16) NOT NULL,
    opened_by         VARCHAR(100) NOT NULL,
    status            VARCHAR(16) NOT NULL,
    drawer_session_id VARCHAR(36) NOT NULL REFERENCES drawer_session (id),
    currency_code     VARCHAR(3) NOT NULL,
    counted_cash      NUMERIC(19, 2),
    closed_by         VARCHAR(100),
    opened_at         TIMESTAMP NOT NULL,
    closed_at         TIMESTAMP
);

CREATE INDEX idx_shift_terminal_status ON shift (terminal_id, status);
