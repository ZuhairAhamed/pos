# Dashboard Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `dashboard` module exposing at-a-glance operational insight (today's sales, revenue, best sellers, low-stock items, open shifts, active cashiers) composed on-demand from existing module facades.

**Architecture:** A Tier-1, pure read-only composition layer. It runs no SQL of its own — `DefaultDashboardService` calls `reporting :: api`, `inventory :: api`, `shift :: api`, and `product :: api` facades and assembles their results into dashboard DTOs. Two owning modules (`inventory`, `shift`) gain a small list-query facade method they don't yet expose. No new tables, no Flyway migration, no events.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Modulith, Spring Data JPA, JUnit 5 + AssertJ + MockMvc. Maven (`./mvnw`).

## Global Constraints

- **JDK 21 required.** Set `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` before any Maven command.
- **Build tool is Maven** (`./mvnw`), never Gradle.
- **Module boundaries are enforced** by `ModularityTests` (`modules.verify()`). A module may import only another module's `@NamedInterface` (its `api`/`erp` sub-package), and only if listed in the consuming module's `package-info.java` `allowedDependencies`.
- **Money is `BigDecimal`** — never `double`.
- **Authorization is method security** via `@PreAuthorize`, not URL rules. Roles are a non-hierarchical `Set<Role>`; use `hasAnyRole('MANAGER','ADMIN')`.
- **Typed config lives in the `configuration` settings store** (`SettingKey` enum), read through `configuration :: api` — never hardcoded.
- **Tests default to** `@SpringBootTest @ActiveProfiles("embedded")` (in-memory SQLite, no Docker).
- **After any change, re-run** the affected module's tests and `ModularityTests`.
- **`dashboard` owns no tables** — no Flyway migration is added; V22 remains the latest version.

---

### Task 1: `inventory :: api` — `listLowStock()`

Adds a list query for stock rows at or below their reorder level. Inventory returns only what it owns (sku / on-hand / reorder-level); the product name is enriched later by `dashboard`.

**Files:**
- Create: `src/main/java/com/company/pos/inventory/api/LowStockItem.java`
- Modify: `src/main/java/com/company/pos/inventory/api/InventoryService.java`
- Modify: `src/main/java/com/company/pos/inventory/infrastructure/StockLevelRepository.java`
- Modify: `src/main/java/com/company/pos/inventory/application/DefaultInventoryService.java`
- Test: `src/test/java/com/company/pos/inventory/InventoryLowStockTest.java`

**Interfaces:**
- Produces: `record LowStockItem(String sku, BigDecimal onHand, BigDecimal reorderLevel)` in `inventory.api`; `List<LowStockItem> InventoryService.listLowStock()`.
- Consumes: existing `StockLevel` entity (`getSku()`, `getQuantityOnHand()`, `getReorderLevel()`).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/inventory/InventoryLowStockTest.java`. It seeds two ERP stock levels through `InventorySync` with a reorder level of 100 (via `pos.inventory.reorder-level`), so a quantity of 5 is below reorder but 500 is not.

```java
package com.company.pos.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.inventory.api.InventoryService;
import com.company.pos.inventory.api.InventorySync;
import com.company.pos.inventory.api.LowStockItem;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
@TestPropertySource(properties = "pos.inventory.reorder-level=100")
class InventoryLowStockTest {

    @Autowired InventoryService inventory;
    @Autowired InventorySync inventorySync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addStockLevel(new ErpStockLevel("LOW", "MAIN", new BigDecimal("5"), 1));
        fake.addStockLevel(new ErpStockLevel("OK", "MAIN", new BigDecimal("500"), 2));
        inventorySync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    @Test
    void listsOnlyRowsBelowReorderLevel() {
        List<LowStockItem> low = inventory.listLowStock();

        assertThat(low).hasSize(1);
        assertThat(low.get(0).sku()).isEqualTo("LOW");
        assertThat(low.get(0).onHand()).isEqualByComparingTo("5");
        assertThat(low.get(0).reorderLevel()).isEqualByComparingTo("100");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=InventoryLowStockTest
```
Expected: FAIL — compilation error, `LowStockItem` and `listLowStock` do not exist.

- [ ] **Step 3: Create the `LowStockItem` DTO**

Create `src/main/java/com/company/pos/inventory/api/LowStockItem.java`:

```java
package com.company.pos.inventory.api;

import java.math.BigDecimal;

public record LowStockItem(String sku, BigDecimal onHand, BigDecimal reorderLevel) {
}
```

- [ ] **Step 4: Add the facade method**

In `src/main/java/com/company/pos/inventory/api/InventoryService.java`, add the import and method:

```java
package com.company.pos.inventory.api;

import java.util.List;
import java.util.Optional;

public interface InventoryService {

    Optional<StockView> onHand(String sku);

    List<LowStockItem> listLowStock();
}
```

- [ ] **Step 5: Add the repository query**

In `src/main/java/com/company/pos/inventory/infrastructure/StockLevelRepository.java`, add the `@Query` (JPQL — arithmetic in `order by` is dialect-neutral across SQLite and Postgres):

```java
package com.company.pos.inventory.infrastructure;

import com.company.pos.inventory.domain.StockLevel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StockLevelRepository extends JpaRepository<StockLevel, UUID> {

    Optional<StockLevel> findBySkuAndLocationCode(String sku, String locationCode);

    List<StockLevel> findBySku(String sku);

    @Query("select s from StockLevel s where s.reorderLevel > 0 and s.quantityOnHand < s.reorderLevel "
            + "order by s.reorderLevel - s.quantityOnHand desc")
    List<StockLevel> findLowStock();
}
```

- [ ] **Step 6: Implement the service method**

In `src/main/java/com/company/pos/inventory/application/DefaultInventoryService.java`, add the import for `LowStockItem` and the method:

```java
    @Override
    public List<LowStockItem> listLowStock() {
        return stock.findLowStock().stream()
                .map(s -> new LowStockItem(s.getSku(), s.getQuantityOnHand(), s.getReorderLevel()))
                .toList();
    }
```

(Add `import com.company.pos.inventory.api.LowStockItem;` to the existing imports.)

- [ ] **Step 7: Run test to verify it passes**

```bash
./mvnw test -Dtest=InventoryLowStockTest
```
Expected: PASS.

- [ ] **Step 8: Run ModularityTests**

```bash
./mvnw test -Dtest=ModularityTests
```
Expected: PASS — no new cross-module dependency introduced (`LowStockItem` lives in `inventory.api`).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/company/pos/inventory src/test/java/com/company/pos/inventory/InventoryLowStockTest.java
git commit -m "feat(inventory): listLowStock() facade query for dashboard"
```

---

### Task 2: `shift :: api` — `listOpenShifts()`

Adds a list query for all currently-open shifts. Reuses the existing `ShiftView` record.

**Files:**
- Modify: `src/main/java/com/company/pos/shift/api/ShiftService.java`
- Modify: `src/main/java/com/company/pos/shift/infrastructure/ShiftRepository.java`
- Modify: `src/main/java/com/company/pos/shift/application/DefaultShiftService.java`
- Test: `src/test/java/com/company/pos/shift/ShiftServiceTest.java` (add a method)

**Interfaces:**
- Produces: `List<ShiftView> ShiftService.listOpenShifts()`.
- Consumes: existing `ShiftView(UUID shiftId, String terminalId, String openedBy, String status, String currencyCode, Instant openedAt, Instant closedAt)`, existing `toView(Shift)` mapper.

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/company/pos/shift/ShiftServiceTest.java`. Add the imports `java.util.List` and `com.company.pos.shift.api.ShiftView` if not present, then:

```java
    @Test
    void listOpenShiftsReturnsOnlyOpenOnes() {
        ShiftView open = shifts.openShift("T01", new BigDecimal("100.00"), "alice");
        ShiftView toClose = shifts.openShift("T02", new BigDecimal("50.00"), "bob");
        shifts.closeShift(toClose.shiftId(), new BigDecimal("50.00"), "bob");

        List<ShiftView> openShifts = shifts.listOpenShifts();

        assertThat(openShifts).extracting(ShiftView::terminalId).containsExactly("T01");
        assertThat(openShifts).extracting(ShiftView::openedBy).containsExactly("alice");
        assertThat(openShifts.get(0).shiftId()).isEqualTo(open.shiftId());
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=ShiftServiceTest#listOpenShiftsReturnsOnlyOpenOnes
```
Expected: FAIL — `listOpenShifts` does not exist (compilation error).

- [ ] **Step 3: Add the facade method**

In `src/main/java/com/company/pos/shift/api/ShiftService.java`, add `import java.util.List;` and the method:

```java
    List<ShiftView> listOpenShifts();
```

- [ ] **Step 4: Add the repository query**

In `src/main/java/com/company/pos/shift/infrastructure/ShiftRepository.java`, add `import java.util.List;` and:

```java
    List<Shift> findByStatusOrderByOpenedAt(String status);
```

- [ ] **Step 5: Implement the service method**

In `src/main/java/com/company/pos/shift/application/DefaultShiftService.java`, add `import java.util.List;` and the method (reuses `toView`):

```java
    @Override
    @Transactional(readOnly = true)
    public List<ShiftView> listOpenShifts() {
        return shifts.findByStatusOrderByOpenedAt("OPEN").stream()
                .map(this::toView)
                .toList();
    }
```

- [ ] **Step 6: Run test to verify it passes**

```bash
./mvnw test -Dtest=ShiftServiceTest
```
Expected: PASS (all methods in the class).

- [ ] **Step 7: Run ModularityTests**

```bash
./mvnw test -Dtest=ModularityTests
```
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/company/pos/shift src/test/java/com/company/pos/shift/ShiftServiceTest.java
git commit -m "feat(shift): listOpenShifts() facade query for dashboard"
```

---

### Task 3: Dashboard module scaffold + `api` (facade, DTOs, config key)

Creates the module skeleton and its public `api`: the `DashboardService` interface, the three dashboard-owned DTOs, and the module's boundary declarations. Adds the revenue-window config key. Deliverable: the app compiles and `ModularityTests` verifies the new module's boundaries. No service implementation yet (the interface has no bean until Task 4 — nothing autowires it, so the context is unaffected).

**Files:**
- Create: `src/main/java/com/company/pos/dashboard/package-info.java`
- Create: `src/main/java/com/company/pos/dashboard/api/package-info.java`
- Create: `src/main/java/com/company/pos/dashboard/api/DashboardService.java`
- Create: `src/main/java/com/company/pos/dashboard/api/RevenueSummary.java`
- Create: `src/main/java/com/company/pos/dashboard/api/LowStockTile.java`
- Create: `src/main/java/com/company/pos/dashboard/api/OpenShifts.java`
- Create: `src/main/java/com/company/pos/dashboard/api/DashboardSnapshot.java`
- Modify: `src/main/java/com/company/pos/configuration/api/SettingKey.java`
- Modify: `src/test/java/com/company/pos/ModularityTests.java`

**Interfaces:**
- Consumes: `reporting.api.SalesSummaryReport`, `reporting.api.ProductPerformanceReport.ProductLine`, `shift.api.ShiftView`.
- Produces (used by Tasks 4 & 5):
  - `record RevenueSummary(BigDecimal today, int windowDays, BigDecimal window)`
  - `record LowStockTile(String sku, String name, BigDecimal onHand, BigDecimal reorderLevel)`
  - `record OpenShifts(List<ShiftView> shifts, List<String> activeCashiers)`
  - `record DashboardSnapshot(LocalDate asOfDate, String currencyCode, SalesSummaryReport todaysSales, RevenueSummary revenue, List<ProductPerformanceReport.ProductLine> bestSellers, List<LowStockTile> lowStock, List<ShiftView> openShifts, List<String> activeCashiers)`
  - `interface DashboardService { DashboardSnapshot snapshot(); SalesSummaryReport salesToday(); RevenueSummary revenue(); List<ProductPerformanceReport.ProductLine> bestSellers(int limit); List<LowStockTile> lowStock(); OpenShifts openShifts(); }`
  - `SettingKey.DASHBOARD_REVENUE_WINDOW_DAYS` (key `"dashboard.revenue.window.days"`, default `"7"`).

- [ ] **Step 1: Create the module `package-info.java`**

Create `src/main/java/com/company/pos/dashboard/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "reporting :: api", "inventory :: api", "shift :: api",
                "product :: api", "configuration :: api" })
package com.company.pos.dashboard;
```

- [ ] **Step 2: Create the `api` named-interface `package-info.java`**

Create `src/main/java/com/company/pos/dashboard/api/package-info.java`:

```java
@org.springframework.modulith.NamedInterface("api")
package com.company.pos.dashboard.api;
```

- [ ] **Step 3: Create the `RevenueSummary` DTO**

Create `src/main/java/com/company/pos/dashboard/api/RevenueSummary.java`:

```java
package com.company.pos.dashboard.api;

import java.math.BigDecimal;

public record RevenueSummary(BigDecimal today, int windowDays, BigDecimal window) {
}
```

- [ ] **Step 4: Create the `LowStockTile` DTO**

Create `src/main/java/com/company/pos/dashboard/api/LowStockTile.java`:

```java
package com.company.pos.dashboard.api;

import java.math.BigDecimal;

public record LowStockTile(String sku, String name, BigDecimal onHand, BigDecimal reorderLevel) {
}
```

- [ ] **Step 5: Create the `OpenShifts` DTO**

Create `src/main/java/com/company/pos/dashboard/api/OpenShifts.java`:

```java
package com.company.pos.dashboard.api;

import com.company.pos.shift.api.ShiftView;
import java.util.List;

public record OpenShifts(List<ShiftView> shifts, List<String> activeCashiers) {
}
```

- [ ] **Step 6: Create the `DashboardSnapshot` DTO**

Create `src/main/java/com/company/pos/dashboard/api/DashboardSnapshot.java`:

```java
package com.company.pos.dashboard.api;

import com.company.pos.reporting.api.ProductPerformanceReport;
import com.company.pos.reporting.api.SalesSummaryReport;
import com.company.pos.shift.api.ShiftView;
import java.time.LocalDate;
import java.util.List;

public record DashboardSnapshot(
        LocalDate asOfDate,
        String currencyCode,
        SalesSummaryReport todaysSales,
        RevenueSummary revenue,
        List<ProductPerformanceReport.ProductLine> bestSellers,
        List<LowStockTile> lowStock,
        List<ShiftView> openShifts,
        List<String> activeCashiers) {
}
```

- [ ] **Step 7: Create the `DashboardService` interface**

Create `src/main/java/com/company/pos/dashboard/api/DashboardService.java`:

```java
package com.company.pos.dashboard.api;

import com.company.pos.reporting.api.ProductPerformanceReport;
import com.company.pos.reporting.api.SalesSummaryReport;
import java.util.List;

public interface DashboardService {

    DashboardSnapshot snapshot();

    SalesSummaryReport salesToday();

    RevenueSummary revenue();

    List<ProductPerformanceReport.ProductLine> bestSellers(int limit);

    List<LowStockTile> lowStock();

    OpenShifts openShifts();
}
```

- [ ] **Step 8: Add the revenue-window config key**

In `src/main/java/com/company/pos/configuration/api/SettingKey.java`, add a new enum constant after `DISCOUNT_CASHIER_MAX_AMOUNT` (change its trailing `;` to `,`):

```java
    DISCOUNT_CASHIER_MAX_AMOUNT("discount.cashier.max.amount", "20.00"),
    DASHBOARD_REVENUE_WINDOW_DAYS("dashboard.revenue.window.days", "7");
```

- [ ] **Step 9: Assert the module is detected in ModularityTests**

In `src/test/java/com/company/pos/ModularityTests.java`, add a test method:

```java
    @Test
    void detectsTheDashboardModule() {
        Set<String> names = modules.stream()
                .map(ApplicationModule::getName)
                .collect(Collectors.toSet());
        assertThat(names).contains("dashboard", "reporting");
    }
```

- [ ] **Step 10: Run ModularityTests to verify boundaries**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=ModularityTests
```
Expected: PASS — `dashboard` module registers and its declared `allowedDependencies` (all `:: api` named interfaces of `reporting`/`inventory`/`shift`/`product`/`configuration`, plus `common`) verify clean.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/company/pos/dashboard src/main/java/com/company/pos/configuration/api/SettingKey.java src/test/java/com/company/pos/ModularityTests.java
git commit -m "feat(dashboard): module scaffold, api DTOs, revenue-window config key"
```

---

### Task 4: `DefaultDashboardService` (composition logic)

Implements the facade by composing `reporting`, `inventory`, `shift`, `product`, and `configuration`. This is the behavioural core; the integration test seeds a real sale, an open shift, and a low-stock SKU, then asserts the full snapshot.

**Files:**
- Create: `src/main/java/com/company/pos/dashboard/application/DefaultDashboardService.java`
- Test: `src/test/java/com/company/pos/dashboard/DashboardServiceTest.java`

**Interfaces:**
- Consumes: `DashboardService` + all `api` DTOs (Task 3); `ReportingService.salesSummary(from,to)` / `productPerformance(from,to,limit)`; `InventoryService.listLowStock()` (Task 1); `ShiftService.listOpenShifts()` (Task 2); `ProductCatalog.findBySku(sku)` → `Optional<ProductView>` (`ProductView.name()`); `ConfigurationService.getString(CURRENCY_CODE)` / `getInt(DASHBOARD_REVENUE_WINDOW_DAYS)`.
- Produces: a `@Service` bean implementing `DashboardService`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/dashboard/DashboardServiceTest.java`. It seeds a product + stock (low), opens a shift, runs one checkout today, then asserts every tile.

```java
package com.company.pos.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.dashboard.api.DashboardService;
import com.company.pos.dashboard.api.DashboardSnapshot;
import com.company.pos.dashboard.api.LowStockTile;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.inventory.api.InventorySync;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.shift.api.ShiftService;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
@TestPropertySource(properties = "pos.inventory.reorder-level=100")
class DashboardServiceTest {

    @Autowired DashboardService dashboard;
    @Autowired SalesService salesService;
    @Autowired CartService carts;
    @Autowired ProductSync productSync;
    @Autowired InventorySync inventorySync;
    @Autowired ShiftService shifts;
    @Autowired ConfigurationService configService;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("5"), 1));
        productSync.sync();
        inventorySync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private void sellTwoColas() {
        UUID cartId = carts.createCart();
        carts.addLine(cartId, "COLA", new BigDecimal("2")); // 2 x 4.50 = 9.00 net, tax 1.35, grand 10.35
        salesService.checkout(new CheckoutCommand(cartId,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))), "cashier");
    }

    @Test
    void snapshotAggregatesEveryTile() {
        shifts.openShift("T01", new BigDecimal("100.00"), "alice");
        sellTwoColas();

        DashboardSnapshot snap = dashboard.snapshot();

        assertThat(snap.asOfDate()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
        assertThat(snap.currencyCode()).isEqualTo("SAR");

        // Today's sales
        assertThat(snap.todaysSales().saleCount()).isEqualTo(1);
        assertThat(snap.todaysSales().grossSales()).isEqualByComparingTo("10.35");

        // Revenue: today's net; window (default 7) also contains the same sale
        assertThat(snap.revenue().windowDays()).isEqualTo(7);
        assertThat(snap.revenue().today()).isEqualByComparingTo("10.35");
        assertThat(snap.revenue().window()).isEqualByComparingTo("10.35");

        // Best sellers
        assertThat(snap.bestSellers()).extracting(l -> l.sku()).contains("COLA");

        // Low stock, name-enriched from product catalog (5 on hand < reorder 100)
        assertThat(snap.lowStock()).extracting(LowStockTile::sku).containsExactly("COLA");
        assertThat(snap.lowStock().get(0).name()).isEqualTo("Cola Can");

        // Open shifts + derived active cashiers
        assertThat(snap.openShifts()).extracting(s -> s.terminalId()).containsExactly("T01");
        assertThat(snap.activeCashiers()).containsExactly("alice");
    }

    @Test
    void lowStockFallsBackToSkuWhenProductMissing() {
        // Stock row exists for a SKU with no product record -> name falls back to sku
        fake.addStockLevel(new ErpStockLevel("GHOST", "MAIN", new BigDecimal("1"), 2));
        inventorySync.sync();

        List<LowStockTile> low = dashboard.lowStock();

        assertThat(low).anySatisfy(t -> {
            assertThat(t.sku()).isEqualTo("GHOST");
            assertThat(t.name()).isEqualTo("GHOST");
        });
    }

    @Test
    void revenueWindowHonoursConfigOverride() {
        // Override the window and confirm it plumbs through to the response.
        configService.put(SettingKey.DASHBOARD_REVENUE_WINDOW_DAYS, "3");
        sellTwoColas();

        assertThat(dashboard.revenue().windowDays()).isEqualTo(3);
        // Window [today-2, today] still contains today's sale, so window net == today net.
        assertThat(dashboard.revenue().window()).isEqualByComparingTo("10.35");
        assertThat(dashboard.revenue().today()).isEqualByComparingTo("10.35");
    }
}
```

> **Note on back-dated sales:** a true "sales several days ago fall outside a
> shorter window" assertion is not possible through the facades — `checkout`
> stamps `created_at = now`, and nothing exposes back-dating. This test proves
> the config override reaches the response and the window range is valid; the
> range-filtering itself is already proven dialect-agnostically by reporting's
> `SalesSummaryReportTest.emptyRangeYieldsZeros`.

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=DashboardServiceTest
```
Expected: FAIL — no `DashboardService` bean (`NoSuchBeanDefinitionException`), because no implementation exists yet.

- [ ] **Step 3: Implement `DefaultDashboardService`**

Create `src/main/java/com/company/pos/dashboard/application/DefaultDashboardService.java`:

```java
package com.company.pos.dashboard.application;

import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.dashboard.api.DashboardService;
import com.company.pos.dashboard.api.DashboardSnapshot;
import com.company.pos.dashboard.api.LowStockTile;
import com.company.pos.dashboard.api.OpenShifts;
import com.company.pos.dashboard.api.RevenueSummary;
import com.company.pos.inventory.api.InventoryService;
import com.company.pos.product.api.ProductCatalog;
import com.company.pos.reporting.api.ProductPerformanceReport;
import com.company.pos.reporting.api.ReportingService;
import com.company.pos.reporting.api.SalesSummaryReport;
import com.company.pos.shift.api.ShiftService;
import com.company.pos.shift.api.ShiftView;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultDashboardService implements DashboardService {

    private static final int DEFAULT_BEST_SELLERS = 5;

    private final ReportingService reports;
    private final InventoryService inventory;
    private final ShiftService shifts;
    private final ProductCatalog products;
    private final ConfigurationService config;

    DefaultDashboardService(ReportingService reports, InventoryService inventory, ShiftService shifts,
            ProductCatalog products, ConfigurationService config) {
        this.reports = reports;
        this.inventory = inventory;
        this.shifts = shifts;
        this.products = products;
        this.config = config;
    }

    @Override
    public DashboardSnapshot snapshot() {
        LocalDate today = today();
        String currency = config.getString(SettingKey.CURRENCY_CODE);
        List<ShiftView> open = shifts.listOpenShifts();
        return new DashboardSnapshot(
                today,
                currency,
                reports.salesSummary(today, today),
                revenue(),
                bestSellers(DEFAULT_BEST_SELLERS),
                lowStock(),
                open,
                activeCashiers(open));
    }

    @Override
    public SalesSummaryReport salesToday() {
        LocalDate today = today();
        return reports.salesSummary(today, today);
    }

    @Override
    public RevenueSummary revenue() {
        LocalDate today = today();
        int windowDays = Math.max(1, config.getInt(SettingKey.DASHBOARD_REVENUE_WINDOW_DAYS));
        SalesSummaryReport todayReport = reports.salesSummary(today, today);
        SalesSummaryReport windowReport = reports.salesSummary(today.minusDays(windowDays - 1L), today);
        return new RevenueSummary(todayReport.netSales(), windowDays, windowReport.netSales());
    }

    @Override
    public List<ProductPerformanceReport.ProductLine> bestSellers(int limit) {
        LocalDate today = today();
        int clamped = Math.max(1, Math.min(limit, 50));
        return reports.productPerformance(today, today, clamped).lines();
    }

    @Override
    public List<LowStockTile> lowStock() {
        return inventory.listLowStock().stream()
                .map(item -> new LowStockTile(item.sku(), nameOf(item.sku()),
                        item.onHand(), item.reorderLevel()))
                .toList();
    }

    @Override
    public OpenShifts openShifts() {
        List<ShiftView> open = shifts.listOpenShifts();
        return new OpenShifts(open, activeCashiers(open));
    }

    private String nameOf(String sku) {
        return products.findBySku(sku).map(p -> p.name()).orElse(sku);
    }

    private List<String> activeCashiers(List<ShiftView> open) {
        return open.stream()
                .map(ShiftView::openedBy)
                .distinct()
                .sorted()
                .toList();
    }

    private LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./mvnw test -Dtest=DashboardServiceTest
```
Expected: PASS — both `snapshotAggregatesEveryTile` and `lowStockFallsBackToSkuWhenProductMissing`.

- [ ] **Step 5: Run ModularityTests**

```bash
./mvnw test -Dtest=ModularityTests
```
Expected: PASS — the service only imports the declared `:: api` interfaces.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/dashboard/application src/test/java/com/company/pos/dashboard/DashboardServiceTest.java
git commit -m "feat(dashboard): DefaultDashboardService composing reporting/inventory/shift/product"
```

---

### Task 5: `DashboardController` (HTTP surface)

Exposes the aggregate `/dashboard` plus five per-tile endpoints, all restricted to MANAGER/ADMIN.

**Files:**
- Create: `src/main/java/com/company/pos/dashboard/web/DashboardController.java`
- Test: `src/test/java/com/company/pos/dashboard/DashboardControllerTest.java`

**Interfaces:**
- Consumes: `DashboardService` (Task 3/4) — `snapshot()`, `salesToday()`, `revenue()`, `bestSellers(int)`, `lowStock()`, `openShifts()`.
- Produces: HTTP endpoints `GET /dashboard`, `/dashboard/sales-today`, `/dashboard/revenue`, `/dashboard/best-sellers`, `/dashboard/low-stock`, `/dashboard/open-shifts`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/dashboard/DashboardControllerTest.java`:

```java
package com.company.pos.dashboard;

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
class DashboardControllerTest {

    @Autowired MockMvc mvc;

    private static final String[] ALL = {
        "/dashboard", "/dashboard/sales-today", "/dashboard/revenue",
        "/dashboard/best-sellers", "/dashboard/low-stock", "/dashboard/open-shifts"
    };

    private static RequestPostProcessor role(String r) {
        return jwt().jwt(j -> j.subject("u")).authorities(new SimpleGrantedAuthority("ROLE_" + r));
    }

    @Test
    void cashierIsForbiddenOnEveryEndpoint() throws Exception {
        for (String path : ALL) {
            mvc.perform(get(path).with(role("CASHIER")))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void managerAndAdminGetEveryEndpoint() throws Exception {
        for (String path : ALL) {
            mvc.perform(get(path).with(role("MANAGER"))).andExpect(status().isOk());
            mvc.perform(get(path).with(role("ADMIN"))).andExpect(status().isOk());
        }
    }

    @Test
    void snapshotExposesEveryTileField() throws Exception {
        mvc.perform(get("/dashboard").with(role("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencyCode").value("SAR"))
                .andExpect(jsonPath("$.asOfDate").exists())
                .andExpect(jsonPath("$.todaysSales").exists())
                .andExpect(jsonPath("$.revenue.windowDays").value(7))
                .andExpect(jsonPath("$.bestSellers").isArray())
                .andExpect(jsonPath("$.lowStock").isArray())
                .andExpect(jsonPath("$.openShifts").isArray())
                .andExpect(jsonPath("$.activeCashiers").isArray());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=DashboardControllerTest
```
Expected: FAIL — no controller mapped; `/dashboard` returns 404 (so `isOk`/`isForbidden` assertions fail).

- [ ] **Step 3: Implement `DashboardController`**

Create `src/main/java/com/company/pos/dashboard/web/DashboardController.java`:

```java
package com.company.pos.dashboard.web;

import com.company.pos.dashboard.api.DashboardService;
import com.company.pos.dashboard.api.DashboardSnapshot;
import com.company.pos.dashboard.api.LowStockTile;
import com.company.pos.dashboard.api.OpenShifts;
import com.company.pos.dashboard.api.RevenueSummary;
import com.company.pos.reporting.api.ProductPerformanceReport;
import com.company.pos.reporting.api.SalesSummaryReport;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
class DashboardController {

    private final DashboardService dashboard;

    DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/dashboard")
    DashboardSnapshot snapshot() {
        return dashboard.snapshot();
    }

    @GetMapping("/dashboard/sales-today")
    SalesSummaryReport salesToday() {
        return dashboard.salesToday();
    }

    @GetMapping("/dashboard/revenue")
    RevenueSummary revenue() {
        return dashboard.revenue();
    }

    @GetMapping("/dashboard/best-sellers")
    List<ProductPerformanceReport.ProductLine> bestSellers(@RequestParam(defaultValue = "5") int limit) {
        return dashboard.bestSellers(limit);
    }

    @GetMapping("/dashboard/low-stock")
    List<LowStockTile> lowStock() {
        return dashboard.lowStock();
    }

    @GetMapping("/dashboard/open-shifts")
    OpenShifts openShifts() {
        return dashboard.openShifts();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./mvnw test -Dtest=DashboardControllerTest
```
Expected: PASS — CASHIER 403 on all six; MANAGER/ADMIN 200; snapshot JSON shape asserted.

- [ ] **Step 5: Run ModularityTests**

```bash
./mvnw test -Dtest=ModularityTests
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/dashboard/web src/test/java/com/company/pos/dashboard/DashboardControllerTest.java
git commit -m "feat(dashboard): HTTP endpoints for snapshot + per-tile views"
```

---

### Task 6: Full verification + documentation

Confirms the whole build is green and documents the module in `docs/run-modes.md`.

**Files:**
- Modify: `docs/run-modes.md`

- [ ] **Step 1: Run the full build**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw clean verify
```
Expected: BUILD SUCCESS — all tests (including the Testcontainers Postgres reporting tests, if Docker is running) and `ModularityTests` pass. The dashboard path is facade-only, so no Postgres-specific dashboard test is required — reporting's SQL already has dual-dialect coverage.

- [ ] **Step 2: Document the dashboard module**

In `docs/run-modes.md`, add a subsection describing the dashboard HTTP surface. Locate the reporting endpoints section (search for `/reports/sales`) and add, after it, a parallel block:

```markdown
### Dashboard (MANAGER/ADMIN)

Operational insight, composed on-demand from module facades (no new tables). "Today" is the UTC day, consistent with reporting.

- `GET /dashboard` — full snapshot: today's sales, revenue (today + rolling window), best sellers, low-stock items, open shifts, active cashiers.
- `GET /dashboard/sales-today` — today's sales summary.
- `GET /dashboard/revenue` — today's net + rolling-window net (window = `dashboard.revenue.window.days`, default 7).
- `GET /dashboard/best-sellers?limit=` — top SKUs by revenue today (`limit` default 5, clamped `[1, 50]`).
- `GET /dashboard/low-stock` — SKUs at/below reorder level, with product name.
- `GET /dashboard/open-shifts` — currently-open shifts + derived active cashiers.

Config key: `dashboard.revenue.window.days` (default `7`) sets the revenue rolling window.
```

- [ ] **Step 3: Commit**

```bash
git add docs/run-modes.md
git commit -m "docs(dashboard): document HTTP surface and revenue-window config"
```

---

## Notes for the implementer

- **Read order independence:** each task's `Interfaces` block lists the exact
  signatures it consumes/produces, so tasks can be reviewed out of order. Tasks
  1 and 2 are independent of each other; Task 3 depends on both being present
  only at the DTO/type level (it references `ShiftView` and
  `ProductPerformanceReport.ProductLine`, which already exist — the new facade
  *methods* from Tasks 1–2 are consumed in Task 4, not Task 3).
- **Why no `database` dependency for `dashboard`:** unlike `reporting`, dashboard
  issues no SQL; it only calls facades, so it needs neither `database` nor a
  `JdbcTemplate`.
- **Cross-dialect safety:** the only new query with arithmetic is
  `StockLevelRepository.findLowStock()` (JPQL). HQL renders `reorder_level -
  quantity_on_hand` portably on both SQLite and Postgres; no native SQL is used.
```
