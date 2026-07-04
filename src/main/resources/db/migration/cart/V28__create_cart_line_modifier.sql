CREATE TABLE cart_line_modifier (
    id          VARCHAR(36) PRIMARY KEY,
    line_id     VARCHAR(36) NOT NULL,
    option_id   VARCHAR(36) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    price_delta NUMERIC(19, 4) NOT NULL,
    CONSTRAINT fk_cart_line_modifier_line FOREIGN KEY (line_id) REFERENCES cart_line (id)
);
CREATE INDEX idx_cart_line_modifier_line ON cart_line_modifier (line_id);

ALTER TABLE cart_line ADD COLUMN base_price NUMERIC(19, 4);
UPDATE cart_line SET base_price = unit_price WHERE base_price IS NULL;
