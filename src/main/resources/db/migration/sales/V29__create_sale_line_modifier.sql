CREATE TABLE sale_line_modifier (
    id           VARCHAR(36) PRIMARY KEY,
    sale_line_id VARCHAR(36) NOT NULL,
    option_id    VARCHAR(36) NOT NULL,
    name         VARCHAR(100) NOT NULL,
    price_delta  NUMERIC(19, 4) NOT NULL,
    CONSTRAINT fk_sale_line_modifier_line FOREIGN KEY (sale_line_id) REFERENCES sale_line (id)
);
CREATE INDEX idx_sale_line_modifier_line ON sale_line_modifier (sale_line_id);
