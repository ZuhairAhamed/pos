package com.company.pos.support;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test helper: empties every application table in the shared in-memory SQLite used by the
 * embedded profile. Needed because the embedded DB (cache=shared) is one global database for the
 * whole JVM across all Spring test contexts, so committing (non-@Transactional) tests must reset
 * it before and after each test to avoid polluting other tests (e.g. advanced sync_cursor,
 * committed product rows). Registered only via @Import on the committing test classes.
 */
public class DatabaseCleaner {

    private final JdbcTemplate jdbc;

    public DatabaseCleaner(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public void clean() {
        List<String> tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' "
                        + "AND name NOT LIKE 'sqlite_%' AND name <> 'flyway_schema_history'",
                String.class);
        jdbc.execute("PRAGMA foreign_keys = OFF");
        for (String table : tables) {
            jdbc.execute("DELETE FROM \"" + table + "\"");
        }
        jdbc.execute("PRAGMA foreign_keys = ON");
    }
}
