# Phase 12 — Kitchen Station Routing (Design)

**Date:** 2026-07-07
**Status:** Approved (brainstorming), pending implementation plan
**Phase:** Phase 12 — third slice of the restaurant-floor track
(`dining (tables/orders)` → `menu (modifiers/variants)` → **kitchen routing** → split billing)

## Goal

Turn captured order lines into kitchen output. A server builds a dine-in order
(Phase 10) with modifiers (Phase 11), then **fires** it: the newly-added lines
are stamped as sent, routed to their prep **stations** (Grill, Fryer, Bar, …),
and a ticket is printed per station on a dedicated kitchen printer. Firing is
**incremental** — fire the starters now, add and fire the mains later — and a
fired line is locked so the kitchen never cooks something the ticket no longer
reflects.

This slice finally consumes the `note` and `course` data that Phases 10–11
captured but left dormant, and it introduces the first store-local **station**
configuration.

## Scope

**In scope (phase-12 core):**

- **Explicit incremental fire action** — `POST /dining/orders/{id}/fire` sends
  every currently-un-fired line to the kitchen, stamping each with a `firedAt`
  timestamp. Firing again after adding more lines routes **only** the new lines.
- **Per-line fired state & locking** — a line is either un-fired (editable) or
  fired (locked). `updateLine` / `removeLine` reject a fired line; `addLine`
  always adds an un-fired line.
- **POS-local station overlay** — a MANAGER/ADMIN-managed map of `sku →
  station name`, mirroring the way the `menu` module overlays SKUs. Any SKU with
  no mapping falls back to a configurable default station.
- **Station-grouped ticket printing** — one fire produces one ticket **per
  station involved**, printed to a dedicated `KitchenPrinter` port. Each ticket
  shows the station, table label, fired time, and each line as `qty × name`
  with modifiers and note and course.
- **Async, resilient routing** — firing publishes a `KitchenTicketsFired` fact;
  a passive `@ApplicationModuleListener` in the new `kitchen` module prints
  after the fire commits, in its own transaction, through the Spring Modulith
  transactional outbox. A momentarily-dead printer never blocks or rolls back a
  fire.

**Explicitly deferred (not this phase):**

- **Cancel / re-fire tickets.** Editing or removing a line already sent to the
  kitchen is rejected, not reprinted as a correction. Voiding an order whose
  lines are already fired does **not** notify the kitchen to stop — staff handle
  that verbally. A "MODIFIED" / "CANCEL" reprint flow is a later increment.
- **Kitchen Display System (KDS).** No screen, no queryable/bumpable ticket
  state. Output is print-only. Ticket state is not persisted beyond the fired
  timestamp on the order line.
- **Per-station physical printers.** One logical kitchen printer for now; all
  station tickets print to it (station identity is the ticket header). A real
  second device can bind to the port later without touching consumers.
- **Configurable course vocabulary.** `CourseTag` stays the fixed
  `STARTER / MAIN / DESSERT / DRINK` enum. Routing is by station, not course, so
  the `dining.course.tags` config key deferred in Phase 10 stays deferred
  (YAGNI).
- **Print-time idempotency / dedupe.** At-least-once outbox delivery means a
  listener replay can reprint a ticket — the same known gap the `inventory` and
  `cashdrawer` local listeners carry today.

## Architecture

A new **`kitchen`** module owns station configuration and printing. **`dining`**
gains the fire action and per-line fired state. The two communicate by the
established rule — *events for facts, calls for queries*:

- `dining.fireOrder(...)` marks lines fired, then **publishes**
  `KitchenTicketsFired` (a self-contained fact carrying the fired-line
  snapshot).
- `kitchen` **listens** to that event, resolves each line's station from its own
  overlay, groups by station, and prints.

`dining` never learns what a station is. `kitchen` never reaches into orders —
it consumes only the event. The dependency is one-way (`kitchen → dining :: api`),
so the module graph stays acyclic.

```
dining  ──publishes──▶  KitchenTicketsFired (dining :: api)
                                │
                                ▼  @ApplicationModuleListener (after commit, async, own tx)
kitchen  ──resolves station (own overlay) ──▶ groups by station ──▶ KitchenPrinter (device :: api)
```

### The fire action (dining changes)

- `OrderLine` domain entity gains **`Instant firedAt`** (nullable). `null` =
  un-fired and editable; non-null = fired at that instant and locked.
- New facade method:

  ```java
  OrderView fireOrder(UUID orderId, String firedBy)
  ```

  - Guard: order must be `OPEN`.
  - Stamps `firedAt = now` on every currently-un-fired line.
  - Rejects with `DomainException.validation` if there are **no** un-fired lines
    ("nothing to fire").
  - Resolves each fired line's product **name** via `ProductCatalog`
    (`product :: api`, already an allowed dependency) and the order's
    **tableLabel** via the table registry.
  - Publishes `KitchenTicketsFired` with only the newly-fired lines.
  - Returns the updated `OrderView` (lines now carry `firedAt`).
- `updateLine` / `removeLine` now reject a line whose `firedAt != null`
  (`DomainException.validation`, "line already sent to kitchen"). Un-fired lines
  behave exactly as in Phase 10/11.
- `OrderStatus` is unchanged — an order stays `OPEN` through firing and only
  moves to `CLOSED` at checkout. `close`/`void` logic is untouched.
- `OrderLineView` gains `Instant firedAt`.
- Endpoint `POST /dining/orders/{id}/fire` — authenticated CASHIER and up
  (firing is routine floor work, not a manager action).

### The event (`dining :: api`)

```java
record KitchenTicketsFired(
    UUID orderId,
    String tableLabel,          // table's label, or "Takeaway" for a table-less QUICK_SERVICE order
    Instant firedAt,
    List<FiredLine> lines)

record FiredLine(
    String sku,
    String name,                // resolved from ProductCatalog at fire time
    BigDecimal qty,
    String note,                // nullable
    CourseTag course,           // nullable
    List<FiredModifier> modifiers)

record FiredModifier(String name)   // cooks need the label only — no price
```

Self-contained snapshot, exactly like `SaleCompleted`. No money crosses to the
kitchen — a cook's ticket carries name, quantity, modifiers, note, and course,
nothing priced.

### Station overlay (kitchen module)

- **One table:** `kitchen_station_assignment (sku, station_name)`. Stations are
  just names — no separate `Station` entity, no station lifecycle. A single
  store runs a handful of stations; a key→value overlay is the honest model
  (YAGNI, contrast the richer `menu` group/option entities which had real
  structure).
- `KitchenService` facade:

  ```java
  StationAssignmentView assignSku(String sku, String stationName)   // MANAGER/ADMIN
  void unassignSku(String sku)                                       // MANAGER/ADMIN
  List<StationAssignmentView> listAssignments()                      // any auth
  String stationFor(String sku)     // assignment if present, else the default station
  ```

  `StationAssignmentView(String sku, String stationName)`.
- Unmapped SKUs resolve to the configurable default station
  (`KITCHEN_DEFAULT_STATION`, default `"Kitchen"`).
- Endpoints:
  - `POST   /kitchen/stations/assignments`  body `{sku, stationName}` — MANAGER/ADMIN
  - `DELETE /kitchen/stations/assignments/{sku}` — MANAGER/ADMIN
  - `GET    /kitchen/stations/assignments` — any auth

### Print path (kitchen listener + port)

- **`KitchenTicketsFiredListener`** — `@ApplicationModuleListener` on
  `KitchenTicketsFired`. For each fired line, resolves the station; groups lines
  by station; for each station builds a ticket and calls
  `KitchenPrinter.print(lines)` then `cut()`. One `print`+`cut` per station.
- Ticket layout (per station): a header line with the **station name**, a line
  with **table label** and **fired time**, then each order line as
  `qty × name`, its modifiers indented beneath, then the note and course tag if
  present.
- New port **`KitchenPrinter`** in `device :: api`, reusing the existing
  `PrintLine` record:

  ```java
  interface KitchenPrinter {
      void print(List<PrintLine> lines);
      void cut();
  }
  ```

  Fake **`InMemoryKitchenPrinter`** (in `device/infrastructure`) records each
  printed ticket in a list so a fire producing Grill + Fryer tickets is
  observable as two recorded tickets. Distinct from the receipt `Printer` port:
  it is separately addressable (a real second physical device binds here later
  with no consumer change) and separately testable — this is the "one kitchen
  printer" decision, modelled as its own logical port.

## Configuration

New typed key in the `configuration` settings store:

| Key | Default | Meaning |
|-----|---------|---------|
| `KITCHEN_DEFAULT_STATION` (`kitchen.default.station`) | `Kitchen` | Station header used for any SKU with no explicit assignment. |

Read through `configuration :: api`, env-overridable, per the project convention.

## Module boundaries

- New `kitchen/package-info.java`:
  `allowedDependencies = { "common", "database", "dining :: api", "device :: api", "configuration :: api" }`.
  `@NamedInterface("api")` on `kitchen/api/package-info.java`.
- `dining` dependency list is **unchanged** — the event lives in `dining`'s own
  `api`, and product-name / table-label resolution use the `product :: api` and
  local table registry it already has.
- `device :: api` gains the `KitchenPrinter` interface; `device` remains an
  OPEN/usable-by-anyone module as today.
- Graph stays acyclic: `dining` does not depend on `kitchen`.
- `ModularityTests` (`modules.verify()`) must stay green.

## Migrations

Per-module directories, **globally sequential** version numbers (next free after
V30):

- **V31** — `kitchen_station_assignment` table, `db/migration/kitchen/`. Store-server only.
- **V32** — add `fired_at` (nullable TIMESTAMP) to `order_line`, `db/migration/dining/`.

Register the `kitchen` migration path in `application-store-server.yml`. Embedded
mode (SQLite, Hibernate `ddl-auto`, Flyway disabled) picks up both from the
entities automatically.

## Testing

- **`DiningFireTest`** (`@SpringBootTest @ActiveProfiles("embedded")`):
  - Fire stamps `firedAt` on all un-fired lines and prints the correct ticket
    **per station** (assign SKUs to Grill/Fryer, fire, assert two recorded
    tickets with the right lines, quantities, modifiers, notes).
  - Second fire after adding a line routes **only** the new line (one new
    ticket), leaving already-fired lines untouched.
  - `updateLine` / `removeLine` on a fired line is rejected.
  - Firing with no un-fired lines is rejected.
- **`KitchenStationAssignmentTest`**: assign / unassign / list; unmapped SKU
  resolves to the default station; auth (MANAGER assigns → success, CASHIER →
  403).
- The listener is async (after-commit). Tests assert kitchen output using the
  **same await mechanism the existing `SaleCompleted`-listener tests use** in
  this repo (match the established convention — do not invent a new one).
- `ModularityTests` stays green with the new module and boundaries.

## Success criteria

1. A server can open a dine-in order, add lines, and fire — producing one
   printed kitchen ticket per involved station with correct qty / modifiers /
   note / course, grouped correctly.
2. Adding more lines and firing again routes only the new lines; already-fired
   lines are locked against edit/remove.
3. Station assignments are MANAGER/ADMIN-manageable; unmapped SKUs fall back to
   the configured default station.
4. A printer failure during firing does not roll back or block the fire; the
   fired state is durable and the print publication redelivers.
5. `./mvnw verify` is green (all tests + `ModularityTests`) in both persistence
   modes; V31/V32 validate under Testcontainers PostgreSQL.
