---
name: create-migration
description: Use when adding or changing a database table/column for a POS module — scaffolds a correctly named, correctly placed Flyway migration. Versions are globally sequential across all module folders.
disable-model-invocation: true
---

# Create a Flyway migration

This project keeps Flyway migrations in **per-module folders** under
`src/main/resources/db/migration/<module>/`, but version numbers are a **single
global sequence** shared across every folder (V1, V2, … V13 so far). PostgreSQL
(`store-server` profile) runs these; the SQLite `embedded` profile has Flyway
disabled and lets Hibernate manage its schema, so each migration must be
**PostgreSQL-correct**.

## Steps

1. **Find the next version number** — scan ALL module folders, not just one:
   ```bash
   find src/main/resources/db/migration -name '*.sql' | sed -E 's#.*/V([0-9]+)__.*#\1#' | sort -n | tail -1
   ```
   The new file is that number + 1.

2. **Pick the module folder** matching the owning module (e.g. `shift`,
   `cashdrawer`, `sales`). Create it if the module is new.

3. **Name the file** `V<n>__<module>_<snake_case_description>.sql`, e.g.
   `V14__shift_add_variance_reason.sql`. The `<module>` prefix in the
   description keeps names unique and greppable (existing files all follow
   this — see `V13__shift.sql`, `V10__payment_terminal_fields.sql`).

4. **Write PostgreSQL-valid DDL.** Use types that match existing migrations
   (read a neighbour file first for the column/money/timestamp conventions in
   use). Money is stored consistently with the JavaMoney/Moneta usage in the
   domain — check the entity before guessing column types.

5. **If you created a NEW module folder**, add it to the `flyway.locations`
   list in `src/main/resources/application-store-server.yml` (comma-separated,
   `classpath:db/migration/<module>`). A folder Flyway never scans is silently
   ignored.

6. **Verify** the migration applies:
   ```bash
   JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test
   ```
   Testcontainers spins up PostgreSQL and runs migrations during the test
   build, so a bad migration fails the suite.

## Gotchas
- Never edit an already-applied migration — add a new one (Flyway checksums).
- Keep DDL SQLite-agnostic only where the embedded profile relies on it; the
  migrations themselves target PostgreSQL.
- Don't reuse a version number across folders — the sequence is global.
