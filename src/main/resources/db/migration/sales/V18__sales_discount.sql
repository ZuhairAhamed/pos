ALTER TABLE sale_line ADD COLUMN gross_amount NUMERIC(19, 2) NOT NULL DEFAULT 0;
ALTER TABLE sale_line ADD COLUMN line_discount_amount NUMERIC(19, 2) NOT NULL DEFAULT 0;
ALTER TABLE sale_line ADD COLUMN line_discount_type VARCHAR(8);
ALTER TABLE sale_line ADD COLUMN line_discount_reason VARCHAR(32);

-- Existing rows predate discounts: their gross equals their (undiscounted) net.
UPDATE sale_line SET gross_amount = net_amount;

ALTER TABLE sale ADD COLUMN txn_discount_amount NUMERIC(19, 2) NOT NULL DEFAULT 0;
ALTER TABLE sale ADD COLUMN txn_discount_type VARCHAR(8);
ALTER TABLE sale ADD COLUMN txn_discount_reason VARCHAR(32);
ALTER TABLE sale ADD COLUMN discount_total NUMERIC(19, 2) NOT NULL DEFAULT 0;
