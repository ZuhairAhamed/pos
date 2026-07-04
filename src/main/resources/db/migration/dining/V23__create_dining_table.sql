CREATE TABLE dining_table (
    id     VARCHAR(36) PRIMARY KEY,
    label  VARCHAR(60) NOT NULL UNIQUE,
    seats  INTEGER     NOT NULL,
    active BOOLEAN     NOT NULL DEFAULT TRUE
);
