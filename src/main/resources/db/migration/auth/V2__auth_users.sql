CREATE TABLE app_user (
    id            VARCHAR(36) PRIMARY KEY,
    username      VARCHAR(100) NOT NULL UNIQUE,
    cashier_code  VARCHAR(20) UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    pin_hash      VARCHAR(100),
    display_name  VARCHAR(200) NOT NULL,
    roles         VARCHAR(200) NOT NULL,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE
);
