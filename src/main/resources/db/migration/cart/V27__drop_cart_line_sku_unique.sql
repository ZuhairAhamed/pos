-- Modifiers allow two lines with the same sku but different selections, so the
-- (cart_id, sku) uniqueness no longer holds. cart_line keeps its own id PK.
ALTER TABLE cart_line DROP CONSTRAINT uq_cart_line_sku;
