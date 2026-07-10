# POS Terminal (JavaFX)

A standalone, touch-first **JavaFX desktop POS terminal** for the restaurant
seat-to-payment slice. It is a thin REST client: it holds no database and no
business rules — it talks to a running **store-server backend** over HTTP and
renders the screens (login → table map → order → payment). All authoritative
totals come from the server; the terminal only computes a client-side
*estimated* subtotal to preview before closing a check.

Package root: `com.company.pos.terminal`. Money is `BigDecimal` at scale 2.
There is one shared stylesheet, `src/main/resources/css/app.css`.

## Prerequisites

- **JDK 21** (the repo default toolchain is 17 — set `JAVA_HOME` first).
- A **running backend** exposing the store HTTP API (the modular monolith in
  this repo), reachable at the configured base URL. Either persistence mode
  works: `store-server` (PostgreSQL) or `embedded` (SQLite).

## Build & test

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -f pos-terminal/pom.xml clean test
```

Tests are headless (JavaFX ViewModels + API clients against an in-JVM stub
server); no display, TestFX, or `Application.launch` is required.

## Run

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -f pos-terminal/pom.xml javafx:run
```

Configuration comes from `src/main/resources/pos-terminal.properties` and can be
overridden per-launch with `-D` system properties (same keys):

| Key | Default | Meaning |
| --- | --- | --- |
| `server.base-url` | `http://localhost:8080` | Backend HTTP base URL |
| `terminal.id` | `T01` | This terminal's id |
| `store.id` | `S01` | Store id |
| `poll.interval.seconds` | `5` | Table-map live-refresh interval |

Point the terminal at a non-default backend either by editing
`pos-terminal.properties` or on the command line:

```bash
./mvnw -f pos-terminal/pom.xml javafx:run \
  -Dserver.base-url=http://192.168.1.20:8080 -Dterminal.id=T02
```

If the backend is unreachable, screens surface a clear error banner
("Cannot reach store server") rather than crashing.

## Seed data (backend prerequisites)

The terminal creates nothing itself — it consumes what the backend exposes. All
seeding is done **against the backend's HTTP API** (or its dev bootstrap), not
from the terminal. Endpoints below are stated at the endpoint level; assume the
standard method-security roles apply (MANAGER for registration/provisioning).

- **Users / login** — users are provisioned on the backend; there is no default
  user shipped in a migration. As an operator step, create a cashier and/or
  manager account on the backend, then log in from the terminal with those
  credentials (password) or the staff PIN. Do not assume a hardcoded default.

- **Dining tables** — register at least one active DINE_IN table:
  `POST /dining/tables` with a `RegisterTableCommand` body (MANAGER). This
  produces a free table that appears on the terminal's table map.

- **Products / catalog** — the catalog is **read-only over HTTP**
  (`GET /products`); products are populated by the backend's ERP down-sync. In a
  dev backend the `FakeErpClient` supplies sample products, so ensure the backend
  has a product catalog seeded (the dev backend does so via `FakeErpClient`). For
  a full walkthrough you want **at least a few products across ≥2 categories and
  one product that has a modifier group** so the modifier picker can be exercised.

> Assumption: exact request bodies (field names for `RegisterTableCommand`, the
> ERP sync trigger, modifier-group setup) are owned by the backend modules and
> are not re-verified here. If a value is unknown for your environment, treat it
> as an operator-provided placeholder.

## Service-charge caveat (important for this slice)

The terminal's estimated total (the client subtotal passed to the payment
screen) is **pre-service-charge**. If the backend has the DINE_IN service charge
**enabled**, the server's authoritative `grandTotal` will *exceed* the estimate,
and an exact-match cash tender computed from the estimate will be rejected as a
short tender (the cash short-tender guard compares tendered against the
estimate). For this slice:

- Run with the **service charge OFF** (the store default), **or**
- Tender **extra** cash to cover the added service charge.

Service-charge display on the entry screen is deferred; the confirmation screen
*does* show the authoritative `serviceChargeAmount` line when it is greater than
zero, sourced from the returned `SaleView`.

## Manual end-to-end walkthrough

With a backend running and seeded per above, launch the terminal (`javafx:run`)
and walk the flow:

1. **Login** — sign in as a cashier/server (password or PIN).
2. **Table map** — the floor shows free vs. occupied tables; it refreshes on the
   poll interval. Tap a **free** table to open a new check, or an occupied one to
   resume it.
3. **Order** — add items from the touch menu grid. For a product with a modifier
   group, pick the modifiers when prompted. Adjust quantities / remove lines as
   needed; the running estimated subtotal updates.
4. **Fire** — send the order to the kitchen. Fired lines lock (shown muted).
5. **Pay** — tap **Pay** to open the payment screen (estimated total shown):
   - **Cash** — enter cash tendered; a live **change** preview appears once the
     amount covers the estimate. A short tender is rejected inline *before* any
     server call. Tap **Take cash payment**.
   - **Card** — tap **Take card payment** (no tender field).
6. **Confirmation** — on a successful close the screen shows the **authoritative
   `SaleView` totals** (subtotal, tax, service charge when > 0, and a prominent
   grand total), plus the receipt number and change due for cash.
7. **Reprint** — tap **Reprint receipt** to re-issue the receipt server-side
   (the dev backend's `InMemoryPrinter` logs it).
8. **Back to the floor** — tap **Done — new table** to return to the table map;
   the just-closed table is free again.

If the backend is stopped mid-flow, actions surface the unreachable-server error
rather than proceeding.
