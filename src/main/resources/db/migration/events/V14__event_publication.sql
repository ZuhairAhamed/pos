-- Spring Modulith Event Publication Registry (transactional outbox).
-- Schema must match org.springframework.modulith.events.jpa.JpaEventPublication so that
-- Hibernate `ddl-auto: validate` passes on the store-server profile. If validation fails,
-- adjust the column types below to match the type named in the Hibernate error (Step 6).
CREATE TABLE IF NOT EXISTS event_publication (
    id               UUID NOT NULL,
    listener_id      TEXT NOT NULL,
    event_type       TEXT NOT NULL,
    serialized_event TEXT NOT NULL,
    publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date  TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_event_publication_completion_date
    ON event_publication (completion_date);
CREATE INDEX IF NOT EXISTS idx_event_publication_listener_serialized
    ON event_publication (listener_id, serialized_event);
