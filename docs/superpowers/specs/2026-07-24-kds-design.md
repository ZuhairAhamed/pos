# Kitchen Display System (KDS) — Design Spec

**Slice:** Terminal slice 17 (full-stack — backend + terminal)
**Branch:** `feat/terminal-ui-restaurant-slice`
**Date:** 2026-07-24
**Status:** Approved design, pending implementation plan

## Problem

The restaurant POS can *fire* an order to the kitchen (`POST /dining/orders/{id}/fire`),
but firing is **one-way**: `DefaultDiningService.fireOrder` publishes a `KitchenTicketsFired`
event, the `kitchen` module prints one paper ticket per station, and nothing else happens.
There is:

- No persisted record of a fired ticket (the `kitchen` module stores only SKU→station assignments).
- No prep-state tracking (`OrderLine.firedAt` is the only signal; there is no preparing/ready/bumped state).
- No feedback loop — servers cannot see that food is ready; cooks cannot clear tickets from a screen.

For a restaurant this is the single most important missing UI: fired orders effectively vanish
into a printer. This slice adds a real **Kitchen Display System** — a live, touch-driven ticket
board for the kitchen — and closes the loop back to the floor.

## Scope decisions (locked)

| Decision | Choice |
|---|---|
| Depth | **Full closed-loop**: persistent tickets + prep-state machine + bump/recall + push + KDS screen + floor feedback |
| Bump granularity | **Ticket-level** (one state per station ticket; one tap advances the whole ticket) |
| Access | **Home tile "Kitchen"**, visible to **any authenticated user** (no new role) |
| Ready feedback | **Table-map badge** — a "🔔 Ready" indicator on the table tile whose order has a ready ticket |
| Push mechanism | **Approach A** — reuse the existing `/ws/floor` socket with a `topic` tag (`FLOOR` \| `KITCHEN`) |
| Auto-cancel on void | **Yes** — voiding the source order cancels its non-terminal tickets |
| Audit | **No** — ticket transitions are not audited (operational, high-frequency; explicit non-goal) |
| Aging thresholds | **`configuration` settings** with terminal defaults |

## Key architectural constraint

`kitchen` already depends on `dining :: api` (it listens to `KitchenTicketsFired`). Therefore
`dining` **cannot** depend on `kitchen` — `ModularityTests` would reject the cycle. Consequence:
the Table Map's READY badge **cannot** come from the dining order summary. The terminal performs a
**client-side join** — it fetches `/kitchen/tickets`, derives the set of `orderId`s that have a
`READY` ticket, and overlays the badge onto the matching table tile. `kitchen` remains the sole
owner of ticket state, consistent with the codebase principle that the terminal re-fetches
authoritative state via REST.

## State machine (ticket-level)

```
FIRED ──advance──▶ PREPARING ──advance──▶ READY ──advance──▶ BUMPED
   ▲                   ▲                     │
   └──── recall ───────┴──────── recall ─────┘
        (recall: BUMPED▶READY, READY▶PREPARING, PREPARING▶FIRED)

CANCELLED  ◀── order voided (automatic, from any non-terminal state)
```

- **advance** — one tap; moves to the next state. The final advance (READY→BUMPED) is the "bump".
- **recall** — undo an accidental advance; steps back one state. Not allowed out of `CANCELLED`.
- **BUMPED** — terminal for the happy path; the card leaves the active board but stays recall-eligible
  for a short window (see `listActiveTickets`).
- **CANCELLED** — terminal; set automatically when the source dining order is voided, so killed food
  does not linger on the board.
- Each transition stamps a timestamp: `firedAt`, `preparingAt`, `readyAt`, `bumpedAt` (nullable).
  These drive the aging color and are retained for possible future prep-time analytics (not built here).

Transitions are guarded by an **expected current state** supplied by the caller (optimistic
concurrency): if two cooks tap the same card, the second call sees a state mismatch and is rejected
with a conflict, prompting the terminal to re-fetch. This mirrors the existing stale-safety pattern
(split-close ships quote-time line IDs).

## Backend design (`kitchen` module)

### Domain (package-private)

- **`KitchenTicket`** (aggregate root): `id` (UUID), `orderId` (UUID), `tableLabel` (String snapshot),
  `station` (String), `state` (`KitchenTicketState` enum), `firedAt`, `preparingAt`, `readyAt`,
  `bumpedAt` (Instant, nullable). Holds `lines` (`orphanRemoval = true`). Domain methods enforce the
  transition rules (`advance(expected, now)`, `recall(expected)`, `cancel(now)`).
- **`KitchenTicketLine`** (child): `id`, `ticketId`, `sku`, `name`, `qty` (BigDecimal), `note`,
  `course` (`CourseTag`), `modifiers` (element collection of snapshot names). All copied from the
  `KitchenTicketsFired.FiredLine` payload at creation time (immutable snapshot — never re-reads the
  live order, exactly like dining line snapshots).
- **`KitchenTicketState`** enum: `FIRED`, `PREPARING`, `READY`, `BUMPED`, `CANCELLED`.

**Idempotency:** natural key `(orderId, station, firedAt)`. The persist listener no-ops if a ticket
with that key already exists, so at-least-once redelivery of `KitchenTicketsFired` is safe. (A single
order fired multiple times — course by course — produces distinct `firedAt` values and therefore
distinct tickets.)

### Infrastructure

- **`KitchenTicketRepository`** (Spring Data JPA): find active tickets (state not in `BUMPED`/`CANCELLED`,
  or `BUMPED` within the recall window) ordered by `firedAt` ascending; find by natural key; find by
  `orderId` (for void-cancel).

### Application

- **`DefaultKitchenTicketService`** implements the new `KitchenTicketService` api facade.
- **`KitchenTicketRecordingListener`** — new `@ApplicationModuleListener` on `KitchenTicketsFired`.
  Groups fired lines by `stationFor(sku)`, persists one `KitchenTicket` per station (idempotent),
  then publishes `KitchenTicketChanged`. **The existing `KitchenTicketsFiredListener` (printing) is
  left untouched** — KDS is additive; printers keep printing. Each `(event, listener)` pair is its own
  outbox row, so the two listeners are independent.
- **`OrderVoidedListener`** — new `@ApplicationModuleListener` on `DiningFloorChanged`. On
  `FloorChangeType.ORDER_VOIDED`, cancels that order's non-terminal tickets and publishes
  `KitchenTicketChanged`. (`kitchen` already permits a `dining :: api` dependency, so this needs no
  new allowed-dependency entry.)

### API (`kitchen :: api`, `@NamedInterface`)

- **`KitchenTicketService`** facade:
  - `List<KitchenTicketView> listActiveTickets(Optional<String> station)` — not `BUMPED`/`CANCELLED`,
    plus tickets bumped within the recall window (`KITCHEN_TICKET_RECALL_WINDOW_SECONDS`), FIFO by `firedAt`.
  - `KitchenTicketView advance(UUID ticketId, KitchenTicketState expectedState)`
  - `KitchenTicketView recall(UUID ticketId, KitchenTicketState expectedState)`
- **`KitchenTicketView`** DTO: `id`, `orderId`, `tableLabel`, `station`, `state`, timestamps, `lines`
  (`KitchenTicketLineView`: `sku`, `name`, `qty`, `note`, `course`, `modifiers`).
- **`KitchenTicketChanged`** event: `record KitchenTicketChanged(UUID ticketId, UUID orderId, Instant at)`.
  Deliberately a **thin invalidation event with no `*ChangeType` enum** — it is consumed only by
  `realtime` (never by `audit`), so it does not trigger the exhaustive-switch/audit coupling. Ticket
  transitions are intentionally **not** audited.

### Web

- **`KitchenTicketController`** (authenticated; **not** manager-gated — kitchen operations):
  - `GET  /kitchen/tickets?station=` → active tickets (optional station filter)
  - `POST /kitchen/tickets/{id}/advance` body `{ "expectedState": "FIRED" }` → advanced ticket
  - `POST /kitchen/tickets/{id}/recall`  body `{ "expectedState": "READY" }` → recalled ticket
  - Expected-state mismatch → **409 Conflict** (terminal re-fetches).

### Persistence / Flyway

- New migration under `src/main/resources/db/migration/kitchen/` at global version **V36** (ceiling is
  V35): create `kitchen_ticket` and `kitchen_ticket_line` (+ element-collection table for modifiers).
  Store-server only; `embedded` auto-creates via Hibernate `ddl-auto` (Flyway disabled there).

### Configuration keys (new `SettingKey`s, env-overridable, with defaults)

- `KITCHEN_TICKET_WARN_SECONDS` (default `300`) — age at which a card turns amber.
- `KITCHEN_TICKET_ALERT_SECONDS` (default `600`) — age at which a card turns red.
- `KITCHEN_TICKET_RECALL_WINDOW_SECONDS` (default `180`) — how long a bumped ticket stays recall-eligible.

## Realtime design (Approach A)

- `realtime` adds `kitchen :: api` to its `allowedDependencies`.
- New **`KitchenTicketChangedListener`** consumes `KitchenTicketChanged` and broadcasts an invalidation
  ping on the **existing** `FloorWebSocketHandler` / `/ws/floor` socket.
- The ping payload gains a **`topic`** field: existing dining pings carry `topic: "FLOOR"`; kitchen pings
  carry `topic: "KITCHEN"`. Payload remains pure invalidation (no domain state) — the terminal re-fetches.
- Handshake JWT gating, `SecurityConfig`, and the single-handler design are **unchanged**.

## Terminal design (`pos-terminal`)

### Navigation & Home

- New **Kitchen** tile on `home.fxml` (visible to any authenticated user) → `navigator.toKitchen()`.
- `Navigator` gains `toKitchen()`.

### KDS screen — `KitchenDisplayController` + `kitchen-display.fxml`

- Full-screen board, **one column per station**, FIFO ticket cards. Each card shows table label,
  line items (qty × name), modifiers, course tag, and note.
- **Tap-to-advance**; a small **Recall** affordance on the card.
- **Aging color** (green → amber → red) computed from ticket age against the configured thresholds.
- **BUMPED** cards drop into a collapsed "recently bumped" strip for the recall window, then disappear.
- Implements `Navigator.Screen`; owns its **own** `Services.newRealtimeClient()` (per-screen client —
  avoids the shared one-shot-`close()` bug from slice 14) and closes it in `onLeave()`. Filters pings
  for `topic == "KITCHEN"` → re-fetch tickets. **~10s poll fallback** (kitchen is more time-sensitive
  than the 45s floor fallback).
- **FX-threading convention** (the recurring bug class): `KitchenDisplayViewModel` methods are
  synchronous and return plain values; the controller runs them off-thread via `FxTasks.run` and reads
  results only in the FX-thread `onDone` via a holder. Any inter-thread time source is an injected
  `Supplier<Instant> clock` (aging math), never inline `Instant.now()`. `onDone` never calls a blocking
  VM method; advance/recall re-kick a fresh `FxTasks` task rather than doing HTTP inside `onDone`.

### ViewModel — `KitchenDisplayViewModel`

- `loadTickets()` → fetch `/kitchen/tickets`, return the rows (synchronous).
- `advance(ticketId, expectedState)` / `recall(ticketId, expectedState)` → call the API (synchronous).
- Aging bucket computation (`GREEN`/`AMBER`/`RED`) from the injected clock and configured thresholds.
- Writes only `errorMessage` off-thread inside `ui.accept(...)`; plain fields are the synchronous truth.

### Table Map READY badge

- `TableMapViewModel` additionally fetches `/kitchen/tickets` and derives the set of `orderId`s with a
  `READY` ticket (**client-side join**).
- `TableMapController` overlays a "🔔 Ready" badge on tiles whose order is in that set.
- The table map reacts to **both** `FLOOR` and `KITCHEN` pings (re-fetch orders and/or tickets accordingly).

### Terminal API wrappers

- Extend the terminal `KitchenApi` (or a new `KitchenTicketApi`) with `listTickets(station?)`,
  `advance(ticketId, expectedState)`, `recall(ticketId, expectedState)`.
- `WebSocketRealtimeClient` gains `topic` awareness in the parsed frame (unit-testable).

## Error handling & edge cases

- **Concurrent tap** — `advance`/`recall` are guarded by `expectedState`; a mismatch returns 409 and the
  terminal shows a brief notice + re-fetches.
- **Redelivery** — ticket creation is idempotent on `(orderId, station, firedAt)`.
- **Order voided** — `OrderVoidedListener` cancels non-terminal tickets so the board clears killed food.
- **Order closed with unbumped tickets** — out of scope to auto-bump; a paid order's remaining tickets
  age out naturally (kitchen staff bump them). Revisit only if it proves noisy in practice.
- **No station assignment** — `stationFor(sku)` already falls back to the configured default station,
  so every fired line lands on some column.

## Testing strategy

**Backend (embedded SQLite, `@SpringBootTest @ActiveProfiles("embedded")`):**
- `KitchenTicketService` test: fire → one ticket per station; idempotent redelivery; legal transitions
  (FIRED→PREPARING→READY→BUMPED) and recall; illegal transition rejected; `expectedState` mismatch →
  conflict; void order → tickets `CANCELLED`.
- Recording-listener test: `KitchenTicketsFired` persists tickets grouped by `stationFor`, snapshots lines.
- Void-listener test: `DiningFloorChanged(ORDER_VOIDED)` cancels that order's active tickets.
- Web + auth test: the three endpoints, 409 on mismatch, authenticated access.
- Realtime E2E `@SpringBootTest(webEnvironment = RANDOM_PORT)`: a ticket transition delivers a
  `topic: "KITCHEN"` ping over the socket (mirrors the slice-14 floor E2E).
- `ModularityTests` green with the new `realtime → kitchen :: api` edge and updated `package-info`.

**Terminal (headless, `./mvnw -f pos-terminal/pom.xml test`):**
- `KitchenDisplayViewModel` test **including the async-dispatcher regression test** (a deferred, undrained
  `ui` dispatcher) — synchronous-only tests would mask the FX-threading bug class.
- Aging-bucket computation with an injected `Supplier<Instant> clock` (green/amber/red boundaries).
- Table-map badge derivation: orderIds with READY tickets → badge set.
- `WebSocketRealtimeClient` topic-filter unit test (`KITCHEN` vs `FLOOR`).

## Non-goals (YAGNI — deliberately deferred)

- Item-level (per-line) prep state — ticket-level only.
- A separate expo/runner screen — table-map badge only this slice.
- A dedicated `KITCHEN` role — any authenticated user.
- Auditing ticket transitions — operational and high-frequency; explicitly not audited.
- Prep-time analytics / reports — timestamps are stored but no report is built here.
- Hold-and-fire / course-timing automation.
- KDS sound alerts or kitchen hardware changes.
- Auto-bump of an order's remaining tickets on paid-close.

## Module-boundary checklist (must hold for `ModularityTests`)

- `realtime` `allowedDependencies` gains `kitchen :: api`.
- `kitchen` continues to depend on `dining :: api` (for both `KitchenTicketsFired` and `DiningFloorChanged`)
  — no new edge needed; `dining` must **not** gain any dependency on `kitchen`.
- New public types (`KitchenTicketService`, `KitchenTicketView`, `KitchenTicketLineView`,
  `KitchenTicketState`, `KitchenTicketChanged`) live in `kitchen/api` and are covered by its
  `@NamedInterface`; domain/infrastructure stay package-private.
