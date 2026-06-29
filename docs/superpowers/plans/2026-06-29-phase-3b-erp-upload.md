# Phase 3b — ERP Up-Sync (sales + stock movements) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upload every completed sale and its stock-movement deltas to the ERP idempotently, riding the Phase 3a transactional outbox, so the store trades fully offline and drains cleanly when the ERP link returns.

**Architecture:** A new Tier-3 `sync` module subscribes to the `sales` `SaleCompleted` event with an `@ApplicationModuleListener` (after-commit, async, own transaction, tracked by the Spring Modulith Event Publication Registry). For each sale it fetches the full `SaleView` via `sales :: api`, maps it to a `SaleUpload`, derives `StockMovementUpload` deltas (−quantity per line at the sale's location), and calls the ERP adapter's two new idempotent upload methods. When the ERP is offline the adapter throws, so the listener throws, so the registry keeps the publication **incomplete**; a scheduled drain (and republish-on-restart, already enabled in 3a) resubmits it when the link returns. Each listener run is `REQUIRES_NEW` — it rolls back on throw and is marked complete only on success — so replay applies exactly once locally; the ERP calls are at-least-once, so the adapter dedupes on `saleId`. The real ERP adapter is still the in-memory `FakeErpClient`; only its contract grows.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith 1.2.5 (Event Publication Registry from Phase 3a — `spring-modulith-starter-jpa`, `spring-modulith-events-jackson`, already present), Spring Data JPA, JUnit 5 + Awaitility (transitive via `spring-boot-starter-test`) + spring-security-test, Testcontainers (Postgres), Maven (`./mvnw`).

## Global Constraints

- **Root package** `com.company.pos`; one package-per-module under it, boundaries enforced by `ApplicationModules.of(PosApplication.class).verify()` (the `ModularityTests`). A module is consumed cross-module only through a sub-package marked `@org.springframework.modulith.NamedInterface("api")`, and the consumer lists it in `allowedDependencies` as `module :: api`. `sales.api` and `integration.api` are already named interfaces. The new `sync` module exposes **no** named interface (nothing depends on it).
- **No dependency cycle:** `sync` depends on `sales :: api` (subscribe to `SaleCompleted`, call `getSale`) and `integration :: api` (the `ErpClient` + upload DTOs). Neither `sales` nor `integration` depends on `sync` — same shape as `inventory`/`cashdrawer` subscribing to `sales`.
- **JDK 21 required.** The machine default `java` is 17. Run every Maven command as: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw …`.
- **Build tool is Maven** via the committed `./mvnw` wrapper. No system `mvn`.
- **Reuse the Phase 3a outbox — add NO new persistence.** The `event_publication` table (Flyway V14) is the upload queue. There is **no new Flyway migration** and **no new sync cursor** in this phase (cursors are for down-sync; up-sync is driven by the outbox). The latest migration remains V14.
- **Money is `BigDecimal` + currency code — never `double`/`float`.** Upload DTOs carry the `BigDecimal` amounts already computed by `sales` (subtotal, taxTotal, grandTotal, per-line unitPrice/netAmount/taxAmount/lineTotal, payment amounts) verbatim — do not recompute or re-round them. Stock deltas are the line quantity negated (scale preserved as `sales`/`inventory` produced it).
- **Idempotency is mandatory.** Upload is at-least-once (the outbox replays on failure/restart). `ErpClient.uploadSale` dedupes on `SaleUpload.saleId`; `ErpClient.uploadStockMovements` dedupes on its `saleId` batch key. A repeat `saleId` is a no-op, never a duplicate or an error.
- **Offline-first:** nothing in the sell path may block on connectivity. The upload runs only after the sale commits, on a separate async thread; if it fails, the sale is unaffected and the publication is retried later. A completed sale is never rolled back by an upload failure.
- **PCI:** payment uploads carry only `method`, amounts, and `maskedPan` (already masked by `payment`); never a raw PAN. The `SaleView`/`SalePaymentView` the uploader reads has no raw PAN to leak.
- **Stateless JWT bearer auth** is already in place; the one new endpoint (`POST /sync/erp/upload`) is **MANAGER-only** via `@PreAuthorize("hasRole('MANAGER')")`, mirroring the existing `POST /sync/erp`. Roles: `CASHIER`, `MANAGER`, `ADMIN`.
- **Test isolation (carry the Phase 3a lesson):** the embedded profile uses ONE shared in-memory SQLite (`jdbc:sqlite:file:pos?mode=memory&cache=shared`) that is global across all Spring test contexts. Any NON-`@Transactional` test that commits (every sell-path test here) MUST use the existing `com.company.pos.support.DatabaseCleaner` (registered via `@Import(DatabaseCleaner.class)`, `databaseCleaner.clean()` as the first line of `@BeforeEach` and in `@AfterEach`) and assert async side-effects with Awaitility. Pure POJO tests (no Spring context) need neither.
- **Errors** surface as `DomainException.notFound/validation/conflict(...)` → RFC-7807 `ProblemDetail` via the existing `ApiExceptionHandler`. The `FakeErpClient` "offline" failure is a plain `RuntimeException` (it simulates an I/O failure, not a domain error) so it propagates to the registry as an incomplete publication.

---

## Existing code this phase consumes (already implemented — do not redefine)

```java
// com.company.pos.sales.api  (named interface "api")
public record SaleCompleted(UUID saleId, String receiptNumber, String terminalId, String locationCode,
        String currencyCode, BigDecimal grandTotal, BigDecimal cashTotal, List<SoldLine> lines)
        implements com.company.pos.common.events.DomainEvent {
    public record SoldLine(String sku, BigDecimal quantity) {}
}
public interface SalesService {
    SaleView checkout(CheckoutCommand command, String cashierUsername);
    SaleView getSale(UUID saleId);          // <-- used by the uploader for full detail
    void reprint(UUID saleId);
}
public record SaleView(UUID id, String receiptNumber, String status, String currencyCode,
        BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal, java.time.Instant createdAt,
        List<SaleLineView> lines, List<SalePaymentView> payments) {}
public record SaleLineView(int lineNo, String sku, String name, BigDecimal quantity,
        BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxAmount, BigDecimal lineTotal,
        String currencyCode) {}
public record SalePaymentView(String method, BigDecimal amount, BigDecimal amountTendered,
        BigDecimal changeDue, String maskedPan) {}

// com.company.pos.integration.api  (named interface "api")
public interface ErpClient {                // <-- GROWS in Task 1 (two upload methods)
    List<ErpProduct> fetchProductsSince(long version);
    List<ErpStockLevel> fetchStockLevelsSince(long version);
}
// com.company.pos.integration.erp.FakeErpClient  (public @Component implementing ErpClient) — GROWS in Task 1

// org.springframework.modulith.events.IncompleteEventPublications  (bean, verified present in Phase 3a)
//   void resubmitIncompletePublications(java.util.function.Predicate<EventPublication> filter);
//   void resubmitIncompletePublicationsOlderThan(java.time.Duration duration);   // verify in Task 3
// org.springframework.modulith.events.core.EventPublicationRegistry  (bean, verified present in Phase 3a)
//   java.util.Collection<...> findIncompletePublications();

// Test helper (created in Phase 3a)
// com.company.pos.support.DatabaseCleaner  (plain class; new JdbcTemplate(DataSource); void clean())

// Seeding facades (Phase 1): ProductSync.sync():int, InventorySync.sync():int
// new ErpProduct(sku,name,categoryCode,categoryName,barcode,uom,price,currency,version,active)
// Checkout (Phase 2): CartService.createCart()/addLine(cartId, sku, qty);
//   new CheckoutCommand(cartId, List<TenderInput>); new TenderInput(PaymentMethod.CASH, amount, tendered)
// Device fake: InMemoryPaymentTerminal.setApprove(boolean)
```

## New surface produced by this phase

| Module | New types / endpoints |
|---|---|
| `integration` (`api`) | `SaleUpload` (+nested `Line`, `Payment`), `StockMovementUpload`; `ErpClient.uploadSale(SaleUpload)`, `ErpClient.uploadStockMovements(String saleId, List<StockMovementUpload>)` |
| `integration` (`erp`) | `FakeErpClient`: idempotent upload stores, `uploadedSales()`, `uploadedMovementBatches()`, `setAvailable(boolean)`, extended `clear()` |
| `sync` (new module) | `SaleUploadListener` (`@ApplicationModuleListener`), `ErpUploadDrainScheduler` (`@ConditionalOnProperty`), `ErpUploadController` (`POST /sync/erp/upload`, MANAGER-only) |

## Module dependency declarations introduced

```
sync   allowedDependencies = { "common", "sales :: api", "integration :: api" }   // Task 2
```
`integration` and `sales` are unchanged (no new dependencies). No cycle.

## Config added (no migration)

`application.yml` gains a `pos.sync.erp.upload` block (scheduled drain, default OFF); `application-store-server.yml` turns the scheduled drain ON (matching how it enables down-sync). No Flyway change.

---

### Task 1: `integration` — ERP upload contract (DTOs + idempotent `FakeErpClient`)

Add the upload DTOs and the two `ErpClient` upload methods, and implement them in `FakeErpClient` with idempotent in-memory stores, test accessors, and an `available` toggle that simulates the ERP being offline. This task is pure contract + fake; the `sync` module that calls it arrives in Task 2.

**Files:**
- Create: `src/main/java/com/company/pos/integration/api/SaleUpload.java`
- Create: `src/main/java/com/company/pos/integration/api/StockMovementUpload.java`
- Modify: `src/main/java/com/company/pos/integration/api/ErpClient.java`
- Modify: `src/main/java/com/company/pos/integration/erp/FakeErpClient.java`
- Test: `src/test/java/com/company/pos/integration/FakeErpClientUploadTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `record SaleUpload(UUID saleId, String receiptNumber, String terminalId, String locationCode, String currencyCode, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal, Instant createdAt, List<Line> lines, List<Payment> payments)` with `record Line(int lineNo, String sku, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxAmount, BigDecimal lineTotal)` and `record Payment(String method, BigDecimal amount, BigDecimal amountTendered, BigDecimal changeDue, String maskedPan)`.
  - `record StockMovementUpload(String sku, String locationCode, BigDecimal quantityDelta, String reason)`.
  - `ErpClient.uploadSale(SaleUpload sale)` — idempotent on `sale.saleId()`.
  - `ErpClient.uploadStockMovements(String saleId, List<StockMovementUpload> movements)` — idempotent on `saleId`.
  - `FakeErpClient`: `List<SaleUpload> uploadedSales()`, `Map<String, List<StockMovementUpload>> uploadedMovementBatches()`, `void setAvailable(boolean available)`; `clear()` also resets upload state and `available=true`; when `available` is false both upload methods throw `RuntimeException("ERP offline")` **before** recording anything.

- [ ] **Step 1: Write the upload DTOs**

`src/main/java/com/company/pos/integration/api/StockMovementUpload.java`:

```java
package com.company.pos.integration.api;

import java.math.BigDecimal;

public record StockMovementUpload(String sku, String locationCode, BigDecimal quantityDelta,
        String reason) {
}
```

`src/main/java/com/company/pos/integration/api/SaleUpload.java`:

```java
package com.company.pos.integration.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SaleUpload(UUID saleId, String receiptNumber, String terminalId, String locationCode,
        String currencyCode, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal,
        Instant createdAt, List<Line> lines, List<Payment> payments) {

    public record Line(int lineNo, String sku, String name, BigDecimal quantity,
            BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxAmount, BigDecimal lineTotal) {
    }

    public record Payment(String method, BigDecimal amount, BigDecimal amountTendered,
            BigDecimal changeDue, String maskedPan) {
    }
}
```

- [ ] **Step 2: Add the upload methods to `ErpClient`**

Replace `src/main/java/com/company/pos/integration/api/ErpClient.java`:

```java
package com.company.pos.integration.api;

import java.util.List;

public interface ErpClient {

    List<ErpProduct> fetchProductsSince(long version);

    List<ErpStockLevel> fetchStockLevelsSince(long version);

    /** Uploads a completed sale. Idempotent: a repeat {@code sale.saleId()} is a no-op. */
    void uploadSale(SaleUpload sale);

    /**
     * Uploads the stock-movement deltas produced by a sale. Idempotent on {@code saleId}: a repeat
     * batch for the same sale is a no-op.
     */
    void uploadStockMovements(String saleId, List<StockMovementUpload> movements);
}
```

- [ ] **Step 3: Write the failing `FakeErpClient` test**

`src/test/java/com/company/pos/integration/FakeErpClientUploadTest.java`:

```java
package com.company.pos.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.integration.api.SaleUpload;
import com.company.pos.integration.api.StockMovementUpload;
import com.company.pos.integration.erp.FakeErpClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FakeErpClientUploadTest {

    private SaleUpload sampleSale(UUID id) {
        return new SaleUpload(id, "S01-T01-000001", "T01", "MAIN", "SAR",
                new BigDecimal("9.00"), new BigDecimal("1.35"), new BigDecimal("10.35"), Instant.EPOCH,
                List.of(new SaleUpload.Line(1, "COLA", "Cola Can", new BigDecimal("2"),
                        new BigDecimal("4.50"), new BigDecimal("9.00"), new BigDecimal("1.35"),
                        new BigDecimal("10.35"))),
                List.of(new SaleUpload.Payment("CASH", new BigDecimal("10.35"),
                        new BigDecimal("20.00"), new BigDecimal("9.65"), null)));
    }

    @Test
    void uploadSaleIsIdempotentOnSaleId() {
        FakeErpClient fake = new FakeErpClient();
        UUID id = UUID.randomUUID();

        fake.uploadSale(sampleSale(id));
        fake.uploadSale(sampleSale(id)); // retry

        assertThat(fake.uploadedSales()).hasSize(1);
        assertThat(fake.uploadedSales().get(0).saleId()).isEqualTo(id);
    }

    @Test
    void uploadStockMovementsIsIdempotentOnSaleId() {
        FakeErpClient fake = new FakeErpClient();
        List<StockMovementUpload> deltas =
                List.of(new StockMovementUpload("COLA", "MAIN", new BigDecimal("-2"), "SALE"));

        fake.uploadStockMovements("sale-1", deltas);
        fake.uploadStockMovements("sale-1", deltas); // retry

        assertThat(fake.uploadedMovementBatches()).hasSize(1);
        assertThat(fake.uploadedMovementBatches().get("sale-1")).hasSize(1);
    }

    @Test
    void uploadsThrowWhenOffline() {
        FakeErpClient fake = new FakeErpClient();
        fake.setAvailable(false);

        assertThatThrownBy(() -> fake.uploadSale(sampleSale(UUID.randomUUID())))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> fake.uploadStockMovements("sale-1", List.of()))
                .isInstanceOf(RuntimeException.class);
        assertThat(fake.uploadedSales()).isEmpty();
        assertThat(fake.uploadedMovementBatches()).isEmpty();
    }

    @Test
    void clearResetsUploadsAndAvailability() {
        FakeErpClient fake = new FakeErpClient();
        fake.uploadSale(sampleSale(UUID.randomUUID()));
        fake.setAvailable(false);

        fake.clear();

        assertThat(fake.uploadedSales()).isEmpty();
        // available reset to true -> this upload succeeds
        fake.uploadSale(sampleSale(UUID.randomUUID()));
        assertThat(fake.uploadedSales()).hasSize(1);
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=FakeErpClientUploadTest`
Expected: FAIL — `uploadSale`/`uploadStockMovements`/`uploadedSales`/`setAvailable` do not exist (compile error).

- [ ] **Step 5: Implement the upload methods in `FakeErpClient`**

Replace `src/main/java/com/company/pos/integration/erp/FakeErpClient.java`:

```java
package com.company.pos.integration.erp;

import com.company.pos.integration.api.ErpClient;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.api.SaleUpload;
import com.company.pos.integration.api.StockMovementUpload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** In-memory ERP stand-in. Down-sync for Phase 1; up-sync (uploads) added in Phase 3b. */
@Component
public class FakeErpClient implements ErpClient {

    private final List<ErpProduct> products = new ArrayList<>();
    private final List<ErpStockLevel> stockLevels = new ArrayList<>();
    private final Map<UUID, SaleUpload> uploadedSales = new LinkedHashMap<>();
    private final Map<String, List<StockMovementUpload>> movementBatches = new LinkedHashMap<>();
    private volatile boolean available = true;

    public void addProduct(ErpProduct product) {
        products.add(product);
    }

    public void addStockLevel(ErpStockLevel level) {
        stockLevels.add(level);
    }

    /** Simulate the ERP link being up (true) or down (false). When down, uploads throw. */
    public void setAvailable(boolean available) {
        this.available = available;
    }

    public List<SaleUpload> uploadedSales() {
        return new ArrayList<>(uploadedSales.values());
    }

    public Map<String, List<StockMovementUpload>> uploadedMovementBatches() {
        return new LinkedHashMap<>(movementBatches);
    }

    public void clear() {
        products.clear();
        stockLevels.clear();
        uploadedSales.clear();
        movementBatches.clear();
        available = true;
    }

    @Override
    public List<ErpProduct> fetchProductsSince(long version) {
        return products.stream()
                .filter(p -> p.version() > version)
                .sorted(Comparator.comparingLong(ErpProduct::version))
                .toList();
    }

    @Override
    public List<ErpStockLevel> fetchStockLevelsSince(long version) {
        return stockLevels.stream()
                .filter(s -> s.version() > version)
                .sorted(Comparator.comparingLong(ErpStockLevel::version))
                .toList();
    }

    @Override
    public void uploadSale(SaleUpload sale) {
        requireAvailable();
        uploadedSales.putIfAbsent(sale.saleId(), sale); // idempotent on saleId
    }

    @Override
    public void uploadStockMovements(String saleId, List<StockMovementUpload> movements) {
        requireAvailable();
        movementBatches.putIfAbsent(saleId, List.copyOf(movements)); // idempotent on saleId
    }

    private void requireAvailable() {
        if (!available) {
            throw new RuntimeException("ERP offline");
        }
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=FakeErpClientUploadTest`
Expected: PASS (all four tests).

- [ ] **Step 7: Confirm no regression in existing integration + boundary tests**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ErpIntegrationTest,ProductErpSyncServiceTest,InventoryErpSyncServiceTest,ModularityTests`
Expected: PASS — the new methods are additive; `integration` boundaries unchanged (DTOs use only `java.*`).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/company/pos/integration/ \
        src/test/java/com/company/pos/integration/FakeErpClientUploadTest.java
git commit -m "feat(integration): idempotent ERP sale + stock-movement upload contract"
```

---

### Task 2: `sync` module — upload completed sales over the outbox

Create the `sync` module with an `@ApplicationModuleListener` on `SaleCompleted` that fetches the full sale, maps it to a `SaleUpload`, derives the stock-movement deltas, and uploads both to the ERP. Because it is an `@ApplicationModuleListener`, it runs after the sale commits, asynchronously, and is tracked by the outbox (so Task 3 can make it resilient).

**Files:**
- Create: `src/main/java/com/company/pos/sync/package-info.java`
- Create: `src/main/java/com/company/pos/sync/application/SaleUploadListener.java`
- Test: `src/test/java/com/company/pos/sync/SaleUploadedToErpTest.java`
- Modify: `src/test/java/com/company/pos/ModularityTests.java`

**Interfaces:**
- Consumes: `SaleCompleted` (sales :: api); `SalesService.getSale(UUID)` → `SaleView`; `ErpClient.uploadSale(SaleUpload)` + `ErpClient.uploadStockMovements(String, List<StockMovementUpload>)` (integration :: api, from Task 1).
- Produces: a `@Component` `SaleUploadListener` with `@ApplicationModuleListener void on(SaleCompleted)`. No public cross-module API (nothing depends on `sync`).

- [ ] **Step 1: Declare the module**

`src/main/java/com/company/pos/sync/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "sales :: api", "integration :: api" })
package com.company.pos.sync;
```

> If `ModularityTests` later reports an allowed-dependency the listener does not actually use (e.g. `common`), trim it to exactly what is imported. If it reports a dependency that is used but not listed, add it. The boundary verify is the source of truth.

- [ ] **Step 2: Write the failing upload test**

`src/test/java/com/company/pos/sync/SaleUploadedToErpTest.java`:

```java
package com.company.pos.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.SaleUpload;
import com.company.pos.integration.api.StockMovementUpload;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * NOT @Transactional: the upload runs in an after-commit async listener, so the sale must really
 * commit and the assertion polls until the listener has run. Uses DatabaseCleaner because committed
 * rows would otherwise leak across the shared in-memory DB (see Phase 3a). FakeErpClient is a
 * singleton bean, so its upload state is reset via fake.clear() in @BeforeEach/@AfterEach.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class SaleUploadedToErpTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void cleanup() {
        databaseCleaner.clean();
        fake.clear();
    }

    @Test
    void completedSaleAndItsMovementsAreUploaded() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // total 10.35
        UUID saleId = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))),
                "cashier").id();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(fake.uploadedSales()).hasSize(1);
            SaleUpload uploaded = fake.uploadedSales().get(0);
            assertThat(uploaded.saleId()).isEqualTo(saleId);
            assertThat(uploaded.grandTotal()).isEqualByComparingTo("10.35");
            assertThat(uploaded.lines()).hasSize(1);
            assertThat(uploaded.lines().get(0).sku()).isEqualTo("COLA");
            assertThat(uploaded.payments()).hasSize(1);
            assertThat(uploaded.payments().get(0).method()).isEqualTo("CASH");

            assertThat(fake.uploadedMovementBatches()).containsKey(saleId.toString());
            List<StockMovementUpload> deltas = fake.uploadedMovementBatches().get(saleId.toString());
            assertThat(deltas).hasSize(1);
            assertThat(deltas.get(0).sku()).isEqualTo("COLA");
            assertThat(deltas.get(0).quantityDelta()).isEqualByComparingTo("-2"); // sold 2 -> -2
            assertThat(deltas.get(0).reason()).isEqualTo("SALE");
        });
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=SaleUploadedToErpTest`
Expected: FAIL — nothing uploads (no `SaleUploadListener` yet); the Awaitility assertion times out (`uploadedSales` empty).

- [ ] **Step 4: Write the upload listener**

`src/main/java/com/company/pos/sync/application/SaleUploadListener.java`:

```java
package com.company.pos.sync.application;

import com.company.pos.integration.api.ErpClient;
import com.company.pos.integration.api.SaleUpload;
import com.company.pos.integration.api.StockMovementUpload;
import com.company.pos.sales.api.SaleCompleted;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Uploads a completed sale and its stock-movement deltas to the ERP. Runs after the sale commits,
 * asynchronously, in its own transaction, tracked by the Spring Modulith Event Publication Registry
 * (Phase 3a). If the ERP is offline the upload throws and the publication stays incomplete for
 * later replay (Phase 3b scheduled/manual drain + republish-on-restart) — the committed sale is
 * never affected. Uploads are idempotent on saleId, so at-least-once replay never double-posts.
 */
@Component
class SaleUploadListener {

    private static final Logger log = LoggerFactory.getLogger(SaleUploadListener.class);

    private final SalesService sales;
    private final ErpClient erp;

    SaleUploadListener(SalesService sales, ErpClient erp) {
        this.sales = sales;
        this.erp = erp;
    }

    @ApplicationModuleListener
    void on(SaleCompleted event) {
        SaleView sale = sales.getSale(event.saleId());
        erp.uploadSale(toUpload(event, sale));
        erp.uploadStockMovements(event.saleId().toString(), toMovements(event, sale));
        log.info("Uploaded sale {} ({} lines) to ERP", sale.receiptNumber(), sale.lines().size());
    }

    private SaleUpload toUpload(SaleCompleted event, SaleView sale) {
        List<SaleUpload.Line> lines = sale.lines().stream()
                .map(l -> new SaleUpload.Line(l.lineNo(), l.sku(), l.name(), l.quantity(),
                        l.unitPrice(), l.netAmount(), l.taxAmount(), l.lineTotal()))
                .toList();
        List<SaleUpload.Payment> payments = sale.payments().stream()
                .map(p -> new SaleUpload.Payment(p.method(), p.amount(), p.amountTendered(),
                        p.changeDue(), p.maskedPan()))
                .toList();
        return new SaleUpload(sale.id(), sale.receiptNumber(), event.terminalId(),
                event.locationCode(), sale.currencyCode(), sale.subtotal(), sale.taxTotal(),
                sale.grandTotal(), sale.createdAt(), lines, payments);
    }

    private List<StockMovementUpload> toMovements(SaleCompleted event, SaleView sale) {
        return sale.lines().stream()
                .map(l -> new StockMovementUpload(l.sku(), event.locationCode(),
                        l.quantity().negate(), "SALE"))
                .toList();
    }
}
```

- [ ] **Step 5: Run the upload test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=SaleUploadedToErpTest`
Expected: PASS — after the sale commits, the after-commit listener uploads the sale and its `-2` COLA movement within the poll window.

- [ ] **Step 6: Add a module-presence assertion to `ModularityTests`**

In `src/test/java/com/company/pos/ModularityTests.java`, add this test method after `detectsTheCheckoutCoreModules()`:

```java
    @Test
    void detectsTheSyncModule() {
        Set<String> names = modules.stream()
                .map(ApplicationModule::getName)
                .collect(Collectors.toSet());
        assertThat(names).contains("sync");
    }
```

(`Set`, `Collectors`, `ApplicationModule`, and `assertThat` are already imported in that file.)

- [ ] **Step 7: Verify module boundaries**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ModularityTests`
Expected: PASS — `sync → sales :: api, integration :: api` declared; `sync` detected; no cycle. If the verify reports an unused or missing allowed dependency for `sync`, adjust `sync/package-info.java` to match exactly what `SaleUploadListener` imports, then re-run.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/company/pos/sync/ \
        src/test/java/com/company/pos/sync/SaleUploadedToErpTest.java \
        src/test/java/com/company/pos/ModularityTests.java
git commit -m "feat(sync): upload completed sales + stock movements to ERP over the outbox"
```

---

### Task 3: `sync` — offline resilience, scheduled drain, manual trigger

Make the upload survive the ERP being offline: when the upload fails the publication stays incomplete; a `@Scheduled` drain resubmits aged-out incomplete publications, and a MANAGER-only endpoint triggers a drain on demand. Prove the offline→recover→drain cycle and that replay is idempotent.

**Files:**
- Create: `src/main/java/com/company/pos/sync/application/ErpUploadDrainScheduler.java`
- Create: `src/main/java/com/company/pos/sync/web/ErpUploadController.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-store-server.yml`
- Test: `src/test/java/com/company/pos/sync/ErpUploadOfflineThenDrainsTest.java`
- Test: `src/test/java/com/company/pos/sync/ErpUploadControllerTest.java`

**Interfaces:**
- Consumes: `org.springframework.modulith.events.IncompleteEventPublications` (`resubmitIncompletePublications(Predicate)`, `resubmitIncompletePublicationsOlderThan(Duration)`); `org.springframework.modulith.events.core.EventPublicationRegistry.findIncompletePublications()` (tests); the upload listener from Task 2; `FakeErpClient.setAvailable` (tests).
- Produces: `ErpUploadDrainScheduler` (`@ConditionalOnProperty(prefix = "pos.sync.erp.upload", name = "scheduled", havingValue = "true")`); `ErpUploadController` exposing `POST /sync/erp/upload` (MANAGER-only) that resubmits incomplete publications.

- [ ] **Step 1: Write the scheduled drainer**

`src/main/java/com/company/pos/sync/application/ErpUploadDrainScheduler.java`:

```java
package com.company.pos.sync.application;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically resubmits outbox publications that failed to complete (e.g. ERP uploads attempted
 * while the link was down), once they are older than a minimum age so an in-flight retry is not
 * re-fired. Disabled by default; enabled on the store-server profile.
 */
@Component
@ConditionalOnProperty(prefix = "pos.sync.erp.upload", name = "scheduled", havingValue = "true")
class ErpUploadDrainScheduler {

    private final IncompleteEventPublications incomplete;
    private final Duration minAge;

    ErpUploadDrainScheduler(IncompleteEventPublications incomplete,
            @org.springframework.beans.factory.annotation.Value("${pos.sync.erp.upload.min-age-ms:10000}") long minAgeMs) {
        this.incomplete = incomplete;
        this.minAge = Duration.ofMillis(minAgeMs);
    }

    @Scheduled(fixedDelayString = "${pos.sync.erp.upload.fixed-delay-ms:30000}",
            initialDelayString = "${pos.sync.erp.upload.fixed-delay-ms:30000}")
    void drain() {
        incomplete.resubmitIncompletePublicationsOlderThan(minAge);
    }
}
```

> If `resubmitIncompletePublicationsOlderThan(Duration)` does not exist in Modulith 1.2.5 (verify by compiling), replace the `drain()` body with `incomplete.resubmitIncompletePublications(p -> true);` (the predicate form is confirmed present from Phase 3a's `OutboxResilienceTest`) and drop the `minAge` field/parameter. Do not invent a different API.

- [ ] **Step 2: Write the manual-drain controller**

`src/main/java/com/company/pos/sync/web/ErpUploadController.java`:

```java
package com.company.pos.sync.web;

import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual trigger to drain the ERP upload outbox now (e.g. after the link is restored), mirroring
 * the MANAGER-only down-sync trigger POST /sync/erp. Resubmission is asynchronous; this returns
 * immediately once the resubmit has been kicked off.
 */
@RestController
class ErpUploadController {

    private final IncompleteEventPublications incomplete;

    ErpUploadController(IncompleteEventPublications incomplete) {
        this.incomplete = incomplete;
    }

    @PostMapping("/sync/erp/upload")
    @PreAuthorize("hasRole('MANAGER')")
    void drain() {
        incomplete.resubmitIncompletePublications(p -> true);
    }
}
```

- [ ] **Step 3: Add the upload config block**

In `src/main/resources/application.yml`, under the existing `pos.sync.erp` block (which currently has `scheduled` and `fixed-delay-ms`), add an `upload` child so the block reads:

```yaml
  sync:
    erp:
      scheduled: ${POS_SYNC_ERP_SCHEDULED:false}
      fixed-delay-ms: ${POS_SYNC_ERP_DELAY_MS:60000}
      upload:
        scheduled: ${POS_SYNC_ERP_UPLOAD_SCHEDULED:false}
        fixed-delay-ms: ${POS_SYNC_ERP_UPLOAD_DELAY_MS:30000}
        min-age-ms: ${POS_SYNC_ERP_UPLOAD_MIN_AGE_MS:10000}
```

(Keep the existing `scheduled`/`fixed-delay-ms` lines exactly; only add the `upload:` subtree. Indentation: `upload:` is a child of `erp:`.)

In `src/main/resources/application-store-server.yml`, the top of the file already enables down-sync:

```yaml
pos:
  sync:
    erp:
      scheduled: true
```

Extend it to also enable the upload drain:

```yaml
pos:
  sync:
    erp:
      scheduled: true
      upload:
        scheduled: true
```

- [ ] **Step 4: Write the offline-then-drain test**

`src/test/java/com/company/pos/sync/ErpUploadOfflineThenDrainsTest.java`:

```java
package com.company.pos.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves offline resilience: with the ERP "down", a completed sale still commits but its upload
 * fails and stays as an incomplete publication; once the ERP is back and the outbox is drained, the
 * sale uploads — exactly once (idempotent), despite the retry.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class ErpUploadOfflineThenDrainsTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    IncompleteEventPublications incomplete;
    @Autowired
    EventPublicationRegistry registry;
    @Autowired
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void cleanup() {
        databaseCleaner.clean();
        fake.clear();
    }

    @Test
    void saleUploadedWhenErpRecoversAndIsNotDoublePosted() {
        fake.setAvailable(false); // ERP offline

        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        UUID saleId = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))),
                "cashier").id();

        // The upload failed -> an incomplete publication is retained; nothing uploaded yet.
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(registry.findIncompletePublications()).isNotEmpty());
        assertThat(fake.uploadedSales()).isEmpty();

        // ERP recovers; drain the outbox (twice, to prove idempotency under repeated replay).
        fake.setAvailable(true);
        incomplete.resubmitIncompletePublications(p -> true);
        incomplete.resubmitIncompletePublications(p -> true);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(fake.uploadedSales()).hasSize(1);
            assertThat(fake.uploadedSales().get(0).saleId()).isEqualTo(saleId);
            assertThat(registry.findIncompletePublications()).isEmpty();
        });
    }
}
```

- [ ] **Step 5: Run the offline-then-drain test**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ErpUploadOfflineThenDrainsTest`
Expected: PASS — sale commits while offline, publication stays incomplete, then after recovery + resubmit the sale uploads exactly once and the registry drains.

- [ ] **Step 6: Write the controller auth test**

`src/test/java/com/company/pos/sync/ErpUploadControllerTest.java`:

```java
package com.company.pos.sync;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

// Auth-stamping mirrors the existing SyncControllerTest exactly (jwt().authorities(ROLE_*)).
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class ErpUploadControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void managerCanTriggerDrain() throws Exception {
        mvc.perform(post("/sync/erp/upload")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isOk());
    }

    @Test
    void cashierIsForbidden() throws Exception {
        mvc.perform(post("/sync/erp/upload")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CASHIER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(post("/sync/erp/upload"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 7: Run the controller test**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ErpUploadControllerTest`
Expected: PASS — manager 200, cashier 403, anonymous 401.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/company/pos/sync/ \
        src/main/resources/application.yml \
        src/main/resources/application-store-server.yml \
        src/test/java/com/company/pos/sync/ErpUploadOfflineThenDrainsTest.java \
        src/test/java/com/company/pos/sync/ErpUploadControllerTest.java
git commit -m "feat(sync): scheduled + manual ERP upload drain with offline resilience"
```

---

### Task 4: Capstone end-to-end + docs + full-suite verification

Prove the whole up-sync path through the REST surface, document it, and run the full suite as the phase gate.

**Files:**
- Test: `src/test/java/com/company/pos/ErpUpSyncEndToEndTest.java`
- Modify: `docs/run-modes.md`

**Interfaces:**
- Consumes: REST endpoints `/auth/login`, `/sync/erp`, `/carts`, `/carts/{id}/lines`, `/sales`, `/sync/erp/upload`; `FakeErpClient.uploadedSales()` / `setAvailable`; `DatabaseCleaner`.
- Produces: nothing consumed downstream.

- [ ] **Step 1: Write the end-to-end test**

`src/test/java/com/company/pos/ErpUpSyncEndToEndTest.java`:

```java
package com.company.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.util.Identifiers;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.support.DatabaseCleaner;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full up-sync path over REST: sell while the ERP is offline (sale still completes), then restore
 * the link and drain via POST /sync/erp/upload; the sale reaches the (fake) ERP exactly once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class ErpUpSyncEndToEndTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    FakeErpClient fake;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("20"), 1));
        users.save(new User(Identifiers.newId(), "cashier", "Cashier One",
                encoder.encode("pw"), Set.of(Role.CASHIER)));
        users.save(new User(Identifiers.newId(), "manager", "Store Manager",
                encoder.encode("pw"), Set.of(Role.MANAGER)));
    }

    @AfterEach
    void cleanup() {
        databaseCleaner.clean();
        fake.clear();
    }

    @Test
    void sellOfflineThenDrainUploadsToErp() throws Exception {
        String managerToken = login("manager");
        mvc.perform(post("/sync/erp").header("Authorization", managerToken))
                .andExpect(status().isOk());
        String token = login("cashier");

        fake.setAvailable(false); // ERP link down

        String createdCart = mvc.perform(post("/carts").header("Authorization", token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String cartId = JsonPath.read(createdCart, "$.cartId");
        mvc.perform(post("/carts/" + cartId + "/lines").header("Authorization", token)
                        .contentType("application/json").content("{\"sku\":\"COLA\",\"quantity\":2}"))
                .andExpect(status().isOk());
        // Sale completes even though the ERP is offline.
        mvc.perform(post("/sales").header("Authorization", token).contentType("application/json")
                        .content("{\"cartId\":\"" + cartId + "\",\"tenders\":[{\"method\":\"CASH\",\"tendered\":20.00}]}"))
                .andExpect(status().isCreated());

        // Link restored; manager drains the upload outbox.
        fake.setAvailable(true);
        mvc.perform(post("/sync/erp/upload").header("Authorization", managerToken))
                .andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(fake.uploadedSales()).hasSize(1));
    }

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }
}
```

- [ ] **Step 2: Run the end-to-end test**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ErpUpSyncEndToEndTest`
Expected: PASS.

- [ ] **Step 3: Document the up-sync**

Append to `docs/run-modes.md`:

```markdown
## ERP up-sync (Phase 3b)

Completed sales and their stock-movement deltas are uploaded to the ERP over the Phase 3a outbox.
A `sync`-module `@ApplicationModuleListener` on `SaleCompleted` fetches the full sale (`sales :: api`),
maps it to a `SaleUpload`, derives the per-line stock deltas (−quantity at the sale's location), and
calls the ERP adapter's idempotent `uploadSale` / `uploadStockMovements`.

- **Offline-first:** the upload runs only after the sale commits, asynchronously. If the ERP is
  down the call throws and the publication stays incomplete — the sale is unaffected. The store
  keeps trading; uploads queue in `event_publication`.
- **Drain:** incomplete publications are resubmitted on restart (republish-on-restart), on a
  schedule when `pos.sync.erp.upload.scheduled=true` (on by default for `store-server`), and on
  demand via `POST /sync/erp/upload` (MANAGER-only).
- **Idempotency:** uploads are at-least-once; the ERP adapter dedupes on `saleId`, so replay never
  double-posts. Sales/movements are immutable facts, so there is no merge/conflict logic — just
  idempotent delivery.
- The ERP adapter is still the in-memory `FakeErpClient`; a real vendor adapter replaces it later
  without touching the `sync` module.

Operator notifications for stuck (persistently failing) uploads and low-stock are Phase 3c. A
bounded async executor and a poison-publication retry cap (see the Phase 3a operational limits
above) should land before this path carries real ERP load.
```

- [ ] **Step 4: Run the full suite (phase gate)**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test`
Expected: BUILD SUCCESS, 0 failures, 0 errors. Confirm specifically: `ModularityTests` (boundaries + `sync` detected), `DatabaseStoreServerTest` (Postgres Testcontainer, Flyway V1–V14, `ddl-auto: validate` — no new migration, so the version count is unchanged), and the four new `sync`/integration tests. Report the actual totals line and whether `DatabaseStoreServerTest` truly ran on Docker (do not claim full verification if Docker was unavailable and it skipped).

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/company/pos/ErpUpSyncEndToEndTest.java docs/run-modes.md
git commit -m "test(sync): up-sync e2e (sell offline -> drain -> ERP); phase 3b docs"
```

---

## Notes for the executor

- **Reuse, don't rebuild.** The outbox already exists (Phase 3a). This phase adds NO migration, NO new table, NO cursor. If you find yourself creating an `erp_upload_queue` table or a Flyway V15, stop — the `event_publication` registry IS the queue.
- **Every committing test uses `DatabaseCleaner`** (`@Import(DatabaseCleaner.class)`, `clean()` first in `@BeforeEach` and in `@AfterEach`) and Awaitility for async assertions. The only Spring-free test here is `FakeErpClientUploadTest` (a POJO). Do not make any of these `@Transactional`.
- **Idempotency is the safety net for at-least-once replay** — never weaken `putIfAbsent` to a plain `put`, and never make the listener swallow the ERP exception (the throw is what queues the retry).
- **Verify the Modulith resubmit API** at Task 3 Step 1 by compiling; fall back to the predicate form if `resubmitIncompletePublicationsOlderThan(Duration)` is absent (it is the only unverified API call in this plan; `resubmitIncompletePublications(Predicate)` and `findIncompletePublications()` were proven in Phase 3a).
- **Scope boundary:** no `notification` module, no low-stock, no real ERP adapter, no relocation of the existing down-sync orchestration — those are Phase 3c / follow-ups.
- **Verification before "done":** the final `./mvnw -q test` (Task 4, Step 4) must be green, including `DatabaseStoreServerTest` on Docker. Report the actual output; do not claim success from a partial run.
