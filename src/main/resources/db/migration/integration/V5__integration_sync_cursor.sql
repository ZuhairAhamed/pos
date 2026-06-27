CREATE TABLE sync_cursor (
    stream       VARCHAR(64) PRIMARY KEY,
    cursor_value BIGINT NOT NULL
);
