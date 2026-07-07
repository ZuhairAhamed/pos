CREATE TABLE dining_order_sale (
    id       VARCHAR(36) PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL,
    sale_id  VARCHAR(36) NOT NULL
);
CREATE INDEX idx_dining_order_sale_order ON dining_order_sale (order_id);
