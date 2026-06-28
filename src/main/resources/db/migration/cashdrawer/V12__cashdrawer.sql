CREATE TABLE drawer_session (
    id             VARCHAR(36) PRIMARY KEY,
    terminal_id    VARCHAR(16) NOT NULL,
    status         VARCHAR(16) NOT NULL,
    opening_float  NUMERIC(19, 2) NOT NULL,
    counted_amount NUMERIC(19, 2),
    currency_code  VARCHAR(3) NOT NULL,
    opened_by      VARCHAR(100) NOT NULL,
    opened_at      TIMESTAMP NOT NULL,
    closed_at      TIMESTAMP
);

CREATE INDEX idx_drawer_session_terminal_status ON drawer_session (terminal_id, status);

CREATE TABLE cash_movement (
    id          VARCHAR(36) PRIMARY KEY,
    session_id  VARCHAR(36) NOT NULL REFERENCES drawer_session (id),
    type        VARCHAR(16) NOT NULL,
    amount      NUMERIC(19, 2) NOT NULL,
    reference   VARCHAR(120),
    created_by  VARCHAR(100),
    created_at  TIMESTAMP NOT NULL
);

CREATE INDEX idx_cash_movement_session ON cash_movement (session_id);
