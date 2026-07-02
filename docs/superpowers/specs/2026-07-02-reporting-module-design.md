# Reporting Module Design (MVP)

**Date:** 2026-07-02
**Status:** Approved (brainstorming), pending implementation plan
**Phase:** Phase 8 (MVP completion) — the `reporting` slice of plan §14, after `customer`

## Goal

Add a `reporting` module: on-demand business reports over historical sales,
payment, tax, and product data — the `reporting (sales, product, cashier, tax,
payment)` slice of the plan's §14 MVP line.

## Scope

**In scope — the §14 MVP five reports**, each over a `[from, to]` date range:
1. **Sales summary** — counts and money totals (subtotal, discounts, tax, gross,
   net of returns).
2. **Payment breakdown** — collected/refunded by payment method.
3. **Tax summary** — taxable base, tax collected, net of refund tax.
4. **Cashier report** — sales and discounts grouped by cashier.
5. **Product performance** — top SKUs by revenue (quantity, revenue, discounts).

Each report is available as JSON (default) and CSV (`?format=csv`).

**Explicitly deferred (not this phase):**
- Profit reporting — requires a product-cost (COGS) field that does not exist.
- Hourly / daily time-series (day-bucketed) reports and inventory-valuation
  reports (plan §5 but not §14's MVP list).
- JasperReports / PDF printable output (plan §7) — CSV covers the MVP need.
- A denormalized/event-sourced reporting read model (plan §7 alternative) —
  on-demand queries are sufficient at single-store volume.
- A `STORE_TIMEZONE` setting — reports use UTC day boundaries for now.

## Architecture

A Tier-1 `reporting` module in the house hexagonal layout, but a **pure read
layer**: it owns no tables, persists nothing, and publishes no events.

```
reporting/
├── api/            ReportingService facade + 5 report DTOs + package-info @NamedInterface("api")
├── web/            ReportingController (@RestController)
├── application/    DefaultReportingService (@Service, @Transactional(readOnly = true))
└── infrastructure/ ReportingQueries (JdbcTemplate native SQL → RowMappers → DTOs)
```

### Data access — reporting-owned native-SQL projections

`reporting` reads the source tables (`sale`, `sale_line`, `payment`,
`sales_return`) **directly via native SQL** on the shared `DataSource`
(`JdbcTemplate`), mapping results into `reporting`'s own DTOs. It does **not**
import any source module's entities, repositories, or facades — so there is no
Java-level module dependency on `sales`/`payment`/`inventory`, and
`ModularityTests` sees a near-leaf module.

The coupling is therefore **schema-level, not code-level**: a column rename in
`sales` would not fail `reporting` at compile time. This is the accepted,
plan-endorsed (§7 "read models / jOOQ projections") nature of a reporting
layer, and it keeps all reporting logic cohesive in one module without bloating
other facades.

- **`allowedDependencies = { "common", "database", "configuration :: api" }`.**
  `configuration::api` supplies the store currency (and name) for report
  headers, especially for an empty range where no rows carry a currency.
- **No Flyway migration, no new tables.** V22 remains the latest version.
- `ReportingService` is exposed through `api` + `@NamedInterface("api")` so the
  future `dashboard` module can call it synchronously ("calls for queries").
- Nothing depends on `reporting`; the graph is acyclic by construction.

## Date range and timezone

All endpoints take `from` and `to` as required `LocalDate` query params,
interpreted as **UTC day boundaries**: `fromInstant = from.atStartOfDay(UTC)`,
`toInstant = to.plusDays(1).atStartOfDay(UTC)` (inclusive `from` day through
inclusive `to` day; upper bound exclusive). Each query filters the relevant
table's `created_at` on `>= fromInstant AND < toInstant`.

There is no store-timezone setting yet; UTC-day is the documented default. A
`STORE_TIMEZONE` configuration key is a future refinement. Because the five
reports are range-scoped (not day-bucketed), no timezone-sensitive SQL date
functions are used — the queries are dialect-neutral across SQLite and Postgres.

## The five reports

All money fields are `BigDecimal`. Currency is read from the result rows,
falling back to the `configuration` currency for an empty range.

### 1. `GET /reports/sales?from=&to=`
SQL over `sale` (WHERE `status = 'COMPLETED'` AND `created_at` in range):
`COUNT(*)`, `SUM(subtotal)`, `SUM(discount_total)`, `SUM(txn_discount_amount)`,
`SUM(tax_total)`, `SUM(grand_total)`. Plus `sales_return` (created_at in range):
`COUNT(*)`, `SUM(refund_grand_total)`.

`SalesSummaryReport(LocalDate from, LocalDate to, String currencyCode,
long saleCount, BigDecimal subtotal, BigDecimal lineDiscounts,
BigDecimal txnDiscounts, BigDecimal taxTotal, BigDecimal grossSales,
long returnCount, BigDecimal refundTotal, BigDecimal netSales)`
where `grossSales = SUM(grand_total)`, `refundTotal = SUM(refund_grand_total)`,
`netSales = grossSales − refundTotal`. Zero sums render as `0.00`.

### 2. `GET /reports/payments?from=&to=`
SQL over `payment` (created_at in range) GROUP BY `method`, `direction`:
`COUNT(*)`, `SUM(amount)`. Collected = direction `SALE`; refunded = direction
`REFUND`.

`PaymentBreakdownReport(from, to, currencyCode,
List<PaymentLine> lines, BigDecimal totalCollected, BigDecimal totalRefunded)`
where `PaymentLine(String method, long count, BigDecimal collected,
BigDecimal refunded)`, one line per method (CASH/CARD/WALLET) that appears.

### 3. `GET /reports/tax?from=&to=`
SQL over `sale` (COMPLETED, in range): `SUM(subtotal)` as taxable,
`SUM(tax_total)` as collected; `sales_return` (in range): `SUM(refund_tax_total)`.

`TaxSummaryReport(from, to, currencyCode, BigDecimal taxableAmount,
BigDecimal taxCollected, BigDecimal refundTax, BigDecimal netTax)`
where `netTax = taxCollected − refundTax`.

### 4. `GET /reports/cashiers?from=&to=`
SQL over `sale` (COMPLETED, in range) GROUP BY `cashier_username`: `COUNT(*)`,
`SUM(grand_total)`, `SUM(discount_total + txn_discount_amount)`; ORDER BY total
sales descending.

`CashierReport(from, to, currencyCode, List<CashierLine> lines)`
where `CashierLine(String cashierUsername, long saleCount,
BigDecimal totalSales, BigDecimal totalDiscounts)`.

### 5. `GET /reports/products?from=&to=&limit=`
SQL over `sale_line` JOIN `sale` (ON `sale_line.sale_id = sale.id`, sale
COMPLETED and `sale.created_at` in range) GROUP BY `sku`, `name`:
`SUM(quantity)`, `SUM(line_total)`, `SUM(line_discount_amount)`; ORDER BY
revenue descending; `LIMIT :limit`.

`ProductPerformanceReport(from, to, currencyCode, List<ProductLine> lines)`
where `ProductLine(String sku, String name, BigDecimal quantitySold,
BigDecimal revenue, BigDecimal discounts)`. `limit` defaults to 50, clamped to
`[1, 500]`.

## Output formats

- **JSON** (default): the DTOs above.
- **CSV** (`?format=csv` → `Content-Type: text/csv`): a small hand-rolled writer
  (no new dependency). List reports emit a header + one row per line; summary
  reports emit a header + a single row. Column order matches the DTO fields.

## Authorization

Every report endpoint is `@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")` —
managers run the store's reports, admins retain oversight; cashiers are denied.
Roles are a non-hierarchical `Set<Role>`, so `hasAnyRole` (not `hasRole`) is
required to admit both.

## Error handling

- Missing `from` or `to` → 400 (required params).
- `from > to` → `DomainException.validation` → 400.
- Empty range → zeroed/empty report, 200 (not an error).
- `limit` outside `[1, 500]` → clamped, not rejected.

## Testing

- **Report-accuracy integration tests** (`@SpringBootTest @ActiveProfiles("embedded")`,
  committing via `DatabaseCleaner`): drive several real checkouts through
  `SalesService` with known amounts, cashiers, SKUs, and mixed tenders
  (cash + card), plus one return, then assert each of the five reports' numbers
  exactly against the seeded data. Proves the SQL against real persisted rows.
- **Date-range / timestamp-binding test (the key portability risk):** Hibernate
  persists `Instant created_at` differently on SQLite vs Postgres, and native
  SQL must compare against the stored representation. Seed sales inside and
  outside the range and assert only in-range rows are counted — this catches a
  wrong SQLite binding immediately.
- **Postgres reporting test (Testcontainers):** because the normal suite runs
  reporting only on SQLite, add one Postgres-profile test for the sales-summary
  range query so the range filter and aggregation are proven on **both**
  dialects.
- **Controller tests** (`jwt()` post-processor with `SimpleGrantedAuthority`):
  CASHIER → 403 on every endpoint; MANAGER and ADMIN → 200; JSON shape asserted;
  the `?format=csv` variant returns `text/csv` with the expected header row.
- **`ModularityTests`:** confirms `reporting → { common, database,
  configuration :: api }` only, and that no module depends on `reporting`.
- **Full `./mvnw clean verify`** including the Testcontainers Postgres run.

## Out-of-scope notes (future phases)
- `dashboard` (next §14 slice) will consume `ReportingService` for at-a-glance
  tiles.
- Profit reporting once a product-cost field lands; hourly/daily time-series and
  a `STORE_TIMEZONE` setting; inventory valuation; JasperReports/PDF; and a
  denormalized event-sourced read model if report volume ever outgrows
  on-demand queries.
