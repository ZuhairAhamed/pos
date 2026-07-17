# Terminal WebSocket Push (Live Floor Map) — Design

**Date:** 2026-07-17
**Branch:** `feat/terminal-ui-restaurant-slice` (continues the terminal slice sequence)
**Status:** Approved — ready for implementation plan.

## Problem

The JavaFX terminal's dining floor map (`TableMapController` / `TableMapViewModel`) is
kept fresh by a `Timeline` that re-fetches `GET /dining/orders` + `GET /dining/tables`
every `poll.interval.seconds` (default **5s**). With several terminals on one store that
is dozens of redundant requests per minute, and worse, a change made on one terminal
(open, transfer, merge, void, pay) is invisible on the others until the next tick. There
is no server→client push channel anywhere in the system today.

## Goal

When dining state changes on the server, connected terminals refresh their floor map
**immediately**, without per-terminal polling pressure — while a dropped connection still
self-heals.

## Approved decisions

1. **Transport: raw WebSocket.** JDK 21 ships a first-class WebSocket client
   (`HttpClient.newWebSocketBuilder()`), keeping the thin client idiomatic. Backend adds
   `spring-boot-starter-websocket` + one handler. (SSE has no first-class JDK client;
   STOMP needs a broker — both rejected.)
2. **Payload: invalidation ping.** A frame carries only "the floor changed" (plus a
   change-type/ids for logging). The terminal reacts by calling its **existing**
   `refresh()` REST fetch. The server stays the sole source of truth — no floor state is
   carried over the socket. (Event-carried state rejected: it would duplicate the REST
   DTOs and create a second, divergent truth.)
3. **Polling: retained as a slow fallback.** Default `poll.interval.seconds` raised
   **5 → 45**. Push handles the fast path; the slow poll self-heals a silently dropped
   socket. Also keeps the dwell-minute counter ticking without a state-change event.
4. **Scope: floor map only.** `TableMapController` consumes push. Open-order
   stale-detection (another cashier moves/merges/voids the order you are editing) is a
   deliberate follow-up slice — it drags in FX cross-thread edit-conflict cases.

## Architecture

```
dining (DefaultDiningService)                     realtime (NEW module)               pos-terminal
────────────────────────────                      ─────────────────────               ────────────
every mutating write ──publish──▶ DiningFloorChanged            │                            │
   (outbox, after-commit, async)        │                       │                            │
                                        └──@ApplicationModuleListener──▶ FloorWebSocketHandler.broadcast(json)
                                                                          │  (fan-out to all sessions)
                                                                          ▼   ws://…/ws/floor  (JWT on handshake)
                                                                        WebSocketRealtimeClient.onText
                                                                          │
                                                                          ▼  onMessage = () -> refresh()
                                                                        TableMapController.refresh()  (FxTasks → REST)
```

### Backend: the fact — `DiningFloorChanged`

- New record in **`dining.api`** (already `@NamedInterface("api")`), implementing
  `common.events.DomainEvent`:
  `record DiningFloorChanged(FloorChangeType change, UUID tableId, UUID orderId, Instant at)`
  plus enum `FloorChangeType { TABLE_REGISTERED, TABLE_DEACTIVATED, ORDER_OPENED,
  LINE_ADDED, LINE_UPDATED, LINE_REMOVED, ORDER_FIRED, ORDER_CLOSED, ORDER_SPLIT_CLOSED,
  ORDER_TRANSFERRED, ORDER_MERGED, ORDER_VOIDED }`.
- Published from **every mutating `DefaultDiningService` method** via a private helper
  `publishFloorChanged(change, tableId, orderId)`. Rationale for "every write": the floor
  tile derives from a re-fetch, so **over-refresh is harmless and under-refresh leaves
  stale tiles** — publishing uniformly on every write is simpler to reason about and
  future-proof (e.g. if the tile later shows fired/edited state). `tableId`/`orderId` may
  be null where not applicable (e.g. `deactivateTable` has only a tableId).
- Goes through the existing Spring Modulith outbox (`DomainEvents.publish`) exactly like
  `KitchenTicketsFired`: **after commit, async, own transaction** — a dead socket can
  never roll back or block a sale.

### Backend: the transport — new `realtime` module

`com.company.pos.realtime`, `allowedDependencies = { "common", "dining :: api" }`:

- **`infrastructure/FloorWebSocketHandler`** — `extends TextWebSocketHandler`. Holds a
  `CopyOnWriteArraySet<WebSocketSession>`; `afterConnectionEstablished` adds,
  `afterConnectionClosed` removes. `broadcast(String json)` sends a `TextMessage` to every
  **open** session, removing/skipping any that is closed or throws `IOException` (one dead
  terminal never blocks the others). It ignores inbound frames (push-only channel).
- **`infrastructure/WebSocketConfig`** — `@Configuration @EnableWebSocket implements
  WebSocketConfigurer`; registers the handler at **`/ws/floor`** with
  `setAllowedOriginPatterns("*")` (LAN terminals, no browser origin).
- **`application/DiningFloorChangedListener`** — `@ApplicationModuleListener void
  on(DiningFloorChanged e)` → serialises a tiny ping and calls `handler.broadcast(...)`.
  Ping shape: `{"type":"FLOOR_CHANGED","change":"ORDER_OPENED","at":"2026-…Z"}`
  (Jackson `ObjectMapper`). The terminal ignores the body today; the fields are for logs
  and future filtering.

### Backend: security

No `SecurityConfig` change needed. `/ws/floor` falls under `.anyRequest().authenticated()`,
so the handshake **HTTP GET** is authenticated by the existing OAuth2 resource-server JWT
filter. The terminal attaches `Authorization: Bearer <token>` to the handshake via
`WebSocket.Builder.header(...)`. An unauthenticated handshake is rejected (401) before the
upgrade. CSRF is already disabled globally.

### Terminal: the client — `RealtimeClient` seam

- **`api/RealtimeClient`** (interface): `void connect(Runnable onMessage)` and
  `void close()`. Lets the controller depend on an abstraction and lets tests inject a
  fake (the real socket cannot be stood up in the terminal's headless test scope — the
  existing `StubServer` is HTTP-only).
- **`api/WebSocketRealtimeClient`** (real impl): JDK `HttpClient.newWebSocketBuilder()`.
  - `wsUri(httpBaseUrl, path)` (static, pure, **unit-tested**): `http→ws`, `https→wss`,
    trailing-slash-safe, appends the path.
  - Reads the bearer from `SessionManager` at connect time and on every reconnect.
  - A `WebSocket.Listener` whose `onText(...)` runs `onMessage.run()` then
    `webSocket.request(1)` (**unit-tested** via a fake `WebSocket`).
  - On close/error, schedules a bounded-backoff reconnect on a single daemon thread so a
    backend restart re-establishes push; `close()` cancels reconnect and closes the socket.
  - **Thin by design**: the actual socket round-trip is not unit-tested (no WS server in
    scope); the two pure seams above are. This mirrors the project's "keep untestable
    adapters thin, test the seam" convention (cf. the in-memory device fakes).
- **`Services`** composition root constructs the `RealtimeClient` once and exposes it.

### Terminal: wiring — `TableMapController`

- `initialize()`: after the first `refresh()`, start the (now 45s) fallback poller **and**
  `realtimeClient.connect(this::refresh)`.
- `onLeave()`: stop the poller **and** `realtimeClient.close()`.
- **FX-threading:** `onMessage` fires on a WebSocket/HttpClient thread. It calls the
  controller's existing `refresh()`, which routes through `FxTasks.run(vm::refresh, …)`
  (background thread) and `vm.refresh()` marshals UI mutations via `ui.accept(...)`
  (`Platform.runLater`). The push callback therefore **never touches the scene graph
  directly** — it reuses the already-safe refresh path. No new observable is written
  off-thread.
- **Config:** `TerminalConfig` gains `realtimeEnabled()` (default `true`) and
  `realtimePath()` (default `/ws/floor`); `poll.interval.seconds` default becomes `45`.
  When `realtimeEnabled` is false, the controller skips `connect(...)` and the poller alone
  drives refresh (a clean escape hatch).

## Testing strategy

**Backend**
- **Publication** (`@SpringBootTest @ActiveProfiles("embedded") @RecordApplicationEvents`,
  service-level): each representative write (`openOrder`, `addLine`, `transferOrder`,
  `closeOrder`, `voidOrder`, `registerTable`) publishes exactly one `DiningFloorChanged`
  with the expected `change`.
- **Handler** (pure JUnit): `broadcast` reaches every open session; a session throwing
  `IOException` is skipped and the others still receive; a closed session is dropped.
- **Listener** (pure JUnit, capturing fake handler): `on(event)` broadcasts a non-empty
  JSON ping containing the change type.
- **End-to-end** (`@SpringBootTest(webEnvironment = RANDOM_PORT) @ActiveProfiles("embedded")`):
  seed users; a JDK WebSocket handshake **without** a token is rejected; **with** a valid
  token it connects and, after an autowired `DiningService` write, receives a frame within
  a generous await (`CountDownLatch`).

**Terminal** (`./mvnw -f pos-terminal/pom.xml clean test`, headless)
- `wsUri(...)` derivation: http→ws, https→wss, trailing slash, path appended.
- `onText(...)` invokes the callback once and requests one more frame (fake `WebSocket`).
- `TerminalConfig`: `realtime.enabled` / `realtime.path` parse with defaults; new
  `poll.interval.seconds` default is 45.
- Existing `TableMapViewModelTest` (incl. the deferred-dispatcher regression) stays green —
  the VM refresh path is unchanged.

**Manual E2E** (documented, not automated): backend `embedded,dev`; two terminals on the
floor map; open/transfer/void on one → the other updates within ~1s without a poll tick;
kill+restart backend → terminals reconnect and the 45s poll bridges the gap.

## Non-goals / deferred

- Open-order screen stale-detection (follow-up slice).
- Per-table topic subscriptions / filtering (one store, few terminals → single broadcast).
- Retry cap / dead-letter for the push listener (same known outbox gaps as today).
- Presence, typing indicators, or any client→server socket traffic (push-only).

## Module-boundary impact

- New module `realtime` (`{ "common", "dining :: api" }`). No existing module depends on
  it. `ModularityTests` must stay green.
- `dining.api` gains `DiningFloorChanged` + `FloorChangeType` (published interface).
- Root `pom.xml` gains `spring-boot-starter-websocket`.
- No Flyway migration, no new `configuration` setting, no `pos-terminal` Maven change
  (JDK WebSocket is built in).
