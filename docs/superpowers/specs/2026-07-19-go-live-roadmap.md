# Restaurant POS — Go-Live Roadmap

_Date: 2026-07-19. Branch: `feat/terminal-ui-restaurant-slice`._

## Context

The JavaFX terminal (`pos-terminal/`, a REST thin-client) has a **complete
front-of-house restaurant service loop**: floor map → order editing (items,
modifiers, courses, qty) → fire → pay (single + split by-item/even) → void,
transfer, merge → discounts + service-charge waiver with manager-PIN approval →
shift open/close (blind count) → cash drawer pay-in/out → printed + emailed
receipts → live floor push (`/ws/floor`).

This document reconciles the **backend HTTP surface (91 REST endpoints + 1
WebSocket across 17 modules)** against **what the terminal actually exposes** to
find the UI (and backing) still missing before a real restaurant can open its
doors. It is the decomposition artifact; each item below gets its own
spec → plan → implementation cycle.

## Scope decision

- **Sequencing:** dependency-driven — can't-open blockers → can't-configure →
  can't-close-a-day → money correctness → service quality → biggest optional build.
- **Go-live line:** **all of 1–8** are in scope. Nothing ships until the full
  restaurant experience (including KDS + 86ing) is built.
- The two items with **no backend at all** (staff logins, product catalogue) are
  in scope as backend + UI, not UI-only.

## Roadmap

| # | Sub-project | Nature | Rationale |
|---|-------------|--------|-----------|
| 1 | **Staff / user management** — create, list, deactivate cashiers (roles + PIN) | Backend endpoint **+** admin UI | Hard blocker. Only login today is the hardcoded `dev` seeder (`manager`/`manager`). Cannot onboard real staff → cannot open. |
| 2 | **Product catalogue admin** — create/edit products & prices without a real ERP | Backend endpoint **+** admin UI | Hard blocker. Products only arrive via `FakeErpClient` or the dev seeder today. No menu = no restaurant. |
| 3 | **Store setup console** — tables, menu (modifiers/variants), kitchen routing, config settings | UI over mostly-existing endpoints | Must configure *this* restaurant before trading. Currently raw-curl / seeder only. |
| 4 | **Daily close-out & reporting** — end-of-day Z-report: sales + payment breakdown + tax + per-cashier, reprintable | UI over existing endpoints | Needed every trading day; managers close out and reconcile against the drawer. |
| 5 | **ERP sync control & queue visibility** — trigger pull, drain upload queue, see pending backlog + last-sync status | UI (+ likely a small status endpoint) | Offline-first system's operational safety valve — staff must *see* the queue draining. |
| 6 | **Returns / refunds** — manager-gated refund against a prior sale | UI over `POST /returns` | Money-handling correctness. Restaurants refund too. |
| 7 | **Item 86ing / availability** — mark items unavailable so they can't be ordered | Backend flag + order-time guard **+** UI | Service quality — stops selling what the kitchen can't make. Net-new but small. |
| 8 | **Kitchen Display System** — digital station queues + bump/done | Backend (ticket aggregate, queries) **+** UI | Largest build. Last because firing already **prints paper tickets**, so the kitchen can run without it. |

## Deferred (noted, not in go-live scope)

Live manager dashboard (reporting in #4 covers the hard need), audit-log viewer,
customer/loyalty, standalone inventory-lookup screen, retail cart hold/resume.

## Architectural note

Items 1–6 are manager/admin screens. They will share a **new "Admin/Manager"
navigation area** in the terminal, gated behind a manager/admin role check —
a coherent one-time addition established in sub-project #1.

## Backend gaps confirmed during analysis

- **No user-management endpoint** anywhere — users exist only via `DevUserSeeder`.
- **No product create/edit endpoint** — catalogue enters via ERP sync (`FakeErpClient`) or `DevCatalogueSeeder`.
- **No kitchen ticket entity or KDS query/bump endpoints** — `fire` publishes `KitchenTicketsFired`, whose listener **prints a paper ticket per station**; only static SKU→station routing is persisted.
- **No item-availability / 86ing concept** — `Product.active` is an ERP-catalog flag, not per-service availability; `addLine` performs no availability check.
- Reports support single-day filter (`from==to`) but **no shift-id filter**.
