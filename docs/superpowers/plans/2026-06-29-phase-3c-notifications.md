# Phase 3c — Notifications (sync-error + low-stock) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Alert operators when ERP uploads are stuck in the outbox and when stock crosses a reorder threshold, through a vendor-neutral `Notifier` port with an in-memory/log adapter — completing Phase 3's notification scope.

**Architecture:** A new Tier-3 `notification` module is a passive subscriber. `inventory` gains a per-SKU `reorderLevel` (sourced from a config property) and its existing after-commit `SaleCompleted` listener edge-detects a downward crossing and publishes `inventory.api.LowStockDetected`. `notification` subscribes to that event with an `@ApplicationModuleListener` and raises a `LOW_STOCK` alert, and runs a scheduled monitor that polls the Phase 3a Event Publication Registry for publications stuck beyond a min-age and raises a deduped `SYNC_ERROR` alert. Both alerts go through a `Notifier` port whose only implementation this phase is an `InMemoryNotifier` fake (real SMS/email/push adapters land later with no module change). No retry-cap/dead-letter engine and no real channel are built here — operator visibility is the deliverable.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith 1.2.5 (Event Publication Registry from Phase 3a), Spring Data JPA, Flyway (store-server), JUnit 5 + Awaitility (transitive via `spring-boot-starter-test`) + spring-security-test, Testcontainers (Postgres), Maven (`./mvnw`).

## Global Constraints

- **Root package** `com.company.pos`; one package-per-module under it, boundaries enforced by `ApplicationModules.of(PosApplication.class).verify()` (`ModularityTests`). Cross-module access only via a sub-package marked `@org.springframework.modulith.NamedInterface("api")`, listed by the consumer in `allowedDependencies` as `module :: api`. `inventory.api` is already a named interface (the new `LowStockDetected` event goes there).
- **No dependency cycle:** `notification` depends only on `inventory :: api` (to subscribe to `LowStockDetected`). It reads the framework Event Publication Registry (`org.springframework.modulith.events.core.EventPublicationRegistry`), which is infrastructure, not a module, so it is **not** an `allowedDependencies` entry. `inventory` does **not** depend on `notification`. Trim `notification`'s `allowedDependencies` to exactly what its main sources import (the boundary verify is the source of truth).
- **JDK 21 required.** The machine default `java` is 17. Run every Maven command as: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw …`.
- **Build tool is Maven** via the committed `./mvnw` wrapper. No system `mvn`.
- **Money is `BigDecimal` — never `double`/`float`.** `reorderLevel`, `onHand`, and the crossing comparison use `BigDecimal` (`precision = 19, scale = 3`, matching `quantity_on_hand`). Compare with `compareTo`/`signum`, never `equals`.
- **Single physical `pos` schema.** Entities are schema-agnostic; the store-server profile sets `hibernate.default_schema: pos`. The new column is added by Flyway **V15** in `db/migration/inventory` (already in `flyway.locations` — no `flyway.locations` change). Embedded (`ddl-auto: update`, no Flyway) lets Hibernate add the column. store-server runs `ddl-auto: validate`, so the V15 column must match the entity mapping (verified by `DatabaseStoreServerTest`). The latest migration becomes **V15**.
- **Reuse the Phase 3a outbox.** The stuck-upload monitor reads incomplete publications from the registry; it adds NO table and NO cursor. The only new migration is V15 (the reorder column).
- **Low-stock is off by default.** `pos.inventory.reorder-level` defaults to `0` (disabled) so no existing test emits `LowStockDetected` and production ships dormant until an operator sets a threshold. The crossing is **edge-triggered**: emit only when `reorder > 0 && previousOnHand >= reorder && updatedOnHand < reorder` (so being already-low does not re-alert every sale).
- **Notifications are best-effort and never block the publisher.** The `LowStockAlertListener` is an `@ApplicationModuleListener` (after-commit, async, registry-tracked), like `inventory`/`cashdrawer`/`sync`. The stuck-upload monitor is a `@Scheduled` poller, default OFF (`@ConditionalOnProperty`), ON for store-server.
- **Alerts carry no PII / no secrets.** Alert messages contain only sku/location/quantities (low-stock) or publication counts/ages (sync-error) — never card data or credentials.
- **Test isolation (carry the Phase 3a/3b lesson):** every NON-`@Transactional` test that commits MUST use `com.company.pos.support.DatabaseCleaner` (`@Import`, `clean()` first in `@BeforeEach` and in `@AfterEach`) and Awaitility for async side-effects, and must reset singleton fakes (`fake.clear()`, `notifier.clear()`).

---

## Existing code this phase consumes / changes (already implemented — do not redefine)

```java
// com.company.pos.common.events
public interface DomainEvent {}
@Component public class DomainEvents { public void publish(DomainEvent event); }   // inventory will use this

// com.company.pos.inventory.api  (named interface "api")
public interface InventoryService { Optional<StockView> onHand(String sku); }
public interface InventorySync { int sync(); }
// + NEW this phase: LowStockDetected (Task 1)

// com.company.pos.inventory.domain.StockLevel  (CHANGED Task 1: + reorderLevel)
//   getId()/getSku()/getLocationCode()/getQuantityOnHand()/setQuantityOnHand(BigDecimal)/getErpVersion()/setErpVersion(long)
// com.company.pos.inventory.application.SaleCompletedListener  (CHANGED Task 1: @ApplicationModuleListener, now also publishes LowStockDetected)
// com.company.pos.inventory.application.InventoryErpSyncService (CHANGED Task 1: sets reorderLevel from a @Value property)
// com.company.pos.inventory.infrastructure.StockLevelRepository : Optional<StockLevel> findBySkuAndLocationCode(String, String) (public)

// org.springframework.modulith.events.core.EventPublicationRegistry  (bean, verified present in Phase 3a)
//   java.util.Collection<TargetEventPublication> findIncompletePublications();
// org.springframework.modulith.events.core.TargetEventPublication  (element type — accessors verified by compile in Task 3)
//   java.util.UUID getIdentifier();  java.time.Instant getPublicationDate();

// Seeding/driving (Phases 1-2): ProductSync.sync(), InventorySync.sync(), ErpProduct(...), ErpStockLevel(sku,location,qty,version),
//   CartService.createCart()/addLine(cartId,sku,qty), SalesService.checkout(new CheckoutCommand(cartId, List<TenderInput>), cashier),
//   new TenderInput(PaymentMethod.CASH, amount, tendered), InMemoryPaymentTerminal.setApprove(boolean)
// Phase 3b: FakeErpClient (public @Component) — setAvailable(boolean) simulates the ERP offline so an upload publication stays incomplete.
// Test helper (Phase 3a): com.company.pos.support.DatabaseCleaner — @Import + clean()
```

## New surface produced by this phase

| Module | New types / endpoints |
|---|---|
| `inventory` (`api`) | `LowStockDetected(String sku, String locationCode, BigDecimal onHand, BigDecimal reorderLevel)` (a `DomainEvent`) |
| `inventory` (domain/app) | `StockLevel.reorderLevel` (+ getter/setter); `InventoryErpSyncService` sets it from `pos.inventory.reorder-level`; `SaleCompletedListener` emits `LowStockDetected` |
| `notification` (new module) | `Notifier` (port), `Alert(AlertType, String message, Instant occurredAt)`, `AlertType {SYNC_ERROR, LOW_STOCK}`, `InMemoryNotifier` (fake `@Component`), `LowStockAlertListener`, `StuckUploadMonitor` (+ `StuckUploadScheduler`) |

## Module dependency declarations introduced

```
notification   allowedDependencies = { "inventory :: api" }    // Task 2 (trim to exactly what's imported)
```
`inventory` is unchanged (it already allows `common`, used for `DomainEvents`).

## Migrations & config added

| Version | Location dir | Change |
|---|---|---|
| V15 | `db/migration/inventory` | `ALTER TABLE stock_level ADD COLUMN reorder_level …` |

Config added to `application.yml`: `pos.inventory.reorder-level` (default 0) and `pos.notification.stuck-upload.{scheduled,fixed-delay-ms,min-age-ms}` (scheduled default false). `application-store-server.yml` enables `pos.notification.stuck-upload.scheduled: true`. No `flyway.locations` change (`db/migration/inventory` already listed).

---

### Task 1: `inventory` — reorder level + `LowStockDetected` event

Add a per-SKU `reorderLevel` to `StockLevel` (populated from a config property on ERP sync), and make the existing after-commit `SaleCompleted` listener publish `LowStockDetected` when a decrement edge-crosses below it. No new module dependency; uses the existing `common.events.DomainEvents`.

**Files:**
- Create: `src/main/java/com/company/pos/inventory/api/LowStockDetected.java`
- Modify: `src/main/java/com/company/pos/inventory/domain/StockLevel.java`
- Modify: `src/main/java/com/company/pos/inventory/application/InventoryErpSyncService.java`
- Modify: `src/main/java/com/company/pos/inventory/application/SaleCompletedListener.java`
- Create: `src/main/resources/db/migration/inventory/V15__stock_reorder_level.sql`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/company/pos/inventory/LowStockEmittedTest.java`

**Interfaces:**
- Consumes: `SaleCompleted` (sales :: api); `StockLevelRepository.findBySkuAndLocationCode`; `Identifiers.newId()`; `DomainEvents.publish` (common); the `pos.inventory.reorder-level` property.
- Produces: `record LowStockDetected(String sku, String locationCode, BigDecimal onHand, BigDecimal reorderLevel) implements DomainEvent`; `StockLevel.getReorderLevel()` / `setReorderLevel(BigDecimal)`.

- [ ] **Step 1: Write the `LowStockDetected` event**

`src/main/java/com/company/pos/inventory/api/LowStockDetected.java`:

```java
package com.company.pos.inventory.api;

import com.company.pos.common.events.DomainEvent;
import java.math.BigDecimal;

/** Published when an on-hand quantity crosses downward through its reorder level. */
public record LowStockDetected(String sku, String locationCode, BigDecimal onHand,
        BigDecimal reorderLevel) implements DomainEvent {
}
```

- [ ] **Step 2: Add `reorderLevel` to `StockLevel`**

In `src/main/java/com/company/pos/inventory/domain/StockLevel.java`, add the field (after `quantityOnHand`) and its accessors. Insert the field declaration:

```java
    @Column(name = "reorder_level", nullable = false, precision = 19, scale = 3)
    private BigDecimal reorderLevel = BigDecimal.ZERO;
```

and add these methods alongside the existing getters/setters:

```java
    public BigDecimal getReorderLevel() {
        return reorderLevel;
    }

    public void setReorderLevel(BigDecimal reorderLevel) {
        this.reorderLevel = reorderLevel;
    }
```

(Leave the constructor `StockLevel(UUID, String, String)` as-is — `reorderLevel` keeps its `BigDecimal.ZERO` default until set.)

- [ ] **Step 3: Write the failing emission test**

`src/test/java/com/company/pos/inventory/LowStockEmittedTest.java`:

```java
package com.company.pos.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.cart.api.CartService;
import com.company.pos.common.events.DomainEvent;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.inventory.api.InventoryService;
import com.company.pos.inventory.api.InventorySync;
import com.company.pos.inventory.api.LowStockDetected;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * NOT @Transactional: the stock decrement and the LowStockDetected publish happen in an
 * after-commit async listener. A test-only @ApplicationModuleListener captures the event;
 * DatabaseCleaner keeps the shared in-memory DB isolated. reorder-level forced to 19 so selling
 * 2 of 20 (-> 18) crosses below it.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@TestPropertySource(properties = "pos.inventory.reorder-level=19")
@Import({ DatabaseCleaner.class, LowStockEmittedTest.CapturingListener.class })
class LowStockEmittedTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    InventoryService inventory;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    InventorySync inventorySync;
    @Autowired
    DatabaseCleaner databaseCleaner;
    @Autowired
    CapturingListener captured;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
        captured.clear();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("20"), 1));
        productSync.sync();
        inventorySync.sync();
    }

    @AfterEach
    void cleanup() {
        databaseCleaner.clean();
        captured.clear();
    }

    @Test
    void crossingReorderLevelEmitsLowStockDetected() {
        var cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // 20 -> 18, below reorder 19
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100")))), "cashier");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(captured.events()).hasSize(1);
            LowStockDetected e = captured.events().get(0);
            assertThat(e.sku()).isEqualTo("COLA");
            assertThat(e.onHand()).isEqualByComparingTo("18");
            assertThat(e.reorderLevel()).isEqualByComparingTo("19");
        });
    }

    @Test
    void stayingAtOrAboveReorderLevelEmitsNothing() {
        var cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("1")); // 20 -> 19, NOT below reorder 19
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100")))), "cashier");

        // Wait until the decrement landed (proves the listener ran), then assert no event was emitted.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(inventory.onHand("COLA").orElseThrow().quantityOnHand())
                        .isEqualByComparingTo("19"));
        assertThat(captured.events()).isEmpty();
    }

    /** Test-only after-commit capture of LowStockDetected. */
    @Component
    static class CapturingListener {
        private final List<LowStockDetected> events = new CopyOnWriteArrayList<>();

        @ApplicationModuleListener
        void on(LowStockDetected event) {
            events.add(event);
        }

        List<LowStockDetected> events() {
            return events;
        }

        void clear() {
            events.clear();
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=LowStockEmittedTest`
Expected: FAIL — `LowStockDetected` is never published (the listener doesn't emit it yet), so the first test's Awaitility times out / `captured.events()` is empty. (It must compile — `LowStockDetected` and `getReorderLevel` exist from Steps 1–2.)

- [ ] **Step 5: Source `reorderLevel` from config on ERP sync**

In `src/main/java/com/company/pos/inventory/application/InventoryErpSyncService.java`: add the import `import java.math.BigDecimal;` and `import org.springframework.beans.factory.annotation.Value;`, add a field and constructor parameter, and set it on each upsert. The class becomes (changes marked):

```java
@Service
@Transactional
class InventoryErpSyncService implements InventorySync {

    private static final String STREAM = "stock";

    private final ErpClient erpClient;
    private final SyncCursorStore cursors;
    private final StockLevelRepository stock;
    private final BigDecimal reorderLevel;

    InventoryErpSyncService(ErpClient erpClient, SyncCursorStore cursors, StockLevelRepository stock,
            @Value("${pos.inventory.reorder-level:0}") BigDecimal reorderLevel) {
        this.erpClient = erpClient;
        this.cursors = cursors;
        this.stock = stock;
        this.reorderLevel = reorderLevel;
    }

    @Override
    public int sync() {
        long cursor = cursors.get(STREAM);
        List<ErpStockLevel> batch = erpClient.fetchStockLevelsSince(cursor);
        long maxVersion = cursor;
        int upserted = 0;

        for (ErpStockLevel e : batch) {
            if (e.version() > maxVersion) {
                maxVersion = e.version();
            }
            StockLevel existing = stock.findBySkuAndLocationCode(e.sku(), e.locationCode()).orElse(null);
            if (existing != null && existing.getErpVersion() >= e.version()) {
                continue;
            }
            StockLevel level = existing != null
                    ? existing
                    : new StockLevel(Identifiers.newId(), e.sku(), e.locationCode());
            level.setQuantityOnHand(e.quantityOnHand());
            level.setReorderLevel(reorderLevel);
            level.setErpVersion(e.version());
            stock.save(level);
            upserted++;
        }

        if (maxVersion > cursor) {
            cursors.set(STREAM, maxVersion);
        }
        return upserted;
    }
}
```

- [ ] **Step 6: Emit `LowStockDetected` from the sale listener**

Replace `src/main/java/com/company/pos/inventory/application/SaleCompletedListener.java`:

```java
package com.company.pos.inventory.application;

import com.company.pos.common.events.DomainEvents;
import com.company.pos.common.util.Identifiers;
import com.company.pos.inventory.api.LowStockDetected;
import com.company.pos.inventory.domain.StockLevel;
import com.company.pos.inventory.domain.StockMovement;
import com.company.pos.inventory.infrastructure.StockLevelRepository;
import com.company.pos.inventory.infrastructure.StockMovementRepository;
import com.company.pos.sales.api.SaleCompleted;
import java.math.BigDecimal;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Decrements on-hand and appends a movement-ledger row when a sale completes (after-commit, async,
 * own transaction; see Phase 3a). From Phase 3c it also publishes {@link LowStockDetected} when a
 * decrement edge-crosses below the SKU's reorder level. Exceptions propagate (no swallow) so a
 * failure leaves the publication incomplete for replay; a negative-stock result is only a warning.
 */
@Component
class SaleCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(SaleCompletedListener.class);

    private final StockLevelRepository stock;
    private final StockMovementRepository movements;
    private final DomainEvents events;

    SaleCompletedListener(StockLevelRepository stock, StockMovementRepository movements,
            DomainEvents events) {
        this.stock = stock;
        this.movements = movements;
        this.events = events;
    }

    @ApplicationModuleListener
    void on(SaleCompleted event) {
        for (SaleCompleted.SoldLine line : event.lines()) {
            applyMovement(event, line);
        }
    }

    private void applyMovement(SaleCompleted event, SaleCompleted.SoldLine line) {
        StockLevel level = stock.findBySkuAndLocationCode(line.sku(), event.locationCode())
                .orElseGet(() -> stock.save(
                        new StockLevel(Identifiers.newId(), line.sku(), event.locationCode())));
        BigDecimal previous = level.getQuantityOnHand();
        BigDecimal updated = previous.subtract(line.quantity());
        if (updated.signum() < 0) {
            log.warn("Stock for sku {} at {} went negative ({}) after sale {}",
                    line.sku(), event.locationCode(), updated, event.receiptNumber());
        }
        level.setQuantityOnHand(updated);
        movements.save(new StockMovement(Identifiers.newId(), line.sku(), event.locationCode(),
                line.quantity().negate(), "SALE", event.saleId().toString(), Instant.now()));

        BigDecimal reorder = level.getReorderLevel();
        if (reorder.signum() > 0 && previous.compareTo(reorder) >= 0 && updated.compareTo(reorder) < 0) {
            events.publish(new LowStockDetected(line.sku(), event.locationCode(), updated, reorder));
        }
    }
}
```

- [ ] **Step 7: Write the migration**

`src/main/resources/db/migration/inventory/V15__stock_reorder_level.sql`:

```sql
ALTER TABLE stock_level ADD COLUMN reorder_level NUMERIC(19, 3) NOT NULL DEFAULT 0;
```

- [ ] **Step 8: Add the config default**

In `src/main/resources/application.yml`, add an `inventory` block under the top-level `pos:` key (sibling of `auth` and `sync`):

```yaml
  inventory:
    reorder-level: ${POS_INVENTORY_REORDER_LEVEL:0}
```

(`0` = low-stock disabled by default. Tests override it via `@TestPropertySource`.)

- [ ] **Step 9: Run the emission test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=LowStockEmittedTest`
Expected: PASS (both tests).

- [ ] **Step 10: Verify the migration validates on Postgres and inventory regressions pass**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=DatabaseStoreServerTest,SaleDecrementsStockTest,InventoryErpSyncServiceTest,InventoryServiceTest`
Expected: PASS — V15 validates against the `reorderLevel` mapping on the Postgres Testcontainer (Flyway V1–V15); the existing inventory tests are unaffected (reorder default 0 → no emission). If `DatabaseStoreServerTest` fails on `stock_level` validation, reconcile the V15 column type with the entity mapping (`NUMERIC(19,3)`, `NOT NULL`) and re-run.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/company/pos/inventory/ \
        src/main/resources/db/migration/inventory/V15__stock_reorder_level.sql \
        src/main/resources/application.yml \
        src/test/java/com/company/pos/inventory/LowStockEmittedTest.java
git commit -m "feat(inventory): per-SKU reorder level + LowStockDetected on downward crossing"
```

---

### Task 2: `notification` module — `Notifier` port + low-stock alerting

Create the `notification` module: a `Notifier` port, an `Alert` model, an `InMemoryNotifier` fake, and a `LowStockAlertListener` that turns `LowStockDetected` into a `LOW_STOCK` alert.

**Files:**
- Create: `src/main/java/com/company/pos/notification/package-info.java`
- Create: `src/main/java/com/company/pos/notification/api/AlertType.java`
- Create: `src/main/java/com/company/pos/notification/api/Alert.java`
- Create: `src/main/java/com/company/pos/notification/api/Notifier.java`
- Create: `src/main/java/com/company/pos/notification/infrastructure/InMemoryNotifier.java`
- Create: `src/main/java/com/company/pos/notification/application/LowStockAlertListener.java`
- Test: `src/test/java/com/company/pos/notification/LowStockAlertTest.java`
- Modify: `src/test/java/com/company/pos/ModularityTests.java`

**Interfaces:**
- Consumes: `LowStockDetected` (inventory :: api, from Task 1); the sale-driving facades for the test.
- Produces:
  - `enum AlertType { SYNC_ERROR, LOW_STOCK }`
  - `record Alert(AlertType type, String message, Instant occurredAt)`
  - `interface Notifier { void send(Alert alert); }`
  - `InMemoryNotifier implements Notifier` (public `@Component`): `List<Alert> alerts()`, `void clear()` (used by tests).
  - `LowStockAlertListener` — `@ApplicationModuleListener void on(LowStockDetected)`.

- [ ] **Step 1: Declare the module**

`src/main/java/com/company/pos/notification/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "inventory :: api" })
package com.company.pos.notification;
```

> If `ModularityTests` reports an allowed-dependency the module does not import, trim it; if it reports a used-but-unlisted dependency, add it. Set it to exactly what the main sources import.

- [ ] **Step 2: Write the alert model and port**

`src/main/java/com/company/pos/notification/api/AlertType.java`:

```java
package com.company.pos.notification.api;

public enum AlertType {
    SYNC_ERROR,
    LOW_STOCK
}
```

`src/main/java/com/company/pos/notification/api/Alert.java`:

```java
package com.company.pos.notification.api;

import java.time.Instant;

public record Alert(AlertType type, String message, Instant occurredAt) {
}
```

`src/main/java/com/company/pos/notification/api/Notifier.java`:

```java
package com.company.pos.notification.api;

/** Delivers operator alerts. The only implementation this phase is the in-memory fake; real
 *  SMS/email/push adapters implement this later with no change to callers. */
public interface Notifier {

    void send(Alert alert);
}
```

- [ ] **Step 3: Write the in-memory fake**

`src/main/java/com/company/pos/notification/infrastructure/InMemoryNotifier.java`:

```java
package com.company.pos.notification.infrastructure;

import com.company.pos.notification.api.Alert;
import com.company.pos.notification.api.Notifier;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** In-memory + log Notifier stand-in. Replaced by a real channel adapter later. */
@Component
public class InMemoryNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(InMemoryNotifier.class);

    private final List<Alert> alerts = new CopyOnWriteArrayList<>();

    @Override
    public void send(Alert alert) {
        alerts.add(alert);
        log.info("ALERT [{}] {}", alert.type(), alert.message());
    }

    public List<Alert> alerts() {
        return List.copyOf(alerts);
    }

    public void clear() {
        alerts.clear();
    }
}
```

- [ ] **Step 4: Write the failing low-stock alert test**

`src/test/java/com/company/pos/notification/LowStockAlertTest.java`:

```java
package com.company.pos.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.inventory.api.InventorySync;
import com.company.pos.notification.api.AlertType;
import com.company.pos.notification.infrastructure.InMemoryNotifier;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Drives a real sale that crosses the reorder level and asserts a LOW_STOCK alert reaches the
 * in-memory Notifier (exercises inventory emission -> notification listener). Non-@Transactional
 * + DatabaseCleaner + Awaitility; the singleton InMemoryNotifier is reset around each test.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@TestPropertySource(properties = "pos.inventory.reorder-level=19")
@Import(DatabaseCleaner.class)
class LowStockAlertTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    InventorySync inventorySync;
    @Autowired
    InMemoryNotifier notifier;
    @Autowired
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
        notifier.clear();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("20"), 1));
        productSync.sync();
        inventorySync.sync();
    }

    @AfterEach
    void cleanup() {
        databaseCleaner.clean();
        notifier.clear();
    }

    @Test
    void crossingReorderRaisesLowStockAlert() {
        var cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // 20 -> 18, below reorder 19
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100")))), "cashier");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<com.company.pos.notification.api.Alert> lowStock = notifier.alerts().stream()
                    .filter(a -> a.type() == AlertType.LOW_STOCK)
                    .toList();
            assertThat(lowStock).hasSize(1);
            assertThat(lowStock.get(0).message()).contains("COLA");
        });
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=LowStockAlertTest`
Expected: FAIL — no `LowStockAlertListener` yet, so no `LOW_STOCK` alert is delivered (Awaitility times out).

- [ ] **Step 6: Write the low-stock alert listener**

`src/main/java/com/company/pos/notification/application/LowStockAlertListener.java`:

```java
package com.company.pos.notification.application;

import com.company.pos.inventory.api.LowStockDetected;
import com.company.pos.notification.api.Alert;
import com.company.pos.notification.api.AlertType;
import com.company.pos.notification.api.Notifier;
import java.time.Instant;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Turns a {@link LowStockDetected} fact into a LOW_STOCK operator alert (after-commit, async). */
@Component
class LowStockAlertListener {

    private final Notifier notifier;

    LowStockAlertListener(Notifier notifier) {
        this.notifier = notifier;
    }

    @ApplicationModuleListener
    void on(LowStockDetected event) {
        String message = "Low stock: %s at %s on-hand %s (reorder %s)".formatted(
                event.sku(), event.locationCode(), event.onHand(), event.reorderLevel());
        notifier.send(new Alert(AlertType.LOW_STOCK, message, Instant.now()));
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=LowStockAlertTest`
Expected: PASS — the sale crosses the reorder level, `inventory` emits `LowStockDetected`, and `notification` delivers a `LOW_STOCK` alert within the poll window.

- [ ] **Step 8: Add a module-presence assertion to `ModularityTests`**

In `src/test/java/com/company/pos/ModularityTests.java`, add after `detectsTheSyncModule()`:

```java
    @Test
    void detectsTheNotificationModule() {
        Set<String> names = modules.stream()
                .map(ApplicationModule::getName)
                .collect(Collectors.toSet());
        assertThat(names).contains("notification");
    }
```

- [ ] **Step 9: Verify module boundaries**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ModularityTests`
Expected: PASS — `notification → inventory :: api` declared; `notification` detected; no cycle. If the verify flags an allowed-but-unused or used-but-unlisted dependency for `notification`, set `package-info.java`'s `allowedDependencies` to exactly what its main sources import, then re-run.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/company/pos/notification/ \
        src/test/java/com/company/pos/notification/LowStockAlertTest.java \
        src/test/java/com/company/pos/ModularityTests.java
git commit -m "feat(notification): Notifier port + low-stock alerting via InMemoryNotifier"
```

---

### Task 3: `notification` — stuck-upload (sync-error) monitor

Add a monitor that polls the Phase 3a outbox for publications stuck longer than a min-age and raises a deduped `SYNC_ERROR` alert, plus a scheduled trigger (default off; on for store-server). The monitor's `scan()` is always a bean so tests can invoke it directly.

**Files:**
- Create: `src/main/java/com/company/pos/notification/application/StuckUploadMonitor.java`
- Create: `src/main/java/com/company/pos/notification/application/StuckUploadScheduler.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-store-server.yml`
- Test: `src/test/java/com/company/pos/notification/StuckUploadAlertTest.java`

**Interfaces:**
- Consumes: `org.springframework.modulith.events.core.EventPublicationRegistry.findIncompletePublications()` → `Collection<TargetEventPublication>`; `TargetEventPublication.getIdentifier()` (`UUID`) and `getPublicationDate()` (`Instant`); `Notifier.send(Alert)`; the `pos.notification.stuck-upload.min-age-ms` property; `FakeErpClient.setAvailable(false)` (test).
- Produces: `StuckUploadMonitor` with `void scan()` (always a `@Component`); `StuckUploadScheduler` (`@ConditionalOnProperty(prefix = "pos.notification.stuck-upload", name = "scheduled", havingValue = "true")`) that calls `scan()`.

- [ ] **Step 1: Write the monitor**

`src/main/java/com/company/pos/notification/application/StuckUploadMonitor.java`:

```java
package com.company.pos.notification.application;

import com.company.pos.notification.api.Alert;
import com.company.pos.notification.api.AlertType;
import com.company.pos.notification.api.Notifier;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.stereotype.Component;

/**
 * Polls the transactional outbox for publications that have stayed incomplete longer than the
 * configured min-age (e.g. ERP uploads that keep failing because the link is down) and raises a
 * SYNC_ERROR alert. Deduped by publication id so a single stuck row alerts once, not every scan.
 */
@Component
class StuckUploadMonitor {

    private final EventPublicationRegistry registry;
    private final Notifier notifier;
    private final long minAgeMs;
    private final Set<UUID> alerted = ConcurrentHashMap.newKeySet();

    StuckUploadMonitor(EventPublicationRegistry registry, Notifier notifier,
            @Value("${pos.notification.stuck-upload.min-age-ms:300000}") long minAgeMs) {
        this.registry = registry;
        this.notifier = notifier;
        this.minAgeMs = minAgeMs;
    }

    void scan() {
        Instant cutoff = Instant.now().minusMillis(minAgeMs);
        for (TargetEventPublication publication : registry.findIncompletePublications()) {
            if (publication.getPublicationDate().isBefore(cutoff)
                    && alerted.add(publication.getIdentifier())) {
                String message = "Outbox publication %s stuck since %s (not delivered)".formatted(
                        publication.getIdentifier(), publication.getPublicationDate());
                notifier.send(new Alert(AlertType.SYNC_ERROR, message, Instant.now()));
            }
        }
    }
}
```

> Verify the `TargetEventPublication` accessors compile against Modulith 1.2.5: `getIdentifier()` (`UUID`) and `getPublicationDate()` (`Instant`). `EventPublicationRegistry.findIncompletePublications()` is confirmed from Phase 3a. If an accessor name differs in this patch version, adjust to the actual API (inspect `org.springframework.modulith.events.core.TargetEventPublication`); do not invent one.

- [ ] **Step 2: Write the scheduled trigger**

`src/main/java/com/company/pos/notification/application/StuckUploadScheduler.java`:

```java
package com.company.pos.notification.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically runs the stuck-upload scan. Disabled by default; enabled on store-server. */
@Component
@ConditionalOnProperty(prefix = "pos.notification.stuck-upload", name = "scheduled",
        havingValue = "true")
class StuckUploadScheduler {

    private final StuckUploadMonitor monitor;

    StuckUploadScheduler(StuckUploadMonitor monitor) {
        this.monitor = monitor;
    }

    @Scheduled(fixedDelayString = "${pos.notification.stuck-upload.fixed-delay-ms:60000}",
            initialDelayString = "${pos.notification.stuck-upload.fixed-delay-ms:60000}")
    void run() {
        monitor.scan();
    }
}
```

- [ ] **Step 3: Add the config**

In `src/main/resources/application.yml`, add a `notification` block under the top-level `pos:` key (sibling of `inventory`, `sync`, `auth`):

```yaml
  notification:
    stuck-upload:
      scheduled: ${POS_NOTIFICATION_STUCK_UPLOAD_SCHEDULED:false}
      fixed-delay-ms: ${POS_NOTIFICATION_STUCK_UPLOAD_DELAY_MS:60000}
      min-age-ms: ${POS_NOTIFICATION_STUCK_UPLOAD_MIN_AGE_MS:300000}
```

In `src/main/resources/application-store-server.yml`, under the existing `pos.sync...` tree, add a sibling `notification` block (keep all existing keys):

```yaml
  notification:
    stuck-upload:
      scheduled: true
```

(Place it under the existing top-level `pos:` key in that file, alongside `sync:`.)

- [ ] **Step 4: Write the stuck-upload test**

`src/test/java/com/company/pos/notification/StuckUploadAlertTest.java`:

```java
package com.company.pos.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.notification.api.Alert;
import com.company.pos.notification.api.AlertType;
import com.company.pos.notification.application.StuckUploadMonitor;
import com.company.pos.notification.infrastructure.InMemoryNotifier;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * With the ERP "offline", a completed sale leaves an incomplete upload publication. With min-age
 * forced to 0, scan() alerts on it exactly once (deduped on re-scan). Non-@Transactional +
 * DatabaseCleaner + Awaitility; FakeErpClient and InMemoryNotifier reset around each test.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@TestPropertySource(properties = "pos.notification.stuck-upload.min-age-ms=0")
@Import(DatabaseCleaner.class)
class StuckUploadAlertTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    InMemoryNotifier notifier;
    @Autowired
    StuckUploadMonitor monitor;
    @Autowired
    EventPublicationRegistry registry;
    @Autowired
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
        notifier.clear();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void cleanup() {
        databaseCleaner.clean();
        notifier.clear();
    }

    @Test
    void stuckUploadRaisesOneSyncErrorAlert() {
        fake.setAvailable(false); // ERP offline -> the sale's upload publication stays incomplete

        var cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("1"));
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("10")))), "cashier");

        // Wait until the failed upload has left an incomplete publication in the outbox.
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(registry.findIncompletePublications()).isNotEmpty());

        monitor.scan();
        monitor.scan(); // second scan must NOT add a duplicate alert (dedup by publication id)

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<Alert> syncErrors = notifier.alerts().stream()
                    .filter(a -> a.type() == AlertType.SYNC_ERROR)
                    .toList();
            assertThat(syncErrors).hasSize(1);
        });
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

This test imports `StuckUploadMonitor`, so it cannot compile (let alone fail meaningfully) until Steps 1–3 exist — write Steps 1–4 in order, then run. The TDD "red" here is the absence of the monitor (the alert is never sent); the "green" is the monitor + config delivering exactly one deduped `SYNC_ERROR`.

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=StuckUploadAlertTest`
Expected: PASS — one `SYNC_ERROR` alert for the stuck upload publication; the second `scan()` is deduped (still exactly one).

- [ ] **Step 6: Verify boundaries unaffected**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ModularityTests`
Expected: PASS — the monitor reads the framework registry (not a module), so `notification`'s `allowedDependencies` is unchanged.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/pos/notification/application/ \
        src/main/resources/application.yml \
        src/main/resources/application-store-server.yml \
        src/test/java/com/company/pos/notification/StuckUploadAlertTest.java
git commit -m "feat(notification): scheduled stuck-upload monitor raising deduped SYNC_ERROR alerts"
```

---

### Task 4: Capstone end-to-end + docs + full-suite verification

Prove both alert types through the REST sell path, document the notification surface, and run the full suite as the phase gate.

**Files:**
- Test: `src/test/java/com/company/pos/NotificationsEndToEndTest.java`
- Modify: `docs/run-modes.md`

**Interfaces:**
- Consumes: REST `/auth/login`, `/sync/erp`, `/carts`, `/carts/{id}/lines`, `/sales`; `InMemoryNotifier.alerts()`; `StuckUploadMonitor.scan()`; `FakeErpClient.setAvailable`; `DatabaseCleaner`.
- Produces: nothing consumed downstream.

- [ ] **Step 1: Write the end-to-end test**

`src/test/java/com/company/pos/NotificationsEndToEndTest.java`:

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
import com.company.pos.notification.api.AlertType;
import com.company.pos.notification.application.StuckUploadMonitor;
import com.company.pos.notification.infrastructure.InMemoryNotifier;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end over REST: (1) a sale that crosses the reorder level raises a LOW_STOCK alert;
 * (2) with the ERP offline, a sale's stuck upload publication raises a SYNC_ERROR alert when the
 * monitor scans. Non-@Transactional + DatabaseCleaner + Awaitility; fakes reset around each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@TestPropertySource(properties = {
        "pos.inventory.reorder-level=19",
        "pos.notification.stuck-upload.min-age-ms=0" })
@Import(DatabaseCleaner.class)
class NotificationsEndToEndTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    FakeErpClient fake;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    InMemoryNotifier notifier;
    @Autowired
    StuckUploadMonitor monitor;
    @Autowired
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
        notifier.clear();
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
        notifier.clear();
    }

    @Test
    void crossingReorderRaisesLowStockAlert() throws Exception {
        String managerToken = login("manager");
        mvc.perform(post("/sync/erp").header("Authorization", managerToken))
                .andExpect(status().isOk());
        String token = login("cashier");

        ringUpTwoColas(token);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(notifier.alerts().stream().anyMatch(a -> a.type() == AlertType.LOW_STOCK))
                        .isTrue());
    }

    @Test
    void offlineUploadRaisesSyncErrorAlertOnScan() throws Exception {
        String managerToken = login("manager");
        mvc.perform(post("/sync/erp").header("Authorization", managerToken))
                .andExpect(status().isOk());
        String token = login("cashier");

        fake.setAvailable(false); // ERP offline -> upload publication stays incomplete
        ringUpTwoColas(token);

        monitor.scan();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(notifier.alerts().stream().anyMatch(a -> a.type() == AlertType.SYNC_ERROR))
                        .isTrue());
    }

    private void ringUpTwoColas(String token) throws Exception {
        String createdCart = mvc.perform(post("/carts").header("Authorization", token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String cartId = JsonPath.read(createdCart, "$.cartId");
        mvc.perform(post("/carts/" + cartId + "/lines").header("Authorization", token)
                        .contentType("application/json").content("{\"sku\":\"COLA\",\"quantity\":2}"))
                .andExpect(status().isOk());
        mvc.perform(post("/sales").header("Authorization", token).contentType("application/json")
                        .content("{\"cartId\":\"" + cartId + "\",\"tenders\":[{\"method\":\"CASH\",\"tendered\":20.00}]}"))
                .andExpect(status().isCreated());
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

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=NotificationsEndToEndTest`
Expected: PASS (both methods).

- [ ] **Step 3: Document the notification surface**

Append to `docs/run-modes.md`:

```markdown
## Notifications (Phase 3c)

The `notification` module raises operator alerts through a `Notifier` port (in-memory/log fake this
phase; real SMS/email/push later, no module change). Two alert types:

- **LOW_STOCK** — `inventory` carries a per-SKU `reorderLevel` (set from `pos.inventory.reorder-level`,
  default `0` = disabled). When a sale's stock decrement edge-crosses below it, `inventory` publishes
  `LowStockDetected`; `notification` turns it into a LOW_STOCK alert (after-commit, async).
- **SYNC_ERROR** — a scheduled monitor (`pos.notification.stuck-upload.scheduled`, off by default,
  on for `store-server`) polls the Phase 3a outbox for publications still incomplete beyond
  `pos.notification.stuck-upload.min-age-ms` (default 5 min) — e.g. ERP uploads stuck because the
  link is down — and raises a SYNC_ERROR alert, deduped per publication so a stuck row alerts once.

This is observability only: there is still no automatic retry-cap/dead-letter and the async executor
is still unbounded (see the Phase 3a operational limits). Real channels, those hardening items, and
auto-reordering are later work.
```

- [ ] **Step 4: Run the full suite (phase gate)**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test`
Expected: BUILD SUCCESS, 0 failures, 0 errors. Confirm specifically: `ModularityTests` (boundaries + `notification` detected), `DatabaseStoreServerTest` (Postgres Testcontainer, Flyway V1–V15, `ddl-auto: validate`), and the four new notification/inventory tests. Report the actual totals line and whether `DatabaseStoreServerTest` truly ran on Docker (do not claim full verification if Docker was unavailable and it skipped).

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/company/pos/NotificationsEndToEndTest.java docs/run-modes.md
git commit -m "test(notification): e2e low-stock + sync-error alerts; phase 3c docs"
```

---

## Notes for the executor

- **Reuse, don't rebuild.** The stuck-upload source is the Phase 3a outbox (`event_publication`). The only new migration is V15 (the reorder column); no new table, no cursor, no dead-letter engine.
- **Low-stock is edge-triggered and off by default.** Emit only on a downward crossing (`prev >= reorder && updated < reorder`, `reorder > 0`). `pos.inventory.reorder-level` defaults to `0`, so existing tests emit nothing; the 3c tests set it via `@TestPropertySource`.
- **Every committing test uses `DatabaseCleaner`** (`@Import`, `clean()` first in `@BeforeEach` and in `@AfterEach`) + Awaitility, and resets the singleton fakes (`fake.clear()`, `notifier.clear()`). Do not make these `@Transactional`.
- **Verify the `TargetEventPublication` accessors** (`getIdentifier()`, `getPublicationDate()`) compile against Modulith 1.2.5 at Task 3 Step 1; adjust to the real API if a name differs — it is the only unverified API in this plan (`findIncompletePublications()` itself is confirmed from Phase 3a).
- **Trim `notification`'s `allowedDependencies`** to exactly what its main sources import (the 3b "unused `common`" nit) — let `ModularityTests` confirm.
- **Scope boundary:** no real notification channel, no bounded executor / idempotent-listener / dead-letter hardening, no auto-reordering — those are deferred (some to the Phase 3 hardening follow-up noted in `docs/run-modes.md`).
- **Verification before "done":** the final `./mvnw -q test` (Task 4, Step 4) must be green including `DatabaseStoreServerTest` on Docker. Report the actual output; do not claim success from a partial run.
