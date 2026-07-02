# Reporting Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a read-only `reporting` module exposing the §14 MVP five reports (sales summary, payment breakdown, tax summary, cashier, product performance) as MANAGER/ADMIN JSON + CSV endpoints over a date range.

**Architecture:** A Tier-1 Spring Modulith module that is a pure read layer — it owns no tables and reads the existing `sale`/`sale_line`/`sales_return`/`payment` tables via native SQL (`JdbcTemplate`) mapped into its own DTOs. No entity imports, so no code-level dependency on source modules; the coupling is schema-level only (plan §7 read-model pattern). Reports are computed on-demand.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Modulith, Spring JDBC (`JdbcTemplate`, autoconfigured via the existing data-jpa starter), JUnit 5, MockMvc, Testcontainers PostgreSQL.

## Global Constraints

- **JDK 21 required.** Set `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` before any Maven command. Build with `./mvnw`, never Gradle.
- **After every task, run the affected tests AND `./mvnw test -Dtest=ModularityTests`.** The final task runs full `./mvnw clean verify` including the Testcontainers Postgres test.
- **Money is `BigDecimal`, never `double`.** SQL sums map to `BigDecimal`; use `COALESCE(SUM(x), 0)` so an empty range yields `0`, never `null`.
- **`reporting` is a pure read layer.** It creates NO tables and NO Flyway migration (V22 stays the latest). It reads source tables via `JdbcTemplate` native SQL only. It MUST NOT import any source module's entity, repository, or facade in main code.
- **`allowedDependencies = { "common", "database", "configuration :: api" }`** — verbatim. `common` for `DomainException`; `configuration :: api` for the store currency; `database` is the OPEN persistence module. Nothing else, and nothing depends on `reporting`. `ModularityTests` must stay green.
- **Authorization:** every report endpoint is `@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")` (class-level on the controller). Roles are a non-hierarchical `Set<Role>`; `hasAnyRole` admits both MANAGER and ADMIN. Method security is already enabled (`@EnableMethodSecurity` in `SecurityConfig`). Test auth uses the `jwt()` post-processor with `.authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))` etc. — NOT `@WithMockUser`.
- **Date range:** endpoints take `from` and `to` as required `LocalDate` (`@DateTimeFormat(iso = DATE)`), interpreted as **UTC day boundaries**: `fromTs = from.atStartOfDay(UTC).toInstant()`, `toTs = to.plusDays(1).atStartOfDay(UTC).toInstant()`; filter `created_at >= fromTs AND created_at < toTs`. `from > to` → `DomainException.validation` (400). No `STORE_TIMEZONE` setting this phase.
- **Timestamp-binding is the one real portability risk.** Hibernate persists `Instant created_at` and native SQL must compare against the stored representation, which differs between embedded SQLite and Postgres. Task 1 proves the binding empirically on SQLite (a range that excludes vs includes a seeded sale); Task 6 proves it on Postgres (Testcontainers). Bind range params as `java.sql.Timestamp.from(instant)` unless Task 1's probe forces a documented alternative.
- **Physical column names (verbatim — write SQL against these):**
  - `sale`(id, receipt_number, store_id, terminal_id, cashier_username, location_code, status, currency_code, subtotal, tax_total, grand_total, created_at, txn_discount_amount, txn_discount_type, txn_discount_reason, discount_total, customer_id). All persisted sales have `status = 'COMPLETED'`.
  - `sale_line`(id, sale_id, line_no, sku, name, quantity, unit_price, net_amount, tax_amount, line_total, currency_code, gross_amount, line_discount_amount, line_discount_type, line_discount_reason).
  - `sales_return`(id, credit_note_number, original_sale_id, …, status, currency_code, refund_subtotal, refund_tax_total, refund_grand_total, created_at).
  - `payment`(id, sale_id, method, amount, amount_tendered, change_due, currency_code, masked_pan, auth_token, return_id, **txn_type**, created_at). `method` ∈ {CASH,CARD,WALLET}; **`txn_type`** is the direction ∈ {SALE,REFUND}.

---

## File Structure

**New module `com.company.pos.reporting`:**
- `reporting/package-info.java` — `@ApplicationModule(allowedDependencies = { "common", "database", "configuration :: api" })`
- `reporting/api/package-info.java` — `@NamedInterface("api")`
- `reporting/api/ReportingService.java` — facade interface (all 5 report methods)
- `reporting/api/SalesSummaryReport.java`, `PaymentBreakdownReport.java` (+ `PaymentLine`), `TaxSummaryReport.java`, `CashierReport.java` (+ `CashierLine`), `ProductPerformanceReport.java` (+ `ProductLine`) — DTO records
- `reporting/application/DefaultReportingService.java` — `@Service`, validates range, converts to Instants, reads currency, delegates to queries
- `reporting/application/ReportRanges.java` — small static helper for the UTC-day bounds + range validation
- `reporting/infrastructure/ReportingQueries.java` — `@Component`, `JdbcTemplate` native SQL → DTOs
- `reporting/web/ReportingController.java` — `@RestController`, class-level `@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")`
- `reporting/web/ReportCsv.java` — hand-rolled CSV writer (Task 5)

**No migrations, no changes to existing modules.**

---

## Task 1: Module skeleton + Sales Summary report (proves the date-range binding)

Establishes the module, the query infrastructure, the date-range helper, and the first report. Its test is also the **binding probe** that de-risks the SQLite timestamp comparison for every later task.

**Files:**
- Create: `src/main/java/com/company/pos/reporting/package-info.java`
- Create: `src/main/java/com/company/pos/reporting/api/package-info.java`
- Create: `src/main/java/com/company/pos/reporting/api/ReportingService.java`
- Create: `src/main/java/com/company/pos/reporting/api/SalesSummaryReport.java`
- Create: `src/main/java/com/company/pos/reporting/application/ReportRanges.java`
- Create: `src/main/java/com/company/pos/reporting/application/DefaultReportingService.java`
- Create: `src/main/java/com/company/pos/reporting/infrastructure/ReportingQueries.java`
- Test: `src/test/java/com/company/pos/reporting/SalesSummaryReportTest.java`

**Interfaces:**
- Consumes: `ConfigurationService.getString(SettingKey.CURRENCY_CODE)`; `DomainException.validation(String)`; `JdbcTemplate` (autoconfigured). For seeding, the test uses `SalesService`, `CartService`, `ProductSync`, `FakeErpClient`, `ReturnService` (test scope only).
- Produces (later tasks rely on these):
  - `ReportingService.salesSummary(LocalDate from, LocalDate to) → SalesSummaryReport`
  - `SalesSummaryReport(LocalDate from, LocalDate to, String currencyCode, long saleCount, BigDecimal subtotal, BigDecimal lineDiscounts, BigDecimal txnDiscounts, BigDecimal taxTotal, BigDecimal grossSales, long returnCount, BigDecimal refundTotal, BigDecimal netSales)`
  - `ReportRanges.startOf(LocalDate) → Instant`, `ReportRanges.endOf(LocalDate) → Instant`, `ReportRanges.requireValid(LocalDate from, LocalDate to) → void`
  - `ReportingQueries` is a `@Component` with `JdbcTemplate`; later tasks add methods to it.
  - `DefaultReportingService` holds `ReportingQueries queries` + `ConfigurationService config`; later tasks add its other facade methods.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/reporting/SalesSummaryReportTest.java`:

```java
package com.company.pos.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.reporting.api.ReportingService;
import com.company.pos.reporting.api.SalesSummaryReport;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class SalesSummaryReportTest {

    private static final LocalDate ALL_FROM = LocalDate.parse("2000-01-01");
    private static final LocalDate ALL_TO = LocalDate.parse("2100-01-01");

    @Autowired ReportingService reports;
    @Autowired SalesService salesService;
    @Autowired CartService carts;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private SaleView sellTwoColas() {
        UUID cartId = carts.createCart();
        carts.addLine(cartId, "COLA", new BigDecimal("2")); // 2 x 4.50 = 9.00 net, tax 1.35, grand 10.35
        return salesService.checkout(new CheckoutCommand(cartId,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))), "cashier");
    }

    @Test
    void summarisesSalesInRange() {
        sellTwoColas();
        sellTwoColas();

        SalesSummaryReport r = reports.salesSummary(ALL_FROM, ALL_TO);

        assertThat(r.saleCount()).isEqualTo(2);
        assertThat(r.grossSales()).isEqualByComparingTo("20.70"); // 2 x 10.35
        assertThat(r.taxTotal()).isEqualByComparingTo("2.70");     // 2 x 1.35
        assertThat(r.subtotal()).isEqualByComparingTo("18.00");    // 2 x 9.00
        assertThat(r.returnCount()).isZero();
        assertThat(r.refundTotal()).isEqualByComparingTo("0");
        assertThat(r.netSales()).isEqualByComparingTo("20.70");
        assertThat(r.currencyCode()).isEqualTo("SAR");
    }

    @Test
    void emptyRangeYieldsZeros() {
        sellTwoColas();

        // A past window that cannot contain a just-now sale — this PROVES the created_at
        // binding actually filters on SQLite (a broken binding would wrongly include the sale).
        SalesSummaryReport r = reports.salesSummary(LocalDate.parse("2019-01-01"), LocalDate.parse("2019-12-31"));

        assertThat(r.saleCount()).isZero();
        assertThat(r.grossSales()).isEqualByComparingTo("0");
        assertThat(r.currencyCode()).isEqualTo("SAR");
    }

    @Test
    void rejectsInvertedRange() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> reports.salesSummary(ALL_TO, ALL_FROM))
                .isInstanceOf(com.company.pos.common.exception.DomainException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=SalesSummaryReportTest`
Expected: FAIL — compilation error, `package com.company.pos.reporting.api does not exist`.

- [ ] **Step 3: Create the module boundary**

`src/main/java/com/company/pos/reporting/package-info.java`:
```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "configuration :: api" })
package com.company.pos.reporting;
```

`src/main/java/com/company/pos/reporting/api/package-info.java`:
```java
@org.springframework.modulith.NamedInterface("api")
package com.company.pos.reporting.api;
```

- [ ] **Step 4: Create the SalesSummaryReport DTO and the facade (sales method only for now)**

`src/main/java/com/company/pos/reporting/api/SalesSummaryReport.java`:
```java
package com.company.pos.reporting.api;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SalesSummaryReport(LocalDate from, LocalDate to, String currencyCode,
        long saleCount, BigDecimal subtotal, BigDecimal lineDiscounts, BigDecimal txnDiscounts,
        BigDecimal taxTotal, BigDecimal grossSales, long returnCount, BigDecimal refundTotal,
        BigDecimal netSales) {
}
```

`src/main/java/com/company/pos/reporting/api/ReportingService.java`:
```java
package com.company.pos.reporting.api;

import java.time.LocalDate;

public interface ReportingService {

    SalesSummaryReport salesSummary(LocalDate from, LocalDate to);
}
```

- [ ] **Step 5: Create the range helper**

`src/main/java/com/company/pos/reporting/application/ReportRanges.java`:
```java
package com.company.pos.reporting.application;

import com.company.pos.common.exception.DomainException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

final class ReportRanges {

    private ReportRanges() {
    }

    static void requireValid(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw DomainException.validation("Both from and to dates are required");
        }
        if (from.isAfter(to)) {
            throw DomainException.validation("from must not be after to");
        }
    }

    static Instant startOf(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    static Instant endOf(LocalDate day) {
        return day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
```

- [ ] **Step 6: Create the query component (sales summary SQL)**

`src/main/java/com/company/pos/reporting/infrastructure/ReportingQueries.java`:
```java
package com.company.pos.reporting.infrastructure;

import com.company.pos.reporting.api.SalesSummaryReport;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ReportingQueries {

    private final JdbcTemplate jdbc;

    public ReportingQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SalesSummaryReport salesSummary(LocalDate from, LocalDate to, Instant fromTs, Instant toTs,
            String currency) {
        Timestamp lo = Timestamp.from(fromTs);
        Timestamp hi = Timestamp.from(toTs);

        SaleAgg s = jdbc.queryForObject(
                "SELECT COUNT(*) AS cnt, "
                        + "COALESCE(SUM(subtotal), 0) AS subtotal, "
                        + "COALESCE(SUM(discount_total), 0) AS line_disc, "
                        + "COALESCE(SUM(txn_discount_amount), 0) AS txn_disc, "
                        + "COALESCE(SUM(tax_total), 0) AS tax, "
                        + "COALESCE(SUM(grand_total), 0) AS gross "
                        + "FROM sale WHERE status = 'COMPLETED' AND created_at >= ? AND created_at < ?",
                (rs, n) -> new SaleAgg(rs.getLong("cnt"), rs.getBigDecimal("subtotal"),
                        rs.getBigDecimal("line_disc"), rs.getBigDecimal("txn_disc"),
                        rs.getBigDecimal("tax"), rs.getBigDecimal("gross")),
                lo, hi);

        ReturnAgg r = jdbc.queryForObject(
                "SELECT COUNT(*) AS cnt, "
                        + "COALESCE(SUM(refund_grand_total), 0) AS refund, "
                        + "COALESCE(SUM(refund_tax_total), 0) AS refund_tax "
                        + "FROM sales_return WHERE created_at >= ? AND created_at < ?",
                (rs, n) -> new ReturnAgg(rs.getLong("cnt"), rs.getBigDecimal("refund"),
                        rs.getBigDecimal("refund_tax")),
                lo, hi);

        BigDecimal net = s.gross().subtract(r.refund());
        return new SalesSummaryReport(from, to, currency, s.cnt(), s.subtotal(), s.lineDisc(),
                s.txnDisc(), s.tax(), s.gross(), r.cnt(), r.refund(), net);
    }

    record SaleAgg(long cnt, BigDecimal subtotal, BigDecimal lineDisc, BigDecimal txnDisc,
            BigDecimal tax, BigDecimal gross) {
    }

    record ReturnAgg(long cnt, BigDecimal refund, BigDecimal refundTax) {
    }
}
```

- [ ] **Step 7: Create the service**

`src/main/java/com/company/pos/reporting/application/DefaultReportingService.java`:
```java
package com.company.pos.reporting.application;

import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.reporting.api.ReportingService;
import com.company.pos.reporting.api.SalesSummaryReport;
import com.company.pos.reporting.infrastructure.ReportingQueries;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultReportingService implements ReportingService {

    private final ReportingQueries queries;
    private final ConfigurationService config;

    DefaultReportingService(ReportingQueries queries, ConfigurationService config) {
        this.queries = queries;
        this.config = config;
    }

    @Override
    public SalesSummaryReport salesSummary(LocalDate from, LocalDate to) {
        ReportRanges.requireValid(from, to);
        String currency = config.getString(SettingKey.CURRENCY_CODE);
        return queries.salesSummary(from, to, ReportRanges.startOf(from), ReportRanges.endOf(to), currency);
    }
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=SalesSummaryReportTest`
Expected: PASS — all 3 tests green. **If `emptyRangeYieldsZeros` fails (returns count 1), the SQLite timestamp binding is wrong:** try binding `fromTs`/`toTs` as `Instant` directly (drop the `Timestamp.from(...)`), or as ISO-8601 strings, until the past-window correctly excludes the just-now sale. Document whichever binding works in a comment in `ReportingQueries`.

- [ ] **Step 9: Run ModularityTests**

Run: `./mvnw test -Dtest=ModularityTests`
Expected: PASS — `reporting` verifies with `{ common, database, configuration :: api }`.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/company/pos/reporting src/test/java/com/company/pos/reporting/SalesSummaryReportTest.java
git commit -m "feat(reporting): module skeleton + sales summary report (date-range binding proven)"
```

---

## Task 2: Payment breakdown + Tax summary reports

Two small aggregates over `payment` and `sale`/`sales_return`, added to the facade and query component.

**Files:**
- Create: `src/main/java/com/company/pos/reporting/api/PaymentBreakdownReport.java`
- Create: `src/main/java/com/company/pos/reporting/api/TaxSummaryReport.java`
- Modify: `src/main/java/com/company/pos/reporting/api/ReportingService.java`
- Modify: `src/main/java/com/company/pos/reporting/infrastructure/ReportingQueries.java`
- Modify: `src/main/java/com/company/pos/reporting/application/DefaultReportingService.java`
- Test: `src/test/java/com/company/pos/reporting/PaymentAndTaxReportTest.java`

**Interfaces:**
- Consumes: `ReportRanges`, `ReportingQueries`, `ConfigurationService` (Task 1).
- Produces:
  - `ReportingService.paymentBreakdown(LocalDate, LocalDate) → PaymentBreakdownReport`
  - `ReportingService.taxSummary(LocalDate, LocalDate) → TaxSummaryReport`
  - `PaymentBreakdownReport(LocalDate from, LocalDate to, String currencyCode, List<PaymentLine> lines, BigDecimal totalCollected, BigDecimal totalRefunded)` with `PaymentLine(String method, long count, BigDecimal collected, BigDecimal refunded)`
  - `TaxSummaryReport(LocalDate from, LocalDate to, String currencyCode, BigDecimal taxableAmount, BigDecimal taxCollected, BigDecimal refundTax, BigDecimal netTax)`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/reporting/PaymentAndTaxReportTest.java`:

```java
package com.company.pos.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.reporting.api.PaymentBreakdownReport;
import com.company.pos.reporting.api.ReportingService;
import com.company.pos.reporting.api.TaxSummaryReport;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class PaymentAndTaxReportTest {

    private static final LocalDate ALL_FROM = LocalDate.parse("2000-01-01");
    private static final LocalDate ALL_TO = LocalDate.parse("2100-01-01");

    @Autowired ReportingService reports;
    @Autowired SalesService salesService;
    @Autowired CartService carts;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired com.company.pos.device.infrastructure.InMemoryPaymentTerminal terminal;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
        terminal.setApprove(true);
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private void sellTwoColas(PaymentMethod method) {
        UUID cartId = carts.createCart();
        carts.addLine(cartId, "COLA", new BigDecimal("2")); // grand 10.35
        BigDecimal tendered = method == PaymentMethod.CASH ? new BigDecimal("20.00") : new BigDecimal("10.35");
        salesService.checkout(new CheckoutCommand(cartId,
                List.of(new TenderInput(method, new BigDecimal("10.35"), tendered))), "cashier");
    }

    @Test
    void paymentsGroupedByMethod() {
        sellTwoColas(PaymentMethod.CASH);
        sellTwoColas(PaymentMethod.CARD);

        PaymentBreakdownReport r = reports.paymentBreakdown(ALL_FROM, ALL_TO);

        assertThat(r.totalCollected()).isEqualByComparingTo("20.70");
        assertThat(r.lines()).anySatisfy(l -> {
            assertThat(l.method()).isEqualTo("CASH");
            assertThat(l.count()).isEqualTo(1);
            assertThat(l.collected()).isEqualByComparingTo("10.35");
        });
        assertThat(r.lines()).anySatisfy(l -> {
            assertThat(l.method()).isEqualTo("CARD");
            assertThat(l.collected()).isEqualByComparingTo("10.35");
        });
    }

    @Test
    void taxSummaryTotals() {
        sellTwoColas(PaymentMethod.CASH);

        TaxSummaryReport r = reports.taxSummary(ALL_FROM, ALL_TO);

        assertThat(r.taxableAmount()).isEqualByComparingTo("9.00");
        assertThat(r.taxCollected()).isEqualByComparingTo("1.35");
        assertThat(r.refundTax()).isEqualByComparingTo("0");
        assertThat(r.netTax()).isEqualByComparingTo("1.35");
        assertThat(r.currencyCode()).isEqualTo("SAR");
    }
}
```

> Note on the CASH tender: passing an explicit amount `10.35` with `tendered` `20.00` records a cash payment of `10.35` (change `9.65`). The `TenderInput(method, amount, tendered)` shape is `(PaymentMethod, BigDecimal amount, BigDecimal tendered)`; for CARD, `tendered` is ignored by the terminal path.

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=PaymentAndTaxReportTest`
Expected: FAIL — `paymentBreakdown` / `taxSummary` not defined on `ReportingService`.

- [ ] **Step 3: Create the two DTOs**

`src/main/java/com/company/pos/reporting/api/PaymentBreakdownReport.java`:
```java
package com.company.pos.reporting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PaymentBreakdownReport(LocalDate from, LocalDate to, String currencyCode,
        List<PaymentLine> lines, BigDecimal totalCollected, BigDecimal totalRefunded) {

    public record PaymentLine(String method, long count, BigDecimal collected, BigDecimal refunded) {
    }
}
```

`src/main/java/com/company/pos/reporting/api/TaxSummaryReport.java`:
```java
package com.company.pos.reporting.api;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TaxSummaryReport(LocalDate from, LocalDate to, String currencyCode,
        BigDecimal taxableAmount, BigDecimal taxCollected, BigDecimal refundTax, BigDecimal netTax) {
}
```

- [ ] **Step 4: Add the facade methods**

In `src/main/java/com/company/pos/reporting/api/ReportingService.java`, add:
```java
    PaymentBreakdownReport paymentBreakdown(LocalDate from, LocalDate to);

    TaxSummaryReport taxSummary(LocalDate from, LocalDate to);
```

- [ ] **Step 5: Add the queries**

In `src/main/java/com/company/pos/reporting/infrastructure/ReportingQueries.java`, add imports `java.util.ArrayList`, `java.util.List`, `com.company.pos.reporting.api.PaymentBreakdownReport`, `com.company.pos.reporting.api.PaymentBreakdownReport.PaymentLine`, `com.company.pos.reporting.api.TaxSummaryReport`, and these methods:

```java
    public PaymentBreakdownReport paymentBreakdown(LocalDate from, LocalDate to, Instant fromTs,
            Instant toTs, String currency) {
        List<MethodDirTotal> rows = jdbc.query(
                "SELECT method, txn_type AS direction, COUNT(*) AS cnt, "
                        + "COALESCE(SUM(amount), 0) AS total "
                        + "FROM payment WHERE created_at >= ? AND created_at < ? "
                        + "GROUP BY method, txn_type",
                (rs, n) -> new MethodDirTotal(rs.getString("method"), rs.getString("direction"),
                        rs.getLong("cnt"), rs.getBigDecimal("total")),
                Timestamp.from(fromTs), Timestamp.from(toTs));

        java.util.Map<String, PaymentLine> byMethod = new java.util.LinkedHashMap<>();
        BigDecimal totalCollected = BigDecimal.ZERO;
        BigDecimal totalRefunded = BigDecimal.ZERO;
        for (MethodDirTotal row : rows) {
            PaymentLine cur = byMethod.getOrDefault(row.method(),
                    new PaymentLine(row.method(), 0, BigDecimal.ZERO, BigDecimal.ZERO));
            if ("SALE".equals(row.direction())) {
                cur = new PaymentLine(row.method(), row.cnt(), cur.collected().add(row.total()),
                        cur.refunded());
                totalCollected = totalCollected.add(row.total());
            } else {
                cur = new PaymentLine(row.method(), cur.count(), cur.collected(),
                        cur.refunded().add(row.total()));
                totalRefunded = totalRefunded.add(row.total());
            }
            byMethod.put(row.method(), cur);
        }
        return new PaymentBreakdownReport(from, to, currency, new ArrayList<>(byMethod.values()),
                totalCollected, totalRefunded);
    }

    public TaxSummaryReport taxSummary(LocalDate from, LocalDate to, Instant fromTs, Instant toTs,
            String currency) {
        Timestamp lo = Timestamp.from(fromTs);
        Timestamp hi = Timestamp.from(toTs);
        SaleAgg s = jdbc.queryForObject(
                "SELECT COUNT(*) AS cnt, COALESCE(SUM(subtotal), 0) AS subtotal, "
                        + "0 AS line_disc, 0 AS txn_disc, COALESCE(SUM(tax_total), 0) AS tax, "
                        + "0 AS gross FROM sale "
                        + "WHERE status = 'COMPLETED' AND created_at >= ? AND created_at < ?",
                (rs, n) -> new SaleAgg(rs.getLong("cnt"), rs.getBigDecimal("subtotal"),
                        rs.getBigDecimal("line_disc"), rs.getBigDecimal("txn_disc"),
                        rs.getBigDecimal("tax"), rs.getBigDecimal("gross")),
                lo, hi);
        BigDecimal refundTax = jdbc.queryForObject(
                "SELECT COALESCE(SUM(refund_tax_total), 0) FROM sales_return "
                        + "WHERE created_at >= ? AND created_at < ?",
                BigDecimal.class, lo, hi);
        BigDecimal netTax = s.tax().subtract(refundTax);
        return new TaxSummaryReport(from, to, currency, s.subtotal(), s.tax(), refundTax, netTax);
    }

    record MethodDirTotal(String method, String direction, long cnt, BigDecimal total) {
    }
```

- [ ] **Step 6: Add the service methods**

In `src/main/java/com/company/pos/reporting/application/DefaultReportingService.java`, add imports for the two new DTOs and:
```java
    @Override
    public PaymentBreakdownReport paymentBreakdown(LocalDate from, LocalDate to) {
        ReportRanges.requireValid(from, to);
        String currency = config.getString(SettingKey.CURRENCY_CODE);
        return queries.paymentBreakdown(from, to, ReportRanges.startOf(from), ReportRanges.endOf(to), currency);
    }

    @Override
    public TaxSummaryReport taxSummary(LocalDate from, LocalDate to) {
        ReportRanges.requireValid(from, to);
        String currency = config.getString(SettingKey.CURRENCY_CODE);
        return queries.taxSummary(from, to, ReportRanges.startOf(from), ReportRanges.endOf(to), currency);
    }
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=PaymentAndTaxReportTest`
Expected: PASS.

- [ ] **Step 8: Run ModularityTests + the Task 1 test (no regression)**

Run: `./mvnw test -Dtest=ModularityTests,SalesSummaryReportTest,PaymentAndTaxReportTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/company/pos/reporting src/test/java/com/company/pos/reporting/PaymentAndTaxReportTest.java
git commit -m "feat(reporting): payment breakdown + tax summary reports"
```

---

## Task 3: Cashier report + Product performance report

Two GROUP BY list reports over `sale` and `sale_line JOIN sale`.

**Files:**
- Create: `src/main/java/com/company/pos/reporting/api/CashierReport.java`
- Create: `src/main/java/com/company/pos/reporting/api/ProductPerformanceReport.java`
- Modify: `src/main/java/com/company/pos/reporting/api/ReportingService.java`
- Modify: `src/main/java/com/company/pos/reporting/infrastructure/ReportingQueries.java`
- Modify: `src/main/java/com/company/pos/reporting/application/DefaultReportingService.java`
- Test: `src/test/java/com/company/pos/reporting/CashierAndProductReportTest.java`

**Interfaces:**
- Produces:
  - `ReportingService.cashierReport(LocalDate, LocalDate) → CashierReport`
  - `ReportingService.productPerformance(LocalDate, LocalDate, int limit) → ProductPerformanceReport`
  - `CashierReport(LocalDate from, LocalDate to, String currencyCode, List<CashierLine> lines)` with `CashierLine(String cashierUsername, long saleCount, BigDecimal totalSales, BigDecimal totalDiscounts)`
  - `ProductPerformanceReport(LocalDate from, LocalDate to, String currencyCode, List<ProductLine> lines)` with `ProductLine(String sku, String name, BigDecimal quantitySold, BigDecimal revenue, BigDecimal discounts)`
  - Service clamps `limit` to `[1, 500]`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/reporting/CashierAndProductReportTest.java`:

```java
package com.company.pos.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.reporting.api.CashierReport;
import com.company.pos.reporting.api.ProductPerformanceReport;
import com.company.pos.reporting.api.ReportingService;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class CashierAndProductReportTest {

    private static final LocalDate ALL_FROM = LocalDate.parse("2000-01-01");
    private static final LocalDate ALL_TO = LocalDate.parse("2100-01-01");

    @Autowired ReportingService reports;
    @Autowired SalesService salesService;
    @Autowired CartService carts;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("CHIP", "Chips", "SNK", "Snacks", "bcCHIP",
                "EA", new BigDecimal("2.00"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private void sell(String cashier, String sku, String qty) {
        UUID cartId = carts.createCart();
        carts.addLine(cartId, sku, new BigDecimal(qty));
        salesService.checkout(new CheckoutCommand(cartId,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("1000.00")))), cashier);
    }

    @Test
    void cashierReportGroupsByCashier() {
        sell("alice", "COLA", "2"); // grand 10.35
        sell("alice", "CHIP", "1"); // 2.00 net, 0.30 tax, 2.30 grand
        sell("bob", "COLA", "1");   // 4.50 net, 0.675->0.68 tax, ~5.18 grand

        CashierReport r = reports.cashierReport(ALL_FROM, ALL_TO);

        CashierReport.CashierLine alice = r.lines().stream()
                .filter(l -> l.cashierUsername().equals("alice")).findFirst().orElseThrow();
        assertThat(alice.saleCount()).isEqualTo(2);
        assertThat(alice.totalSales()).isEqualByComparingTo("12.65"); // 10.35 + 2.30
        // Ordered by totalSales desc: alice (12.65) before bob.
        assertThat(r.lines().get(0).cashierUsername()).isEqualTo("alice");
    }

    @Test
    void productPerformanceRanksByRevenue() {
        sell("alice", "COLA", "2"); // COLA revenue 10.35 (line_total incl tax)
        sell("bob", "CHIP", "1");   // CHIP revenue 2.30

        ProductPerformanceReport r = reports.productPerformance(ALL_FROM, ALL_TO, 50);

        assertThat(r.lines().get(0).sku()).isEqualTo("COLA");
        assertThat(r.lines().get(0).quantitySold()).isEqualByComparingTo("2");
        assertThat(r.lines()).anySatisfy(l -> assertThat(l.sku()).isEqualTo("CHIP"));
    }

    @Test
    void productLimitClampsToAtLeastOne() {
        sell("alice", "COLA", "1");
        sell("bob", "CHIP", "1");
        assertThat(reports.productPerformance(ALL_FROM, ALL_TO, 1).lines()).hasSize(1);
        assertThat(reports.productPerformance(ALL_FROM, ALL_TO, 0).lines()).hasSize(1); // clamped to 1
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=CashierAndProductReportTest`
Expected: FAIL — `cashierReport` / `productPerformance` not defined.

- [ ] **Step 3: Create the two DTOs**

`src/main/java/com/company/pos/reporting/api/CashierReport.java`:
```java
package com.company.pos.reporting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CashierReport(LocalDate from, LocalDate to, String currencyCode,
        List<CashierLine> lines) {

    public record CashierLine(String cashierUsername, long saleCount, BigDecimal totalSales,
            BigDecimal totalDiscounts) {
    }
}
```

`src/main/java/com/company/pos/reporting/api/ProductPerformanceReport.java`:
```java
package com.company.pos.reporting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ProductPerformanceReport(LocalDate from, LocalDate to, String currencyCode,
        List<ProductLine> lines) {

    public record ProductLine(String sku, String name, BigDecimal quantitySold, BigDecimal revenue,
            BigDecimal discounts) {
    }
}
```

- [ ] **Step 4: Add the facade methods**

In `ReportingService.java`, add:
```java
    CashierReport cashierReport(LocalDate from, LocalDate to);

    ProductPerformanceReport productPerformance(LocalDate from, LocalDate to, int limit);
```

- [ ] **Step 5: Add the queries**

In `ReportingQueries.java`, add imports for `CashierReport`, `CashierReport.CashierLine`, `ProductPerformanceReport`, `ProductPerformanceReport.ProductLine`, and:

```java
    public CashierReport cashierReport(LocalDate from, LocalDate to, Instant fromTs, Instant toTs,
            String currency) {
        List<CashierLine> lines = jdbc.query(
                "SELECT cashier_username, COUNT(*) AS cnt, "
                        + "COALESCE(SUM(grand_total), 0) AS total_sales, "
                        + "COALESCE(SUM(discount_total + txn_discount_amount), 0) AS total_disc "
                        + "FROM sale WHERE status = 'COMPLETED' AND created_at >= ? AND created_at < ? "
                        + "GROUP BY cashier_username ORDER BY total_sales DESC",
                (rs, n) -> new CashierLine(rs.getString("cashier_username"), rs.getLong("cnt"),
                        rs.getBigDecimal("total_sales"), rs.getBigDecimal("total_disc")),
                Timestamp.from(fromTs), Timestamp.from(toTs));
        return new CashierReport(from, to, currency, lines);
    }

    public ProductPerformanceReport productPerformance(LocalDate from, LocalDate to, Instant fromTs,
            Instant toTs, String currency, int limit) {
        List<ProductLine> lines = jdbc.query(
                "SELECT sl.sku, sl.name, COALESCE(SUM(sl.quantity), 0) AS qty, "
                        + "COALESCE(SUM(sl.line_total), 0) AS revenue, "
                        + "COALESCE(SUM(sl.line_discount_amount), 0) AS discounts "
                        + "FROM sale_line sl JOIN sale s ON sl.sale_id = s.id "
                        + "WHERE s.status = 'COMPLETED' AND s.created_at >= ? AND s.created_at < ? "
                        + "GROUP BY sl.sku, sl.name ORDER BY revenue DESC LIMIT ?",
                (rs, n) -> new ProductLine(rs.getString("sku"), rs.getString("name"),
                        rs.getBigDecimal("qty"), rs.getBigDecimal("revenue"),
                        rs.getBigDecimal("discounts")),
                Timestamp.from(fromTs), Timestamp.from(toTs), limit);
        return new ProductPerformanceReport(from, to, currency, lines);
    }
```

- [ ] **Step 6: Add the service methods (with limit clamping)**

In `DefaultReportingService.java`, add imports for the two DTOs and:
```java
    @Override
    public CashierReport cashierReport(LocalDate from, LocalDate to) {
        ReportRanges.requireValid(from, to);
        String currency = config.getString(SettingKey.CURRENCY_CODE);
        return queries.cashierReport(from, to, ReportRanges.startOf(from), ReportRanges.endOf(to), currency);
    }

    @Override
    public ProductPerformanceReport productPerformance(LocalDate from, LocalDate to, int limit) {
        ReportRanges.requireValid(from, to);
        int clamped = Math.max(1, Math.min(limit, 500));
        String currency = config.getString(SettingKey.CURRENCY_CODE);
        return queries.productPerformance(from, to, ReportRanges.startOf(from), ReportRanges.endOf(to),
                currency, clamped);
    }
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=CashierAndProductReportTest`
Expected: PASS.

- [ ] **Step 8: Run ModularityTests + all reporting tests**

Run: `./mvnw test -Dtest=ModularityTests,'com.company.pos.reporting.*'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/company/pos/reporting src/test/java/com/company/pos/reporting/CashierAndProductReportTest.java
git commit -m "feat(reporting): cashier + product performance reports"
```

---

## Task 4: ReportingController — JSON endpoints + method security

Exposes all five reports over HTTP, MANAGER/ADMIN-gated.

**Files:**
- Create: `src/main/java/com/company/pos/reporting/web/ReportingController.java`
- Test: `src/test/java/com/company/pos/reporting/ReportingControllerTest.java`

**Interfaces:**
- Consumes: `ReportingService` (all 5 methods).
- Produces: `GET /reports/sales`, `/reports/payments`, `/reports/tax`, `/reports/cashiers`, `/reports/products` — each `?from=&to=` (`LocalDate`, ISO), products also `?limit=` (default 50). All `hasAnyRole('MANAGER','ADMIN')`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/reporting/ReportingControllerTest.java`:

```java
package com.company.pos.reporting;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class ReportingControllerTest {

    @Autowired MockMvc mvc;

    private static RequestPostProcessor role(String r) {
        return jwt().jwt(j -> j.subject("u")).authorities(new SimpleGrantedAuthority("ROLE_" + r));
    }

    @Test
    void cashierIsForbidden() throws Exception {
        mvc.perform(get("/reports/sales").param("from", "2000-01-01").param("to", "2100-01-01")
                        .with(role("CASHIER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerGetsSalesSummary() throws Exception {
        mvc.perform(get("/reports/sales").param("from", "2000-01-01").param("to", "2100-01-01")
                        .with(role("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencyCode").value("SAR"))
                .andExpect(jsonPath("$.saleCount").exists())
                .andExpect(jsonPath("$.netSales").exists());
    }

    @Test
    void adminGetsAllReportEndpoints() throws Exception {
        for (String path : new String[] { "/reports/sales", "/reports/payments", "/reports/tax",
                "/reports/cashiers", "/reports/products" }) {
            mvc.perform(get(path).param("from", "2000-01-01").param("to", "2100-01-01")
                            .with(role("ADMIN")))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void invertedRangeIs400() throws Exception {
        mvc.perform(get("/reports/sales").param("from", "2100-01-01").param("to", "2000-01-01")
                        .with(role("MANAGER")))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=ReportingControllerTest`
Expected: FAIL — 404 (no controller) on the report paths.

- [ ] **Step 3: Create the controller**

`src/main/java/com/company/pos/reporting/web/ReportingController.java`:
```java
package com.company.pos.reporting.web;

import com.company.pos.reporting.api.CashierReport;
import com.company.pos.reporting.api.PaymentBreakdownReport;
import com.company.pos.reporting.api.ProductPerformanceReport;
import com.company.pos.reporting.api.ReportingService;
import com.company.pos.reporting.api.SalesSummaryReport;
import com.company.pos.reporting.api.TaxSummaryReport;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
class ReportingController {

    private final ReportingService reports;

    ReportingController(ReportingService reports) {
        this.reports = reports;
    }

    @GetMapping("/reports/sales")
    SalesSummaryReport sales(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.salesSummary(from, to);
    }

    @GetMapping("/reports/payments")
    PaymentBreakdownReport payments(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.paymentBreakdown(from, to);
    }

    @GetMapping("/reports/tax")
    TaxSummaryReport tax(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.taxSummary(from, to);
    }

    @GetMapping("/reports/cashiers")
    CashierReport cashiers(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.cashierReport(from, to);
    }

    @GetMapping("/reports/products")
    ProductPerformanceReport products(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "50") int limit) {
        return reports.productPerformance(from, to, limit);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=ReportingControllerTest`
Expected: PASS. (`DomainException.validation` maps to 400 via the existing `ApiExceptionHandler`; the CASHIER 403 comes from `@PreAuthorize`.)

- [ ] **Step 5: Run ModularityTests**

Run: `./mvnw test -Dtest=ModularityTests`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/reporting/web/ReportingController.java src/test/java/com/company/pos/reporting/ReportingControllerTest.java
git commit -m "feat(reporting): MANAGER/ADMIN JSON report endpoints"
```

---

## Task 5: CSV export (`?format=csv`)

Adds a CSV variant to every report endpoint via a `?format=csv` param, using a small hand-rolled writer (no new dependency).

**Files:**
- Create: `src/main/java/com/company/pos/reporting/web/ReportCsv.java`
- Modify: `src/main/java/com/company/pos/reporting/web/ReportingController.java`
- Test: `src/test/java/com/company/pos/reporting/ReportingCsvTest.java`

**Interfaces:**
- Consumes: the 5 report DTOs.
- Produces: each endpoint honors `?format=csv` → `Content-Type: text/csv`, returning header + rows; default JSON unchanged.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/reporting/ReportingCsvTest.java`:

```java
package com.company.pos.reporting;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class ReportingCsvTest {

    @Autowired MockMvc mvc;

    private static RequestPostProcessor manager() {
        return jwt().jwt(j -> j.subject("u")).authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    @Test
    void salesCsvHasHeaderAndContentType() throws Exception {
        mvc.perform(get("/reports/sales").param("from", "2000-01-01").param("to", "2100-01-01")
                        .param("format", "csv").with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "from,to,currencyCode,saleCount,subtotal,lineDiscounts,txnDiscounts,taxTotal,grossSales,returnCount,refundTotal,netSales")));
    }

    @Test
    void productsCsvHasHeaderRow() throws Exception {
        mvc.perform(get("/reports/products").param("from", "2000-01-01").param("to", "2100-01-01")
                        .param("format", "csv").with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "sku,name,quantitySold,revenue,discounts")));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=ReportingCsvTest`
Expected: FAIL — the `format` param is ignored; response is JSON, not `text/csv`.

- [ ] **Step 3: Create the CSV writer**

`src/main/java/com/company/pos/reporting/web/ReportCsv.java`:
```java
package com.company.pos.reporting.web;

import com.company.pos.reporting.api.CashierReport;
import com.company.pos.reporting.api.PaymentBreakdownReport;
import com.company.pos.reporting.api.ProductPerformanceReport;
import com.company.pos.reporting.api.SalesSummaryReport;
import com.company.pos.reporting.api.TaxSummaryReport;

final class ReportCsv {

    private ReportCsv() {
    }

    static String of(SalesSummaryReport r) {
        String header = "from,to,currencyCode,saleCount,subtotal,lineDiscounts,txnDiscounts,"
                + "taxTotal,grossSales,returnCount,refundTotal,netSales";
        String row = String.join(",", s(r.from()), s(r.to()), r.currencyCode(),
                String.valueOf(r.saleCount()), s(r.subtotal()), s(r.lineDiscounts()),
                s(r.txnDiscounts()), s(r.taxTotal()), s(r.grossSales()),
                String.valueOf(r.returnCount()), s(r.refundTotal()), s(r.netSales()));
        return header + "\n" + row + "\n";
    }

    static String of(TaxSummaryReport r) {
        String header = "from,to,currencyCode,taxableAmount,taxCollected,refundTax,netTax";
        String row = String.join(",", s(r.from()), s(r.to()), r.currencyCode(),
                s(r.taxableAmount()), s(r.taxCollected()), s(r.refundTax()), s(r.netTax()));
        return header + "\n" + row + "\n";
    }

    static String of(PaymentBreakdownReport r) {
        StringBuilder sb = new StringBuilder("method,count,collected,refunded\n");
        for (PaymentBreakdownReport.PaymentLine l : r.lines()) {
            sb.append(String.join(",", l.method(), String.valueOf(l.count()),
                    s(l.collected()), s(l.refunded()))).append('\n');
        }
        return sb.toString();
    }

    static String of(CashierReport r) {
        StringBuilder sb = new StringBuilder("cashierUsername,saleCount,totalSales,totalDiscounts\n");
        for (CashierReport.CashierLine l : r.lines()) {
            sb.append(String.join(",", l.cashierUsername(), String.valueOf(l.saleCount()),
                    s(l.totalSales()), s(l.totalDiscounts()))).append('\n');
        }
        return sb.toString();
    }

    static String of(ProductPerformanceReport r) {
        StringBuilder sb = new StringBuilder("sku,name,quantitySold,revenue,discounts\n");
        for (ProductPerformanceReport.ProductLine l : r.lines()) {
            sb.append(String.join(",", l.sku(), csv(l.name()), s(l.quantitySold()),
                    s(l.revenue()), s(l.discounts()))).append('\n');
        }
        return sb.toString();
    }

    private static String s(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
```

- [ ] **Step 4: Add the `format` param + CSV branch to each endpoint**

In `ReportingController.java`, add imports:
```java
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
```
Change each endpoint to accept `@RequestParam(defaultValue = "json") String format` and return `ResponseEntity<?>`, returning CSV when requested. For example, the sales endpoint becomes:
```java
    @GetMapping("/reports/sales")
    ResponseEntity<?> sales(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "json") String format) {
        SalesSummaryReport r = reports.salesSummary(from, to);
        return "csv".equalsIgnoreCase(format)
                ? ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv")).body(ReportCsv.of(r))
                : ResponseEntity.ok(r);
    }
```
Apply the same pattern to `payments` (`ReportCsv.of(paymentBreakdown)`), `tax`, `cashiers`, and `products` (keep the `limit` param on products). Each returns `ResponseEntity<?>` — JSON DTO by default, `text/csv` string when `format=csv`.

- [ ] **Step 5: Run the CSV test + the JSON controller test (no regression)**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=ReportingCsvTest,ReportingControllerTest`
Expected: PASS — CSV returns `text/csv` with the header; JSON responses still 200 with the DTO body.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/reporting/web src/test/java/com/company/pos/reporting/ReportingCsvTest.java
git commit -m "feat(reporting): CSV export variant for all report endpoints"
```

---

## Task 6: Postgres reporting test, docs, and full verify

Proves the native SQL + timestamp binding on Postgres (not just SQLite), documents the module, and runs the full gate.

**Files:**
- Test: `src/test/java/com/company/pos/reporting/ReportingPostgresTest.java`
- Modify: `docs/run-modes.md`

**Interfaces:**
- Consumes: all prior tasks.

- [ ] **Step 1: Write the Postgres reporting test**

Create `src/test/java/com/company/pos/reporting/ReportingPostgresTest.java`. It mirrors `DatabaseStoreServerTest`'s Testcontainers harness (store-server profile, Flyway runs the real migrations) and drives a real checkout, then asserts the sales-summary range filter and totals work on Postgres — proving the `created_at` binding on the other dialect.

```java
package com.company.pos.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.reporting.api.ReportingService;
import com.company.pos.reporting.api.SalesSummaryReport;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("store-server")
@Testcontainers
@TestPropertySource(properties = "pos.auth.jwt.secret=test-only-secret-not-for-production-use-abc123")
class ReportingPostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired ReportingService reports;
    @Autowired SalesService salesService;
    @Autowired CartService carts;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;

    @Test
    void salesSummaryWorksOnPostgres() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();

        UUID cartId = carts.createCart();
        carts.addLine(cartId, "COLA", new BigDecimal("2"));
        salesService.checkout(new CheckoutCommand(cartId,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))), "cashier");

        SalesSummaryReport all = reports.salesSummary(LocalDate.parse("2000-01-01"), LocalDate.parse("2100-01-01"));
        assertThat(all.saleCount()).isEqualTo(1);
        assertThat(all.grossSales()).isEqualByComparingTo("10.35");

        SalesSummaryReport past = reports.salesSummary(LocalDate.parse("2019-01-01"), LocalDate.parse("2019-12-31"));
        assertThat(past.saleCount()).isZero();
    }
}
```

> Note: this test requires a running Docker daemon (like `DatabaseStoreServerTest`). If Docker is unavailable in the environment, it is skipped/errors the same way that test does — report that explicitly rather than as a reporting failure.

- [ ] **Step 2: Run the Postgres test**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=ReportingPostgresTest`
Expected: PASS (with Docker running). If the `Timestamp.from(...)` binding chosen in Task 1 works on SQLite but not Postgres (or vice-versa), reconcile to a binding that passes BOTH `SalesSummaryReportTest` (embedded) and this test (Postgres) — that dual-dialect pass is the whole point of this task.

- [ ] **Step 3: Document the module in run-modes.md**

Add a "Reporting (Phase 8)" section to `docs/run-modes.md` matching the depth/style of the existing "Customer (Phase 7)" and "Audit Trail (Phase 6)" sections. Cover: the five endpoints (table with roles + params), the `?format=csv` variant, MANAGER/ADMIN authorization via `hasAnyRole`, the read-only native-SQL design (reporting owns no tables, reads source tables directly — the accepted schema-level coupling, no code dependency, no migration), the UTC-day date-range semantics (and that `STORE_TIMEZONE` is deferred), and the deferred items (profit/COGS, hourly/inventory, JasperReports, event-sourced read model).

- [ ] **Step 4: Full verify (the gate)**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw clean verify`
Expected: BUILD SUCCESS. Includes `ModularityTests`, all reporting tests, and the Testcontainers Postgres tests (`DatabaseStoreServerTest` + `ReportingPostgresTest`). Confirm the report totals in every reporting test are green and that no other module regressed.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/company/pos/reporting/ReportingPostgresTest.java docs/run-modes.md
git commit -m "test(reporting): Postgres dual-dialect proof + docs; full verify green"
```

---

## Self-Review

**Spec coverage:**
- Sales summary → Task 1. Payment breakdown + tax summary → Task 2. Cashier + product performance → Task 3. All five covered. ✓
- Reporting-owned native-SQL read layer, no entity imports, no migration → Tasks 1–3 (`ReportingQueries` via `JdbcTemplate`), Global Constraints. ✓
- `allowedDependencies = { common, database, configuration::api }`, acyclic → Task 1 package-info + ModularityTests each task. ✓
- Date range as UTC-day LocalDate bounds; `from > to` → 400 → `ReportRanges` (Task 1) + controller test (Task 4). ✓
- Timestamp-binding portability → Task 1 empty-range probe (SQLite) + Task 6 Postgres test. ✓
- `hasAnyRole('MANAGER','ADMIN')`, cashier 403 → Task 4 (class-level) + tests. ✓
- JSON + CSV (`?format=csv`, text/csv) → Task 4 (JSON) + Task 5 (CSV) + tests. ✓
- `limit` clamped `[1,500]`, default 50 → Task 3 (service) + Task 4 (param default). ✓
- Currency from configuration → `DefaultReportingService` (Task 1). ✓
- Full `./mvnw clean verify` incl. Postgres → Task 6. ✓

**Placeholder scan:** No `TBD`/`TODO`. Task 1 Step 8 and Task 6 Step 2 give a concrete fallback instruction for the one empirically-determined detail (timestamp binding) — this is a deliberate probe-and-adjust gate, not a missing value; the primary binding (`Timestamp.from(instant)`) is specified.

**Type consistency:** `ReportingService` accrues exactly five methods (`salesSummary`, `paymentBreakdown`, `taxSummary`, `cashierReport`, `productPerformance`) across Tasks 1–3, each matching its controller call in Task 4. DTO record shapes are defined once (Tasks 1–3) and consumed unchanged by the controller (Task 4), CSV writer (Task 5), and tests. `ReportingQueries` method signatures `(LocalDate, LocalDate, Instant, Instant, String[, int])` match their `DefaultReportingService` call sites. `txn_type` (not `direction`) is used in the payment SQL, and `discount_total`/`txn_discount_amount` are used per the verified schema.
