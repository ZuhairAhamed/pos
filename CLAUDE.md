# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

POS is a Spring Boot 3.3.5 / Java 21 modular-monolith point-of-sale system built
on Spring Modulith. A single `pos.jar` runs in two persistence modes selected by
Spring profile. The authoritative design docs are `plan/pos-architecture-combined.md`
and `plan/pos-requirement.md`; run/deployment details are in `docs/run-modes.md`.

## Build & test

Maven via the wrapper — **NOT Gradle**. Requires **JDK 21** (the system default
`java` is 17, which will fail the build — set `JAVA_HOME` first):

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"

./mvnw -q test-compile                      # fast compile check (main + test)
./mvnw -q test                              # full test suite
./mvnw -B verify                            # build + tests + module-boundary check (what CI runs)
./mvnw -q test -Dtest=ClassName             # single test class
./mvnw -q test -Dtest=ClassName#methodName  # single test method
./mvnw -q test -Dtest=ModularityTests       # verify Spring Modulith boundaries only
```

Tests use Testcontainers for PostgreSQL, so **Docker must be running** for the
suite (and `./mvnw verify`) to pass. CI (`.github/workflows/ci.yml`) runs
`./mvnw -B verify` on GitHub-hosted runners that provide Docker.

## Run

Default profile is `embedded` (SQLite, in-process; Flyway disabled, Hibernate
manages the schema). For multi-register stores use `store-server` (PostgreSQL,
Flyway-managed). Profile selection, env vars, and the API surface per phase are
documented in `docs/run-modes.md` — consult it rather than guessing flags.

## Architecture

16 Spring Modulith modules under `com.company.pos.*` (auth, cart, cashdrawer,
common, configuration, database, device, integration, inventory, payment,
pricing, product, receipt, sales, shift, tax). Each module follows a hexagonal
layout:

- `web/` — REST controllers
- `api/` — the module's public surface: service interface + `*View` DTOs.
  `api/package-info.java` declares `@NamedInterface("api")`.
- `application/` — implementation of the `api` interface + event listeners
- `domain/` — JPA entities and value objects (module-internal)
- `infrastructure/` — Spring Data repositories (module-internal)

**Module boundary rules (enforced by `ModularityTests` via `modules.verify()`):**

- A module may only reach another module through its `:: api` named interface —
  never its `domain`/`application`/`infrastructure` packages.
- Allowed dependencies are declared in each module root `package-info.java`:
  `@ApplicationModule(allowedDependencies = { "common", "database", "other :: api" })`.
- Prefer **domain events** over direct service calls for cross-module write-side
  coupling. Example: `cashdrawer`'s `SaleCompletedCashListener`
  (`@ApplicationModuleListener`) reacts to `sales`' `SaleCompleted` event.

Run `./mvnw -q test -Dtest=ModularityTests` after any structural change.

## Database & migrations

Flyway migrations live in **per-module folders** under
`src/main/resources/db/migration/<module>/`, but version numbers are a **single
global sequence** shared across all folders (V1…V13 currently — the next is V14,
not a per-folder reset). Naming convention: `V<n>__<module>_<snake_case_desc>.sql`.

- When adding a **new module folder**, append it to the comma-separated
  `flyway.locations` in `application-store-server.yml` — Flyway silently ignores
  folders not listed there.
- Migrations target **PostgreSQL**. The `embedded`/SQLite profile has Flyway
  disabled and lets Hibernate manage its schema, so write PostgreSQL-correct DDL.
- Never edit an already-applied migration (Flyway checksums) — add a new one.

The `/create-migration` skill automates the version/folder/naming choices.

## Conventions

- Money uses JavaMoney/Moneta (`MonetaryAmount`) throughout — never `double` or
  `float` for monetary values; rounding must be explicit.
- Compute totals/tax/change server-side; do not trust amounts from the client.
- Secrets and connection details come from env vars (`POS_JWT_SECRET`,
  `POS_DB_URL`/`POS_DB_USER`/`POS_DB_PASSWORD`, etc.); the values baked into
  `application.yml` are dev-only defaults.

## Claude tooling in this repo

- Skills: `/create-migration` (new Flyway migration), `/new-module` (scaffold a
  Spring Modulith module to the conventions above).
- Subagents: `security-reviewer` (auth/money/cash integrity) and
  `modulith-reviewer` (boundary/layering design review).
- A PostToolUse hook compiles (`test-compile`) on every `.java` edit; a
  PreToolUse hook blocks edits to `target/` and SQLite db files.
