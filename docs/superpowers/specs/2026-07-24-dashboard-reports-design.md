# Manager Dashboard + Reports — Design Spec

**Slice:** Terminal slice 18 (terminal-only — no backend changes)
**Branch:** `feat/terminal-ui-restaurant-slice`
**Date:** 2026-07-24
**Status:** Approved design, pending implementation plan

## Problem

Managers are blind on the terminal. The backend already computes everything they need —
`GET /dashboard` (today's sales, revenue, best-sellers, low-stock, open shifts) and five
`GET /reports/*` endpoints (sales, payments, tax, cashiers, products; JSON or CSV) — all
MANAGER/ADMIN-gated. But the JavaFX terminal surfaces **none** of it: there is no dashboard,
no reporting, no end-of-day figures. This slice adds two read-only manager screens on top of
the existing APIs.

## Scope decisions (locked)

| Decision | Choice |
|---|---|
| Backend changes | **None** — `/dashboard` and `/reports/*` already exist and are MANAGER/ADMIN-gated |
| Dashboard presentation | **KPI tiles + tables** — no charting dependency |
| Reports date range | **Preset chips + Custom** — presets (Today, Yesterday, Last 7 days, This month) + a Custom path with two `DatePicker`s |
| CSV export | **Included** — fetch the backend `text/csv` and save via `FileChooser` |
| Navigation | **Dashboard = Home tile** (MANAGER/ADMIN); **Reports = Admin-hub tile** (MANAGER/ADMIN) |
| Reports screen shape | **One screen** with a report-type selector + shared date-range bar + results area |

This is one cohesive slice; the Dashboard and Reports halves are independent and could be split
into 18a/18b if execution warrants — the plan keeps them as separate task groups.

## Architecture

Terminal-only. Two new screens (Dashboard, Reports), two new API clients (`DashboardApi`,
`ReportingApi`), and two terminal primitives introduced for the first time: a **raw-text GET** on
`ApiClient` (for CSV) and a **`FileChooser` save** (for export). Everything follows the established
terminal conventions: Controller + synchronous ViewModel + `FxTasks` off-thread I/O; the only
off-thread observable a VM writes is `errorMessage` inside the injected `Consumer<Runnable> ui`;
any inter-thread time source is an injected `Supplier<Instant> clock`. New DTOs are
`@JsonIgnoreProperties(ignoreUnknown = true)`.

### New wiring
- `Services` gains `public final DashboardApi dashboardApi;` and `public final ReportingApi reportingApi;`.
- `Navigator` gains `toDashboard()` and `toReports()`.
- Home screen gains a **Dashboard** tile (visible when `session.isManager()`); Admin hub gains a
  **Reports** tile (visible when `session.isManager()`).

## Dashboard screen

`Navigator.toDashboard()` → `DashboardController` + `dashboard.fxml`. One `GET /dashboard` call
returns a `DashboardSnapshot`:

```
DashboardSnapshot(asOfDate, currencyCode, todaysSales:SalesSummaryReport,
                  revenue:RevenueSummary, bestSellers:List<ProductLine>,
                  lowStock:List<LowStockTile>, openShifts:List<ShiftView>,
                  activeCashiers:List<String>)
RevenueSummary(today, windowDays, window)
LowStockTile(sku, name, onHand, reorderLevel)
ShiftView(shiftId, terminalId, openedBy, status, currencyCode, openedAt, closedAt)
```

Rendering:
- **KPI tiles:** Today's gross sales + `saleCount` (from `todaysSales`), Revenue today
  (`revenue.today`), Revenue rolling-`windowDays` (`revenue.window`). All money as
  `BigDecimal amount + currencyCode`.
- **Tables:** Best sellers (`sku, name, quantitySold, revenue`), Low stock
  (`sku, name, onHand, reorderLevel`), Open shifts (`terminalId, openedBy, openedAt`) with the
  `activeCashiers` list shown alongside.
- A **Refresh** button. Read-only. No auto-poll this slice.

### Blind-close safety (hard constraint)
The shift close count is deliberately blind — a cashier must never learn `expectedCash`/`variance`
before counting. The dashboard's `openShifts` uses `ShiftView`, which carries **no** `expectedCash`,
`variance`, or cash-sales **amount** — so the dashboard structurally cannot leak the blind-count
figure. The implementation must render **only** the `ShiftView` fields listed above; it must not
reach for reconciliation data. (Dashboard is MANAGER/ADMIN-gated regardless.)

## Reports screen

`Navigator.toReports()` → `ReportsController` + `reports.fxml`. One screen, three regions:

1. **Report-type selector** — a `ToggleGroup` of segmented buttons: Sales · Payments · Tax ·
   Cashiers · Products.
2. **Date-range bar** — preset chips (Today, Yesterday, Last 7 days, This month) + a **Custom**
   toggle that reveals two `DatePicker`s (From / To). Presets resolve to a `(LocalDate from,
   LocalDate to)` pair computed against an injected `Supplier<Instant> clock` (never inline
   `Instant.now()`; timezone = system default via `LocalDate.now(clock-derived)` helper). Changing
   the report type OR the range re-runs the fetch.
3. **Results area** — renders the selected report:
   - **Scalar reports** (Sales, Tax) → summary tiles.
     - Sales (`SalesSummaryReport`): `saleCount, subtotal, lineDiscounts, txnDiscounts, taxTotal,
       grossSales, returnCount, refundTotal, netSales`.
     - Tax (`TaxSummaryReport`): `taxableAmount, taxCollected, refundTax, netTax`.
   - **List reports** (Payments, Cashiers, Products) → a `TableView` of `lines`.
     - Payments (`PaymentBreakdownReport`): lines `(method, count, collected, refunded)` +
       totals `totalCollected, totalRefunded`.
     - Cashiers (`CashierReport`): lines `(cashierUsername, saleCount, totalSales, totalDiscounts)`.
     - Products (`ProductPerformanceReport`): lines `(sku, name, quantitySold, revenue, discounts)`;
       Products passes `limit` (default 50).
   - Money renders `BigDecimal + currencyCode` (from the report DTO). Empty ranges render zeroed
     tiles / empty tables, not errors.
   - The render switch is exhaustive over the five report types.
4. **Export CSV** button → `GET /reports/{type}?from=&to=&format=csv` (raw text) → `FileChooser`
   save with a default name like `sales-2026-07-01_2026-07-24.csv`.

## New API clients & terminal primitives

### `DashboardApi`
- `DashboardSnapshot snapshot()` → `GET /dashboard`.
- Terminal DTOs mirroring the backend records: `DashboardSnapshot`, `RevenueSummary`,
  `LowStockTile`, `ShiftView`, plus the shared `SalesSummaryReport` and `ProductLine`
  (see ReportingApi — shared DTOs live in `api/dto` and are reused).

### `ReportingApi`
- Typed JSON methods (all `from`/`to` are `LocalDate`, serialized ISO `yyyy-MM-dd`):
  - `SalesSummaryReport salesReport(LocalDate from, LocalDate to)`
  - `PaymentBreakdownReport paymentsReport(LocalDate from, LocalDate to)`
  - `TaxSummaryReport taxReport(LocalDate from, LocalDate to)`
  - `CashierReport cashiersReport(LocalDate from, LocalDate to)`
  - `ProductPerformanceReport productsReport(LocalDate from, LocalDate to, int limit)`
- `String exportCsv(ReportType type, LocalDate from, LocalDate to, Integer limit)` →
  `GET /reports/{type}?from=&to=&format=csv[&limit=]` via the raw-text GET.
- `ReportType` enum (terminal-side): `SALES, PAYMENTS, TAX, CASHIERS, PRODUCTS`, each carrying its
  URL path segment.

### `ApiClient.getText(String path)`
- New method returning the response body as a `String` (no JSON deserialization), for CSV. Same
  bearer-auth and error handling as the JSON methods: a non-2xx throws `ApiException`; a
  session-authed 401 clears the session (consistent with existing behavior). Returns the body
  string (possibly empty) on 2xx.

### CSV file save
- Controller-side: the CSV fetch is the blocking `work` in `FxTasks.run`; in `onDone` (FX thread)
  the controller opens `FileChooser.showSaveDialog(...)`, then writes with
  `Files.writeString(path, csv)`. A cancelled dialog is a no-op; a write failure surfaces as an
  error message. (`showAndWait`/dialogs are allowed in `onDone` — they are UI, not blocking I/O;
  the HTTP fetch already happened in `work`.)

## Error handling

- Any dashboard/report fetch failure → VM sets `errorMessage` (only inside `ui.accept`); the screen
  shows the message and stays usable. No crash, no partial-state corruption.
- A `403` (a non-manager reaching the screen despite the role-gated tiles) surfaces as a friendly
  "Not authorized to view this" message (defense-in-depth).
- Export: cancelled `FileChooser` → no-op; CSV fetch failure → normal `ApiException` → error
  message; file-write failure → error message.
- Empty result ranges are a normal state (zeroed tiles / empty tables), never an error.

## Testing strategy

Headless terminal tests (`./mvnw -f pos-terminal/pom.xml test`), following the conventions:

- **`DashboardViewModel` test:** fake `DashboardApi` (anonymous subclass over `new DashboardApi(null)`)
  returns a snapshot → assert exposed KPI values + table rows; error path sets `errorMessage`;
  **async-dispatcher regression test** with a deferred, undrained `ui` dispatcher (assert synchronous
  return before draining, `errorMessage`/state only after draining).
- **`ReportsViewModel` test:** preset → `(LocalDate,LocalDate)` resolution with an injected FIXED
  clock (verify Today, Yesterday, Last 7 days, This month boundaries exactly); selecting each report
  type dispatches the correct `ReportingApi` call and exposes the right view-data; scalar vs list
  rendering data; `exportCsv` returns the fetched raw string; error path; **async-dispatcher
  regression test**.
- **CSV:** the VM's export method returns the raw CSV string from the fake api; the actual
  `FileChooser`/`Files.writeString` is controller glue and is not unit-tested (matches the
  no-controller-test convention). A cancelled-save path is a controller no-op.
- **FXML/CSS:** the existing `FxmlContractTest` (every `@FXML` field ↔ `fx:id`) and `AppCssTest`
  cover the two new FXML files and the new style block.
- No backend test changes (no backend code changes).

## Non-goals (YAGNI — deliberately deferred)

Charts/graphs of any kind; a per-day revenue trend series (the `/dashboard` snapshot returns only
`today` + a single rolling-`window` total, no per-day data); scheduled or emailed reports; PDF
export; report drill-down / row click-through; caching or auto-refresh beyond a manual Refresh
button; pagination of report tables (single-store volumes are small; Products already caps via
`limit`); any new backend endpoint, DTO, or configuration.

## Terminal-convention checklist (must hold)

- ViewModels synchronous; controllers run them off-thread via `FxTasks.run`; results read only in the
  FX-thread `onDone` (holder pattern where needed). The only off-thread observable write is
  `errorMessage` inside `ui.accept`.
- Date/preset math uses an injected `Supplier<Instant> clock`, never inline `Instant.now()`.
- New Api classes are `public` (non-final, so VM tests subclass with fakes), one `ApiClient` field,
  delegate via `client.get(...)`/`client.getText(...)`.
- New DTOs `@JsonIgnoreProperties(ignoreUnknown = true)`.
- Status/emphasis uses colour + text (never colour alone), consistent with the design system.
- Entry tiles role-gated via `session.isManager()` (MANAGER or ADMIN).
