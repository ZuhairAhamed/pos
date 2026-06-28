# Phase 3a — Transactional Outbox Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make in-process domain-event delivery durable and decoupled by adopting the Spring Modulith Event Publication Registry (transactional outbox), so a completed sale is never rolled back by a downstream side-effect and any side-effect that fails or is interrupted by a crash is persisted and replayable.

**Architecture:** Adds the Modulith JPA Event Publication Registry. Today `inventory` and `cashdrawer` subscribe to `SaleCompleted` with plain `@EventListener`s that run *inside* the checkout transaction and swallow `RuntimeException`s (a fragile, documented stop-gap). This phase converts both to `@ApplicationModuleListener` (= `@Async` + `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`). The registry writes one `event_publication` row per (event, listener) inside the publishing transaction, then drains it after commit on a separate thread/transaction; the row's `completion_date` is stamped only when the listener returns normally. A listener failure therefore leaves an *incomplete publication* that can be resubmitted (and is republished on application restart) — without ever touching the already-committed sale. No ERP upload and no notifications are built here; those are Phase 3b and 3c.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith 1.2.5 (`spring-modulith-starter-jpa`, `spring-modulith-events-jackson`), Spring Data JPA, Flyway (store-server), JUnit 5 + Awaitility (transitive via `spring-boot-starter-test`) + spring-security-test, Testcontainers (Postgres), Maven (`./mvnw`).

## Global Constraints

- **Root package** `com.company.pos`; one package-per-module under it, boundaries enforced by `ApplicationModules.of(PosApplication.class).verify()` (the `ModularityTests`). This phase adds **no** new cross-module dependency: `inventory` and `cashdrawer` already declare `sales :: api`. The Event Publication Registry is framework infrastructure (package `org.springframework.modulith.events.*`), not a module, and does not appear in any module's `allowedDependencies`.
- **JDK 21 required.** The machine default `java` is 17. Run every Maven command as: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw …`.
- **Build tool is Maven** via the committed `./mvnw` wrapper. No system `mvn`.
- **Single physical `pos` schema.** Entities are schema-agnostic (no `@Table(schema=…)`); the store-server profile sets `hibernate.default_schema: pos` and `flyway.default-schema: pos`. The framework's `event_publication` table is created unqualified and resolves to `pos.event_publication`.
- **Flyway versions are globally unique and ordered across all per-module locations.** V1–V13 already exist (latest: V12 cashdrawer, V13 shift). The new migration is **V14** in a new `db/migration/events` directory, which MUST be appended to the `flyway.locations` comma-separated list in `application-store-server.yml`. The `embedded` profile uses Hibernate `ddl-auto: update` and **no Flyway** — Hibernate auto-creates `event_publication` there; tests run on `embedded` unless they explicitly use the `store-server` profile + Testcontainers.
- **store-server runs `ddl-auto: validate`.** The V14 migration's schema MUST match the Modulith JPA entity (`org.springframework.modulith.events.jpa.JpaEventPublication`) exactly, or the app fails to boot on store-server. Task 1 proves this with the existing `DatabaseStoreServerTest` (boots store-server on a Postgres Testcontainer) and iterates the DDL until that test is green.
- **Offline-first invariant (the point of this phase):** nothing in the sell path may block on connectivity, and a committed sale must never be rolled back by a downstream side effect. After this phase the `SaleCompleted` listeners run **after commit, asynchronously, in their own transaction** — a listener exception can no longer affect the sale; it just leaves an incomplete publication for replay.
- **Async delivery:** `@ApplicationModuleListener` is `@Async`. `PosApplication` gains `@EnableAsync`. Because listeners now run on a separate thread after commit, tests that assert listener *side-effects* must commit (no `@Transactional`) and poll with Awaitility; tests that only assert event *publication* (`@RecordApplicationEvents`) are unaffected.
- **Errors** continue to surface as `DomainException.notFound/validation/conflict(...)` → RFC-7807 `ProblemDetail` via the existing `ApiExceptionHandler`. No new endpoints this phase.

---

## Existing code this phase consumes or changes (do not redefine)

```java
// com.company.pos.common.events
public interface DomainEvent {}                         // marker; SaleCompleted implements it
@Component public class DomainEvents {                  // wraps ApplicationEventPublisher
    public void publish(DomainEvent event); }

// com.company.pos.sales.api  (named interface "api") — published inside checkout's @Transactional
public record SaleCompleted(UUID saleId, String receiptNumber, String terminalId, String locationCode,
        String currencyCode, BigDecimal grandTotal, BigDecimal cashTotal, List<SoldLine> lines)
        implements DomainEvent { public record SoldLine(String sku, BigDecimal quantity) {} }

// com.company.pos.inventory.application.SaleCompletedListener  (CHANGED in Task 2)
//   currently: @EventListener @Transactional void on(SaleCompleted) { try { … } catch (RuntimeException) { log } }
// com.company.pos.cashdrawer.application.SaleCompletedCashListener  (CHANGED in Task 3)
//   currently: an @EventListener that calls CashDrawerService.recordCashSale(...) and swallows RuntimeException

// Public repositories available for test cleanup (all are `public interface … extends JpaRepository`):
//   inventory.infrastructure.StockLevelRepository, StockMovementRepository
//   cashdrawer.infrastructure.DrawerSessionRepository, CashMovementRepository
//   shift.infrastructure.ShiftRepository
//   auth.infrastructure.UserRepository  (also: Optional<User> findByUsername(String))
//   inventory.api.InventoryService.onHand(sku) → Optional<StockView>  (StockView.quantityOnHand())
//   cashdrawer.api.CashDrawerService.findOpenSession(terminalId) / reconcile(sessionId)
//   shift.api.ShiftService.findOpenShift(terminalId)

// InventoryErpSyncService.sync() SETS on-hand to the absolute ERP value (level.setQuantityOnHand(e.quantityOnHand())),
// so re-syncing in @BeforeEach deterministically resets stock regardless of prior decrements.
```

## New / changed surface produced by this phase

| Area | Change |
|---|---|
| `pom.xml` | add `spring-modulith-starter-jpa`, `spring-modulith-events-jackson` |
| `PosApplication` | add `@EnableAsync` |
| `application.yml` | add `spring.modulith.events.republish-outstanding-publications-on-restart: true` |
| `application-store-server.yml` | append `classpath:db/migration/events` to `flyway.locations` |
| `db/migration/events/V14__event_publication.sql` | new — the registry's outbox table |
| `inventory.application.SaleCompletedListener` | `@EventListener`→`@ApplicationModuleListener`; remove `RuntimeException` swallow |
| `cashdrawer.application.SaleCompletedCashListener` | `@EventListener`→`@ApplicationModuleListener`; remove `RuntimeException` swallow |

## Tests touched

| Test | Change | Why |
|---|---|---|
| `inventory/SaleDecrementsStockTest` | drop `@Transactional`; Awaitility; `@AfterEach` cleanup | asserts async listener side-effect (stock decrement) |
| `cashdrawer/SaleCapturedByDrawerTest` | drop `@Transactional`; Awaitility; `@AfterEach` cleanup | asserts async listener side-effect (cash capture) |
| `CashSaleEndToEndTest` | keep `@Transactional`; drop the stock-decrement step; rename | stock decrement now async — covered by `SaleDecrementsStockTest` |
| `ShiftReconciliationEndToEndTest` | drop `@Transactional`; await cash capture before close; `@AfterEach` cleanup | close-shift assertion depends on the async cash capture |
| `OutboxResilienceTest` (new) | new | proves an incomplete publication is retained and replayable |
| `SaleCompletedEventTest`, `DomainEventsTest` | **unchanged** | assert publication, not listener completion |
| `DatabaseStoreServerTest` | **unchanged** (used as the migration validator) | boots store-server on Postgres with `validate` |

---

### Task 1: Add the Event Publication Registry (dependencies, config, migration)

Wire in the Modulith JPA registry so that every transactional event listener gets a persisted, replayable `event_publication` row. This task changes no listener yet — it only adds the infrastructure and proves the table validates on store-server and auto-creates on embedded.

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/java/com/company/pos/PosApplication.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-store-server.yml`
- Create: `src/main/resources/db/migration/events/V14__event_publication.sql`
- Test: `src/test/java/com/company/pos/OutboxRegistryWiringTest.java` (new — embedded smoke test that the registry beans exist and the table is usable)

**Interfaces:**
- Consumes: nothing new from earlier tasks.
- Produces: the Modulith beans `org.springframework.modulith.events.core.EventPublicationRegistry` and `org.springframework.modulith.events.IncompleteEventPublications` available for injection (used by Task 5); the `event_publication` table on both profiles.

- [ ] **Step 1: Add the Modulith JPA + Jackson dependencies**

In `pom.xml`, inside `<dependencies>` (next to the existing `spring-modulith-starter-core`), add:

```xml
        <dependency>
            <groupId>org.springframework.modulith</groupId>
            <artifactId>spring-modulith-starter-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.modulith</groupId>
            <artifactId>spring-modulith-events-jackson</artifactId>
        </dependency>
```

Versions are governed by the existing `spring-modulith-bom` import (1.2.5) — do **not** add version tags. `spring-modulith-starter-jpa` brings the JPA-backed registry (`spring-modulith-events-jpa`, `-core`); `spring-modulith-events-jackson` provides the `JacksonEventSerializer` that serializes events to/from the `serialized_event` column using the application `ObjectMapper`.

- [ ] **Step 2: Enable async event handling on the application**

Replace `src/main/java/com/company/pos/PosApplication.java`:

```java
package com.company.pos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class PosApplication {

    public static void main(String[] args) {
        SpringApplication.run(PosApplication.class, args);
    }
}
```

`@EnableAsync` is required for `@ApplicationModuleListener`'s `@Async` to take effect (Tasks 2–3). The default `SimpleAsyncTaskExecutor` is sufficient for this phase.

- [ ] **Step 3: Republish outstanding publications on restart**

In `src/main/resources/application.yml`, add a `modulith` block under the top-level `spring:` key (sibling of `application`, `main`, `profiles`, `jpa`):

```yaml
  modulith:
    events:
      republish-outstanding-publications-on-restart: true
```

This makes the app, on startup, re-deliver any `event_publication` row whose `completion_date` is still null (e.g. a side-effect interrupted by a crash). Do **not** add `completion-mode` — that property does not exist in Modulith 1.2.5; the default behaviour stamps `completion_date` on success.

- [ ] **Step 4: Write the outbox migration (store-server)**

`src/main/resources/db/migration/events/V14__event_publication.sql`:

```sql
-- Spring Modulith Event Publication Registry (transactional outbox).
-- Schema must match org.springframework.modulith.events.jpa.JpaEventPublication so that
-- Hibernate `ddl-auto: validate` passes on the store-server profile. If validation fails,
-- adjust the column types below to match the type named in the Hibernate error (Step 6).
CREATE TABLE IF NOT EXISTS event_publication (
    id               UUID NOT NULL,
    listener_id      TEXT NOT NULL,
    event_type       TEXT NOT NULL,
    serialized_event TEXT NOT NULL,
    publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date  TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_event_publication_completion_date
    ON event_publication (completion_date);
CREATE INDEX IF NOT EXISTS idx_event_publication_listener_serialized
    ON event_publication (listener_id, serialized_event);
```

> Rationale for the column types: `JpaEventPublication.id` is a `UUID` (→ Postgres `uuid`); `publicationDate`/`completionDate` are `java.time.Instant`, which Hibernate 6 maps to `timestamp with time zone` on Postgres; the three string columns are unbounded text. The two indexes mirror the registry's lookup patterns (drain-incomplete by completion date; locate-by-event for completion).

- [ ] **Step 5: Register the migration location**

In `src/main/resources/application-store-server.yml`, append `,classpath:db/migration/events` to the end of the existing single comma-separated `locations:` value (keep every existing entry). The line becomes:

```yaml
    locations: classpath:db/migration/configuration,classpath:db/migration/auth,classpath:db/migration/product,classpath:db/migration/inventory,classpath:db/migration/integration,classpath:db/migration/cart,classpath:db/migration/payment,classpath:db/migration/sales,classpath:db/migration/cashdrawer,classpath:db/migration/shift,classpath:db/migration/events
```

- [ ] **Step 6: Validate the migration against the framework entity on Postgres**

Run the existing store-server boot test (it activates the `store-server` profile, runs Flyway against a Postgres Testcontainer, and boots with `ddl-auto: validate`):

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=DatabaseStoreServerTest`
Expected: PASS. A PASS proves the V14 schema matches the `JpaEventPublication` mapping (Hibernate validated it).

If it FAILS with a schema-validation error naming `event_publication`, the message states the column and the type Hibernate expected (most likely friction points: `timestamp` vs `timestamp with time zone`, or `varchar` vs `text`). Edit `V14__event_publication.sql` to match exactly and re-run until green.

> Optional aid to read Hibernate's exact expectation: temporarily add `-Dspring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create -Dspring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=target/event-schema.sql` to a throwaway run, open `target/event-schema.sql`, copy the `create table event_publication` statement, then revert.

- [ ] **Step 7: Write the embedded wiring smoke test**

`src/test/java/com/company/pos/OutboxRegistryWiringTest.java`:

```java
package com.company.pos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class OutboxRegistryWiringTest {

    @Autowired(required = false)
    EventPublicationRegistry registry;
    @Autowired(required = false)
    IncompleteEventPublications incomplete;

    @Test
    void registryBeansArePresentAndStartEmpty() {
        assertThat(registry).as("EventPublicationRegistry bean").isNotNull();
        assertThat(incomplete).as("IncompleteEventPublications bean").isNotNull();
        // Nothing published in this test -> no incomplete publications.
        assertThat(registry.findIncompletePublications()).isEmpty();
    }
}
```

- [ ] **Step 8: Run the wiring smoke test**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=OutboxRegistryWiringTest`
Expected: PASS — the JPA registry auto-configures on embedded, Hibernate auto-creates `event_publication`, and both registry beans inject.

> If `EventPublicationRegistry.findIncompletePublications()` does not resolve (API moved in the patch version), this is the place to discover the correct type — check `org.springframework.modulith.events.core.EventPublicationRegistry` on the classpath and adjust this test and Task 5 accordingly. Do not proceed past this step with a non-compiling registry call.

- [ ] **Step 9: Confirm nothing else regressed**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ModularityTests,PosApplicationTests,DomainEventsTest`
Expected: PASS — module graph unchanged, context still boots, plain publication still works.

- [ ] **Step 10: Commit**

```bash
git add pom.xml \
        src/main/java/com/company/pos/PosApplication.java \
        src/main/resources/application.yml \
        src/main/resources/application-store-server.yml \
        src/main/resources/db/migration/events/ \
        src/test/java/com/company/pos/OutboxRegistryWiringTest.java
git commit -m "feat(outbox): add Spring Modulith event publication registry (JPA) + migration"
```

---

### Task 2: Convert the inventory listener to a durable after-commit consumer

Change `inventory`'s `SaleCompleted` subscriber from an in-transaction `@EventListener` (that swallows exceptions) to an `@ApplicationModuleListener` (after-commit, async, own transaction). Remove the `RuntimeException` swallow: a failure must now propagate so the registry keeps the publication incomplete for replay — it can no longer harm the sale, which has already committed. Rework `SaleDecrementsStockTest` for async delivery.

**Files:**
- Modify: `src/main/java/com/company/pos/inventory/application/SaleCompletedListener.java`
- Test: `src/test/java/com/company/pos/inventory/SaleDecrementsStockTest.java`

**Interfaces:**
- Consumes: `SaleCompleted` (sales :: api); `StockLevelRepository`, `StockMovementRepository` (inventory infra); `Identifiers.newId()`.
- Produces: a `@Component` with `@ApplicationModuleListener void on(SaleCompleted)` that the registry tracks; on success the publication completes, on exception it stays incomplete.

- [ ] **Step 1: Rewrite the listener as an after-commit module listener**

Replace `src/main/java/com/company/pos/inventory/application/SaleCompletedListener.java`:

```java
package com.company.pos.inventory.application;

import com.company.pos.common.util.Identifiers;
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
 * Decrements on-hand and appends a movement-ledger row when a sale completes.
 *
 * <p>From Phase 3a this is an {@link ApplicationModuleListener}: it runs <em>after</em> the
 * checkout transaction commits, asynchronously, in its own transaction. The Spring Modulith
 * Event Publication Registry persists an {@code event_publication} row for this listener inside
 * the publishing (sale) transaction and stamps its completion only when this method returns
 * normally. Consequently a failure here can no longer roll back the sale; it leaves an
 * incomplete publication that is resubmitted on restart (or via
 * {@code IncompleteEventPublications}). We therefore no longer swallow exceptions — letting one
 * propagate is what triggers durable retry. A negative-stock result is still only a warning,
 * not a failure, so it neither blocks nor poisons the publication.
 */
@Component
class SaleCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(SaleCompletedListener.class);

    private final StockLevelRepository stock;
    private final StockMovementRepository movements;

    SaleCompletedListener(StockLevelRepository stock, StockMovementRepository movements) {
        this.stock = stock;
        this.movements = movements;
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
        BigDecimal updated = level.getQuantityOnHand().subtract(line.quantity());
        if (updated.signum() < 0) {
            log.warn("Stock for sku {} at {} went negative ({}) after sale {}",
                    line.sku(), event.locationCode(), updated, event.receiptNumber());
        }
        level.setQuantityOnHand(updated);
        movements.save(new StockMovement(Identifiers.newId(), line.sku(), event.locationCode(),
                line.quantity().negate(), "SALE", event.saleId().toString(), Instant.now()));
    }
}
```

> Removed: `@EventListener`, `@Transactional` (the module listener supplies `REQUIRES_NEW`), and the per-line `try/catch (RuntimeException)`. The `@Transactional` import is no longer needed.

- [ ] **Step 2: Rewrite `SaleDecrementsStockTest` for async, committed delivery**

Replace `src/test/java/com/company/pos/inventory/SaleDecrementsStockTest.java`:

```java
package com.company.pos.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.inventory.api.InventoryService;
import com.company.pos.inventory.api.InventorySync;
import com.company.pos.inventory.infrastructure.StockLevelRepository;
import com.company.pos.inventory.infrastructure.StockMovementRepository;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * NOT @Transactional: the stock decrement now runs in an after-commit async listener, so the
 * sale must really commit and the assertion polls until the listener has run. @AfterEach clears
 * the inventory tables because, without a rolling-back test transaction, rows would otherwise
 * leak across tests in the shared in-memory database.
 */
@SpringBootTest
@ActiveProfiles("embedded")
class SaleDecrementsStockTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    InventoryService inventory;
    @Autowired
    StockMovementRepository movements;
    @Autowired
    StockLevelRepository stockLevels;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    InventorySync inventorySync;

    @BeforeEach
    void seed() {
        movements.deleteAll();
        stockLevels.deleteAll();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("20"), 1));
        productSync.sync();
        inventorySync.sync();
    }

    @AfterEach
    void cleanup() {
        movements.deleteAll();
        stockLevels.deleteAll();
    }

    @Test
    void completingSaleDecrementsOnHandAndWritesMovement() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("3"));
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100")))), "cashier");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(inventory.onHand("COLA").orElseThrow().quantityOnHand())
                    .isEqualByComparingTo("17");   // 20 - 3
            assertThat(movements.findBySku("COLA")).hasSize(1);
            assertThat(movements.findBySku("COLA").get(0).getQuantityDelta())
                    .isEqualByComparingTo("-3");
        });
    }
}
```

- [ ] **Step 3: Run the inventory test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=SaleDecrementsStockTest`
Expected: PASS — checkout commits, the after-commit listener decrements stock within the 5s poll window.

- [ ] **Step 4: Confirm the event still publishes and boundaries hold**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=SaleCompletedEventTest,ModularityTests`
Expected: PASS — `SaleCompleted` is still published synchronously (recorded by `@RecordApplicationEvents`); the module graph is unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/pos/inventory/application/SaleCompletedListener.java \
        src/test/java/com/company/pos/inventory/SaleDecrementsStockTest.java
git commit -m "refactor(inventory): consume SaleCompleted via after-commit outbox listener"
```

---

### Task 3: Convert the cashdrawer listener to a durable after-commit consumer

Same conversion for `cashdrawer`'s cash-capture listener, and rework `SaleCapturedByDrawerTest` for async delivery.

**Files:**
- Modify: `src/main/java/com/company/pos/cashdrawer/application/SaleCompletedCashListener.java`
- Test: `src/test/java/com/company/pos/cashdrawer/SaleCapturedByDrawerTest.java`

**Interfaces:**
- Consumes: `SaleCompleted.terminalId()` / `.cashTotal()` / `.saleId()` (sales :: api); `CashDrawerService.recordCashSale(String, BigDecimal, String)` (cashdrawer :: api).
- Produces: a `@Component` with `@ApplicationModuleListener void on(SaleCompleted)` tracked by the registry.

- [ ] **Step 1: Read the current listener to preserve its exact behaviour**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" sed -n '1,80p' src/main/java/com/company/pos/cashdrawer/application/SaleCompletedCashListener.java`
Note the field names, the no-op-on-zero-cash guard, and the `recordCashSale(terminalId, cashTotal, reference)` call so they are reproduced verbatim below.

- [ ] **Step 2: Rewrite the listener as an after-commit module listener**

Replace `src/main/java/com/company/pos/cashdrawer/application/SaleCompletedCashListener.java` with the following. Preserve the existing zero-cash guard and the `saleId`-as-reference argument exactly as in the current file (adjust the body only if the current guard differs):

```java
package com.company.pos.cashdrawer.application;

import com.company.pos.cashdrawer.api.CashDrawerService;
import com.company.pos.sales.api.SaleCompleted;
import java.math.BigDecimal;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Attributes the cash portion of a completed sale to the terminal's open drawer session.
 *
 * <p>From Phase 3a this is an {@link ApplicationModuleListener}: it runs after the checkout
 * transaction commits, asynchronously, in its own transaction, and is tracked by the Spring
 * Modulith Event Publication Registry. A failure leaves an incomplete publication for replay
 * rather than affecting the (already committed) sale, so we no longer swallow exceptions.
 * {@link CashDrawerService#recordCashSale} is still a no-op when no session is open for the
 * terminal, so cash sales rung up without an open drawer are simply not captured (not errors).
 */
@Component
class SaleCompletedCashListener {

    private final CashDrawerService drawer;

    SaleCompletedCashListener(CashDrawerService drawer) {
        this.drawer = drawer;
    }

    @ApplicationModuleListener
    void on(SaleCompleted event) {
        if (event.cashTotal() == null || event.cashTotal().signum() <= 0) {
            return; // nothing paid in cash
        }
        drawer.recordCashSale(event.terminalId(), event.cashTotal(), event.saleId().toString());
    }
}
```

> If the current file used a different constructor field name or reference string, keep the existing one — the only required change is `@EventListener`(+`@Transactional`/`try-catch`) → `@ApplicationModuleListener`, and removing the exception swallow.

- [ ] **Step 3: Rewrite `SaleCapturedByDrawerTest` for async, committed delivery**

Replace `src/test/java/com/company/pos/cashdrawer/SaleCapturedByDrawerTest.java`:

```java
package com.company.pos.cashdrawer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.cart.api.CartService;
import com.company.pos.cashdrawer.api.CashDrawerService;
import com.company.pos.cashdrawer.api.DrawerReconciliation;
import com.company.pos.cashdrawer.api.DrawerSessionView;
import com.company.pos.cashdrawer.infrastructure.CashMovementRepository;
import com.company.pos.cashdrawer.infrastructure.DrawerSessionRepository;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.inventory.infrastructure.StockLevelRepository;
import com.company.pos.inventory.infrastructure.StockMovementRepository;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * NOT @Transactional: cash capture now runs in an after-commit async listener. @AfterEach clears
 * the drawer tables (and the inventory tables the stock listener also writes) so committed rows
 * do not leak across tests in the shared in-memory database. FK order: cash_movement before
 * drawer_session.
 */
@SpringBootTest
@ActiveProfiles("embedded")
class SaleCapturedByDrawerTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    CashDrawerService drawer;
    @Autowired
    CashMovementRepository cashMovements;
    @Autowired
    DrawerSessionRepository drawerSessions;
    @Autowired
    StockMovementRepository stockMovements;
    @Autowired
    StockLevelRepository stockLevels;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    InMemoryPaymentTerminal terminal;

    @BeforeEach
    void seed() {
        cashMovements.deleteAll();
        drawerSessions.deleteAll();
        stockMovements.deleteAll();
        stockLevels.deleteAll();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
        terminal.setApprove(true);
    }

    @AfterEach
    void cleanup() {
        cashMovements.deleteAll();
        drawerSessions.deleteAll();
        stockMovements.deleteAll();
        stockLevels.deleteAll();
        terminal.setApprove(true);
    }

    @Test
    void cashSaleIsCapturedIntoOpenDrawerSession() {
        DrawerSessionView session = drawer.openSession("T01", new BigDecimal("100.00"), "SAR", "cashier");
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // total 10.35
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))), "cashier");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            DrawerReconciliation recon = drawer.reconcile(session.sessionId());
            assertThat(recon.cashSales()).isEqualByComparingTo("10.35");
            assertThat(recon.cashSalesCount()).isEqualTo(1);
            assertThat(recon.expectedCash()).isEqualByComparingTo("110.35");
        });
    }
}
```

- [ ] **Step 4: Run the cashdrawer capture test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=SaleCapturedByDrawerTest`
Expected: PASS — the after-commit listener captures the cash within the poll window.

- [ ] **Step 5: Confirm the cashdrawer unit + controller tests still pass and boundaries hold**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CashDrawerServiceTest,CashDrawerControllerTest,ModularityTests`
Expected: PASS — those tests drive `CashDrawerService` directly (no async path) and are unchanged.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/cashdrawer/application/SaleCompletedCashListener.java \
        src/test/java/com/company/pos/cashdrawer/SaleCapturedByDrawerTest.java
git commit -m "refactor(cashdrawer): capture cash via after-commit outbox listener"
```

---

### Task 4: Fix the end-to-end tests for after-commit delivery

Two e2e tests assert effects that are now asynchronous. `CashSaleEndToEndTest` only needs its stock-decrement assertion removed (that behaviour is now covered by `SaleDecrementsStockTest`); it stays `@Transactional` and focused on the synchronous checkout path. `ShiftReconciliationEndToEndTest`'s entire point is the close-shift variance, which depends on the async cash capture — it must commit and wait.

**Files:**
- Modify: `src/test/java/com/company/pos/CashSaleEndToEndTest.java`
- Modify: `src/test/java/com/company/pos/ShiftReconciliationEndToEndTest.java`

**Interfaces:**
- Consumes: `CashDrawerService.findOpenSession(terminalId)` / `reconcile(sessionId)`; `ShiftService.findOpenShift(terminalId)`; repositories `ShiftRepository`, `DrawerSessionRepository`, `CashMovementRepository`, `UserRepository` for cleanup.
- Produces: nothing consumed downstream.

- [ ] **Step 1: Trim the async assertion from `CashSaleEndToEndTest`**

In `src/test/java/com/company/pos/CashSaleEndToEndTest.java`, make three edits:

1. Rename the test method so its name no longer claims to assert stock:

```java
    void loginSyncRingUpPayAndPrint() throws Exception {
```

2. Delete the final step block (step 7) entirely:

```java
        // 7. Stock decremented 20 -> 18
        mvc.perform(get("/inventory/COLA").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(18));
```

3. Add a one-line comment in its place documenting where the behaviour is now verified:

```java
        // Stock decrement is now an after-commit async side-effect; verified in SaleDecrementsStockTest.
```

Leave `@Transactional` in place — every remaining assertion (login, sync, cart, checkout response, receipt print, sale retrieval) is synchronous within the request. The `org.springframework.boot.test.web.servlet` / inventory imports that become unused (none should — `quantityOnHand` used no extra import) need no change.

- [ ] **Step 2: Run `CashSaleEndToEndTest` to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CashSaleEndToEndTest`
Expected: PASS.

- [ ] **Step 3: Rewrite `ShiftReconciliationEndToEndTest` to commit and await the cash capture**

Replace `src/test/java/com/company/pos/ShiftReconciliationEndToEndTest.java`:

```java
package com.company.pos;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.cashdrawer.api.CashDrawerService;
import com.company.pos.cashdrawer.infrastructure.CashMovementRepository;
import com.company.pos.cashdrawer.infrastructure.DrawerSessionRepository;
import com.company.pos.common.util.Identifiers;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.shift.infrastructure.ShiftRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * NOT @Transactional: the cash sale must commit so the after-commit cashdrawer listener captures
 * it before the shift is closed. We poll the open session's reconciliation until the cash sale
 * lands, then close. @AfterEach clears shift/drawer/user rows (FK order: cash_movement,
 * shift, drawer_session) so the next run opens a clean session; sale and stock rows are harmless
 * (stock is reset to its absolute ERP value by inventorySync in @BeforeEach).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class ShiftReconciliationEndToEndTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    FakeErpClient fake;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    CashDrawerService drawer;
    @Autowired
    CashMovementRepository cashMovements;
    @Autowired
    DrawerSessionRepository drawerSessions;
    @Autowired
    ShiftRepository shifts;

    @BeforeEach
    void seed() {
        cashMovements.deleteAll();
        shifts.deleteAll();
        drawerSessions.deleteAll();
        users.deleteAll();
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
        cashMovements.deleteAll();
        shifts.deleteAll();
        drawerSessions.deleteAll();
        users.deleteAll();
    }

    @Test
    void openShiftSellCashPayOutThenCloseReportsVariance() throws Exception {
        String managerToken = login("manager");
        mvc.perform(post("/sync/erp").header("Authorization", managerToken))
                .andExpect(status().isOk());
        String token = login("cashier");

        // 1. Open a shift with a 100.00 float
        String openedShift = mvc.perform(post("/shifts").header("Authorization", token)
                        .contentType("application/json").content("{\"openingFloat\":100.00}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String shiftId = JsonPath.read(openedShift, "$.shiftId");
        var sessionId = drawer.findOpenSession("T01").orElseThrow().sessionId();

        // 2. Ring up 2 colas (total 10.35) and pay cash
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

        // 2b. Wait for the after-commit cashdrawer listener to capture the cash sale.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(drawer.reconcile(sessionId).cashSales()).isEqualByComparingTo("10.35"));

        // 3. Pay out 15.00 (e.g. a supplier cash payment)
        mvc.perform(post("/cash-drawer/pay-out").header("Authorization", token)
                        .contentType("application/json")
                        .content("{\"amount\":15.00,\"reason\":\"supplier\"}"))
                .andExpect(status().isOk());

        // 4. Close the shift. Expected = 100 + 10.35 (cash sale) - 15.00 (pay-out) = 95.35.
        //    Count 95.35 -> variance 0.00.
        mvc.perform(post("/shifts/" + shiftId + "/close").header("Authorization", token)
                        .contentType("application/json").content("{\"countedCash\":95.35}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.cash.cashSales").value(closeTo(10.35, 0.001)))
                .andExpect(jsonPath("$.cash.payOuts").value(closeTo(15.00, 0.001)))
                .andExpect(jsonPath("$.cash.expectedCash").value(closeTo(95.35, 0.001)))
                .andExpect(jsonPath("$.cash.variance").value(closeTo(0.00, 0.001)));
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

- [ ] **Step 4: Run `ShiftReconciliationEndToEndTest` to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ShiftReconciliationEndToEndTest`
Expected: PASS — the cash capture is awaited before close, so the variance is 0.00.

- [ ] **Step 5: Run the full set of checkout/e2e tests for regression**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=AdvancedCheckoutEndToEndTest,CashSaleEndToEndTest,ShiftReconciliationEndToEndTest,CheckoutServiceTest,SalesControllerTest`
Expected: PASS. (`AdvancedCheckoutEndToEndTest` asserts only synchronous checkout behaviour — hold/resume, split payment — so it is unaffected; if it happens to assert a stock or cash side-effect, apply the same await/cleanup pattern.)

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/company/pos/CashSaleEndToEndTest.java \
        src/test/java/com/company/pos/ShiftReconciliationEndToEndTest.java
git commit -m "test(outbox): make e2e tests await after-commit side-effects"
```

---

### Task 5: Prove resilience — an incomplete publication is retained and replayable

Add a test that forces a `SaleCompleted` consumer to fail, asserts the registry keeps an incomplete publication (the sale is unaffected), then fixes the consumer, resubmits, and confirms the publication drains. This is the durability guarantee the whole phase exists to provide. Finish with a short docs note.

**Files:**
- Create: `src/test/java/com/company/pos/OutboxResilienceTest.java`
- Modify: `docs/run-modes.md`

**Interfaces:**
- Consumes: `org.springframework.modulith.events.core.EventPublicationRegistry.findIncompletePublications()`; `org.springframework.modulith.events.IncompleteEventPublications.resubmitIncompletePublications(Predicate)`; `SalesService.checkout(...)`.
- Produces: nothing consumed downstream.

- [ ] **Step 1: Write the resilience test with a toggle-able failing listener**

`src/test/java/com/company/pos/OutboxResilienceTest.java`:

```java
package com.company.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.inventory.infrastructure.StockLevelRepository;
import com.company.pos.inventory.infrastructure.StockMovementRepository;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SaleCompleted;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves the transactional-outbox guarantee: a failing after-commit consumer does NOT roll back
 * the sale, its publication stays incomplete in the registry, and resubmitting it (after the
 * fault clears) drains it. The failing consumer is a test-only bean toggled by a flag.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@Import(OutboxResilienceTest.FailingConsumer.class)
class OutboxResilienceTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    EventPublicationRegistry registry;
    @Autowired
    IncompleteEventPublications incomplete;
    @Autowired
    FailingConsumer failing;
    @Autowired
    StockMovementRepository stockMovements;
    @Autowired
    StockLevelRepository stockLevels;

    @BeforeEach
    void seed() {
        failing.reset();
        stockMovements.deleteAll();
        stockLevels.deleteAll();
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void cleanup() {
        stockMovements.deleteAll();
        stockLevels.deleteAll();
    }

    @Test
    void failedConsumerLeavesReplayablePublicationWithoutAffectingTheSale() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("1"));

        // The sale commits even though a consumer will fail on it.
        var sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("10.00")))), "cashier");
        assertThat(sale).isNotNull();

        // The failing consumer is invoked (once) and throws -> its publication stays incomplete.
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(failing.attempts()).isGreaterThanOrEqualTo(1));
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(registry.findIncompletePublications()).isNotEmpty());

        // Clear the fault and resubmit every incomplete publication.
        int attemptsBeforeReplay = failing.attempts();
        failing.stopFailing();
        incomplete.resubmitIncompletePublications(p -> true);

        // The consumer now succeeds and the registry drains.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(failing.attempts()).isGreaterThan(attemptsBeforeReplay));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(registry.findIncompletePublications()).isEmpty());
    }

    /** Test-only consumer that fails until {@link #stopFailing()} is called. */
    @Component
    static class FailingConsumer {

        private final AtomicInteger attempts = new AtomicInteger();
        private volatile boolean fail = true;

        @ApplicationModuleListener
        void on(SaleCompleted event) {
            attempts.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("forced failure for outbox resilience test");
            }
        }

        int attempts() {
            return attempts.get();
        }

        void stopFailing() {
            this.fail = false;
        }

        void reset() {
            this.fail = true;
            this.attempts.set(0);
        }
    }
}
```

> Notes for the implementer: the `FailingConsumer` is `@Import`ed into the context, so it is a real transactional listener the registry tracks — but it lives only in the test source tree and therefore does not affect `ModularityTests` (which scans `PosApplication`'s main sources). The real `inventory`/`cashdrawer` consumers complete normally on this sale, so the only durable incomplete publication is the failing one. If `EventPublicationRegistry`/`IncompleteEventPublications` symbols differ in 1.2.5 (verified at Task 1 Step 8), adjust the imports/calls here to match.

- [ ] **Step 2: Run the resilience test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=OutboxResilienceTest`
Expected: PASS — the checkout returns a sale, an incomplete publication is retained, and resubmission drains it once the fault clears.

- [ ] **Step 3: Document the outbox behaviour**

Append the following section to `docs/run-modes.md`:

```markdown
## Event outbox & resilience (Phase 3a)

Domain events (`SaleCompleted`, …) are delivered through the Spring Modulith **Event Publication
Registry** — a transactional outbox. Each `(event, listener)` pair is written as an
`event_publication` row inside the publishing transaction (the sale), and its `completion_date`
is stamped only when the listener finishes successfully. Listeners (`inventory` stock decrement,
`cashdrawer` cash capture) are `@ApplicationModuleListener`s: they run **after the sale commits,
asynchronously, in their own transaction**.

Consequences:
- A side-effect failure can never roll back a committed sale; it leaves an *incomplete*
  publication instead.
- Incomplete publications are **re-delivered on application restart**
  (`spring.modulith.events.republish-outstanding-publications-on-restart=true`) and can be
  resubmitted programmatically via `IncompleteEventPublications`.
- On `store-server` the table is created by Flyway migration `V14` (`db/migration/events`); on
  `embedded` Hibernate creates it automatically.

ERP upload of sales/movements over this outbox, and operator notifications for stuck
publications, are Phase 3b and 3c respectively.
```

- [ ] **Step 4: Run the full test suite**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test`
Expected: PASS — all modules, all profiles. Watch specifically for: `ModularityTests` (boundaries), `DatabaseStoreServerTest` (migration validates on Postgres), and the four reworked tests.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/company/pos/OutboxResilienceTest.java docs/run-modes.md
git commit -m "test(outbox): prove incomplete publications are retained and replayable; phase 3a docs"
```

---

## Notes for the executor

- **Timing flakiness:** the Awaitility windows (5–10s) are generous for an in-memory DB on `SimpleAsyncTaskExecutor`. If CI is slow, raise `atMost(...)`; do not convert the assertions back to synchronous — that would defeat the after-commit design.
- **Do not** reintroduce `try/catch (RuntimeException)` in the listeners. Letting an exception propagate is what makes the publication retryable; swallowing it marks the publication complete and silently loses the side-effect.
- **Scope boundary:** if you find yourself adding upload methods to `ErpClient`, an outbound sales queue, a `notification` module, or inventory reorder levels, stop — those belong to Phase 3b/3c, not here.
- **Verification before "done":** the final `./mvnw -q test` (Task 5, Step 4) must be green, including `DatabaseStoreServerTest`. Report the actual output; do not claim success from a partial run.
