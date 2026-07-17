# Terminal Order Stale-Detection Push — Design

**Date:** 2026-07-17
**Branch:** `feat/terminal-ui-restaurant-slice` (continues the terminal slice sequence; slice 15)
**Status:** Approved — ready for implementation plan.

## Problem

The dine-in order screen (`OrderController` / `OrderViewModel`) loads once and then edits
locally — every mutation round-trips, but there is no live sync and no push. If another
terminal **voids, closes, or merges** the order a cashier is editing (all possible via
slices 10/12 and the split/pay paths), the cashier's screen goes stale: they can keep
adding lines to an order that no longer exists, and only discover it when their next action
errors or at payment. Slice 14 added the `/ws/floor` push channel but deliberately scoped
it to the floor map; the order screen was left as the explicit follow-up. This slice is
that follow-up.

## Latent bug this slice must also fix

Slice 14 wired a **single shared** `WebSocketRealtimeClient` as `Services.realtimeClient`,
and `WebSocketRealtimeClient.close()` sets a permanent `closed` flag (+ `shutdownNow()` on
its reconnect scheduler). `TableMapController.onLeave()` closes it. Re-entering the floor
map news up a fresh controller that calls `connect()` on the **already-dead** client, whose
`openSocket()` returns immediately on `closed`. **Result: live floor push works only on the
first floor-map visit per session; every later visit silently falls back to the 45s poll.**
Adding a second consumer (the order screen) forces the fix: per-screen client instances.

## Goal

While a cashier is on the order screen, detect within ~1s that the order was voided / closed
/ absorbed-by-merge on another terminal, lock the screen against further edits, and tell
them to return to the floor. And fix the shared-client lifecycle so both screens get live
push on every visit.

## Approved decisions

1. **Routing: re-check own order on any ping** (terminal-only). The order screen listens to
   the same `/ws/floor` channel and, on *any* floor ping, re-fetches `GET
   /dining/orders/{id}` and inspects the status. No backend change; reuses the existing
   `connect(Runnable)` seam. (Rejected: enriching the ping with `orderId` + routing — it
   would need a backend ping change *and* a shared-client API change for one consumer.)
   Extra GETs are bounded by coalescing (one check in flight at a time) and there is only
   one order screen per terminal.
2. **Stale UX: lock + banner + manual return.** On staleness, disable the editing controls
   and show a banner ("This order was changed on another terminal — return to tables"); the
   existing **Back to tables** button stays enabled as the return path. (Rejected:
   auto-navigating away — jarring mid-action.)
3. **On-OPEN pings: also refresh the lines.** If the re-check shows the order is still OPEN,
   rebuild the line list — so a merge that absorbed *another* order into this one (this
   order is the survivor) shows the added lines. (Rejected: lock-detection only — would miss
   merge-survivor additions.)
4. **Lifecycle: per-screen client via a factory.** Replace `Services.realtimeClient` with
   `Services.newRealtimeClient()`; each screen owns connect-on-enter / close-on-leave of its
   own instance. A `NoopRealtimeClient` is returned when `realtime.enabled=false`, so call
   sites drop their `if(enabled)` checks.

## Architecture

```
/ws/floor (existing, slice 14) ── any DiningFloorChanged ──▶ terminal RealtimeClient.onText
                                                                     │  (per-screen instance)
                            OrderController.onFloorPing ◀────────────┘
                                     │  FxTasks.run (coalesced, off FX thread)
                                     ▼
                            OrderViewModel.recheck()  →  GET /dining/orders/{id}
                                     │
                     ┌───────────────┼────────────────────────┐
              status OPEN        status != OPEN           ApiException
              rebuild lines      STALE                    UNKNOWN
                     │           (controller locks +      (transient — do
                     ▼            shows banner in onDone)   nothing, no lock)
              (onDone: no-op; lines
               re-render via listener)
```

### Terminal: factory + Noop client (fixes the lifecycle bug)

- **`RealtimeClients.create(TerminalConfig, SessionManager)`** (new, package-visible
  factory): returns a fresh `WebSocketRealtimeClient(config.serverBaseUrl(),
  config.realtimePath(), session)` when `config.realtimeEnabled()`, else a
  `NoopRealtimeClient`. Unit-testable without constructing full `Services`.
- **`NoopRealtimeClient implements RealtimeClient`**: `connect`/`close` are no-ops (used
  when push is disabled, so consumers never branch on the flag).
- **`Services`**: drop the `realtimeClient` field; add `RealtimeClient newRealtimeClient()`
  delegating to the factory.
- **`TableMapController`**: hold its own `RealtimeClient` field = `services.newRealtimeClient()`;
  `connect(this::refresh)` unconditionally in `initialize()` (the Noop covers the disabled
  case); `close()` its own instance in `onLeave()`. This is what makes re-visits reconnect.

### Terminal: order-screen stale detection

- **`OrderViewModel.recheck()`** → `RecheckResult` (enum `OPEN` / `STALE` / `UNKNOWN`):
  synchronous, on the calling thread.
  - Re-fetch `dining.order(order.id())`. On `ApiException` → return `UNKNOWN` (network blip
    or 404 — do **not** lock).
  - Set `order = refreshed` (status is now current). If `!"OPEN".equals(status)` → return
    `STALE` (leave the displayed lines as-is; they're about to be locked).
  - If OPEN → rebuild `lines()` + `subtotalText()` via `ui.accept(...)` (same marshalling as
    `apply(...)`), return `OPEN`.
- **`OrderController`** (now `implements Navigator.Screen`):
  - Holds `RealtimeClient realtime = services.newRealtimeClient()`.
  - After `vm.load(orderId)` in `afterCatalogLoaded`, `realtime.connect(this::onFloorPing)`.
  - `onFloorPing()` (fires on the WS thread): coalesced via an `AtomicBoolean checkInFlight`
    — skip if a check is already running or the screen is already stale. Otherwise
    `FxTasks.run(work = vm.recheck() into a holder, onDone = apply result, onError = log)`.
    In `onDone`: `STALE` → lock; `OPEN`/`UNKNOWN` → nothing extra (lines already refreshed
    inside `recheck`); always clear `checkInFlight`.
  - **Lock** = disable `lineBox`, `categoryTabs`, `fireButton`, `voidButton`, `moveButton`,
    `mergeButton`, `splitButton`, `payButton`; keep `backButton` enabled; show `staleBanner`
    with a status-specific message (`VOIDED` → "voided", `CLOSED` → "paid/closed", else
    "changed") read from `vm.currentOrder().status()`. Once locked, further pings are
    ignored.
  - `onLeave()` → `realtime.close()`.
- **`order.fxml`**: add a hidden `staleBanner` Label (`styleClass="stale-banner"`,
  `managed`/`visible` false) after `errorLabel`. **`app.css`**: a `.stale-banner` rule from
  the existing emerald/danger tokens. **`AppCssTest`**: assert `.stale-banner` exists
  (mirrors the slice-9 pattern so the scope can't silently rot).

### FX-threading (the codebase's recurring bug class)

`onFloorPing` fires on the WebSocket/HttpClient thread. The blocking `GET` runs in the
`FxTasks` **work** lambda (`vm.recheck()`); the lock is applied in `onDone` (FX thread) via
a holder; the OPEN line refresh happens inside `recheck` through `ui.accept(...)`
(`Platform.runLater`). Nothing writes an observable or touches a scene-graph node off the FX
thread. `recheck` gets the mandated deferred-dispatcher regression test.

## Testing strategy

**Terminal only** (`./mvnw -f pos-terminal/pom.xml clean test`, headless):
- `NoopRealtimeClient`: `connect`/`close` are safe no-ops.
- `RealtimeClients.create`: enabled config → a `WebSocketRealtimeClient` (distinct instance
  per call); disabled config → a `NoopRealtimeClient`.
- `OrderViewModel.recheck`: OPEN-with-an-added-line → `OPEN` + lines rebuilt; `VOIDED` →
  `STALE` + `currentOrder().status()=="VOIDED"`; `CLOSED` → `STALE`; `ApiException` →
  `UNKNOWN`, state intact, not locked; deferred-dispatcher regression (return value correct
  immediately, observable line update only after drain).
- `AppCssTest`: `.stale-banner` present.
- Existing `OrderViewModelTest` / `TableMapViewModelTest` stay green.

**Manual E2E** (documented): backend `embedded,dev`, two terminals. Terminal A opens table
T1's order and starts editing. Terminal B voids (or merges away) T1's order → within ~1s A's
screen locks with the banner and only "Back to tables" works. Separately: A visits floor map
→ opens an order → returns to floor map → confirm the floor map still receives live updates
(the slice-14 re-visit bug is fixed).

## Non-goals / deferred

- Per-table topic subscriptions / ping filtering (one store, one order screen per terminal →
  re-check-on-any-ping is fine).
- Live *merge into another order from a third terminal* conflict resolution beyond
  lock/refresh.
- Stale detection on the split or payment screens (those already tender an authoritative
  server quote and fail atomically; out of scope here).
- Any backend change (this slice is terminal-only).

## Module-boundary / build impact

- No backend change, no migration, no config key, no Maven change (JDK WebSocket already in;
  `spring-boot-starter-websocket` already added in slice 14).
- Terminal-only: new `RealtimeClients`, `NoopRealtimeClient`; modified `Services`,
  `TableMapController`, `OrderController`, `OrderViewModel`, `order.fxml`, `app.css`.
