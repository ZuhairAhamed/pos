# Dashboard Module Design (MVP)

**Date:** 2026-07-02
**Status:** Approved (brainstorming), pending implementation plan
**Phase:** Phase 9 (MVP completion) — the `dashboard` slice of plan §14/§24, after `reporting`

## Goal

Add a `dashboard` module: at-a-glance operational insight for the store, composed
on-demand from existing module facades. This is the `dashboard` slice of the
plan's §14 MVP line and covers the six §24 displays: **Today's Sales, Revenue
Summary, Best Selling Products, Low-Stock Items, Open Shifts, Active Cashiers.**

## Scope

**In scope — the §24 six tiles**, each reflecting current store state:
1. **Today's Sales** — today's sales summary (counts + money totals).
2. **Revenue Summary** — today plus a rolling window (default last 7 days).
3. **Best Selling Products** — top SKUs by revenue today (small limit).
4. **Low-Stock Items** — SKUs below their reorder level, with product name.
5. **Open Shifts** — shifts currently `OPEN`.
6. **Active Cashiers** — distinct cashiers with an open shift (derived from #5).

Delivered as an aggregate snapshot plus per-tile endpoints for independent
refresh. JSON only.

**Explicitly deferred (not this phase):**
- CSV export (dashboard is a live JSON surface; reporting already covers CSV).
- Server-push / WebSocket live refresh — polling the per-tile endpoints suffices.
- A `STORE_TIMEZONE` setting — "today" uses UTC day boundaries, consistent with
  `reporting`.
- Historical trend/spark-line data per tile — dashboard shows current state, not
  time series; `reporting` owns ranged history.

## Architecture

A Tier-1 `dashboard` module in the house hexagonal layout, and — like
`reporting` — a **pure read layer**: it owns no tables, persists nothing, and
publishes no events. Unlike `reporting`, it runs **no SQL of its own**; it is a
thin composition layer over existing `api` facades ("calls for queries").

```
dashboard/
├── api/            DashboardService facade + tile DTOs + package-info @NamedInterface("api")
├── web/            DashboardController (@RestController)
└── application/    DefaultDashboardService (@Service, @Transactional(readOnly = true))
```

- **`allowedDependencies = { "reporting :: api", "inventory :: api",
  "shift :: api", "product :: api", "configuration :: api", "common" }`.**
  `reporting::api` supplies the sales-derived tiles; `inventory::api` and
  `shift::api` supply the two tiles those modules own; `product::api` supplies
  product names for the low-stock tile; `configuration::api` supplies store
  currency for headers.
- **No `database` dependency, no Flyway migration, no new tables.** V22 remains
  the latest version.
- `DashboardService` is exposed through `api` + `@NamedInterface("api")`.
- Nothing depends on `dashboard`; the graph is acyclic by construction.

## Facade additions (the only changes outside `dashboard`)

Two owning modules gain a list query they don't yet expose. Each is a small,
plan-consistent addition (a module exposing what it owns through its `api`):

- **`inventory :: api`** →
  `List<LowStockItem> listLowStock()`
  where `LowStockItem(String sku, BigDecimal onHand, BigDecimal reorderLevel)`.
  Backed by a repository query for rows where `reorder_level > 0 AND
  quantity_on_hand < reorder_level`, ordered by largest shortfall
  (`reorder_level - quantity_on_hand` descending). Inventory returns only what
  it owns — **no product name**.
- **`shift :: api`** →
  `List<ShiftView> listOpenShifts()` — shifts with `status = OPEN`, ordered by
  `opened_at`. Reuses the existing `ShiftView` record (no new DTO).

## The tiles and DTOs

Money fields are `BigDecimal`. "Today" = **UTC day**: `today = LocalDate.now(UTC)`,
and today's figures come from `reporting.salesSummary(today, today)` /
`reporting.productPerformance(today, today, limit)` — consistent with reporting's
documented UTC-day default. No timezone-sensitive SQL is introduced.

### `DashboardSnapshot`
```
DashboardSnapshot(
    LocalDate asOfDate,              // the UTC "today"
    String currencyCode,
    SalesSummaryReport todaysSales,  // reporting::api DTO, from = to = today
    RevenueSummary revenue,
    List<ProductPerformanceReport.ProductLine> bestSellers,  // reporting::api lines, reused directly
    List<LowStockTile> lowStock,
    List<ShiftView> openShifts,      // shift::api DTO
    List<String> activeCashiers)     // distinct openedBy from openShifts
```

### `RevenueSummary`
```
RevenueSummary(
    BigDecimal today,                // today's netSales
    int windowDays,                  // default 7
    BigDecimal window)               // netSales over [today - (windowDays-1), today]
```

### `LowStockTile` (dashboard-owned, name-enriched)
```
LowStockTile(String sku, String name, BigDecimal onHand, BigDecimal reorderLevel)
```
`DashboardService` enriches each `inventory` `LowStockItem` by calling
`ProductCatalog.findBySku(sku)`; if the product is not found, `name` falls back
to the `sku`. Low-stock lists are inherently small (only SKUs below reorder), so
per-SKU lookups are acceptable — no batch API is added.

### Best sellers
Exposed as `reporting`'s `ProductPerformanceReport.ProductLine` records, reused
directly (top by revenue, `limit` default 5, clamped `[1, 50]` for a dashboard
tile). No new dashboard DTO.

## Endpoints

All endpoints are `@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")` — consistent
with `reporting`. Roles are a non-hierarchical `Set<Role>`, so `hasAnyRole`
admits both MANAGER and ADMIN; cashiers get 403.

| Endpoint | Returns |
|----------|---------|
| `GET /dashboard`              | `DashboardSnapshot` (all six tiles) |
| `GET /dashboard/sales-today`  | today's `SalesSummaryReport` |
| `GET /dashboard/revenue`      | `RevenueSummary` |
| `GET /dashboard/best-sellers?limit=` | best-seller lines |
| `GET /dashboard/low-stock`    | `List<LowStockTile>` |
| `GET /dashboard/open-shifts`  | open shifts + derived active cashiers |

### `DashboardService.snapshot()` data flow

Serial facade calls (single-store volume; no async):
- `todaysSales` ← `reporting.salesSummary(today, today)`
- `revenue` ← `reporting.salesSummary(today, today)` (today) +
  `reporting.salesSummary(today.minusDays(windowDays - 1), today)` (window)
- `bestSellers` ← `reporting.productPerformance(today, today, limit)`
- `lowStock` ← `inventory.listLowStock()`, each row name-enriched via
  `product.findBySku`
- `openShifts` ← `shift.listOpenShifts()`
- `activeCashiers` ← distinct `openedBy` derived from `openShifts` (no query)

`windowDays` is read from configuration key **`dashboard.revenue.window.days`**
(default `7`).

## Error handling

- Empty results (no sales today, no low-stock, no open shifts) → `200` with
  zeroed summaries / empty lists, not errors.
- Unauthenticated → `401`; CASHIER → `403`.
- `limit` on best-sellers outside `[1, 50]` → clamped, not rejected.

## Testing

- **Integration test** (`@SpringBootTest @ActiveProfiles("embedded")`, committing
  via `DatabaseCleaner`): seed real checkouts today (through `SalesService`) with
  known SKUs/amounts, open a shift, and drive a SKU below its reorder level;
  then assert every tile of `GET /dashboard` — today's sales totals, revenue
  (today + window), best sellers, low-stock (including enriched product name),
  open shifts, and derived active cashiers.
- **Revenue-window test:** seed sales today and several days back; assert
  `today` vs `window` (default 7) partition correctly; verify the
  `dashboard.revenue.window.days` override changes the window.
- **Low-stock enrichment test:** a low-stock SKU with a known product name shows
  the name; a low-stock SKU with no matching product falls back to the sku.
- **Controller tests** (`jwt()` post-processor with `SimpleGrantedAuthority`):
  CASHIER → 403 on every endpoint; MANAGER and ADMIN → 200; JSON shape of the
  aggregate and each per-tile endpoint asserted.
- **`ModularityTests`:** confirms `dashboard → { reporting::api, inventory::api,
  shift::api, product::api, configuration::api, common }` only, that the new
  `inventory`/`shift` facade methods don't introduce cycles, and that nothing
  depends on `dashboard`.
- **Full `./mvnw clean verify`** green (SQLite suite; the dashboard path is
  facade-only, so no Postgres-specific dashboard test is required — the
  underlying `reporting` SQL already has dual-dialect coverage).

## Out-of-scope notes (future phases)
- CSV/PDF export of dashboard tiles; live server-push refresh; per-tile trend
  sparklines; a `STORE_TIMEZONE` setting shared with `reporting`; multi-terminal
  cashier presence beyond open-shift derivation.
