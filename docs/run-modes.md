# POS Run Modes

The same `pos.jar` runs in two persistence modes, selected by Spring profile.

## Store-server (recommended, >= 2 registers)

PostgreSQL is the local source of truth; Flyway owns the schema.

```bash
java -jar target/pos.jar \
  --spring.profiles.active=store-server \
  --POS_DB_URL=jdbc:postgresql://localhost:5432/pos \
  --POS_DB_USER=pos \
  --POS_DB_PASSWORD=•••
```

## Embedded (single register)

SQLite in-process; Hibernate manages the schema. Flyway is disabled (Flyway 10
has no first-class SQLite support — see the Phase 0 plan's Design note). Set a
file path for durable storage:

```bash
java -jar target/pos.jar \
  --spring.profiles.active=embedded \
  --POS_DB_URL=jdbc:sqlite:file:/var/lib/pos/pos.db
```

> Encryption at rest (SQLCipher for SQLite, TDE for PostgreSQL) is added in the
> security-hardening pass; it is not part of Phase 0.
