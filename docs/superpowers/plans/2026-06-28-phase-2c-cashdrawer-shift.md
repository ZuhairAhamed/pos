# Phase 2c — Cash Drawer & Shift (Till Reconciliation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Open a shift with a starting cash float, automatically capture cash sales into the drawer, record pay-ins/pay-outs, then close the shift by counting the cash and reporting the over/short variance — all offline, on top of the Phase 2b checkout.

**Architecture:** Introduces two Tier-2 modules. `cashdrawer` owns an append-only cash-movement ledger per drawer **session** (opening float, cash sales, pay-ins, pay-outs), physically opens the drawer through the existing `device.api` `CashDrawer` port, and computes a reconciliation (expected = float + cash sales + pay-ins − pay-outs; variance = counted − expected). `shift` owns the shift lifecycle and **orchestrates** the drawer: opening a shift opens a drawer session with the float; closing it counts the cash and returns the reconciliation. Checkout stays decoupled — `cashdrawer` subscribes to the `sales` `SaleCompleted` event (extended this phase to carry the terminal id and the cash-tender total) and attributes the cash to the terminal's open session, exactly like `inventory` already subscribes for stock. An open shift is **not** required to sell.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith 1.2.5, Spring Data JPA, Flyway (store-server), JavaMoney/Moneta, JUnit 5 + spring-security-test, Maven (`./mvnw`).

## Global Constraints

- **Root package** `com.company.pos`; one package-per-module under it, boundaries enforced by `ApplicationModules.of(PosApplication.class).verify()` (the `ModularityTests`). Any type the application root references must be exposed via a module's `@NamedInterface`.
- **JDK 21 required.** The machine default `java` is 17. Run every Maven command as: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw …`.
- **Build tool is Maven** via the committed `./mvnw` wrapper. No system `mvn`.
- **Modulith named interfaces:** a module is consumed cross-module only through a sub-package marked `@org.springframework.modulith.NamedInterface("api")`, and the consumer must list it in `allowedDependencies` as `module :: api`. `device.api`, `configuration.api`, and `sales.api` are already named interfaces.
- **UUID-as-VARCHAR convention:** entity ids are `UUID` fields annotated `@JdbcTypeCode(SqlTypes.VARCHAR)` + `@Column(length = 36)`; migrations declare `VARCHAR(36)`. A `UUID` foreign-key column (e.g. `session_id`, `drawer_session_id`) uses the same `@JdbcTypeCode(SqlTypes.VARCHAR)` + `@Column(length = 36)` pattern.
- **Money is `BigDecimal` + currency code — never `double`/`float`.** Cash amounts (float, sale cash, pay-in, pay-out, expected, counted, variance) are rounded to **scale 2, `RoundingMode.HALF_UP`**. Use `java.math.RoundingMode.HALF_UP` everywhere.
- **Single physical `pos` schema.** Entities are schema-agnostic (no `@Table(schema=…)`); the store-server profile sets `hibernate.default_schema: pos` and `flyway.default-schema: pos`.
- **Flyway versions are globally unique and ordered across all per-module locations** (V1–V11 already exist; the latest are V10 payment, V11 cart). New migrations continue at **V12** (cashdrawer) then **V13** (shift). Every NEW migration directory MUST be added to the `flyway.locations` comma-separated list in `application-store-server.yml` (this phase adds two: `cashdrawer`, `shift`). The `embedded` profile uses Hibernate `ddl-auto: update` and **no Flyway** — tests run on `embedded`.
- **Stateless JWT bearer auth** is already in place; all new endpoints require an authenticated user. No new role gate this phase (a cashier opens/closes their own shift and records pay-ins/pay-outs). Cashier identity comes from `Principal.getName()` in controllers. Roles: `CASHIER`, `MANAGER`, `ADMIN`.
- **Offline-first:** nothing in the sell path may block on connectivity, and a sale must never be rolled back by a downstream side effect. The `cashdrawer` `SaleCompleted` listener swallows `RuntimeException` so a drawer failure never rolls back the sale (same documented caveat as the `inventory` listener: a DB constraint violation can still poison the shared transaction; full decoupling arrives in Phase 3 via the outbox + `@TransactionalEventListener(AFTER_COMMIT)`).
- **An open shift is NOT required to complete a sale.** Checkout (`sales`) is unchanged. If no drawer session is open for the terminal when a cash sale completes, the cash capture is a no-op (logged), never an error.
- **Errors** surface as `DomainException.notFound/validation/conflict(...)` → RFC-7807 `ProblemDetail` via the existing `ApiExceptionHandler`.

---

## Existing interfaces this phase consumes (already implemented — do not redefine)

```java
// com.company.pos.common.util
public final class Identifiers { public static UUID newId(); }

// com.company.pos.common.exception
public class DomainException extends RuntimeException {
    public static DomainException notFound(String message);
    public static DomainException validation(String message);
    public static DomainException conflict(String message);
}

// com.company.pos.common.events
public interface DomainEvent {}
@Component public class DomainEvents { public void publish(DomainEvent event); }

// com.company.pos.device.api  (named interface "api"; fake adapter InMemoryCashDrawer already a @Component)
public interface CashDrawer { void open(); boolean isOpen(); }

// com.company.pos.configuration.api  (named interface "api")
public interface ConfigurationService { String getString(SettingKey key); /* … */ }
public enum SettingKey { /* …, */ CURRENCY_CODE, TERMINAL_ID, /* … */ }

// com.company.pos.sales.api  (named interface "api") — CHANGED in Task 1 (adds terminalId + cashTotal)
public record SaleCompleted(UUID saleId, String receiptNumber, String locationCode,
        String currencyCode, BigDecimal grandTotal, List<SoldLine> lines) implements DomainEvent {
    public record SoldLine(String sku, BigDecimal quantity) {}
}
// In DefaultSalesService.checkout, `recorded` is a List<com.company.pos.payment.api.PaymentView>
// (one per tender) and `terminalId` is a local String read from config — both available where
// SaleCompleted is published. PaymentView.method() returns "CASH"/"CARD"/"WALLET";
// PaymentView.amount() is the BigDecimal applied to the sale.
```

## New cross-module API surface produced by this phase

| Module | `@NamedInterface("api")` types |
|---|---|
| `cashdrawer` | `CashDrawerService`, `DrawerSessionView`, `CashMovementView`, `DrawerReconciliation` |
| `shift` | `ShiftService`, `ShiftView`, `ShiftSummary` |
| `sales` | `SaleCompleted` gains `terminalId` and `cashTotal` (existing named interface) |

## Module dependency declarations introduced/changed

```
cashdrawer  allowedDependencies = { "common", "database", "device :: api", "configuration :: api" }   // Task 2
cashdrawer  allowedDependencies = { "common", "database", "device :: api", "configuration :: api", "sales :: api" }  // Task 3 adds sales :: api (event subscriber)
shift       allowedDependencies = { "common", "database", "cashdrawer :: api", "configuration :: api" }  // Task 4
```
No cycle: `cashdrawer → sales :: api` (subscribes to `SaleCompleted`, exactly like `inventory`); `cashdrawer → device :: api`; `shift → cashdrawer :: api`. `sales` and `cashdrawer` never depend on `shift`; `cashdrawer` never depends on `shift`.

## Migrations & config added

| Version | Location dir | Tables |
|---|---|---|
| V12 | `db/migration/cashdrawer` | `drawer_session`, `cash_movement` |
| V13 | `db/migration/shift` | `shift` |

Both new directories are appended to `flyway.locations` in `application-store-server.yml` (Task 2 adds `cashdrawer`, Task 4 adds `shift`). No new `SettingKey`s.

---

### Task 1: Sales — extend `SaleCompleted` with `terminalId` and `cashTotal`

The `cashdrawer` subscriber needs to know which terminal a sale belongs to and how much of it was paid in cash. Add both to the `SaleCompleted` event and populate them from the recorded tenders at checkout. This is the only change to the Phase 2b `sales` path; the `inventory` listener is unaffected (it reads other fields).

**Files:**
- Modify: `src/main/java/com/company/pos/sales/api/SaleCompleted.java`
- Modify: `src/main/java/com/company/pos/sales/application/DefaultSalesService.java`
- Test: `src/test/java/com/company/pos/sales/SaleCompletedEventTest.java`

**Interfaces:**
- Consumes: `PaymentView.method()` / `PaymentView.amount()` (from the `recorded` list in `checkout`); the local `terminalId` already read from `config`.
- Produces: `record SaleCompleted(UUID saleId, String receiptNumber, String terminalId, String locationCode, String currencyCode, BigDecimal grandTotal, BigDecimal cashTotal, List<SoldLine> lines)`. `cashTotal` is the sum of `amount()` over tenders whose `method()` equals `"CASH"`, scale 2 HALF_UP (0.00 if none).

- [ ] **Step 1: Write the failing test**

```java
package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SaleCompleted;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
@RecordApplicationEvents
class SaleCompletedEventTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    ApplicationEvents events;
    @Autowired
    InMemoryPaymentTerminal terminal;

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
        terminal.setApprove(true);
    }

    @AfterEach
    void reset() {
        terminal.setApprove(true);
    }

    @Test
    void cashSalePublishesTerminalAndFullCashTotal() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // total 10.35
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))), "cashier");

        SaleCompleted event = events.stream(SaleCompleted.class).findFirst().orElseThrow();
        assertThat(event.terminalId()).isEqualTo("T01");
        assertThat(event.cashTotal()).isEqualByComparingTo("10.35");
    }

    @Test
    void splitSalePublishesOnlyTheCashPortion() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // total 10.35
        // 5.00 on card, remainder 5.35 on cash
        sales.checkout(new CheckoutCommand(cart, List.of(
                new TenderInput(PaymentMethod.CARD, new BigDecimal("5.00"), null),
                new TenderInput(PaymentMethod.CASH, null, new BigDecimal("10.00")))), "cashier");

        SaleCompleted event = events.stream(SaleCompleted.class).findFirst().orElseThrow();
        assertThat(event.cashTotal()).isEqualByComparingTo("5.35");
    }

    @Test
    void cardOnlySalePublishesZeroCash() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // total 10.35
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CARD, new BigDecimal("10.35"), null))), "cashier");

        SaleCompleted event = events.stream(SaleCompleted.class).findFirst().orElseThrow();
        assertThat(event.cashTotal()).isEqualByComparingTo("0.00");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=SaleCompletedEventTest`
Expected: FAIL — `SaleCompleted` has no `terminalId()`/`cashTotal()` accessors (compile error).

- [ ] **Step 3: Add the fields to `SaleCompleted`**

Replace `src/main/java/com/company/pos/sales/api/SaleCompleted.java`:

```java
package com.company.pos.sales.api;

import com.company.pos.common.events.DomainEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record SaleCompleted(UUID saleId, String receiptNumber, String terminalId, String locationCode,
        String currencyCode, BigDecimal grandTotal, BigDecimal cashTotal, List<SoldLine> lines)
        implements DomainEvent {

    public record SoldLine(String sku, BigDecimal quantity) {
    }
}
```

- [ ] **Step 4: Populate the new fields at publish time**

In `src/main/java/com/company/pos/sales/application/DefaultSalesService.java`, locate step 6 where `SaleCompleted` is published. Add `import com.company.pos.payment.api.PaymentMethod;` and `import java.math.RoundingMode;` if not already present (`RoundingMode` is already imported from Phase 2b). Just before the `events.publish(...)` call, compute the cash total from the `recorded` tenders, then update the constructor call. The block becomes:

```java
        // 6. Publish SaleCompleted (in-process; inventory + cashdrawer subscribe synchronously)
        List<SaleCompleted.SoldLine> soldLines = taxed.lines().stream()
                .map(t -> new SaleCompleted.SoldLine(t.sku(), t.quantity()))
                .toList();
        BigDecimal cashTotal = recorded.stream()
                .filter(p -> PaymentMethod.CASH.name().equals(p.method()))
                .map(com.company.pos.payment.api.PaymentView::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        events.publish(new SaleCompleted(saleId, receiptNumber, terminalId, location, currency,
                taxed.grandTotal(), cashTotal, soldLines));
```

> `terminalId` and `location` are already local variables in `checkout` (read from `config` in step 4 of the existing method). `recorded` is the `List<PaymentView>` built in step 3. The `inventory` `SaleCompletedListener` reads `saleId`, `receiptNumber`, `locationCode`, and `lines` only — it is unaffected by the two new fields.

- [ ] **Step 5: Run test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=SaleCompletedEventTest`
Expected: PASS (all three tests).

- [ ] **Step 6: Run the existing sales + inventory tests (no regression)**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CheckoutServiceTest,SalesControllerTest,SaleDecrementsStockTest`
Expected: PASS — these construct `CheckoutCommand`, never `SaleCompleted` directly, so they are unaffected.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/pos/sales/api/SaleCompleted.java \
        src/main/java/com/company/pos/sales/application/DefaultSalesService.java \
        src/test/java/com/company/pos/sales/SaleCompletedEventTest.java
git commit -m "feat(sales): carry terminalId and cash-tender total on SaleCompleted"
```

---

### Task 2: `cashdrawer` module — drawer sessions, cash-movement ledger, reconciliation + REST

A `DrawerSession` per terminal with an append-only `CashMovement` ledger. Opening a session records the float and physically opens the drawer; pay-ins/pay-outs append movements; reconciliation computes expected cash; closing records the counted amount and returns the variance. REST exposes pay-in/pay-out and the reconciliation read. (The `SaleCompleted` subscriber is added in Task 3; session open/close is driven by `shift` in Task 4 but the service methods live here.)

**Files:**
- Create: `src/main/java/com/company/pos/cashdrawer/package-info.java`
- Create: `src/main/java/com/company/pos/cashdrawer/api/package-info.java`
- Create: `src/main/java/com/company/pos/cashdrawer/api/CashDrawerService.java`
- Create: `src/main/java/com/company/pos/cashdrawer/api/DrawerSessionView.java`
- Create: `src/main/java/com/company/pos/cashdrawer/api/CashMovementView.java`
- Create: `src/main/java/com/company/pos/cashdrawer/api/DrawerReconciliation.java`
- Create: `src/main/java/com/company/pos/cashdrawer/domain/DrawerSession.java`
- Create: `src/main/java/com/company/pos/cashdrawer/domain/CashMovement.java`
- Create: `src/main/java/com/company/pos/cashdrawer/infrastructure/DrawerSessionRepository.java`
- Create: `src/main/java/com/company/pos/cashdrawer/infrastructure/CashMovementRepository.java`
- Create: `src/main/java/com/company/pos/cashdrawer/application/DefaultCashDrawerService.java`
- Create: `src/main/java/com/company/pos/cashdrawer/web/CashDrawerController.java`
- Create: `src/main/resources/db/migration/cashdrawer/V12__cashdrawer.sql`
- Modify: `src/main/resources/application-store-server.yml`
- Test: `src/test/java/com/company/pos/cashdrawer/CashDrawerServiceTest.java`
- Test: `src/test/java/com/company/pos/cashdrawer/CashDrawerControllerTest.java`

**Interfaces:**
- Consumes: `CashDrawer.open()` (device port); `ConfigurationService.getString(SettingKey.TERMINAL_ID)` (controller); `Identifiers.newId()`; `DomainException`.
- Produces:
  - `record DrawerSessionView(UUID sessionId, String terminalId, String status, BigDecimal openingFloat, String currencyCode, String openedBy, Instant openedAt)`
  - `record CashMovementView(UUID id, UUID sessionId, String type, BigDecimal amount, String reference, Instant createdAt)`
  - `record DrawerReconciliation(UUID sessionId, BigDecimal openingFloat, BigDecimal cashSales, int cashSalesCount, BigDecimal payIns, BigDecimal payOuts, BigDecimal expectedCash, BigDecimal countedCash, BigDecimal variance, String currencyCode)` (`countedCash`/`variance` are null until the session is closed)
  - `interface CashDrawerService`:
    - `DrawerSessionView openSession(String terminalId, BigDecimal openingFloat, String currencyCode, String openedBy)` — conflict if a session is already open for the terminal; records an `OPENING_FLOAT` movement; opens the drawer.
    - `void recordCashSale(String terminalId, BigDecimal amount, String reference)` — appends a `CASH_SALE` movement to the terminal's open session and opens the drawer; **no-op if no session is open**.
    - `CashMovementView payIn(String terminalId, BigDecimal amount, String reason, String performedBy)`
    - `CashMovementView payOut(String terminalId, BigDecimal amount, String reason, String performedBy)`
    - `DrawerReconciliation reconcile(UUID sessionId)`
    - `DrawerReconciliation closeSession(UUID sessionId, BigDecimal countedAmount)`
    - `Optional<DrawerSessionView> findOpenSession(String terminalId)`
  - Movement type strings: `"OPENING_FLOAT"`, `"CASH_SALE"`, `"PAY_IN"`, `"PAY_OUT"`. Statuses: `"OPEN"`, `"CLOSED"`.
  - Reconciliation: `expectedCash = openingFloat + cashSales + payIns − payOuts` (scale 2 HALF_UP); `variance = countedCash − expectedCash`.

- [ ] **Step 1: Declare the module**

`src/main/java/com/company/pos/cashdrawer/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "device :: api", "configuration :: api" })
package com.company.pos.cashdrawer;
```

`src/main/java/com/company/pos/cashdrawer/api/package-info.java`:

```java
@org.springframework.modulith.NamedInterface("api")
package com.company.pos.cashdrawer.api;
```

- [ ] **Step 2: Write the failing service test**

```java
package com.company.pos.cashdrawer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.cashdrawer.api.CashDrawerService;
import com.company.pos.cashdrawer.api.DrawerReconciliation;
import com.company.pos.cashdrawer.api.DrawerSessionView;
import com.company.pos.common.exception.DomainException;
import com.company.pos.device.infrastructure.InMemoryCashDrawer;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class CashDrawerServiceTest {

    @Autowired
    CashDrawerService drawer;
    @Autowired
    InMemoryCashDrawer device;

    @Test
    void openSessionRecordsFloatAndOpensDrawer() {
        DrawerSessionView session = drawer.openSession("T01", new BigDecimal("100.00"), "SAR", "cashier");

        assertThat(session.status()).isEqualTo("OPEN");
        assertThat(session.openingFloat()).isEqualByComparingTo("100.00");
        assertThat(device.isOpen()).isTrue();
        assertThat(drawer.findOpenSession("T01")).isPresent();
    }

    @Test
    void cannotOpenTwoSessionsForSameTerminal() {
        drawer.openSession("T01", new BigDecimal("100.00"), "SAR", "cashier");
        assertThatThrownBy(() -> drawer.openSession("T01", new BigDecimal("50.00"), "SAR", "cashier"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void reconciliationSumsFloatSalesPayInsAndPayOuts() {
        DrawerSessionView session = drawer.openSession("T01", new BigDecimal("100.00"), "SAR", "cashier");
        drawer.recordCashSale("T01", new BigDecimal("10.35"), "sale-1");
        drawer.recordCashSale("T01", new BigDecimal("4.65"), "sale-2");
        drawer.payIn("T01", new BigDecimal("20.00"), "change fund", "cashier");
        drawer.payOut("T01", new BigDecimal("5.00"), "milk run", "cashier");

        DrawerReconciliation recon = drawer.reconcile(session.sessionId());
        assertThat(recon.openingFloat()).isEqualByComparingTo("100.00");
        assertThat(recon.cashSales()).isEqualByComparingTo("15.00");
        assertThat(recon.cashSalesCount()).isEqualTo(2);
        assertThat(recon.payIns()).isEqualByComparingTo("20.00");
        assertThat(recon.payOuts()).isEqualByComparingTo("5.00");
        // 100 + 15 + 20 - 5 = 130.00
        assertThat(recon.expectedCash()).isEqualByComparingTo("130.00");
        assertThat(recon.countedCash()).isNull();
        assertThat(recon.variance()).isNull();
    }

    @Test
    void closingComputesVariance() {
        DrawerSessionView session = drawer.openSession("T01", new BigDecimal("100.00"), "SAR", "cashier");
        drawer.recordCashSale("T01", new BigDecimal("10.00"), "sale-1");
        // expected 110.00; count 108.50 -> short 1.50
        DrawerReconciliation recon = drawer.closeSession(session.sessionId(), new BigDecimal("108.50"));

        assertThat(recon.expectedCash()).isEqualByComparingTo("110.00");
        assertThat(recon.countedCash()).isEqualByComparingTo("108.50");
        assertThat(recon.variance()).isEqualByComparingTo("-1.50");
        assertThat(drawer.findOpenSession("T01")).isEmpty();
    }

    @Test
    void recordCashSaleWithNoOpenSessionIsNoOp() {
        // no session open for T02
        drawer.recordCashSale("T02", new BigDecimal("9.99"), "sale-x");
        assertThat(drawer.findOpenSession("T02")).isEmpty();
    }

    @Test
    void payInWithoutOpenSessionIsRejected() {
        assertThatThrownBy(() -> drawer.payIn("T09", new BigDecimal("5.00"), "x", "cashier"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void payOutMustBePositive() {
        drawer.openSession("T01", new BigDecimal("100.00"), "SAR", "cashier");
        assertThatThrownBy(() -> drawer.payOut("T01", BigDecimal.ZERO, "x", "cashier"))
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CashDrawerServiceTest`
Expected: FAIL — `CashDrawerService` and friends do not exist (compile error).

- [ ] **Step 4: Write the API records and interface**

`src/main/java/com/company/pos/cashdrawer/api/DrawerSessionView.java`:

```java
package com.company.pos.cashdrawer.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DrawerSessionView(UUID sessionId, String terminalId, String status,
        BigDecimal openingFloat, String currencyCode, String openedBy, Instant openedAt) {
}
```

`src/main/java/com/company/pos/cashdrawer/api/CashMovementView.java`:

```java
package com.company.pos.cashdrawer.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CashMovementView(UUID id, UUID sessionId, String type, BigDecimal amount,
        String reference, Instant createdAt) {
}
```

`src/main/java/com/company/pos/cashdrawer/api/DrawerReconciliation.java`:

```java
package com.company.pos.cashdrawer.api;

import java.math.BigDecimal;
import java.util.UUID;

public record DrawerReconciliation(UUID sessionId, BigDecimal openingFloat, BigDecimal cashSales,
        int cashSalesCount, BigDecimal payIns, BigDecimal payOuts, BigDecimal expectedCash,
        BigDecimal countedCash, BigDecimal variance, String currencyCode) {
}
```

`src/main/java/com/company/pos/cashdrawer/api/CashDrawerService.java`:

```java
package com.company.pos.cashdrawer.api;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface CashDrawerService {

    DrawerSessionView openSession(String terminalId, BigDecimal openingFloat, String currencyCode,
            String openedBy);

    void recordCashSale(String terminalId, BigDecimal amount, String reference);

    CashMovementView payIn(String terminalId, BigDecimal amount, String reason, String performedBy);

    CashMovementView payOut(String terminalId, BigDecimal amount, String reason, String performedBy);

    DrawerReconciliation reconcile(UUID sessionId);

    DrawerReconciliation closeSession(UUID sessionId, BigDecimal countedAmount);

    Optional<DrawerSessionView> findOpenSession(String terminalId);
}
```

- [ ] **Step 5: Write the domain entities**

`src/main/java/com/company/pos/cashdrawer/domain/DrawerSession.java`:

```java
package com.company.pos.cashdrawer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "drawer_session")
public class DrawerSession {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "terminal_id", nullable = false, length = 16)
    private String terminalId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "opening_float", nullable = false, precision = 19, scale = 2)
    private BigDecimal openingFloat;

    @Column(name = "counted_amount", precision = 19, scale = 2)
    private BigDecimal countedAmount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "opened_by", nullable = false, length = 100)
    private String openedBy;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected DrawerSession() {
        // JPA
    }

    public DrawerSession(UUID id, String terminalId, BigDecimal openingFloat, String currencyCode,
            String openedBy, Instant openedAt) {
        this.id = id;
        this.terminalId = terminalId;
        this.openingFloat = openingFloat;
        this.currencyCode = currencyCode;
        this.openedBy = openedBy;
        this.openedAt = openedAt;
        this.status = "OPEN";
    }

    public void close(BigDecimal countedAmount, Instant closedAt) {
        this.countedAmount = countedAmount;
        this.closedAt = closedAt;
        this.status = "CLOSED";
    }

    public boolean isOpen() {
        return "OPEN".equals(status);
    }

    public UUID getId() {
        return id;
    }

    public String getTerminalId() {
        return terminalId;
    }

    public String getStatus() {
        return status;
    }

    public BigDecimal getOpeningFloat() {
        return openingFloat;
    }

    public BigDecimal getCountedAmount() {
        return countedAmount;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public String getOpenedBy() {
        return openedBy;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }
}
```

`src/main/java/com/company/pos/cashdrawer/domain/CashMovement.java`:

```java
package com.company.pos.cashdrawer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "cash_movement")
public class CashMovement {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "session_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID sessionId;

    @Column(nullable = false, length = 16)
    private String type;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 120)
    private String reference;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CashMovement() {
        // JPA
    }

    public CashMovement(UUID id, UUID sessionId, String type, BigDecimal amount, String reference,
            String createdBy, Instant createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.type = type;
        this.amount = amount;
        this.reference = reference;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReference() {
        return reference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
```

- [ ] **Step 6: Write the repositories**

`src/main/java/com/company/pos/cashdrawer/infrastructure/DrawerSessionRepository.java`:

```java
package com.company.pos.cashdrawer.infrastructure;

import com.company.pos.cashdrawer.domain.DrawerSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DrawerSessionRepository extends JpaRepository<DrawerSession, UUID> {

    Optional<DrawerSession> findByTerminalIdAndStatus(String terminalId, String status);
}
```

`src/main/java/com/company/pos/cashdrawer/infrastructure/CashMovementRepository.java`:

```java
package com.company.pos.cashdrawer.infrastructure;

import com.company.pos.cashdrawer.domain.CashMovement;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashMovementRepository extends JpaRepository<CashMovement, UUID> {

    List<CashMovement> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);
}
```

- [ ] **Step 7: Write the service**

`src/main/java/com/company/pos/cashdrawer/application/DefaultCashDrawerService.java`:

```java
package com.company.pos.cashdrawer.application;

import com.company.pos.cashdrawer.api.CashDrawerService;
import com.company.pos.cashdrawer.api.CashMovementView;
import com.company.pos.cashdrawer.api.DrawerReconciliation;
import com.company.pos.cashdrawer.api.DrawerSessionView;
import com.company.pos.cashdrawer.domain.CashMovement;
import com.company.pos.cashdrawer.domain.DrawerSession;
import com.company.pos.cashdrawer.infrastructure.CashMovementRepository;
import com.company.pos.cashdrawer.infrastructure.DrawerSessionRepository;
import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.device.api.CashDrawer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultCashDrawerService implements CashDrawerService {

    private static final Logger log = LoggerFactory.getLogger(DefaultCashDrawerService.class);

    private final DrawerSessionRepository sessions;
    private final CashMovementRepository movements;
    private final CashDrawer device;

    DefaultCashDrawerService(DrawerSessionRepository sessions, CashMovementRepository movements,
            CashDrawer device) {
        this.sessions = sessions;
        this.movements = movements;
        this.device = device;
    }

    @Override
    public DrawerSessionView openSession(String terminalId, BigDecimal openingFloat,
            String currencyCode, String openedBy) {
        sessions.findByTerminalIdAndStatus(terminalId, "OPEN").ifPresent(s -> {
            throw DomainException.conflict("A drawer session is already open for terminal " + terminalId);
        });
        BigDecimal floatAmount = scale(openingFloat);
        if (floatAmount.signum() < 0) {
            throw DomainException.validation("Opening float cannot be negative");
        }
        DrawerSession session = new DrawerSession(Identifiers.newId(), terminalId, floatAmount,
                currencyCode, openedBy, Instant.now());
        sessions.save(session);
        appendMovement(session.getId(), "OPENING_FLOAT", floatAmount, "opening float", openedBy);
        device.open();
        return toSessionView(session);
    }

    @Override
    public void recordCashSale(String terminalId, BigDecimal amount, String reference) {
        Optional<DrawerSession> open = sessions.findByTerminalIdAndStatus(terminalId, "OPEN");
        if (open.isEmpty()) {
            log.info("Cash sale {} on terminal {} not captured — no open drawer session",
                    reference, terminalId);
            return;
        }
        appendMovement(open.get().getId(), "CASH_SALE", scale(amount), reference, null);
        device.open();
    }

    @Override
    public CashMovementView payIn(String terminalId, BigDecimal amount, String reason,
            String performedBy) {
        return record(terminalId, "PAY_IN", amount, reason, performedBy);
    }

    @Override
    public CashMovementView payOut(String terminalId, BigDecimal amount, String reason,
            String performedBy) {
        return record(terminalId, "PAY_OUT", amount, reason, performedBy);
    }

    private CashMovementView record(String terminalId, String type, BigDecimal amount, String reason,
            String performedBy) {
        BigDecimal value = scale(amount);
        if (value.signum() <= 0) {
            throw DomainException.validation(type + " amount must be positive");
        }
        DrawerSession session = sessions.findByTerminalIdAndStatus(terminalId, "OPEN")
                .orElseThrow(() -> DomainException.conflict(
                        "No open drawer session for terminal " + terminalId));
        CashMovement movement = appendMovement(session.getId(), type, value, reason, performedBy);
        device.open();
        return toMovementView(movement);
    }

    @Override
    @Transactional(readOnly = true)
    public DrawerReconciliation reconcile(UUID sessionId) {
        return reconcileSession(loadSession(sessionId));
    }

    @Override
    public DrawerReconciliation closeSession(UUID sessionId, BigDecimal countedAmount) {
        DrawerSession session = loadSession(sessionId);
        if (!session.isOpen()) {
            throw DomainException.conflict("Drawer session " + sessionId + " is not open");
        }
        session.close(scale(countedAmount), Instant.now());
        return reconcileSession(session);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DrawerSessionView> findOpenSession(String terminalId) {
        return sessions.findByTerminalIdAndStatus(terminalId, "OPEN").map(this::toSessionView);
    }

    private DrawerReconciliation reconcileSession(DrawerSession session) {
        List<CashMovement> ledger = movements.findBySessionIdOrderByCreatedAtAsc(session.getId());
        BigDecimal cashSales = sum(ledger, "CASH_SALE");
        BigDecimal payIns = sum(ledger, "PAY_IN");
        BigDecimal payOuts = sum(ledger, "PAY_OUT");
        int cashSalesCount = (int) ledger.stream().filter(m -> "CASH_SALE".equals(m.getType())).count();
        BigDecimal expected = scale(session.getOpeningFloat().add(cashSales).add(payIns).subtract(payOuts));
        BigDecimal counted = session.getCountedAmount();
        BigDecimal variance = counted != null ? scale(counted.subtract(expected)) : null;
        return new DrawerReconciliation(session.getId(), session.getOpeningFloat(), cashSales,
                cashSalesCount, payIns, payOuts, expected, counted, variance, session.getCurrencyCode());
    }

    private BigDecimal sum(List<CashMovement> ledger, String type) {
        return scale(ledger.stream()
                .filter(m -> type.equals(m.getType()))
                .map(CashMovement::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private CashMovement appendMovement(UUID sessionId, String type, BigDecimal amount,
            String reference, String createdBy) {
        CashMovement movement = new CashMovement(Identifiers.newId(), sessionId, type, amount,
                reference, createdBy, Instant.now());
        return movements.save(movement);
    }

    private DrawerSession loadSession(UUID sessionId) {
        return sessions.findById(sessionId)
                .orElseThrow(() -> DomainException.notFound("No drawer session " + sessionId));
    }

    private BigDecimal scale(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private DrawerSessionView toSessionView(DrawerSession s) {
        return new DrawerSessionView(s.getId(), s.getTerminalId(), s.getStatus(), s.getOpeningFloat(),
                s.getCurrencyCode(), s.getOpenedBy(), s.getOpenedAt());
    }

    private CashMovementView toMovementView(CashMovement m) {
        return new CashMovementView(m.getId(), m.getSessionId(), m.getType(), m.getAmount(),
                m.getReference(), m.getCreatedAt());
    }
}
```

- [ ] **Step 8: Write the migration (store-server)**

`src/main/resources/db/migration/cashdrawer/V12__cashdrawer.sql`:

```sql
CREATE TABLE drawer_session (
    id             VARCHAR(36) PRIMARY KEY,
    terminal_id    VARCHAR(16) NOT NULL,
    status         VARCHAR(16) NOT NULL,
    opening_float  NUMERIC(19, 2) NOT NULL,
    counted_amount NUMERIC(19, 2),
    currency_code  VARCHAR(3) NOT NULL,
    opened_by      VARCHAR(100) NOT NULL,
    opened_at      TIMESTAMP NOT NULL,
    closed_at      TIMESTAMP
);

CREATE INDEX idx_drawer_session_terminal_status ON drawer_session (terminal_id, status);

CREATE TABLE cash_movement (
    id          VARCHAR(36) PRIMARY KEY,
    session_id  VARCHAR(36) NOT NULL REFERENCES drawer_session (id),
    type        VARCHAR(16) NOT NULL,
    amount      NUMERIC(19, 2) NOT NULL,
    reference   VARCHAR(120),
    created_by  VARCHAR(100),
    created_at  TIMESTAMP NOT NULL
);

CREATE INDEX idx_cash_movement_session ON cash_movement (session_id);
```

- [ ] **Step 9: Register the migration location**

Edit `src/main/resources/application-store-server.yml` line 22 (`locations:`). Append `,classpath:db/migration/cashdrawer` to the end of the existing single comma-separated value (keep all existing entries). The line becomes:

```yaml
    locations: classpath:db/migration/configuration,classpath:db/migration/auth,classpath:db/migration/product,classpath:db/migration/inventory,classpath:db/migration/integration,classpath:db/migration/cart,classpath:db/migration/payment,classpath:db/migration/sales,classpath:db/migration/cashdrawer
```

- [ ] **Step 10: Run the service test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CashDrawerServiceTest`
Expected: PASS (all seven tests).

- [ ] **Step 11: Write the controller and its test**

`src/main/java/com/company/pos/cashdrawer/web/CashDrawerController.java`:

```java
package com.company.pos.cashdrawer.web;

import com.company.pos.cashdrawer.api.CashDrawerService;
import com.company.pos.cashdrawer.api.CashMovementView;
import com.company.pos.cashdrawer.api.DrawerReconciliation;
import com.company.pos.cashdrawer.api.DrawerSessionView;
import com.company.pos.common.exception.DomainException;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import java.math.BigDecimal;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CashDrawerController {

    private final CashDrawerService drawer;
    private final ConfigurationService config;

    CashDrawerController(CashDrawerService drawer, ConfigurationService config) {
        this.drawer = drawer;
        this.config = config;
    }

    record CashMovementRequest(BigDecimal amount, String reason) {
    }

    @PostMapping("/cash-drawer/pay-in")
    CashMovementView payIn(@RequestBody CashMovementRequest body, Principal principal) {
        return drawer.payIn(terminal(), body.amount(), body.reason(), principal.getName());
    }

    @PostMapping("/cash-drawer/pay-out")
    CashMovementView payOut(@RequestBody CashMovementRequest body, Principal principal) {
        return drawer.payOut(terminal(), body.amount(), body.reason(), principal.getName());
    }

    @GetMapping("/cash-drawer/reconciliation")
    DrawerReconciliation reconciliation() {
        DrawerSessionView session = drawer.findOpenSession(terminal())
                .orElseThrow(() -> DomainException.notFound("No open drawer session for this terminal"));
        return drawer.reconcile(session.sessionId());
    }

    private String terminal() {
        return config.getString(SettingKey.TERMINAL_ID);
    }
}
```

`src/test/java/com/company/pos/cashdrawer/CashDrawerControllerTest.java`:

```java
package com.company.pos.cashdrawer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.cashdrawer.api.CashDrawerService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Transactional
class CashDrawerControllerTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    CashDrawerService drawer;

    @BeforeEach
    void openSession() {
        // default terminal is T01 (SettingKey.TERMINAL_ID default)
        drawer.openSession("T01", new BigDecimal("100.00"), "SAR", "cashier");
    }

    @Test
    void payInThenReconciliationReflectsIt() throws Exception {
        mvc.perform(post("/cash-drawer/pay-in").with(jwt().jwt(j -> j.subject("cashier")))
                        .contentType("application/json")
                        .content("{\"amount\":25.00,\"reason\":\"change fund\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("PAY_IN"))
                .andExpect(jsonPath("$.amount").value(25.00));

        mvc.perform(get("/cash-drawer/reconciliation").with(jwt().jwt(j -> j.subject("cashier"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payIns").value(25.00))
                .andExpect(jsonPath("$.expectedCash").value(125.00));
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(post("/cash-drawer/pay-in").contentType("application/json")
                        .content("{\"amount\":5.00,\"reason\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 12: Run the controller test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CashDrawerControllerTest`
Expected: PASS.

- [ ] **Step 13: Verify module boundaries**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ModularityTests`
Expected: PASS — `cashdrawer → device :: api, configuration :: api` declared; no cycle.

- [ ] **Step 14: Commit**

```bash
git add src/main/java/com/company/pos/cashdrawer/ \
        src/main/resources/db/migration/cashdrawer/ \
        src/main/resources/application-store-server.yml \
        src/test/java/com/company/pos/cashdrawer/
git commit -m "feat(cashdrawer): drawer sessions, cash-movement ledger, reconciliation and REST"
```

---

### Task 3: `cashdrawer` — capture cash sales from `SaleCompleted`

Subscribe to the `sales` `SaleCompleted` event and attribute its cash portion to the terminal's open drawer session, mirroring how `inventory` decrements stock. This is what makes reconciliation reflect real cash sales without coupling checkout to the drawer.

**Files:**
- Modify: `src/main/java/com/company/pos/cashdrawer/package-info.java`
- Create: `src/main/java/com/company/pos/cashdrawer/application/SaleCompletedCashListener.java`
- Test: `src/test/java/com/company/pos/cashdrawer/SaleCapturedByDrawerTest.java`

**Interfaces:**
- Consumes: `SaleCompleted.terminalId()`, `SaleCompleted.cashTotal()`, `SaleCompleted.saleId()` (from Task 1); `CashDrawerService.recordCashSale(String, BigDecimal, String)` (from Task 2).
- Produces: a `@Component @EventListener` that runs in the checkout transaction and never throws into the publisher.

- [ ] **Step 1: Add `sales :: api` to the module**

Replace `src/main/java/com/company/pos/cashdrawer/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "device :: api", "configuration :: api", "sales :: api" })
package com.company.pos.cashdrawer;
```

- [ ] **Step 2: Write the failing test**

```java
package com.company.pos.cashdrawer;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.cashdrawer.api.CashDrawerService;
import com.company.pos.cashdrawer.api.DrawerReconciliation;
import com.company.pos.cashdrawer.api.DrawerSessionView;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class SaleCapturedByDrawerTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    CashDrawerService drawer;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    InMemoryPaymentTerminal terminal;

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
        terminal.setApprove(true);
    }

    @AfterEach
    void reset() {
        terminal.setApprove(true);
    }

    @Test
    void cashSaleIsCapturedIntoOpenDrawerSession() {
        DrawerSessionView session = drawer.openSession("T01", new BigDecimal("100.00"), "SAR", "cashier");
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // total 10.35
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))), "cashier");

        DrawerReconciliation recon = drawer.reconcile(session.sessionId());
        assertThat(recon.cashSales()).isEqualByComparingTo("10.35");
        assertThat(recon.cashSalesCount()).isEqualTo(1);
        assertThat(recon.expectedCash()).isEqualByComparingTo("110.35");
    }

    @Test
    void cardSaleDoesNotChangeCashSales() {
        DrawerSessionView session = drawer.openSession("T01", new BigDecimal("100.00"), "SAR", "cashier");
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CARD, new BigDecimal("10.35"), null))), "cashier");

        DrawerReconciliation recon = drawer.reconcile(session.sessionId());
        assertThat(recon.cashSales()).isEqualByComparingTo("0.00");
        assertThat(recon.cashSalesCount()).isEqualTo(0);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=SaleCapturedByDrawerTest`
Expected: FAIL — `cashSaleIsCapturedIntoOpenDrawerSession` fails: `cashSales` is `0.00` because no listener captures the sale yet.

- [ ] **Step 4: Write the listener**

`src/main/java/com/company/pos/cashdrawer/application/SaleCompletedCashListener.java`:

```java
package com.company.pos.cashdrawer.application;

import com.company.pos.cashdrawer.api.CashDrawerService;
import com.company.pos.sales.api.SaleCompleted;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Captures the cash portion of a completed sale into the terminal's open drawer session.
 * Runs synchronously inside the checkout transaction and swallows {@link RuntimeException} so a
 * drawer failure never rolls back the sale. If no session is open the capture is a no-op (handled
 * inside {@link CashDrawerService#recordCashSale}). Same caveat as the inventory listener: a DB
 * constraint violation can still mark the shared transaction rollback-only; the Phase 3 outbox +
 * {@code @TransactionalEventListener(AFTER_COMMIT)} removes that coupling.
 */
@Component
class SaleCompletedCashListener {

    private static final Logger log = LoggerFactory.getLogger(SaleCompletedCashListener.class);

    private final CashDrawerService drawer;

    SaleCompletedCashListener(CashDrawerService drawer) {
        this.drawer = drawer;
    }

    @EventListener
    @Transactional
    void on(SaleCompleted event) {
        if (event.cashTotal() == null || event.cashTotal().signum() <= 0) {
            return;
        }
        try {
            drawer.recordCashSale(event.terminalId(), event.cashTotal(), event.saleId().toString());
        } catch (RuntimeException ex) {
            log.warn("Failed to capture cash for sale {} on terminal {}",
                    event.receiptNumber(), event.terminalId(), ex);
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=SaleCapturedByDrawerTest`
Expected: PASS (both tests).

- [ ] **Step 6: Verify module boundaries**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ModularityTests`
Expected: PASS — `cashdrawer → sales :: api` declared; no cycle (`sales` does not depend on `cashdrawer`).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/pos/cashdrawer/package-info.java \
        src/main/java/com/company/pos/cashdrawer/application/SaleCompletedCashListener.java \
        src/test/java/com/company/pos/cashdrawer/SaleCapturedByDrawerTest.java
git commit -m "feat(cashdrawer): capture cash sales from SaleCompleted into the open session"
```

---

### Task 4: `shift` module — shift lifecycle orchestrating the drawer + REST

A `Shift` aggregate that owns a cash session: opening a shift opens a drawer session with the float; closing it counts the cash and returns the reconciliation as the shift summary. `shift` orchestrates `cashdrawer` and exposes the lifecycle over REST.

**Files:**
- Create: `src/main/java/com/company/pos/shift/package-info.java`
- Create: `src/main/java/com/company/pos/shift/api/package-info.java`
- Create: `src/main/java/com/company/pos/shift/api/ShiftService.java`
- Create: `src/main/java/com/company/pos/shift/api/ShiftView.java`
- Create: `src/main/java/com/company/pos/shift/api/ShiftSummary.java`
- Create: `src/main/java/com/company/pos/shift/domain/Shift.java`
- Create: `src/main/java/com/company/pos/shift/infrastructure/ShiftRepository.java`
- Create: `src/main/java/com/company/pos/shift/application/DefaultShiftService.java`
- Create: `src/main/java/com/company/pos/shift/web/ShiftController.java`
- Create: `src/main/resources/db/migration/shift/V13__shift.sql`
- Modify: `src/main/resources/application-store-server.yml`
- Test: `src/test/java/com/company/pos/shift/ShiftServiceTest.java`
- Test: `src/test/java/com/company/pos/shift/ShiftControllerTest.java`

**Interfaces:**
- Consumes: `CashDrawerService.openSession(...) -> DrawerSessionView`; `CashDrawerService.closeSession(UUID, BigDecimal) -> DrawerReconciliation`; `CashDrawerService.reconcile(UUID) -> DrawerReconciliation`; `ConfigurationService.getString(SettingKey.CURRENCY_CODE / TERMINAL_ID)`; `Identifiers.newId()`; `DomainException`.
- Produces:
  - `record ShiftView(UUID shiftId, String terminalId, String openedBy, String status, String currencyCode, Instant openedAt, Instant closedAt)`
  - `record ShiftSummary(UUID shiftId, String terminalId, String openedBy, String status, Instant openedAt, Instant closedAt, com.company.pos.cashdrawer.api.DrawerReconciliation cash)`
  - `interface ShiftService`:
    - `ShiftView openShift(String terminalId, BigDecimal openingFloat, String openedBy)` — conflict if a shift is already open for the terminal; opens a drawer session and stores its id.
    - `ShiftSummary closeShift(UUID shiftId, BigDecimal countedCash, String closedBy)` — closes the drawer session and the shift; returns the summary with the cash reconciliation (incl. variance).
    - `ShiftSummary getSummary(UUID shiftId)` — current reconciliation for the shift.
    - `Optional<ShiftView> findOpenShift(String terminalId)`.
  - Statuses: `"OPEN"`, `"CLOSED"`.

- [ ] **Step 1: Declare the module**

`src/main/java/com/company/pos/shift/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "cashdrawer :: api", "configuration :: api" })
package com.company.pos.shift;
```

`src/main/java/com/company/pos/shift/api/package-info.java`:

```java
@org.springframework.modulith.NamedInterface("api")
package com.company.pos.shift.api;
```

- [ ] **Step 2: Write the failing service test**

```java
package com.company.pos.shift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.shift.api.ShiftService;
import com.company.pos.shift.api.ShiftSummary;
import com.company.pos.shift.api.ShiftView;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class ShiftServiceTest {

    @Autowired
    ShiftService shifts;

    @Test
    void openShiftSeedsFloatAndIsFindable() {
        ShiftView shift = shifts.openShift("T01", new BigDecimal("100.00"), "cashier");

        assertThat(shift.status()).isEqualTo("OPEN");
        assertThat(shift.terminalId()).isEqualTo("T01");
        assertThat(shifts.findOpenShift("T01")).isPresent();

        ShiftSummary summary = shifts.getSummary(shift.shiftId());
        assertThat(summary.cash().openingFloat()).isEqualByComparingTo("100.00");
        assertThat(summary.cash().expectedCash()).isEqualByComparingTo("100.00");
    }

    @Test
    void cannotOpenTwoShiftsForSameTerminal() {
        shifts.openShift("T01", new BigDecimal("100.00"), "cashier");
        assertThatThrownBy(() -> shifts.openShift("T01", new BigDecimal("50.00"), "cashier"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void closeShiftReportsVarianceAndClears() {
        ShiftView shift = shifts.openShift("T01", new BigDecimal("100.00"), "cashier");
        // no sales; count 99.00 -> short 1.00
        ShiftSummary summary = shifts.closeShift(shift.shiftId(), new BigDecimal("99.00"), "cashier");

        assertThat(summary.status()).isEqualTo("CLOSED");
        assertThat(summary.cash().expectedCash()).isEqualByComparingTo("100.00");
        assertThat(summary.cash().countedCash()).isEqualByComparingTo("99.00");
        assertThat(summary.cash().variance()).isEqualByComparingTo("-1.00");
        assertThat(shifts.findOpenShift("T01")).isEmpty();
    }

    @Test
    void closingAlreadyClosedShiftIsRejected() {
        ShiftView shift = shifts.openShift("T01", new BigDecimal("100.00"), "cashier");
        shifts.closeShift(shift.shiftId(), new BigDecimal("100.00"), "cashier");
        assertThatThrownBy(() -> shifts.closeShift(shift.shiftId(), new BigDecimal("100.00"), "cashier"))
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ShiftServiceTest`
Expected: FAIL — `ShiftService` and friends do not exist (compile error).

- [ ] **Step 4: Write the API records and interface**

`src/main/java/com/company/pos/shift/api/ShiftView.java`:

```java
package com.company.pos.shift.api;

import java.time.Instant;
import java.util.UUID;

public record ShiftView(UUID shiftId, String terminalId, String openedBy, String status,
        String currencyCode, Instant openedAt, Instant closedAt) {
}
```

`src/main/java/com/company/pos/shift/api/ShiftSummary.java`:

```java
package com.company.pos.shift.api;

import com.company.pos.cashdrawer.api.DrawerReconciliation;
import java.time.Instant;
import java.util.UUID;

public record ShiftSummary(UUID shiftId, String terminalId, String openedBy, String status,
        Instant openedAt, Instant closedAt, DrawerReconciliation cash) {
}
```

`src/main/java/com/company/pos/shift/api/ShiftService.java`:

```java
package com.company.pos.shift.api;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface ShiftService {

    ShiftView openShift(String terminalId, BigDecimal openingFloat, String openedBy);

    ShiftSummary closeShift(UUID shiftId, BigDecimal countedCash, String closedBy);

    ShiftSummary getSummary(UUID shiftId);

    Optional<ShiftView> findOpenShift(String terminalId);
}
```

- [ ] **Step 5: Write the domain entity**

`src/main/java/com/company/pos/shift/domain/Shift.java`:

```java
package com.company.pos.shift.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "shift")
public class Shift {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "terminal_id", nullable = false, length = 16)
    private String terminalId;

    @Column(name = "opened_by", nullable = false, length = 100)
    private String openedBy;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "drawer_session_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID drawerSessionId;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "counted_cash", precision = 19, scale = 2)
    private BigDecimal countedCash;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected Shift() {
        // JPA
    }

    public Shift(UUID id, String terminalId, String openedBy, UUID drawerSessionId,
            String currencyCode, Instant openedAt) {
        this.id = id;
        this.terminalId = terminalId;
        this.openedBy = openedBy;
        this.drawerSessionId = drawerSessionId;
        this.currencyCode = currencyCode;
        this.openedAt = openedAt;
        this.status = "OPEN";
    }

    public void close(BigDecimal countedCash, Instant closedAt) {
        this.countedCash = countedCash;
        this.closedAt = closedAt;
        this.status = "CLOSED";
    }

    public boolean isOpen() {
        return "OPEN".equals(status);
    }

    public UUID getId() {
        return id;
    }

    public String getTerminalId() {
        return terminalId;
    }

    public String getOpenedBy() {
        return openedBy;
    }

    public String getStatus() {
        return status;
    }

    public UUID getDrawerSessionId() {
        return drawerSessionId;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
```

- [ ] **Step 6: Write the repository**

`src/main/java/com/company/pos/shift/infrastructure/ShiftRepository.java`:

```java
package com.company.pos.shift.infrastructure;

import com.company.pos.shift.domain.Shift;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShiftRepository extends JpaRepository<Shift, UUID> {

    Optional<Shift> findByTerminalIdAndStatus(String terminalId, String status);
}
```

- [ ] **Step 7: Write the service**

`src/main/java/com/company/pos/shift/application/DefaultShiftService.java`:

```java
package com.company.pos.shift.application;

import com.company.pos.cashdrawer.api.CashDrawerService;
import com.company.pos.cashdrawer.api.DrawerReconciliation;
import com.company.pos.cashdrawer.api.DrawerSessionView;
import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.shift.api.ShiftService;
import com.company.pos.shift.api.ShiftSummary;
import com.company.pos.shift.api.ShiftView;
import com.company.pos.shift.domain.Shift;
import com.company.pos.shift.infrastructure.ShiftRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultShiftService implements ShiftService {

    private final ShiftRepository shifts;
    private final CashDrawerService drawer;
    private final ConfigurationService config;

    DefaultShiftService(ShiftRepository shifts, CashDrawerService drawer, ConfigurationService config) {
        this.shifts = shifts;
        this.drawer = drawer;
        this.config = config;
    }

    @Override
    public ShiftView openShift(String terminalId, BigDecimal openingFloat, String openedBy) {
        shifts.findByTerminalIdAndStatus(terminalId, "OPEN").ifPresent(s -> {
            throw DomainException.conflict("A shift is already open for terminal " + terminalId);
        });
        String currency = config.getString(SettingKey.CURRENCY_CODE);
        DrawerSessionView session = drawer.openSession(terminalId, openingFloat, currency, openedBy);
        Shift shift = new Shift(Identifiers.newId(), terminalId, openedBy, session.sessionId(),
                currency, Instant.now());
        shifts.save(shift);
        return toView(shift);
    }

    @Override
    public ShiftSummary closeShift(UUID shiftId, BigDecimal countedCash, String closedBy) {
        Shift shift = load(shiftId);
        if (!shift.isOpen()) {
            throw DomainException.conflict("Shift " + shiftId + " is not open");
        }
        DrawerReconciliation cash = drawer.closeSession(shift.getDrawerSessionId(), countedCash);
        shift.close(countedCash, Instant.now());
        return toSummary(shift, cash);
    }

    @Override
    @Transactional(readOnly = true)
    public ShiftSummary getSummary(UUID shiftId) {
        Shift shift = load(shiftId);
        DrawerReconciliation cash = drawer.reconcile(shift.getDrawerSessionId());
        return toSummary(shift, cash);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShiftView> findOpenShift(String terminalId) {
        return shifts.findByTerminalIdAndStatus(terminalId, "OPEN").map(this::toView);
    }

    private Shift load(UUID shiftId) {
        return shifts.findById(shiftId)
                .orElseThrow(() -> DomainException.notFound("No shift " + shiftId));
    }

    private ShiftView toView(Shift shift) {
        return new ShiftView(shift.getId(), shift.getTerminalId(), shift.getOpenedBy(),
                shift.getStatus(), shift.getCurrencyCode(), shift.getOpenedAt(), shift.getClosedAt());
    }

    private ShiftSummary toSummary(Shift shift, DrawerReconciliation cash) {
        return new ShiftSummary(shift.getId(), shift.getTerminalId(), shift.getOpenedBy(),
                shift.getStatus(), shift.getOpenedAt(), shift.getClosedAt(), cash);
    }
}
```

- [ ] **Step 8: Write the migration (store-server)**

`src/main/resources/db/migration/shift/V13__shift.sql`:

```sql
CREATE TABLE shift (
    id                VARCHAR(36) PRIMARY KEY,
    terminal_id       VARCHAR(16) NOT NULL,
    opened_by         VARCHAR(100) NOT NULL,
    status            VARCHAR(16) NOT NULL,
    drawer_session_id VARCHAR(36) NOT NULL REFERENCES drawer_session (id),
    currency_code     VARCHAR(3) NOT NULL,
    counted_cash      NUMERIC(19, 2),
    opened_at         TIMESTAMP NOT NULL,
    closed_at         TIMESTAMP
);

CREATE INDEX idx_shift_terminal_status ON shift (terminal_id, status);
```

- [ ] **Step 9: Register the migration location**

Edit `src/main/resources/application-store-server.yml` line 22 (`locations:`). Append `,classpath:db/migration/shift` to the end of the value (which already ends with `…,classpath:db/migration/cashdrawer` from Task 2). The line becomes:

```yaml
    locations: classpath:db/migration/configuration,classpath:db/migration/auth,classpath:db/migration/product,classpath:db/migration/inventory,classpath:db/migration/integration,classpath:db/migration/cart,classpath:db/migration/payment,classpath:db/migration/sales,classpath:db/migration/cashdrawer,classpath:db/migration/shift
```

- [ ] **Step 10: Run the service test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ShiftServiceTest`
Expected: PASS (all four tests).

- [ ] **Step 11: Write the controller and its test**

`src/main/java/com/company/pos/shift/web/ShiftController.java`:

```java
package com.company.pos.shift.web;

import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.common.exception.DomainException;
import com.company.pos.shift.api.ShiftService;
import com.company.pos.shift.api.ShiftSummary;
import com.company.pos.shift.api.ShiftView;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ShiftController {

    private final ShiftService shifts;
    private final ConfigurationService config;

    ShiftController(ShiftService shifts, ConfigurationService config) {
        this.shifts = shifts;
        this.config = config;
    }

    record OpenShiftRequest(BigDecimal openingFloat) {
    }

    record CloseShiftRequest(BigDecimal countedCash) {
    }

    @PostMapping("/shifts")
    @ResponseStatus(HttpStatus.CREATED)
    ShiftView open(@RequestBody OpenShiftRequest body, Principal principal) {
        return shifts.openShift(terminal(), body.openingFloat(), principal.getName());
    }

    @PostMapping("/shifts/{shiftId}/close")
    ShiftSummary close(@PathVariable UUID shiftId, @RequestBody CloseShiftRequest body,
            Principal principal) {
        return shifts.closeShift(shiftId, body.countedCash(), principal.getName());
    }

    @GetMapping("/shifts/{shiftId}")
    ShiftSummary summary(@PathVariable UUID shiftId) {
        return shifts.getSummary(shiftId);
    }

    @GetMapping("/shifts/open")
    ShiftView open() {
        return shifts.findOpenShift(terminal())
                .orElseThrow(() -> DomainException.notFound("No open shift for this terminal"));
    }

    private String terminal() {
        return config.getString(SettingKey.TERMINAL_ID);
    }
}
```

`src/test/java/com/company/pos/shift/ShiftControllerTest.java`:

```java
package com.company.pos.shift;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Transactional
class ShiftControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void openThenCloseShiftReturnsVariance() throws Exception {
        String opened = mvc.perform(post("/shifts").with(jwt().jwt(j -> j.subject("cashier")))
                        .contentType("application/json").content("{\"openingFloat\":100.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        String shiftId = JsonPath.read(opened, "$.shiftId");

        mvc.perform(post("/shifts/" + shiftId + "/close").with(jwt().jwt(j -> j.subject("cashier")))
                        .contentType("application/json").content("{\"countedCash\":97.50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.cash.expectedCash").value(100.00))
                .andExpect(jsonPath("$.cash.variance").value(-2.50));
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(post("/shifts").contentType("application/json").content("{\"openingFloat\":50.00}"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 12: Run the controller test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ShiftControllerTest`
Expected: PASS.

- [ ] **Step 13: Verify module boundaries**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ModularityTests`
Expected: PASS — `shift → cashdrawer :: api, configuration :: api` declared; no cycle.

- [ ] **Step 14: Commit**

```bash
git add src/main/java/com/company/pos/shift/ \
        src/main/resources/db/migration/shift/ \
        src/main/resources/application-store-server.yml \
        src/test/java/com/company/pos/shift/
git commit -m "feat(shift): shift lifecycle orchestrating the drawer with reconciliation and REST"
```

---

### Task 5: Capstone — open-shift → cash-sale → pay-out → close-shift end-to-end, docs, full verify

A whole-stack REST scenario proving the till workflow, plus documentation/ledger updates and the final full verification (including the Testcontainers store-server migration run through V13).

**Files:**
- Create: `src/test/java/com/company/pos/ShiftReconciliationEndToEndTest.java`
- Modify: `docs/superpowers/plans/2026-06-28-phase-2c-cashdrawer-shift.md` (append an AS-BUILT note)

**Interfaces:**
- Consumes: REST endpoints `/auth/login`, `/sync/erp`, `/shifts`, `/shifts/{id}/close`, `/carts`, `/carts/{id}/lines`, `/sales`, `/cash-drawer/pay-out`.

- [ ] **Step 1: Write the end-to-end test**

`src/test/java/com/company/pos/ShiftReconciliationEndToEndTest.java`:

```java
package com.company.pos;

import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.util.Identifiers;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Transactional
class ShiftReconciliationEndToEndTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    FakeErpClient fake;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("20"), 1));
        users.save(new User(Identifiers.newId(), "cashier", "Cashier One",
                encoder.encode("pw"), Set.of(Role.CASHIER)));
        users.save(new User(Identifiers.newId(), "manager", "Store Manager",
                encoder.encode("pw"), Set.of(Role.MANAGER)));
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

- [ ] **Step 2: Run the end-to-end test**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ShiftReconciliationEndToEndTest`
Expected: PASS.

- [ ] **Step 3: Full verify (whole suite + boundaries + store-server migrations)**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -B verify`
Expected: BUILD SUCCESS. This runs the full JUnit suite, `ModularityTests`, and `DatabaseStoreServerTest` (Testcontainers Postgres applying Flyway V2–V13 then `ddl-auto=validate` — proves the V12 `drawer_session`/`cash_movement` and V13 `shift` tables match the entities). If `DatabaseStoreServerTest` fails on schema validation, reconcile the migration columns with the entity `@Column` definitions and re-run. Requires Docker; if Docker is unavailable, report that explicitly rather than skipping silently.

- [ ] **Step 4: Append the AS-BUILT note to this plan**

At the bottom of `docs/superpowers/plans/2026-06-28-phase-2c-cashdrawer-shift.md`, add an "## AS-BUILT" section noting any deviations discovered during execution (additional call sites, rounding/edge adjustments, the final `./mvnw -B verify` test count).

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/company/pos/ShiftReconciliationEndToEndTest.java \
        docs/superpowers/plans/2026-06-28-phase-2c-cashdrawer-shift.md
git commit -m "test(shift): till-reconciliation e2e (open shift -> cash sale -> pay-out -> close); phase 2c docs"
```

---

## Self-Review

**1. Spec coverage** (locked scope: shift lifecycle + cash-drawer movements + cash count/reconciliation, integrated model, checkout independent; break-management/cash-handover deferred):
- Shift open/close/summary → Task 4 (`openShift`/`closeShift`/`getSummary`). ✓
- Opening float → Task 4 opens a drawer session seeded with `OPENING_FLOAT` (Task 2). ✓
- Cash drawer open (device port), pay-in, pay-out → Task 2. ✓
- Auto cash-sale capture (checkout independent) → Task 1 (`SaleCompleted.cashTotal`/`terminalId`) + Task 3 (subscriber). ✓
- Cash count + reconciliation/variance at close → Task 2 `reconcile`/`closeSession`, surfaced via Task 4 `ShiftSummary.cash`. ✓
- Integrated model (shift owns the session) → Task 4 orchestrates `cashdrawer`. ✓
- Break-management + cash-handover → out of scope (stated in Architecture). ✓
- Full multi-tender sales reporting (card/wallet breakdown) → NOT included; the shift summary reports the cash reconciliation only. Full sales-by-tender reporting belongs to the Phase 4 `reporting` module (noted here so the gap is explicit, not silent).

**2. Placeholder scan:** No `TBD`/`handle edge cases`/"similar to Task N" — every code step contains complete content.

**3. Type consistency:**
- `SaleCompleted(saleId, receiptNumber, terminalId, locationCode, currencyCode, grandTotal, cashTotal, lines)` — produced in Task 1; consumed in Task 3 (`event.terminalId()`, `event.cashTotal()`, `event.saleId()`, `event.receiptNumber()`). The `inventory` listener uses only pre-existing fields. ✓
- `CashDrawerService` signatures (`openSession`, `recordCashSale`, `payIn`, `payOut`, `reconcile`, `closeSession`, `findOpenSession`) — defined in Task 2; consumed by Task 3 (`recordCashSale`) and Task 4 (`openSession`/`closeSession`/`reconcile`). ✓
- `DrawerReconciliation` — defined in Task 2; embedded in `ShiftSummary.cash` (Task 4) and asserted via `$.cash.*` in Task 4/5 tests. ✓
- Movement type strings `"OPENING_FLOAT"`/`"CASH_SALE"`/`"PAY_IN"`/`"PAY_OUT"` and statuses `"OPEN"`/`"CLOSED"` are used identically in the service, reconciliation sums, and tests. ✓
- Migrations: V12 columns (`drawer_session`, `cash_movement`) and V13 (`shift`) match the entity `@Column(length/precision/scale)` definitions; FK `shift.drawer_session_id → drawer_session.id`. ✓
- `flyway.locations` gets `cashdrawer` (Task 2) then `shift` (Task 4) appended — the two edits are sequential and additive. ✓
- Reconciliation math (`expected = float + cashSales + payIns − payOuts`, `variance = counted − expected`) is asserted consistently in the service test (Task 2: 130.00, −1.50), shift test (Task 4: −1.00, −2.50), and e2e (Task 5: expected 95.35, variance 0.00). ✓

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-28-phase-2c-cashdrawer-shift.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?

---

## AS-BUILT

**Executed:** 2026-06-28 — Task 5 capstone (ShiftReconciliationEndToEndTest + full verify).

### Deviations from plan discovered during execution

**(a) pay-in/pay-out with no open session raises `DomainException.conflict` (HTTP 409), not `notFound` (404).**
The plan's prose said "no open session" would surface as a not-found error. During review/execution it was confirmed by the team that the correct semantics are a conflict error: the terminal exists but the state (no open session) conflicts with the operation. The `CashDrawerControllerTest.payInWithoutOpenSessionIsRejected` test asserts `DomainException` (class-level); the service throws `DomainException.conflict(...)`. No plan text change was needed — the code was correct; this note closes the ambiguity.

**(b) `shift` entity and V13 migration persist `closed_by`; `ShiftSummary` exposes `closedBy`.**
The plan's `closeShift` API accepted `closedBy` as a parameter but the original entity design did not explicitly commit to storing it as a column. During Task 4 execution the entity `Shift`, migration `V13__shift.sql`, and `ShiftSummary.closedBy()` were all implemented with the `closed_by VARCHAR(100)` column. The `ddl-auto=validate` pass in `DatabaseStoreServerTest` confirmed the column exists in Postgres and matches the entity `@Column(name = "closed_by", length = 100)` definition. No reconciliation was required in Task 5.

### Final `./mvnw -B verify` result

- **Total tests:** 119 — Failures: 0, Errors: 0, Skipped: 0
- **BUILD SUCCESS**
- **DatabaseStoreServerTest:** RAN AND PASSED (Docker available; Testcontainers Postgres applied all 13 Flyway migrations V1–V13, then `ddl-auto=validate` passed against all entities including `drawer_session`, `cash_movement`, and `shift`)
- **ModularityTests:** PASSED (3 tests — module boundary verification)
- **ShiftReconciliationEndToEndTest:** PASSED (1 test — full till-reconciliation scenario: open shift 100.00 float → cash sale 10.35 → pay-out 15.00 → close with count 95.35 → variance 0.00)
