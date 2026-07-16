# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A single-store, offline-first Point-of-Sale system: a **Spring Modulith modular monolith** (Java 21 + Spring Boot 3.3). One artifact runs in two persistence modes (PostgreSQL store-server, or embedded SQLite). The ERP link is treated as intermittent — the store keeps trading offline and drains queued work when the link returns. It covers both a **retail** track and a **restaurant** track (`dining`, `menu`, `kitchen`, `shift` modules), and now has a touch-first **JavaFX terminal** front-end (`pos-terminal/`, a separate REST thin-client build — see below). Architecture rationale lives in `plan/pos-architecture-combined.md`; per-phase behaviour and the live HTTP API surface are documented in `docs/run-modes.md`.

## Build, test, run

Maven (`./mvnw`), **not Gradle**. Requires **JDK 21** — the system default is 17, so set `JAVA_HOME` first:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
```

```bash
./mvnw verify                 # full build: compile + all tests + module-boundary verification (what CI runs)
./mvnw test                   # tests only
./mvnw -o ...                 # offline, once dependencies are cached
./mvnw test -Dtest=CheckoutServiceTest                       # one test class
./mvnw test -Dtest=CheckoutServiceTest#refusesShortTender    # one test method
./mvnw test -Dtest='com.company.pos.sales.*'                 # one module's tests
```

`ModularityTests` is the boundary enforcer — `modules.verify()` fails the build if any module reaches into another's internals. Run it after any cross-module change:

```bash
./mvnw test -Dtest=ModularityTests
```

Most tests are `@SpringBootTest @ActiveProfiles("embedded")` (in-memory SQLite, no Docker). The Testcontainers PostgreSQL tests (`DatabaseStoreServerTest`, etc.) need a running Docker daemon.

Running the packaged jar (see `docs/run-modes.md` for full flags):

```bash
java -jar target/pos.jar --spring.profiles.active=store-server --POS_DB_URL=... --POS_DB_USER=... --POS_DB_PASSWORD=...
java -jar target/pos.jar --spring.profiles.active=embedded     --POS_DB_URL=jdbc:sqlite:file:/var/lib/pos/pos.db
```

To run the full stack locally with seed data, start the backend with the `dev` profile (`--spring.profiles.active=embedded,dev`) — it registers `@Profile("dev")` seeders (`DevUserSeeder`, `DevCatalogueSeeder`); log in as `manager`/`manager`. Without `dev` there is no seed path (empty catalogue, no user-creation endpoint).

### The JavaFX terminal is a separate build

`pos-terminal/` is a standalone JavaFX REST thin-client (its own `pom.xml`, package `com.company.pos.terminal`) — it is **not** in the root reactor, so `./mvnw verify` does not touch it. Build/run it explicitly (it needs a running backend):

```bash
./mvnw -f pos-terminal/pom.xml clean test   # headless; no display/TestFX needed
./mvnw -f pos-terminal/pom.xml javafx:run    # launches the terminal UI
```

It holds no DB and no business rules — all authoritative totals come from the server (`SaleView` at checkout); the terminal only previews a client-side *estimated* subtotal. See `pos-terminal/README.md`.

**Terminal FX-threading convention (the recurring bug class — get it right):** ViewModel methods are **synchronous** on the calling thread and return plain values; the controller runs them off the FX thread via `FxTasks.run(work, onDone, onError)` and reads the result only in the FX-thread `onDone` (via a `holder` array). The *only* observable a VM writes off-thread is `errorMessage`, and only inside `ui.accept(...)`; plain fields — never the deferred observables — are the synchronous control-flow truth. Any inter-thread time source is an injected `Supplier<Instant> clock`, never inline `Instant.now()`. Every such VM needs an async-dispatcher regression test (a deferred, undrained `ui` dispatcher) — synchronous-only tests mask the bug.

## Module architecture (the part that needs reading multiple files)

Every module lives under `com.company.pos.<module>` and follows a light **hexagonal layout**. This layering is a hard convention — match it:

```
<module>/
├── api/             ← PUBLIC: facade interface(s), DTOs, published events. The ONLY thing other modules may import.
├── web/             ← @RestController (only modules with an HTTP surface)
├── application/     ← use-case services (@Transactional boundary), event listeners
├── domain/          ← entities, value objects — package-private
└── infrastructure/  ← Spring Data JPA repositories, adapters — package-private
```

**Boundaries are enforced, not just documented.** Two mechanisms, both checked by `ModularityTests`:

1. Each module's `package-info.java` declares its `allowedDependencies` (e.g. `sales` may use `cart :: api`, `pricing :: api`, `tax :: api`, `payment :: api`, `receipt :: api`, `configuration :: api`, `common`, `database`). A dependency not in that list fails the build.
2. The `api` (and `erp`) sub-packages are tagged `@NamedInterface` in their own `package-info.java`. A module may depend only on another module's **named interface**, never `domain`/`application`/`infrastructure`. `common` and `database` are `@ApplicationModule(type = OPEN)` — usable by anyone.

When you add a module dependency, you must update the consuming module's `allowedDependencies` AND the producing module must expose what you need through its `api`. Never import another module's JPA entity or repository — pass `api` DTOs.

**Two communication styles** (architecture principle: *"events for facts, calls for queries"*):

- **Synchronous** — call the target's `api` facade for an immediate result inside the same transaction (`ProductCatalog.findBySku`, `PricingService`, `TaxService`).
- **Asynchronous** — publish a domain event for state-change fan-out. `sales` orchestrates checkout and is ignorant of who reacts. `inventory`, `cashdrawer`, `sync`, `notification` are passive `@ApplicationModuleListener` subscribers — they run **after the sale commits, async, in their own transaction**, and must never block or roll back the publisher.

## Transactional outbox (the resilience core)

Domain events are delivered through the **Spring Modulith Event Publication Registry** — a transactional outbox. Each `(event, listener)` pair is written as an `event_publication` row *inside the publishing transaction*; its `completion_date` is stamped only when the listener succeeds. So:

- A side-effect failure (ERP offline, etc.) can never roll back a committed sale — it leaves an *incomplete* publication.
- Incomplete publications are redelivered on restart (`republish-outstanding-publications-on-restart=true`), on a schedule (store-server), and on demand via `POST /sync/erp/upload` (MANAGER-only).
- Idempotency makes at-least-once replay safe: ERP uploads dedupe on `saleId`; sales/movements are immutable facts.

Known, deliberate gaps (do not assume these exist): no retry cap / dead-letter, unbounded async executor, and local listeners (`inventory`/`cashdrawer`) are not yet idempotent. See the "operational limits" notes in `docs/run-modes.md` before touching this path.

## Conventions that bite if missed

- **Money is `BigDecimal` / JavaMoney (Moneta) — never `double`.** Shared helpers in `common/util/Monies.java`. Discount/refund allocation spreads proportionally across lines with the **last line absorbing the rounding remainder** so per-line shares sum exactly (see `docs/run-modes.md` Phase 4 & 5).
- **Authorization is method security**, not URL rules. `SecurityConfig` only permits `/auth/login` + `/auth/pin-login` and authenticates everything else; role checks (`ROLE_MANAGER` for returns, ERP sync, over-cap discounts) are `@PreAuthorize` on the application/web layer. Auth is stateless JWT (HS256 bearer); `POS_JWT_SECRET` must be ≥32 bytes in any real deployment.
- **Typed config lives in the `configuration` settings store**, not hardcoded. Keys like `tax.rate`, `store.id`, `DISCOUNT_CASHIER_MAX_PERCENT` are read through `configuration :: api` and are env-overridable. Add new tunables there.
- **Flyway migrations are per-module directories** under `src/main/resources/db/migration/<module>/`, but version numbers are **globally sequential** (`V1`…`V18`) across all modules — pick the next free number, not the next free number within a folder. Only `store-server` runs Flyway (each module's path is registered in `application-store-server.yml`); `embedded` uses Hibernate `ddl-auto` and disables Flyway (Flyway 10 lacks SQLite support).
- Hardware (`Printer`, `CashDrawer`, `PaymentTerminal`, `Emailer`, …) and the ERP (`ErpClient`) sit behind `device.api` / `integration.api` **ports**. Current implementations are in-memory fakes (`InMemoryPrinter`, `InMemoryEmailer`, `FakeErpClient`); real adapters replace them with no change to consuming modules. Tests assert against the fakes.

## Workflow notes

- Development proceeds in numbered phases on `phase-*` branches; each phase has a spec in `docs/superpowers/specs/` and a plan in `docs/superpowers/plans/`. Read the relevant plan before extending a feature.
- After any change, the two things to re-run are the affected module's tests and `ModularityTests`.
