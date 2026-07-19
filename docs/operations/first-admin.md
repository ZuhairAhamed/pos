# Creating the first admin (production)

`store-server` deployments have **no seed path** — the `dev` profile's `DevUserSeeder`
(`manager`/`manager`) does not run in production. Before anyone can log in and use the
terminal's Admin → Staff screen to create the rest of the staff, one ADMIN account must
be inserted directly into the database, once.

## 1. Generate a BCrypt hash

The app verifies credentials with Spring's `BCryptPasswordEncoder`, which accepts the
`$2a$`/`$2b$`/`$2y$` bcrypt variants. Generate a hash for the chosen password (and,
optionally, a PIN):

Using `htpasswd` (from apache2-utils / httpd-tools):

```bash
htpasswd -bnBC 10 "" 'YOUR_PASSWORD' | tr -d ':\n'
```

Spring's `BCryptPasswordEncoder` accepts `$2a$`, `$2b$`, and `$2y$` bcrypt hashes directly, so no prefix rewriting is needed.

Or with Python:

```bash
python3 -c "import bcrypt; print(bcrypt.hashpw(b'YOUR_PASSWORD', bcrypt.gensalt(10)).decode())"
```

Repeat for the PIN if you want the first admin to also log in by cashier code + PIN.

## 2. Insert the admin row

Connect to the store-server Postgres database and run (substitute the hash from step 1;
the table is `app_user` — re-confirm against `db/migration/auth/`):

```sql
INSERT INTO app_user (id, username, display_name, password_hash, pin_hash,
                      cashier_code, roles, enabled)
VALUES (gen_random_uuid(), 'admin', 'Store Admin',
        '$2a$10$....replace.with.generated.hash....',
        NULL, NULL, 'ADMIN', true);
```

Notes:
- `roles` is a comma-separated list of role names (`RoleSetConverter`), e.g. `ADMIN` or
  `CASHIER,MANAGER,ADMIN`.
- `gen_random_uuid()` is a built-in on Postgres 13+ (no extension needed). On Postgres 12 or
  older it requires the `pgcrypto` extension (`CREATE EXTENSION IF NOT EXISTS pgcrypto;`).
  Alternatively, supply a literal UUID string for the `id` value (the column is `VARCHAR(36)`).
- Set `pin_hash` and `cashier_code` if you generated a PIN in step 1 (both, or neither).

## 3. Verify

```bash
curl -s -X POST "$POS_BASE_URL/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"YOUR_PASSWORD"}'
```

Expected: a `200` with a JWT token. Then log in on the terminal as `admin`, open
**Admin → Staff**, and create the rest of the staff there. This manual step is never
needed again.
