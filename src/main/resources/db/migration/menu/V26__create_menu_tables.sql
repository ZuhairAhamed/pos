CREATE TABLE modifier_group (
    id             VARCHAR(36) PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    min_selections INTEGER NOT NULL,
    max_selections INTEGER NOT NULL,
    active         BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE modifier_option (
    id          VARCHAR(36) PRIMARY KEY,
    group_id    VARCHAR(36) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    price_delta NUMERIC(19, 4) NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_modifier_option_group FOREIGN KEY (group_id) REFERENCES modifier_group (id)
);
CREATE INDEX idx_modifier_option_group ON modifier_option (group_id);

CREATE TABLE modifier_group_assignment (
    id       VARCHAR(36) PRIMARY KEY,
    group_id VARCHAR(36) NOT NULL,
    sku      VARCHAR(64) NOT NULL,
    CONSTRAINT uq_mga_group_sku UNIQUE (group_id, sku),
    CONSTRAINT fk_mga_group FOREIGN KEY (group_id) REFERENCES modifier_group (id)
);
CREATE INDEX idx_mga_sku ON modifier_group_assignment (sku);

CREATE TABLE variant_group (
    id     VARCHAR(36) PRIMARY KEY,
    name   VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE variant_member (
    id               VARCHAR(36) PRIMARY KEY,
    variant_group_id VARCHAR(36) NOT NULL,
    sku              VARCHAR(64) NOT NULL,
    display_label    VARCHAR(100) NOT NULL,
    CONSTRAINT fk_variant_member_group FOREIGN KEY (variant_group_id) REFERENCES variant_group (id)
);
CREATE INDEX idx_variant_member_group ON variant_member (variant_group_id);
