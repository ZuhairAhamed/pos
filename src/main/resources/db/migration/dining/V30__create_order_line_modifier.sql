CREATE TABLE order_line_modifier (
    id            VARCHAR(36) PRIMARY KEY,
    order_line_id VARCHAR(36) NOT NULL,
    option_id     VARCHAR(36) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    price_delta   NUMERIC(19, 4) NOT NULL,
    CONSTRAINT fk_order_line_modifier_line FOREIGN KEY (order_line_id) REFERENCES dining_order_line (id)
);
CREATE INDEX idx_order_line_modifier_line ON order_line_modifier (order_line_id);
