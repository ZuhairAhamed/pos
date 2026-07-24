CREATE TABLE kitchen_ticket (
    id           UUID PRIMARY KEY,
    order_id     UUID NOT NULL,
    table_label  VARCHAR(100) NOT NULL,
    station      VARCHAR(100) NOT NULL,
    state        VARCHAR(20) NOT NULL,
    fired_at     TIMESTAMP NOT NULL,
    preparing_at TIMESTAMP,
    ready_at     TIMESTAMP,
    bumped_at    TIMESTAMP
);

CREATE INDEX idx_kitchen_ticket_state ON kitchen_ticket (state);
CREATE INDEX idx_kitchen_ticket_order ON kitchen_ticket (order_id);
CREATE UNIQUE INDEX ux_kitchen_ticket_natural ON kitchen_ticket (order_id, station, fired_at);

CREATE TABLE kitchen_ticket_line (
    id        UUID PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES kitchen_ticket (id),
    sku       VARCHAR(64) NOT NULL,
    name      VARCHAR(200) NOT NULL,
    qty       NUMERIC(19,3) NOT NULL,
    note      VARCHAR(500),
    course    VARCHAR(32)
);

CREATE TABLE kitchen_ticket_line_modifier (
    line_id UUID NOT NULL REFERENCES kitchen_ticket_line (id),
    name    VARCHAR(200)
);
