# Terminal UI — Restaurant Seat-to-Payment Slice (Design)

**Date:** 2026-07-10
**Status:** Approved (brainstorming), pending implementation plan
**Phase:** First slice of the POS **terminal UI** track — the JavaFX desktop terminal the plan (`plan/pos-architecture-combined.md` §3, §13) always called for but which was never built. The backend (Phases 0–13b) is a headless REST API; this is the first user-facing client.

## Goal

Build the first production-bound **JavaFX desktop terminal** as a **REST thin client** to the store server, delivering one complete restaurant vertical slice: a server logs in, opens/resumes a dine-in order from a shared table map, adds items (with modifiers) via a touch menu grid, fires the order to the kitchen, then closes it as **one bill** and takes payment (cash + card), with the receipt printing server-side.

This slice deliberately establishes the reusable foundation — app shell, session/JWT handling, typed API client layer, one full end-to-end workflow — that every later terminal screen (split billing, service-charge waiver, retail sell path, manager back-office) will build on.

## Scope

**In scope (this slice):**

- **Separate `pos-terminal` Maven module** — a new deployable, sibling to the server, depending only on the server's HTTP API.
- **App shell + navigation** — `Application` entry point, a `Navigator` that switches screens.
- **Login** — username/password and PIN pad (`POST /auth/login`, `POST /auth/pin-login`); JWT capture; role fetch (`GET /auth/me`).
- **Table map** — table grid (`GET /dining/tables`) with free/occupied status from open orders (`GET /dining/orders`); open a new dine-in order on a free table or resume an existing one.
- **Order screen** — current-order line list (qty, modifiers, note, fired-lock indicator) with a **client-computed estimated subtotal**; a **touch menu grid** grouped by product category; add / update / remove lines (fired lines locked); **fire to kitchen**.
- **Modifier picker** — forced/optional modifier groups with min/max validation and priced options.
- **Payment** — single-bill close (`POST /dining/orders/{id}/close`) with cash (tendered + change) and card tenders; confirmation from the returned `SaleView`; **reprint** (`POST /sales/{id}/reprint`).
- **Errors & resilience** — typed error handling, session-expiry re-login, an honest "can't reach store server" state, optimistic-lock (409) refetch.
- **Live floor via polling** — the table map refreshes on an interval.
- **Testing** — unit tests for ViewModels and the API client layer against a stub server; an optional TestFX happy-path smoke test.

**Explicitly deferred (not this slice):**

- **Split billing** (by-item and even) — `POST /dining/orders/{id}/close-split` exists; UI is a later slice.
- **Service-charge display + manager waiver UI** — the `waiveServiceCharge` flag and service-charge total exist server-side; terminal UI deferred.
- **Shift/drawer UI** — no open-shift prompt. The slice assumes a shift may or may not be open; checkout is **not** shift-gated (cash capture into a drawer is a no-op when none is open), so payment still completes. Shift open/close UI is a later slice.
- **WebSocket live push** — polling now; the backend has no push endpoint yet.
- **Retail (quick-service) sell path**, manager back-office (reports/dashboard/audit/config), customer attach, table/menu/station admin CRUD — all later slices.
- **Native packaging** (jpackage installer) — dev runs via the JavaFX Maven plugin; packaging is its own later task.
- **Direct JavaPOS hardware access** — all hardware stays server-mediated via the existing device ports; the terminal never touches hardware.

## Architecture

### Placement

A new top-level Maven module **`pos-terminal/`** with its own `pom.xml` (JavaFX controls + FXML, Jackson, JUnit, optionally TestFX). It is **not** part of the server's `com.company.pos` Spring Modulith monolith and imports no server code — it communicates solely over HTTP. This keeps it independently buildable/shippable and free of the module-boundary constraints that govern the server. If the repo is converted to a Maven reactor, `pos-terminal` and the existing server become sibling modules; otherwise `pos-terminal` builds standalone.

### Pattern — FXML + MVVM + typed API client

```
pos-terminal/
├── app/          PosTerminalApp (Application), Navigator (screen switcher)
├── view/         *.fxml (resources) + thin controllers:
│                 LoginController, TableMapController, OrderController,
│                 ModifierPickerController, PaymentController
├── viewmodel/    LoginViewModel, TableMapViewModel, OrderViewModel,
│                 PaymentViewModel  — plain classes, JavaFX properties,
│                 all logic, unit-testable WITHOUT the FX toolkit
├── api/          ApiClient (HttpClient + Jackson), SessionManager (JWT/roles),
│                 typed clients: AuthApi, ShiftApi (unused this slice but stubbed),
│                 DiningApi, MenuApi, ProductApi, SalesApi;
│                 dto/ records mirroring the server api DTOs
└── config/       TerminalConfig (server.base-url, terminal.id, store.id, poll.interval)
```

- **Controllers are thin:** wire FX controls to ViewModel properties and forward user gestures; no business logic.
- **ViewModels hold state + logic** as JavaFX observable properties, call the API layer through interfaces (so they mock cleanly), and never touch FX `Node`s directly.
- **Threading:** every API call runs off the JavaFX Application Thread in a `Task`; results are marshalled back with `Platform.runLater`. The UI never blocks on I/O.

### API / session layer

- **`ApiClient`** — wraps `java.net.http.HttpClient`, prefixes `server.base-url`, injects `Authorization: Bearer <jwt>` from `SessionManager`, (de)serializes JSON with Jackson, and maps every non-2xx response to a typed **`ApiException(status, ProblemDetail)`** (the server emits RFC-7807 problem+json).
- **`SessionManager`** — holds token, username, roles; `isManager()` derived from roles; cleared on logout or any `401`.
- **Typed clients** return DTO records. DTOs are terminal-side records mirroring the server's `api` DTOs (`TableView`, `OrderView`, `OrderLineView`, `OrderLineModifierView`, `ProductView`, modifier-group views, `SaleView`) and command records (`OpenOrderCommand`, `AddLineCommand`, `CloseOrderCommand`, `TenderInput`, `PaymentMethod`).

## Screens & data flow

### 1. Login
Username/password form + PIN pad. `POST /auth/login` or `/auth/pin-login` → `{token}`; store in `SessionManager`; `GET /auth/me` → roles. On success navigate to the table map. (No shift prompt — see deferred scope.)

### 2. Table map
`GET /dining/tables` → grid of `TableView`; `GET /dining/orders` → open orders to mark tables occupied. Tap a **free** table → `POST /dining/orders {tableId, DINE_IN}` → `OrderView` → order screen. Tap an **occupied** table → fetch its `OrderView` (from the open-orders list / `GET /dining/orders/{id}`) → order screen. A `ScheduledService` re-polls tables + open orders every `poll.interval` seconds (default 5s); manual refresh also available.

### 3. Order screen
- **Left:** the order's lines (`OrderView.lines` → qty, modifier names, note, fired indicator) and a running **"Subtotal (est.)"**.
- **Right:** a touch menu grid — category tabs derived from `GET /products` (`ProductView.categoryName`), product buttons within each tab.
- **Add:** tap a product → if `GET /menu/products/{sku}/modifier-groups` returns groups, open the modifier picker; else add directly. `POST /dining/orders/{id}/lines {sku, qty, note, course, modifierOptionIds}` → refreshed `OrderView`.
- **Edit/remove:** `PUT`/`DELETE /dining/orders/{id}/lines/{lineId}` — controls disabled for lines with a non-null `firedAt`.
- **Fire:** `POST /dining/orders/{id}/fire` (stamps un-fired lines; kitchen tickets print server-side).
- **Pay:** navigate to payment.

**Running total (client-side, estimated):** `OrderViewModel` loads a `MenuCache` (product base prices from `/products`; modifier-option `priceDelta`s from `/menu/products/{sku}/modifier-groups`) and computes `subtotal = Σ (basePrice(sku) + Σ selectedModifierDeltas) × qty`. This is a pre-tax, pre-service-charge estimate, labelled as such. The authoritative subtotal/tax/total appears only in the `SaleView` returned at close.

### 4. Modifier picker
Renders each modifier group with its `minSelections`/`maxSelections` (forced = min ≥ 1). Validates the selection client-side (forced groups satisfied, max respected) before enabling "Add"; the server re-validates on `addLine`. Returns the chosen `modifierOptionIds`.

### 5. Payment
Shows the order and the estimated total. Server picks tenders: **cash** (enter tendered → show change; short tender rejected before any API call) and **card** (amount). Build `CloseOrderCommand{tenders, lineDiscounts=∅, transactionDiscount=null, waiveServiceCharge=false}` → `POST /dining/orders/{id}/close` → `SaleView`. Show a confirmation with the authoritative totals from `SaleView` (the receipt prints on the server-side printer during checkout); offer **reprint** via `POST /sales/{saleId}/reprint`. Return to the table map (the closed table is now free).

## Error handling & resilience

- **`ApiException` → non-blocking banner**; the user can retry the gesture.
- **`400` (validation problem detail)** → inline message near the offending control.
- **`401`** → clear session, return to login ("session expired").
- **Network failure / server unreachable** → an explicit "Can't reach store server" state; the terminal cannot operate without the LAN/server by the chosen topology, and says so honestly rather than pretending to work.
- **`409` optimistic-lock** (two terminals editing one order) → refetch the `OrderView` and inform the user their view was refreshed.

## Configuration

`pos-terminal.properties` (overridable by JVM args / env):
- `server.base-url` (e.g. `https://store-server:8443`)
- `terminal.id`, `store.id`
- `poll.interval` (seconds, default 5)

## Testing strategy

- **ViewModel unit tests (JUnit, no FX toolkit):** API clients are interfaces and mocked. Assert state transitions: add updates lines + recomputed subtotal; a fired line exposes no edit/remove affordance; a short cash tender is rejected before `close` is called; a `401` clears the session.
- **API client tests:** against a stub HTTP server (JDK `com.sun.net.httpserver.HttpServer` or MockWebServer) — assert request path/body/`Authorization` header, success mapping, and error → `ApiException` mapping (including a problem+json body).
- **UI smoke test (optional / follow-up):** TestFX drives login → table → add item → fire → pay against a stubbed API layer.
- **E2E (manual, documented):** run the terminal against a real `store-server` backend. **Prerequisite: seed data** — at least one active table, a handful of products across ≥2 categories, and one product with a modifier group. The spec's implementation plan will include a documented seed recipe and run command.

## Open questions / risks

1. **Repo layout** — introduce a Maven reactor (parent pom with `server` + `pos-terminal` modules) or keep `pos-terminal` as a standalone-building module beside the current single-module server? (Leaning standalone module now; reactor later if it earns its keep.) The implementation plan will pick one explicitly.
2. **Menu-cache freshness** — base prices/deltas are cached per session for the estimate; if ERP down-sync changes a price mid-session the estimate can drift from the authoritative close total. Acceptable for an *estimate*; the close total is always authoritative. Revisit if it confuses servers.
3. **Category source** — categories come from `ProductView.categoryName`; products with no category need a fallback tab ("Other"). Confirmed handled in the order screen.
