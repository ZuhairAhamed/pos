CREATE TABLE dining_order_line (
    id       VARCHAR(36) PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL,
    sku      VARCHAR(64) NOT NULL,
    qty      NUMERIC(19, 3) NOT NULL,
    note     VARCHAR(500),
    course   VARCHAR(20),
    added_by VARCHAR(100) NOT NULL,
    added_at TIMESTAMP   NOT NULL,
    CONSTRAINT fk_dining_order_line_order FOREIGN KEY (order_id) REFERENCES dining_order (id)
);
CREATE INDEX idx_dining_order_line_order ON dining_order_line (order_id);
