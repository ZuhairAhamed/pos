# Phase 4 — Returns & Refunds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a MANAGER process a receipted return that refunds all or part of a prior sale — restoring stock, paying the refund back on the original tender(s), and uploading a credit note to the ERP — guarded against over-refunding across repeated partial returns.

**Architecture:** Extend the `sales` module with an immutable `SalesReturn` aggregate. `ReturnService.processReturn` runs one synchronous transaction (validate → record refund tenders → persist → publish `ReturnCompleted` → print credit note). The reversal of stock / drawer / ERP happens after-commit through three new `@ApplicationModuleListener`s in `inventory`, `cashdrawer`, and `sync` — exactly mirroring the existing `SaleCompleted` fan-out and reusing the Phase 3a transactional outbox, so ERP credit-note upload stays asynchronous and replayable.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith 1.2.5 (Event Publication Registry), Spring Data JPA, Spring Security (method security, JWT), Flyway (store-server), JUnit 5 + Awaitility + spring-security-test, Testcontainers (Postgres), Maven (`./mvnw`).

## Global Constraints

- **Root package** `com.company.pos`; one package-per-module. Boundaries enforced by `ApplicationModules.of(PosApplication.class).verify()` (`ModularityTests`). Cross-module access only via a `@NamedInterface("api")` sub-package, listed in the consumer's `allowedDependencies` as `module :: api`. **This phase introduces NO new module and NO new `allowedDependencies` entry** — every new type lives in an existing module, and every cross-module reference (`ReturnCompleted` in `sales.api`, `PaymentService`/`PaymentMethod` in `payment.api`, `CashDrawerService` in `cashdrawer.api`, `ErpClient` in `integration.api`) is already an allowed dependency of the consuming module.
- **JDK 21 required.** The machine default `java` is 17. Run every Maven command as: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw …`.
- **Build tool is Maven** via the committed `./mvnw` wrapper. No system `mvn`.
- **Money is `BigDecimal` — never `double`/`float`.** Amounts use scale 2 (`HALF_UP`); quantity uses scale 3; unit price uses scale 4 — matching `sale`/`sale_line`. Compare with `compareTo`/`signum`, never `equals`.
- **Single physical `pos` schema.** Entities are schema-agnostic; the store-server profile sets `hibernate.default_schema: pos`. New migrations are **V16** (`db/migration/payment`) and **V17** (`db/migration/sales`) — both locations already in `flyway.locations` (no `flyway.locations` change). Embedded (`ddl-auto: update`, no Flyway) lets Hibernate create the columns/tables. store-server runs `ddl-auto: validate`, so each migration must match the entity mapping (verified by `DatabaseStoreServerTest`, Flyway V1–V17).
- **Returns are MANAGER-only.** `POST /returns` and `GET /returns/{id}` are guarded `@PreAuthorize("hasRole('MANAGER')")`. Roles are carried as `ROLE_<NAME>` authorities (`JwtSupportConfig` sets prefix `ROLE_`; `@EnableMethodSecurity` is on). A cashier receives `403`.
- **Receipted, over-return-guarded, mirror-tender, always-restock.** Returns must reference an original sale; cumulative returned qty per original line can never exceed sold qty; refunds go back on the original tender(s) allocated proportionally; returned goods are always restocked (no damaged-goods path).
- **Notifications/reversal are best-effort and never block the writer.** The three downstream listeners are `@ApplicationModuleListener` (after-commit, async, registry-tracked), like the existing `SaleCompleted` listeners. A failure leaves an incomplete publication for replay; it never affects the committed return.
- **Test isolation (carry the Phase 3a/3b/3c lesson):** every NON-`@Transactional` test that commits MUST use `com.company.pos.support.DatabaseCleaner` (`@Import`, `clean()` first in `@BeforeEach` and in `@AfterEach`), Awaitility for async side-effects, and must reset singleton fakes (`fake.clear()`, `terminal.setApprove(true)`).

---

## Existing code this phase consumes / changes (already implemented — do not redefine)

```java
// com.company.pos.common.events
public interface DomainEvent {}
@Component public class DomainEvents { public void publish(DomainEvent event); }
// com.company.pos.common.util.Identifiers : static UUID newId()
// com.company.pos.common.exception.DomainException : static notFound(String)/validation(String)/conflict(String)

// com.company.pos.sales.domain.Sale  (immutable; getId/getReceiptNumber/getStoreId? ...)
//   getId():UUID  getReceiptNumber():String  getGrandTotal():BigDecimal  getCurrencyCode():String
//   getLocationCode():String  getLines():List<SaleLine>  (status starts "COMPLETED")
// com.company.pos.sales.domain.SaleLine
//   getLineNo():int getSku():String getName():String getQuantity():BigDecimal getUnitPrice():BigDecimal
//   getNetAmount():BigDecimal getTaxAmount():BigDecimal getLineTotal():BigDecimal getCurrencyCode():String
// com.company.pos.sales.infrastructure.SaleRepository extends JpaRepository<Sale,UUID>   // + findByReceiptNumber (Task 3)
// com.company.pos.sales.application.ReceiptNumbering (public @Component)
//   @Transactional String nextReceiptNumber(String storeId, String terminalId)   // store-terminal sequence

// com.company.pos.payment.api.PaymentService   // CHANGED Task 2 (+ refundCash/refundTerminalPayment/findByReturn)
//   PaymentView recordCash(UUID saleId, String currency, BigDecimal amount, BigDecimal tendered)
//   PaymentView recordTerminalPayment(UUID saleId, String currency, BigDecimal amount, PaymentMethod method, String reference)
//   List<PaymentView> findBySale(UUID saleId)
// com.company.pos.payment.api.PaymentMethod { CASH, CARD, WALLET }
// com.company.pos.payment.api.PaymentView(UUID saleId, String method, BigDecimal amount, BigDecimal amountTendered, BigDecimal changeDue, String maskedPan, String currencyCode)
// com.company.pos.payment.domain.Payment   // CHANGED Task 2 (+ returnId + direction)
// com.company.pos.payment.infrastructure.PaymentRepository extends JpaRepository<Payment,UUID>  // + findByReturnId… (Task 2)

// com.company.pos.device.api.PaymentTerminal   // CHANGED Task 1 (+ refund)
//   PaymentResult requestPayment(PaymentRequest request)
// com.company.pos.device.api.PaymentRequest(MonetaryAmount amount, String reference)
// com.company.pos.device.api.PaymentResult(boolean approved, String maskedPan, String token)
// com.company.pos.device.infrastructure.InMemoryPaymentTerminal (public @Component)  // CHANGED Task 1
//   setApprove(boolean) ; PaymentRequest lastRequest()
// com.company.pos.common.util.Monies : static MonetaryAmount of(BigDecimal, String)

// com.company.pos.receipt.api.ReceiptService : void print(ReceiptData)
//   ReceiptData(String receiptNumber, String cashierName, Instant timestamp, List<ReceiptLineData> lines,
//               BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal, List<ReceiptPaymentData> payments, String currencyCode)
//   ReceiptLineData(String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineTotal)
//   ReceiptPaymentData(String method, BigDecimal amount, BigDecimal amountTendered, BigDecimal changeDue, String maskedPan)

// com.company.pos.configuration.api.ConfigurationService.getString(SettingKey)
// com.company.pos.configuration.api.SettingKey.{STORE_ID,TERMINAL_ID,INVENTORY_LOCATION}

// com.company.pos.inventory.domain.StockLevel : getQuantityOnHand()/setQuantityOnHand(BigDecimal); ctor (UUID,String sku,String location)
// com.company.pos.inventory.domain.StockMovement : ctor(UUID id, String sku, String location, BigDecimal delta, String reason, String referenceId, Instant)
// com.company.pos.inventory.infrastructure.StockLevelRepository.findBySkuAndLocationCode(String,String)
// com.company.pos.inventory.infrastructure.StockMovementRepository (+ findBySku)

// com.company.pos.cashdrawer.api.CashDrawerService   // CHANGED Task 7 (+ recordCashRefund)
//   void recordCashSale(String terminalId, BigDecimal amount, String reference)   // no-op when no open session

// com.company.pos.integration.api.ErpClient   // CHANGED Task 8 (+ uploadReturn)
//   void uploadSale(SaleUpload) ; void uploadStockMovements(String saleId, List<StockMovementUpload>)
// com.company.pos.integration.api.StockMovementUpload(String sku, String locationCode, BigDecimal quantityDelta, String reason)
// com.company.pos.integration.erp.FakeErpClient (public @Component)  // CHANGED Task 8
//   setAvailable(boolean) ; uploadedSales() ; clear()

// Driving facades (Phases 1-2): ProductSync.sync(), InventorySync.sync(), ErpProduct(...), ErpStockLevel(sku,location,qty,version),
//   CartService.createCart()/addLine(cartId, sku, BigDecimal qty), SalesService.checkout(new CheckoutCommand(cartId, List<TenderInput>), cashier),
//   new TenderInput(PaymentMethod, amount, tendered)
// Test helper (Phase 3a): com.company.pos.support.DatabaseCleaner — @Import + clean()
```

## New surface produced by this phase

| Module | New types / endpoints |
|---|---|
| `device` (`api`) | `PaymentTerminal.refund(PaymentRequest) : PaymentResult` |
| `payment` (`api`) | `PaymentService.refundCash`, `refundTerminalPayment`, `findByReturn` |
| `payment` (domain/infra) | `PaymentDirection { SALE, REFUND }`; `Payment.returnId`+`direction`; `PaymentRepository.findByReturnIdOrderByCreatedAtAsc` |
| `sales` (`api`) | `ReturnService`, `ReturnCommand` (+`ReturnLineRequest`), `ReturnView`, `SaleReturnLineView`, `ReturnPaymentView`, `ReturnCompleted` (+`ReturnedLine`) event |
| `sales` (domain/app/infra/web) | `SalesReturn`, `SalesReturnLine`; `DefaultReturnService`; `SalesReturnRepository`, `SalesReturnLineRepository` (over-return query); `SaleRepository.findByReceiptNumber`; `ReturnController` |
| `inventory` (app) | `ReturnCompletedListener` (stock add-back) |
| `cashdrawer` (api/app) | `CashDrawerService.recordCashRefund`; `ReturnCompletedCashListener` |
| `integration` (`api`/erp) | `ReturnUpload`; `ErpClient.uploadReturn`; `FakeErpClient.uploadReturn` (+ `uploadedReturns()`) |
| `sync` (app) | `ReturnUploadListener` |

## Migrations added

| Version | Location dir | Change |
|---|---|---|
| V16 | `db/migration/payment` | `payment` + `txn_type` (NOT NULL DEFAULT 'SALE') and nullable `return_id` |
| V17 | `db/migration/sales` | `sales_return` + `sales_return_line` tables |

---

### Task 1: `device` — `PaymentTerminal.refund` port + in-memory adapter

Add a refund operation to the terminal port and the in-memory fake so card/wallet refunds have a device to call.

**Files:**
- Modify: `src/main/java/com/company/pos/device/api/PaymentTerminal.java`
- Modify: `src/main/java/com/company/pos/device/infrastructure/InMemoryPaymentTerminal.java`
- Test: `src/test/java/com/company/pos/device/PaymentTerminalRefundTest.java`

**Interfaces:**
- Consumes: `PaymentRequest`, `PaymentResult` (device.api, existing).
- Produces: `PaymentTerminal.refund(PaymentRequest) : PaymentResult`; `InMemoryPaymentTerminal.refund(...)` honoring the existing `setApprove(boolean)` control.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/company/pos/device/PaymentTerminalRefundTest.java`:

```java
package com.company.pos.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.device.api.PaymentRequest;
import com.company.pos.device.api.PaymentResult;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import com.company.pos.common.util.Monies;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PaymentTerminalRefundTest {

    private final InMemoryPaymentTerminal terminal = new InMemoryPaymentTerminal();

    @Test
    void refundIsApprovedByDefaultAndReturnsMaskedPan() {
        PaymentResult result = terminal.refund(
                new PaymentRequest(Monies.of(new BigDecimal("9.00"), "SAR"), "ret-1"));

        assertThat(result.approved()).isTrue();
        assertThat(result.maskedPan()).isNotBlank();
        assertThat(result.token()).isNotBlank();
    }

    @Test
    void refundCanBeDeclined() {
        terminal.setApprove(false);

        PaymentResult result = terminal.refund(
                new PaymentRequest(Monies.of(new BigDecimal("9.00"), "SAR"), "ret-1"));

        assertThat(result.approved()).isFalse();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=PaymentTerminalRefundTest`
Expected: FAIL — compilation error, `refund` is not on `InMemoryPaymentTerminal`/`PaymentTerminal`.

- [ ] **Step 3: Add `refund` to the port**

In `src/main/java/com/company/pos/device/api/PaymentTerminal.java`, add the method below `requestPayment`:

```java
    /** Refunds a previously-captured amount (a card/wallet credit). */
    PaymentResult refund(PaymentRequest request);
```

- [ ] **Step 4: Implement `refund` on the fake**

In `src/main/java/com/company/pos/device/infrastructure/InMemoryPaymentTerminal.java`, add (after `requestPayment`):

```java
    @Override
    public PaymentResult refund(PaymentRequest request) {
        this.lastRequest = request;
        if (!approve) {
            return new PaymentResult(false, null, null);
        }
        return new PaymentResult(true, "**** **** **** 4242", "ref_" + UUID.randomUUID());
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=PaymentTerminalRefundTest`
Expected: PASS (both cases).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/device/ \
        src/test/java/com/company/pos/device/PaymentTerminalRefundTest.java
git commit -m "feat(device): add refund operation to the PaymentTerminal port"
```

---

### Task 2: `payment` — refund tenders (migration + entity + service)

Record refunds as `Payment` rows tagged `REFUND` and keyed by `returnId`, with cash and terminal refund paths. Refund rows store `sale_id = returnId` so they never appear in the original sale's payment query.

**Files:**
- Create: `src/main/resources/db/migration/payment/V16__payment_refund_fields.sql`
- Create: `src/main/java/com/company/pos/payment/domain/PaymentDirection.java`
- Modify: `src/main/java/com/company/pos/payment/domain/Payment.java`
- Modify: `src/main/java/com/company/pos/payment/infrastructure/PaymentRepository.java`
- Modify: `src/main/java/com/company/pos/payment/api/PaymentService.java`
- Modify: `src/main/java/com/company/pos/payment/application/DefaultPaymentService.java`
- Test: `src/test/java/com/company/pos/payment/RefundPaymentTest.java`

**Interfaces:**
- Consumes: `PaymentTerminal.refund` (Task 1); `PaymentRequest`, `Monies`.
- Produces:
  - `enum PaymentDirection { SALE, REFUND }`.
  - `PaymentService.refundCash(UUID returnId, String currencyCode, BigDecimal amount) : PaymentView`.
  - `PaymentService.refundTerminalPayment(UUID returnId, String currencyCode, BigDecimal amount, PaymentMethod method, String reference) : PaymentView`.
  - `PaymentService.findByReturn(UUID returnId) : List<PaymentView>`.
  - `PaymentRepository.findByReturnIdOrderByCreatedAtAsc(UUID returnId) : List<Payment>`.

- [ ] **Step 1: Write the migration**

`src/main/resources/db/migration/payment/V16__payment_refund_fields.sql`:

```sql
ALTER TABLE payment ADD COLUMN txn_type VARCHAR(16) NOT NULL DEFAULT 'SALE';
ALTER TABLE payment ADD COLUMN return_id VARCHAR(36);
```

- [ ] **Step 2: Write the `PaymentDirection` enum**

`src/main/java/com/company/pos/payment/domain/PaymentDirection.java`:

```java
package com.company.pos.payment.domain;

public enum PaymentDirection {
    SALE,
    REFUND
}
```

- [ ] **Step 3: Add `returnId` + `direction` to `Payment`**

In `src/main/java/com/company/pos/payment/domain/Payment.java`:

Add imports if missing — `PaymentDirection` is same-package (no import). Add the two fields after `authToken`:

```java
    @Column(name = "return_id", length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID returnId;

    @Enumerated(EnumType.STRING)
    @Column(name = "txn_type", nullable = false, length = 16)
    private PaymentDirection direction;
```

Replace the existing constructor with one that takes `returnId` and `direction` (sale call sites pass `null`/`SALE` in Task — updated in Step 5):

```java
    public Payment(UUID id, UUID saleId, UUID returnId, PaymentMethod method, BigDecimal amount,
            BigDecimal amountTendered, BigDecimal changeDue, String currencyCode, String maskedPan,
            String authToken, PaymentDirection direction, Instant createdAt) {
        this.id = id;
        this.saleId = saleId;
        this.returnId = returnId;
        this.method = method;
        this.amount = amount;
        this.amountTendered = amountTendered;
        this.changeDue = changeDue;
        this.currencyCode = currencyCode;
        this.maskedPan = maskedPan;
        this.authToken = authToken;
        this.direction = direction;
        this.createdAt = createdAt;
    }
```

Add getters alongside the existing ones:

```java
    public UUID getReturnId() {
        return returnId;
    }

    public PaymentDirection getDirection() {
        return direction;
    }
```

- [ ] **Step 4: Add the repository queries**

In `src/main/java/com/company/pos/payment/infrastructure/PaymentRepository.java`, add the return query, and **replace** `findBySaleIdOrderByCreatedAtAsc` with a direction-filtered variant (so refund rows — which carry `sale_id = returnId` — never leak into the original sale's payment query):

```java
    List<Payment> findBySaleIdAndDirectionOrderByCreatedAtAsc(UUID saleId, PaymentDirection direction);

    List<Payment> findByReturnIdOrderByCreatedAtAsc(UUID returnId);
```

Update `DefaultPaymentService.findBySale` to call the new query with `PaymentDirection.SALE`:

```java
        return payments.findBySaleIdAndDirectionOrderByCreatedAtAsc(saleId, PaymentDirection.SALE)
```

> Correctness note (caught in review): the original `findBySaleIdOrderByCreatedAtAsc` is *not* kept. Because refund rows set `sale_id = returnId`, an unfiltered `findBySale` would return refund rows. The `SALE`-direction filter is behavior-preserving for the existing callers (`DefaultSalesService.getSale`/`reprint` only ever pass a real sale id, whose payments are all `SALE`).

- [ ] **Step 5: Extend the `PaymentService` API and fix sale call sites**

In `src/main/java/com/company/pos/payment/api/PaymentService.java`, add three methods:

```java
    PaymentView refundCash(UUID returnId, String currencyCode, BigDecimal amount);

    PaymentView refundTerminalPayment(UUID returnId, String currencyCode, BigDecimal amount,
            PaymentMethod method, String reference);

    List<PaymentView> findByReturn(UUID returnId);
```

In `src/main/java/com/company/pos/payment/application/DefaultPaymentService.java`:

(a) Add the import `import com.company.pos.payment.domain.PaymentDirection;`.

(b) Update the two existing `new Payment(...)` calls to pass `null` returnId and `PaymentDirection.SALE`:

In `recordCash`, replace the `new Payment(...)` with:

```java
        Payment payment = new Payment(Identifiers.newId(), saleId, null, PaymentMethod.CASH,
                due, tendered, change, currencyCode, null, null, PaymentDirection.SALE, Instant.now());
```

In `recordTerminalPayment`, replace the `new Payment(...)` with:

```java
        Payment payment = new Payment(Identifiers.newId(), saleId, null, method, due, due, ZERO,
                currencyCode, result.maskedPan(), result.token(), PaymentDirection.SALE, Instant.now());
```

(c) Add the three new methods (before the closing brace):

```java
    @Override
    public PaymentView refundCash(UUID returnId, String currencyCode, BigDecimal amount) {
        BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
        if (value.signum() <= 0) {
            throw DomainException.validation("Refund amount must be positive");
        }
        Payment payment = new Payment(Identifiers.newId(), returnId, returnId, PaymentMethod.CASH,
                value, value, ZERO, currencyCode, null, null, PaymentDirection.REFUND, Instant.now());
        payments.save(payment);
        return new PaymentView(returnId, PaymentMethod.CASH.name(), value, value, ZERO, null,
                currencyCode);
    }

    @Override
    public PaymentView refundTerminalPayment(UUID returnId, String currencyCode, BigDecimal amount,
            PaymentMethod method, String reference) {
        if (method != PaymentMethod.CARD && method != PaymentMethod.WALLET) {
            throw DomainException.validation("Method " + method + " is not terminal-mediated");
        }
        BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
        if (value.signum() <= 0) {
            throw DomainException.validation("Refund amount must be positive");
        }
        PaymentResult result = terminal.refund(
                new PaymentRequest(Monies.of(value, currencyCode), reference));
        if (!result.approved()) {
            throw DomainException.validation(method + " refund was declined");
        }
        Payment payment = new Payment(Identifiers.newId(), returnId, returnId, method, value, value,
                ZERO, currencyCode, result.maskedPan(), result.token(), PaymentDirection.REFUND,
                Instant.now());
        payments.save(payment);
        return new PaymentView(returnId, method.name(), value, value, ZERO, result.maskedPan(),
                currencyCode);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentView> findByReturn(UUID returnId) {
        return payments.findByReturnIdOrderByCreatedAtAsc(returnId).stream()
                .map(p -> new PaymentView(p.getReturnId(), p.getMethod().name(), p.getAmount(),
                        p.getAmountTendered(), p.getChangeDue(), p.getMaskedPan(),
                        p.getCurrencyCode()))
                .toList();
    }
```

- [ ] **Step 6: Write the refund test**

`src/test/java/com/company/pos/payment/RefundPaymentTest.java`:

```java
package com.company.pos.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.common.util.Identifiers;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.payment.api.PaymentService;
import com.company.pos.payment.api.PaymentView;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
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
 * Refund payments are recorded keyed by returnId, retrievable via findByReturn, and do NOT appear
 * in the original sale's payment list. Non-@Transactional (commits) + DatabaseCleaner.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class RefundPaymentTest {

    @Autowired
    PaymentService payments;
    @Autowired
    InMemoryPaymentTerminal terminal;
    @Autowired
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void clean() {
        databaseCleaner.clean();
        terminal.setApprove(true);
    }

    @AfterEach
    void cleanup() {
        databaseCleaner.clean();
        terminal.setApprove(true);
    }

    @Test
    void cashRefundIsRecordedUnderTheReturn() {
        UUID returnId = Identifiers.newId();

        PaymentView pv = payments.refundCash(returnId, "SAR", new BigDecimal("9.00"));

        assertThat(pv.method()).isEqualTo("CASH");
        assertThat(pv.amount()).isEqualByComparingTo("9.00");
        List<PaymentView> byReturn = payments.findByReturn(returnId);
        assertThat(byReturn).hasSize(1);
        // Refund rows are keyed by returnId, so the original sale's query stays clean.
        assertThat(payments.findBySale(returnId)).isEmpty();
    }

    @Test
    void terminalRefundGoesThroughTheTerminal() {
        UUID returnId = Identifiers.newId();

        PaymentView pv = payments.refundTerminalPayment(returnId, "SAR", new BigDecimal("4.50"),
                PaymentMethod.CARD, returnId.toString());

        assertThat(pv.method()).isEqualTo("CARD");
        assertThat(pv.maskedPan()).isNotBlank();
        assertThat(payments.findByReturn(returnId)).hasSize(1);
    }
}
```

> Note: `findBySale(returnId)` is empty because `findBySale` is filtered to `direction = SALE` (Step 4), and the only rows carrying `sale_id = returnId` are `REFUND`-direction. This is the test's way of proving refund rows don't pollute the original-sale payment query.

- [ ] **Step 7: Run the refund test**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=RefundPaymentTest`
Expected: PASS.

- [ ] **Step 8: Verify the migration validates on Postgres and payment/sales regressions pass**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=DatabaseStoreServerTest,RefundPaymentTest`
Expected: PASS — V16 validates against the `Payment` mapping on the Postgres Testcontainer (Flyway V1–V16). If `DatabaseStoreServerTest` fails on `payment` validation, reconcile column types (`txn_type` `VARCHAR(16) NOT NULL`, `return_id` `VARCHAR(36)`) with the entity and re-run. Also run the existing sale-path tests to confirm the constructor change didn't break checkout:
`JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CheckoutEndToEndTest` (or the full payment/sales tests — see Task 9 gate).

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/db/migration/payment/V16__payment_refund_fields.sql \
        src/main/java/com/company/pos/payment/ \
        src/test/java/com/company/pos/payment/RefundPaymentTest.java
git commit -m "feat(payment): refund tenders (cash + terminal) tagged REFUND, keyed by returnId"
```

---

### Task 3: `sales` — `SalesReturn` aggregate, migration, repositories, API types & event

Create the immutable return record, its tables, the over-return guard query, receipt lookup, and all `sales.api` types and the `ReturnCompleted` event. No service yet — this task's deliverable is the persistence layer + a repository test.

**Files:**
- Create: `src/main/resources/db/migration/sales/V17__sales_return.sql`
- Create: `src/main/java/com/company/pos/sales/domain/SalesReturn.java`
- Create: `src/main/java/com/company/pos/sales/domain/SalesReturnLine.java`
- Create: `src/main/java/com/company/pos/sales/infrastructure/SalesReturnRepository.java`
- Create: `src/main/java/com/company/pos/sales/infrastructure/SalesReturnLineRepository.java`
- Modify: `src/main/java/com/company/pos/sales/infrastructure/SaleRepository.java`
- Create: `src/main/java/com/company/pos/sales/api/ReturnCommand.java`
- Create: `src/main/java/com/company/pos/sales/api/ReturnView.java`
- Create: `src/main/java/com/company/pos/sales/api/SaleReturnLineView.java`
- Create: `src/main/java/com/company/pos/sales/api/ReturnPaymentView.java`
- Create: `src/main/java/com/company/pos/sales/api/ReturnCompleted.java`
- Test: `src/test/java/com/company/pos/sales/SalesReturnPersistenceTest.java`

**Interfaces:**
- Consumes: `DomainEvent`; JPA.
- Produces:
  - `SalesReturn` (aggregate) + `SalesReturnLine` (with `salesReturn` `@ManyToOne`, `originalLineNo`).
  - `SalesReturnRepository extends JpaRepository<SalesReturn, UUID>`.
  - `SalesReturnLineRepository` with `BigDecimal sumReturnedQuantity(UUID originalSaleId, int originalLineNo)` (0 when none).
  - `SaleRepository.findByReceiptNumber(String) : Optional<Sale>`.
  - `ReturnCommand(UUID originalSaleId, String receiptNumber, List<ReturnLineRequest> lines)`, `ReturnLineRequest(int lineNo, BigDecimal quantity)`.
  - `ReturnView(UUID id, String creditNoteNumber, UUID originalSaleId, String status, String currencyCode, BigDecimal refundSubtotal, BigDecimal refundTaxTotal, BigDecimal refundGrandTotal, Instant createdAt, List<SaleReturnLineView> lines, List<ReturnPaymentView> refunds)`.
  - `SaleReturnLineView(int lineNo, int originalLineNo, String sku, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxAmount, BigDecimal lineTotal, String currencyCode)`.
  - `ReturnPaymentView(String method, BigDecimal amount, String maskedPan)`.
  - `ReturnCompleted(UUID returnId, UUID originalSaleId, String creditNoteNumber, String terminalId, String locationCode, String currencyCode, BigDecimal refundGrandTotal, BigDecimal cashRefundTotal, List<ReturnedLine> lines)` + `ReturnedLine(String sku, BigDecimal quantity)`.

- [ ] **Step 1: Write the migration**

`src/main/resources/db/migration/sales/V17__sales_return.sql`:

```sql
CREATE TABLE sales_return (
    id                 VARCHAR(36) PRIMARY KEY,
    credit_note_number VARCHAR(40) NOT NULL UNIQUE,
    original_sale_id   VARCHAR(36) NOT NULL,
    store_id           VARCHAR(16) NOT NULL,
    terminal_id        VARCHAR(16) NOT NULL,
    manager_username   VARCHAR(100) NOT NULL,
    location_code      VARCHAR(32) NOT NULL,
    status             VARCHAR(16) NOT NULL,
    currency_code      VARCHAR(3) NOT NULL,
    refund_subtotal    NUMERIC(19, 2) NOT NULL,
    refund_tax_total   NUMERIC(19, 2) NOT NULL,
    refund_grand_total NUMERIC(19, 2) NOT NULL,
    created_at         TIMESTAMP NOT NULL
);

CREATE TABLE sales_return_line (
    id               VARCHAR(36) PRIMARY KEY,
    sales_return_id  VARCHAR(36) NOT NULL REFERENCES sales_return (id),
    line_no          INTEGER NOT NULL,
    original_line_no INTEGER NOT NULL,
    sku              VARCHAR(64) NOT NULL,
    name             VARCHAR(300) NOT NULL,
    quantity         NUMERIC(19, 3) NOT NULL,
    unit_price       NUMERIC(19, 4) NOT NULL,
    net_amount       NUMERIC(19, 2) NOT NULL,
    tax_amount       NUMERIC(19, 2) NOT NULL,
    line_total       NUMERIC(19, 2) NOT NULL,
    currency_code    VARCHAR(3) NOT NULL
);
```

- [ ] **Step 2: Write the `SalesReturn` aggregate**

`src/main/java/com/company/pos/sales/domain/SalesReturn.java`:

```java
package com.company.pos.sales.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sales_return")
public class SalesReturn {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "credit_note_number", nullable = false, unique = true, length = 40)
    private String creditNoteNumber;

    @Column(name = "original_sale_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID originalSaleId;

    @Column(name = "store_id", nullable = false, length = 16)
    private String storeId;

    @Column(name = "terminal_id", nullable = false, length = 16)
    private String terminalId;

    @Column(name = "manager_username", nullable = false, length = 100)
    private String managerUsername;

    @Column(name = "location_code", nullable = false, length = 32)
    private String locationCode;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "refund_subtotal", nullable = false, precision = 19, scale = 2)
    private BigDecimal refundSubtotal;

    @Column(name = "refund_tax_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal refundTaxTotal;

    @Column(name = "refund_grand_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal refundGrandTotal;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "salesReturn", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<SalesReturnLine> lines = new ArrayList<>();

    protected SalesReturn() {
        // JPA
    }

    public SalesReturn(UUID id, String creditNoteNumber, UUID originalSaleId, String storeId,
            String terminalId, String managerUsername, String locationCode, String currencyCode,
            BigDecimal refundSubtotal, BigDecimal refundTaxTotal, BigDecimal refundGrandTotal,
            Instant createdAt) {
        this.id = id;
        this.creditNoteNumber = creditNoteNumber;
        this.originalSaleId = originalSaleId;
        this.storeId = storeId;
        this.terminalId = terminalId;
        this.managerUsername = managerUsername;
        this.locationCode = locationCode;
        this.currencyCode = currencyCode;
        this.refundSubtotal = refundSubtotal;
        this.refundTaxTotal = refundTaxTotal;
        this.refundGrandTotal = refundGrandTotal;
        this.createdAt = createdAt;
        this.status = "COMPLETED";
    }

    public void addLine(SalesReturnLine line) {
        lines.add(line);
    }

    public UUID getId() {
        return id;
    }

    public String getCreditNoteNumber() {
        return creditNoteNumber;
    }

    public UUID getOriginalSaleId() {
        return originalSaleId;
    }

    public String getStatus() {
        return status;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public BigDecimal getRefundSubtotal() {
        return refundSubtotal;
    }

    public BigDecimal getRefundTaxTotal() {
        return refundTaxTotal;
    }

    public BigDecimal getRefundGrandTotal() {
        return refundGrandTotal;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<SalesReturnLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
```

- [ ] **Step 3: Write the `SalesReturnLine` entity**

`src/main/java/com/company/pos/sales/domain/SalesReturnLine.java`:

```java
package com.company.pos.sales.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sales_return_line")
public class SalesReturnLine {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "sales_return_id", nullable = false)
    private SalesReturn salesReturn;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "original_line_no", nullable = false)
    private int originalLineNo;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal lineTotal;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    protected SalesReturnLine() {
        // JPA
    }

    public SalesReturnLine(UUID id, SalesReturn salesReturn, int lineNo, int originalLineNo,
            String sku, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal netAmount,
            BigDecimal taxAmount, BigDecimal lineTotal, String currencyCode) {
        this.id = id;
        this.salesReturn = salesReturn;
        this.lineNo = lineNo;
        this.originalLineNo = originalLineNo;
        this.sku = sku;
        this.name = name;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.netAmount = netAmount;
        this.taxAmount = taxAmount;
        this.lineTotal = lineTotal;
        this.currencyCode = currencyCode;
    }

    public int getLineNo() {
        return lineNo;
    }

    public int getOriginalLineNo() {
        return originalLineNo;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }
}
```

- [ ] **Step 4: Write the repositories**

`src/main/java/com/company/pos/sales/infrastructure/SalesReturnRepository.java`:

```java
package com.company.pos.sales.infrastructure;

import com.company.pos.sales.domain.SalesReturn;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesReturnRepository extends JpaRepository<SalesReturn, UUID> {
}
```

`src/main/java/com/company/pos/sales/infrastructure/SalesReturnLineRepository.java`:

```java
package com.company.pos.sales.infrastructure;

import com.company.pos.sales.domain.SalesReturnLine;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalesReturnLineRepository extends JpaRepository<SalesReturnLine, UUID> {

    /** Total quantity already returned for an original sale line (0 when none). */
    @Query("select coalesce(sum(l.quantity), 0) from SalesReturnLine l "
            + "where l.salesReturn.originalSaleId = :saleId and l.originalLineNo = :lineNo")
    BigDecimal sumReturnedQuantity(@Param("saleId") UUID saleId, @Param("lineNo") int lineNo);
}
```

- [ ] **Step 5: Add receipt lookup to `SaleRepository`**

Replace `src/main/java/com/company/pos/sales/infrastructure/SaleRepository.java`:

```java
package com.company.pos.sales.infrastructure;

import com.company.pos.sales.domain.Sale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaleRepository extends JpaRepository<Sale, UUID> {

    Optional<Sale> findByReceiptNumber(String receiptNumber);
}
```

- [ ] **Step 6: Write the API DTOs and event**

`src/main/java/com/company/pos/sales/api/ReturnCommand.java`:

```java
package com.company.pos.sales.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Request to return lines of a prior sale. Identify the sale by id or receipt number. */
public record ReturnCommand(UUID originalSaleId, String receiptNumber, List<ReturnLineRequest> lines) {

    public record ReturnLineRequest(int lineNo, BigDecimal quantity) {
    }
}
```

`src/main/java/com/company/pos/sales/api/SaleReturnLineView.java`:

```java
package com.company.pos.sales.api;

import java.math.BigDecimal;

public record SaleReturnLineView(int lineNo, int originalLineNo, String sku, String name,
        BigDecimal quantity, BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxAmount,
        BigDecimal lineTotal, String currencyCode) {
}
```

`src/main/java/com/company/pos/sales/api/ReturnPaymentView.java`:

```java
package com.company.pos.sales.api;

import java.math.BigDecimal;

public record ReturnPaymentView(String method, BigDecimal amount, String maskedPan) {
}
```

`src/main/java/com/company/pos/sales/api/ReturnView.java`:

```java
package com.company.pos.sales.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReturnView(UUID id, String creditNoteNumber, UUID originalSaleId, String status,
        String currencyCode, BigDecimal refundSubtotal, BigDecimal refundTaxTotal,
        BigDecimal refundGrandTotal, Instant createdAt, List<SaleReturnLineView> lines,
        List<ReturnPaymentView> refunds) {
}
```

`src/main/java/com/company/pos/sales/api/ReturnCompleted.java`:

```java
package com.company.pos.sales.api;

import com.company.pos.common.events.DomainEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Published after a return commits; inventory/cashdrawer/sync reverse their sale effects. */
public record ReturnCompleted(UUID returnId, UUID originalSaleId, String creditNoteNumber,
        String terminalId, String locationCode, String currencyCode, BigDecimal refundGrandTotal,
        BigDecimal cashRefundTotal, List<ReturnedLine> lines) implements DomainEvent {

    public record ReturnedLine(String sku, BigDecimal quantity) {
    }
}
```

- [ ] **Step 7: Write the persistence test**

`src/test/java/com/company/pos/sales/SalesReturnPersistenceTest.java`:

```java
package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.common.util.Identifiers;
import com.company.pos.sales.domain.SalesReturn;
import com.company.pos.sales.domain.SalesReturnLine;
import com.company.pos.sales.infrastructure.SalesReturnLineRepository;
import com.company.pos.sales.infrastructure.SalesReturnRepository;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Persists a SalesReturn with one line and proves the over-return sum query aggregates by original
 * sale + original line. Non-@Transactional (commits) + DatabaseCleaner.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class SalesReturnPersistenceTest {

    @Autowired
    SalesReturnRepository returns;
    @Autowired
    SalesReturnLineRepository returnLines;
    @Autowired
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void clean() {
        databaseCleaner.clean();
    }

    @AfterEach
    void cleanup() {
        databaseCleaner.clean();
    }

    @Test
    void sumReturnedQuantityAggregatesByOriginalLine() {
        UUID originalSaleId = Identifiers.newId();
        assertThat(returnLines.sumReturnedQuantity(originalSaleId, 1)).isEqualByComparingTo("0");

        SalesReturn r = new SalesReturn(Identifiers.newId(), "S01-T01R-000001", originalSaleId,
                "S01", "T01", "manager", "MAIN", "SAR", new BigDecimal("4.50"),
                new BigDecimal("0.68"), new BigDecimal("5.18"), Instant.now());
        r.addLine(new SalesReturnLine(Identifiers.newId(), r, 1, 1, "COLA", "Cola Can",
                new BigDecimal("1.000"), new BigDecimal("4.5000"), new BigDecimal("4.50"),
                new BigDecimal("0.68"), new BigDecimal("5.18"), "SAR"));
        returns.save(r);

        assertThat(returnLines.sumReturnedQuantity(originalSaleId, 1)).isEqualByComparingTo("1.000");
        assertThat(returnLines.sumReturnedQuantity(originalSaleId, 2)).isEqualByComparingTo("0");
    }
}
```

- [ ] **Step 8: Run the persistence test**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=SalesReturnPersistenceTest`
Expected: PASS — tables auto-created under embedded; the sum query returns the persisted quantity and 0 for an unreturned line.

- [ ] **Step 9: Verify the migration validates on Postgres**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=DatabaseStoreServerTest`
Expected: PASS — V17 creates `sales_return`/`sales_return_line` on the Postgres Testcontainer and Hibernate `validate` matches the entity mappings (Flyway V1–V17).

- [ ] **Step 10: Commit**

```bash
git add src/main/resources/db/migration/sales/V17__sales_return.sql \
        src/main/java/com/company/pos/sales/domain/SalesReturn.java \
        src/main/java/com/company/pos/sales/domain/SalesReturnLine.java \
        src/main/java/com/company/pos/sales/infrastructure/ \
        src/main/java/com/company/pos/sales/api/ReturnCommand.java \
        src/main/java/com/company/pos/sales/api/ReturnView.java \
        src/main/java/com/company/pos/sales/api/SaleReturnLineView.java \
        src/main/java/com/company/pos/sales/api/ReturnPaymentView.java \
        src/main/java/com/company/pos/sales/api/ReturnCompleted.java \
        src/test/java/com/company/pos/sales/SalesReturnPersistenceTest.java
git commit -m "feat(sales): SalesReturn aggregate, over-return query, receipt lookup, return API types"
```

---

### Task 4: `sales` — `ReturnService` write path

Add the service that validates a return, computes proportional refunds, tenders them on the original methods, persists the immutable `SalesReturn`, publishes `ReturnCompleted`, and prints the credit note.

**Files:**
- Create: `src/main/java/com/company/pos/sales/api/ReturnService.java`
- Create: `src/main/java/com/company/pos/sales/application/DefaultReturnService.java`
- Test: `src/test/java/com/company/pos/sales/ReturnServiceTest.java`

**Interfaces:**
- Consumes: `SaleRepository.findById`/`findByReceiptNumber`; `SalesReturnRepository`, `SalesReturnLineRepository.sumReturnedQuantity`; `PaymentService.findBySale`/`refundCash`/`refundTerminalPayment`/`findByReturn`; `ReceiptService.print`; `ConfigurationService.getString`; `ReceiptNumbering.nextReceiptNumber`; `DomainEvents.publish`; `ReturnCommand`, `ReturnView`, `ReturnCompleted`.
- Produces: `ReturnService.processReturn(ReturnCommand, String managerUsername) : ReturnView`; `ReturnService.getReturn(UUID) : ReturnView`.

- [ ] **Step 1: Write the `ReturnService` interface**

`src/main/java/com/company/pos/sales/api/ReturnService.java`:

```java
package com.company.pos.sales.api;

import java.util.UUID;

public interface ReturnService {

    ReturnView processReturn(ReturnCommand command, String managerUsername);

    ReturnView getReturn(UUID returnId);
}
```

- [ ] **Step 2: Write the failing service test**

`src/test/java/com/company/pos/sales/ReturnServiceTest.java`:

```java
package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.cart.api.CartService;
import com.company.pos.common.exception.DomainException;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.inventory.api.InventorySync;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.ReturnCommand;
import com.company.pos.sales.api.ReturnService;
import com.company.pos.sales.api.ReturnView;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
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

/**
 * Drives a real cash sale, then exercises the return write path directly (synchronous):
 * proportional refund math, the over-return guard, and the recorded refund tender. After-commit
 * stock/drawer/ERP reversal is covered in Tasks 6-9. Non-@Transactional + DatabaseCleaner.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class ReturnServiceTest {

    @Autowired
    SalesService sales;
    @Autowired
    ReturnService returns;
    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    InventorySync inventorySync;
    @Autowired
    InMemoryPaymentTerminal terminal;
    @Autowired
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
        fake.clear();
        terminal.setApprove(true);
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("20"), 1));
        productSync.sync();
        inventorySync.sync();
    }

    @AfterEach
    void cleanup() {
        databaseCleaner.clean();
        terminal.setApprove(true);
    }

    /** Sell 2x COLA for cash, then return 1. */
    private SaleView sellTwoColasForCash() {
        var cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        return sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100")))), "cashier");
    }

    @Test
    void returningOneOfTwoRefundsHalfProportionally() {
        // 2x COLA @ 4.50 net = 9.00, +15% VAT (default config) = 1.35 -> grand 10.35.
        // Return 1 of 2: refundNet = 4.50, refundTax = 0.675 -> 0.68 (HALF_UP), refund total = 5.18.
        SaleView sale = sellTwoColasForCash();
        assertThat(sale.grandTotal()).isEqualByComparingTo("10.35");

        ReturnView ret = returns.processReturn(new ReturnCommand(sale.id(), null,
                List.of(new ReturnCommand.ReturnLineRequest(1, new BigDecimal("1")))), "manager");

        assertThat(ret.refundGrandTotal()).isEqualByComparingTo("5.18");
        assertThat(ret.refundSubtotal()).isEqualByComparingTo("4.50");
        assertThat(ret.refundTaxTotal()).isEqualByComparingTo("0.68");
        assertThat(ret.lines()).hasSize(1);
        assertThat(ret.lines().get(0).quantity()).isEqualByComparingTo("1");
        assertThat(ret.refunds()).hasSize(1);
        assertThat(ret.refunds().get(0).method()).isEqualTo("CASH");
        assertThat(ret.refunds().get(0).amount()).isEqualByComparingTo("5.18");
    }

    @Test
    void overReturnIsRejectedAcrossTwoPartialReturns() {
        SaleView sale = sellTwoColasForCash();
        returns.processReturn(new ReturnCommand(sale.id(), null,
                List.of(new ReturnCommand.ReturnLineRequest(1, new BigDecimal("1")))), "manager");

        // Second return of 2 would make cumulative 3 > 2 sold.
        assertThatThrownBy(() -> returns.processReturn(new ReturnCommand(sale.id(), null,
                List.of(new ReturnCommand.ReturnLineRequest(1, new BigDecimal("2")))), "manager"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void lookupByReceiptNumberResolvesTheSale() {
        SaleView sale = sellTwoColasForCash();

        ReturnView ret = returns.processReturn(new ReturnCommand(null, sale.receiptNumber(),
                List.of(new ReturnCommand.ReturnLineRequest(1, new BigDecimal("1")))), "manager");

        assertThat(ret.originalSaleId()).isEqualTo(sale.id());
    }

    @Test
    void getReturnReturnsThePersistedRecord() {
        SaleView sale = sellTwoColasForCash();
        ReturnView created = returns.processReturn(new ReturnCommand(sale.id(), null,
                List.of(new ReturnCommand.ReturnLineRequest(1, new BigDecimal("1")))), "manager");

        ReturnView fetched = returns.getReturn(created.id());

        assertThat(fetched.creditNoteNumber()).isEqualTo(created.creditNoteNumber());
        assertThat(fetched.refunds()).hasSize(1);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ReturnServiceTest`
Expected: FAIL — `ReturnService` has no implementation bean (`DefaultReturnService` does not exist yet); context/autowire fails.

- [ ] **Step 4: Write `DefaultReturnService`**

`src/main/java/com/company/pos/sales/application/DefaultReturnService.java`:

```java
package com.company.pos.sales.application;

import com.company.pos.common.events.DomainEvents;
import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.payment.api.PaymentService;
import com.company.pos.payment.api.PaymentView;
import com.company.pos.receipt.api.ReceiptData;
import com.company.pos.receipt.api.ReceiptLineData;
import com.company.pos.receipt.api.ReceiptPaymentData;
import com.company.pos.receipt.api.ReceiptService;
import com.company.pos.sales.api.ReturnCommand;
import com.company.pos.sales.api.ReturnCompleted;
import com.company.pos.sales.api.ReturnPaymentView;
import com.company.pos.sales.api.ReturnService;
import com.company.pos.sales.api.ReturnView;
import com.company.pos.sales.api.SaleReturnLineView;
import com.company.pos.sales.domain.Sale;
import com.company.pos.sales.domain.SaleLine;
import com.company.pos.sales.domain.SalesReturn;
import com.company.pos.sales.domain.SalesReturnLine;
import com.company.pos.sales.infrastructure.SaleRepository;
import com.company.pos.sales.infrastructure.SalesReturnLineRepository;
import com.company.pos.sales.infrastructure.SalesReturnRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultReturnService implements ReturnService {

    private static final Logger log = LoggerFactory.getLogger(DefaultReturnService.class);
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final SaleRepository sales;
    private final SalesReturnRepository returns;
    private final SalesReturnLineRepository returnLines;
    private final PaymentService payments;
    private final ReceiptService receipts;
    private final ConfigurationService config;
    private final ReceiptNumbering numbering;
    private final DomainEvents events;

    DefaultReturnService(SaleRepository sales, SalesReturnRepository returns,
            SalesReturnLineRepository returnLines, PaymentService payments, ReceiptService receipts,
            ConfigurationService config, ReceiptNumbering numbering, DomainEvents events) {
        this.sales = sales;
        this.returns = returns;
        this.returnLines = returnLines;
        this.payments = payments;
        this.receipts = receipts;
        this.config = config;
        this.numbering = numbering;
        this.events = events;
    }

    @Override
    public ReturnView processReturn(ReturnCommand command, String managerUsername) {
        Sale sale = resolveSale(command);
        if (command.lines() == null || command.lines().isEmpty()) {
            throw DomainException.validation("A return must have at least one line");
        }

        UUID returnId = Identifiers.newId();
        String currency = sale.getCurrencyCode();
        String storeId = config.getString(SettingKey.STORE_ID);
        String terminalId = config.getString(SettingKey.TERMINAL_ID);
        String location = config.getString(SettingKey.INVENTORY_LOCATION);

        // 1. Validate each requested line and compute its proportional refund.
        List<ReturnCompleted.ReturnedLine> returnedLines = new ArrayList<>();
        BigDecimal refundSubtotal = ZERO;
        BigDecimal refundTaxTotal = ZERO;
        BigDecimal refundGrandTotal = ZERO;
        int lineNo = 1;

        SalesReturn salesReturn = new SalesReturn(returnId,
                numbering.nextReceiptNumber(storeId, terminalId + "R"), sale.getId(), storeId,
                terminalId, managerUsername, location, currency, ZERO, ZERO, ZERO, Instant.now());

        for (ReturnCommand.ReturnLineRequest req : command.lines()) {
            SaleLine original = findOriginalLine(sale, req.lineNo());
            BigDecimal requested = req.quantity();
            if (requested == null || requested.signum() <= 0) {
                throw DomainException.validation("Return quantity must be positive for line "
                        + req.lineNo());
            }
            BigDecimal sold = original.getQuantity();
            BigDecimal already = returnLines.sumReturnedQuantity(sale.getId(), req.lineNo());
            if (already.add(requested).compareTo(sold) > 0) {
                throw DomainException.conflict("Line " + req.lineNo() + " return exceeds sold quantity"
                        + " (sold " + sold + ", already returned " + already + ", requested " + requested + ")");
            }

            BigDecimal refundNet = proportion(original.getNetAmount(), requested, sold);
            BigDecimal refundTax = proportion(original.getTaxAmount(), requested, sold);
            BigDecimal refundLineTotal = refundNet.add(refundTax);
            refundSubtotal = refundSubtotal.add(refundNet);
            refundTaxTotal = refundTaxTotal.add(refundTax);
            refundGrandTotal = refundGrandTotal.add(refundLineTotal);

            salesReturn.addLine(new SalesReturnLine(Identifiers.newId(), salesReturn, lineNo++,
                    original.getLineNo(), original.getSku(), original.getName(), requested,
                    original.getUnitPrice(), refundNet, refundTax, refundLineTotal, currency));
            returnedLines.add(new ReturnCompleted.ReturnedLine(original.getSku(), requested));
        }

        // 2. Allocate the refund across the original tenders and execute each.
        List<PaymentView> originalPayments = payments.findBySale(sale.getId());
        BigDecimal originalGrand = sale.getGrandTotal().setScale(2, RoundingMode.HALF_UP);
        BigDecimal allocated = ZERO;
        BigDecimal cashRefundTotal = ZERO;
        for (int i = 0; i < originalPayments.size(); i++) {
            PaymentView op = originalPayments.get(i);
            BigDecimal portion = (i == originalPayments.size() - 1)
                    ? refundGrandTotal.subtract(allocated)
                    : refundGrandTotal.multiply(op.amount())
                            .divide(originalGrand, 2, RoundingMode.HALF_UP);
            allocated = allocated.add(portion);
            if (portion.signum() <= 0) {
                continue;
            }
            PaymentMethod method = PaymentMethod.valueOf(op.method());
            if (method == PaymentMethod.CASH) {
                payments.refundCash(returnId, currency, portion);
                cashRefundTotal = cashRefundTotal.add(portion);
            } else {
                payments.refundTerminalPayment(returnId, currency, portion, method,
                        returnId.toString());
            }
        }

        // 3. Persist the immutable return (refund totals known now).
        SalesReturn finalReturn = new SalesReturn(returnId, salesReturn.getCreditNoteNumber(),
                sale.getId(), storeId, terminalId, managerUsername, location, currency,
                refundSubtotal, refundTaxTotal, refundGrandTotal, Instant.now());
        for (SalesReturnLine l : salesReturn.getLines()) {
            finalReturn.addLine(new SalesReturnLine(Identifiers.newId(), finalReturn, l.getLineNo(),
                    l.getOriginalLineNo(), l.getSku(), l.getName(), l.getQuantity(),
                    l.getUnitPrice(), l.getNetAmount(), l.getTaxAmount(), l.getLineTotal(),
                    l.getCurrencyCode()));
        }
        returns.save(finalReturn);

        // 4. Publish the reversal event (recorded to the outbox for after-commit fan-out).
        events.publish(new ReturnCompleted(returnId, sale.getId(), finalReturn.getCreditNoteNumber(),
                terminalId, location, currency, refundGrandTotal, cashRefundTotal, returnedLines));

        // 5. Print the credit note (best-effort — never fails the return).
        printCreditNote(finalReturn);

        return toView(finalReturn);
    }

    @Override
    @Transactional(readOnly = true)
    public ReturnView getReturn(UUID returnId) {
        SalesReturn r = returns.findById(returnId)
                .orElseThrow(() -> DomainException.notFound("No return " + returnId));
        return toView(r);
    }

    private Sale resolveSale(ReturnCommand command) {
        if (command.originalSaleId() != null) {
            return sales.findById(command.originalSaleId())
                    .orElseThrow(() -> DomainException.notFound("No sale " + command.originalSaleId()));
        }
        if (command.receiptNumber() != null && !command.receiptNumber().isBlank()) {
            return sales.findByReceiptNumber(command.receiptNumber())
                    .orElseThrow(() -> DomainException.notFound(
                            "No sale with receipt " + command.receiptNumber()));
        }
        throw DomainException.validation("A return must reference a sale id or receipt number");
    }

    private SaleLine findOriginalLine(Sale sale, int lineNo) {
        return sale.getLines().stream()
                .filter(l -> l.getLineNo() == lineNo)
                .findFirst()
                .orElseThrow(() -> DomainException.validation(
                        "Sale " + sale.getId() + " has no line " + lineNo));
    }

    private BigDecimal proportion(BigDecimal originalAmount, BigDecimal returnQty, BigDecimal soldQty) {
        return originalAmount.multiply(returnQty).divide(soldQty, 2, RoundingMode.HALF_UP);
    }

    private void printCreditNote(SalesReturn r) {
        try {
            List<ReceiptLineData> lines = r.getLines().stream()
                    .map(l -> new ReceiptLineData(l.getName(), l.getQuantity(), l.getUnitPrice(),
                            l.getLineTotal()))
                    .toList();
            List<ReceiptPaymentData> pays = payments.findByReturn(r.getId()).stream()
                    .map(p -> new ReceiptPaymentData(p.method(), p.amount(), p.amountTendered(),
                            p.changeDue(), p.maskedPan()))
                    .toList();
            receipts.print(new ReceiptData(r.getCreditNoteNumber(), "RETURN", r.getCreatedAt(), lines,
                    r.getRefundSubtotal(), r.getRefundTaxTotal(), r.getRefundGrandTotal(), pays,
                    r.getCurrencyCode()));
        } catch (RuntimeException ex) {
            log.warn("Credit-note print failed for return {} ({}) — return is recorded",
                    r.getId(), r.getCreditNoteNumber(), ex);
        }
    }

    private ReturnView toView(SalesReturn r) {
        List<SaleReturnLineView> lines = new ArrayList<>();
        for (SalesReturnLine l : r.getLines()) {
            lines.add(new SaleReturnLineView(l.getLineNo(), l.getOriginalLineNo(), l.getSku(),
                    l.getName(), l.getQuantity(), l.getUnitPrice(), l.getNetAmount(), l.getTaxAmount(),
                    l.getLineTotal(), l.getCurrencyCode()));
        }
        List<ReturnPaymentView> refunds = payments.findByReturn(r.getId()).stream()
                .map(p -> new ReturnPaymentView(p.method(), p.amount(), p.maskedPan()))
                .toList();
        return new ReturnView(r.getId(), r.getCreditNoteNumber(), r.getOriginalSaleId(),
                r.getStatus(), r.getCurrencyCode(), r.getRefundSubtotal(), r.getRefundTaxTotal(),
                r.getRefundGrandTotal(), r.getCreatedAt(), lines, refunds);
    }
}
```

> Simplification note for the implementer: the two-pass `SalesReturn` construction above (build to gather totals, then rebuild with totals) keeps the aggregate immutable. If you prefer, gather the line refund amounts into local lists first and construct `SalesReturn` once with the final totals and lines — same result, fewer objects. Either is acceptable; keep the persisted record's totals equal to the sum of its line totals.

- [ ] **Step 5: Run the test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ReturnServiceTest`
Expected: PASS — proportional half-refund, over-return rejection, receipt-number lookup, and `getReturn` all green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/sales/api/ReturnService.java \
        src/main/java/com/company/pos/sales/application/DefaultReturnService.java \
        src/test/java/com/company/pos/sales/ReturnServiceTest.java
git commit -m "feat(sales): ReturnService — proportional refund, over-return guard, ReturnCompleted"
```

---

### Task 5: `sales` — `ReturnController` (MANAGER-only REST)

Expose the return write/read over REST, guarded so only managers can refund.

**Files:**
- Create: `src/main/java/com/company/pos/sales/web/ReturnController.java`
- Test: `src/test/java/com/company/pos/sales/ReturnControllerSecurityTest.java`

**Interfaces:**
- Consumes: `ReturnService`; `ReturnCommand`/`ReturnView`; `Principal`.
- Produces: `POST /returns` (`201`, `hasRole('MANAGER')`); `GET /returns/{returnId}` (`hasRole('MANAGER')`).

- [ ] **Step 1: Write the controller**

`src/main/java/com/company/pos/sales/web/ReturnController.java`:

```java
package com.company.pos.sales.web;

import com.company.pos.sales.api.ReturnCommand;
import com.company.pos.sales.api.ReturnService;
import com.company.pos.sales.api.ReturnView;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ReturnController {

    private final ReturnService returns;

    ReturnController(ReturnService returns) {
        this.returns = returns;
    }

    @PostMapping("/returns")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MANAGER')")
    ReturnView process(@RequestBody ReturnCommand command, Principal principal) {
        return returns.processReturn(command, principal.getName());
    }

    @GetMapping("/returns/{returnId}")
    @PreAuthorize("hasRole('MANAGER')")
    ReturnView get(@PathVariable UUID returnId) {
        return returns.getReturn(returnId);
    }
}
```

- [ ] **Step 2: Write the failing security test**

`src/test/java/com/company/pos/sales/ReturnControllerSecurityTest.java`:

```java
package com.company.pos.sales;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.util.Identifiers;
import com.company.pos.support.DatabaseCleaner;
import com.jayway.jsonpath.JsonPath;
import java.util.Set;
import java.util.UUID;
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
 * A cashier is forbidden from POST /returns; a manager is authorized (passes the security layer —
 * here the body references a non-existent sale, so a manager gets 404, NOT 403). Proves the
 * MANAGER-only guard without needing a full sale fixture.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class ReturnControllerSecurityTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
        users.save(new User(Identifiers.newId(), "cashier", "Cashier One",
                encoder.encode("pw"), Set.of(Role.CASHIER)));
        users.save(new User(Identifiers.newId(), "manager", "Store Manager",
                encoder.encode("pw"), Set.of(Role.MANAGER)));
    }

    @AfterEach
    void cleanup() {
        databaseCleaner.clean();
    }

    @Test
    void cashierIsForbiddenFromProcessingReturns() throws Exception {
        String token = login("cashier");
        mvc.perform(post("/returns").header("Authorization", token)
                        .contentType("application/json")
                        .content(body(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerPassesSecurity() throws Exception {
        String token = login("manager");
        // Non-existent sale id -> service throws notFound -> 404 (NOT 403): the guard let the manager in.
        mvc.perform(post("/returns").header("Authorization", token)
                        .contentType("application/json")
                        .content(body(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    private String body(UUID saleId) {
        return "{\"originalSaleId\":\"" + saleId + "\",\"lines\":[{\"lineNo\":1,\"quantity\":1}]}";
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

- [ ] **Step 3: Run the security test**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ReturnControllerSecurityTest`
Expected: PASS — cashier `403`; manager `404` (authorized past the guard, then the missing sale yields not-found). If the manager case returns `403`, the role-prefix/`hasRole` wiring is wrong — confirm `JwtSupportConfig` sets prefix `ROLE_` and the user has `MANAGER`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/company/pos/sales/web/ReturnController.java \
        src/test/java/com/company/pos/sales/ReturnControllerSecurityTest.java
git commit -m "feat(sales): MANAGER-only POST /returns and GET /returns/{id}"
```

---

### Task 6: `inventory` — restock on `ReturnCompleted`

Add a second after-commit listener that adds returned quantities back to on-hand and appends a positive `"RETURN"` movement.

**Files:**
- Create: `src/main/java/com/company/pos/inventory/application/ReturnCompletedListener.java`
- Test: `src/test/java/com/company/pos/inventory/ReturnRestocksTest.java`

**Interfaces:**
- Consumes: `ReturnCompleted` (sales :: api — already an allowed dependency of `inventory`); `StockLevelRepository`, `StockMovementRepository`; `Identifiers`.
- Produces: `ReturnCompletedListener` (`@ApplicationModuleListener void on(ReturnCompleted)`).

- [ ] **Step 1: Write the listener**

`src/main/java/com/company/pos/inventory/application/ReturnCompletedListener.java`:

```java
package com.company.pos.inventory.application;

import com.company.pos.common.util.Identifiers;
import com.company.pos.inventory.domain.StockLevel;
import com.company.pos.inventory.domain.StockMovement;
import com.company.pos.inventory.infrastructure.StockLevelRepository;
import com.company.pos.inventory.infrastructure.StockMovementRepository;
import com.company.pos.sales.api.ReturnCompleted;
import java.time.Instant;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Adds returned quantities back to on-hand and appends a positive RETURN movement when a return
 * completes (after-commit, async, own transaction; mirrors {@link SaleCompletedListener}). A return
 * only increases stock, so there is no negative-stock warning and no low-stock emission.
 */
@Component
class ReturnCompletedListener {

    private final StockLevelRepository stock;
    private final StockMovementRepository movements;

    ReturnCompletedListener(StockLevelRepository stock, StockMovementRepository movements) {
        this.stock = stock;
        this.movements = movements;
    }

    @ApplicationModuleListener
    void on(ReturnCompleted event) {
        for (ReturnCompleted.ReturnedLine line : event.lines()) {
            StockLevel level = stock.findBySkuAndLocationCode(line.sku(), event.locationCode())
                    .orElseGet(() -> stock.save(
                            new StockLevel(Identifiers.newId(), line.sku(), event.locationCode())));
            level.setQuantityOnHand(level.getQuantityOnHand().add(line.quantity()));
            movements.save(new StockMovement(Identifiers.newId(), line.sku(), event.locationCode(),
                    line.quantity(), "RETURN", event.returnId().toString(), Instant.now()));
        }
    }
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/com/company/pos/inventory/ReturnRestocksTest.java`:

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
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.ReturnCommand;
import com.company.pos.sales.api.ReturnService;
import com.company.pos.sales.api.SaleView;
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

/**
 * A completed return adds stock back: sell 2 of 20 (-> 18), return 1 (-> 19). Non-@Transactional +
 * DatabaseCleaner + Awaitility (the restock runs in an after-commit async listener).
 */
@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class ReturnRestocksTest {

    @Autowired
    SalesService sales;
    @Autowired
    ReturnService returns;
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

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
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
    }

    @Test
    void returnAddsStockBack() {
        var cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100")))), "cashier");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(inventory.onHand("COLA").orElseThrow().quantityOnHand())
                        .isEqualByComparingTo("18"));

        returns.processReturn(new ReturnCommand(sale.id(), null,
                List.of(new ReturnCommand.ReturnLineRequest(1, new BigDecimal("1")))), "manager");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(inventory.onHand("COLA").orElseThrow().quantityOnHand())
                        .isEqualByComparingTo("19"));
    }
}
```

- [ ] **Step 3: Run the test (red, then green after Step 1 is in place)**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ReturnRestocksTest`
Expected: PASS — on-hand returns to 19 after the return. (If the listener were missing, the second Awaitility would time out at 18.)

- [ ] **Step 4: Verify boundaries unaffected**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ModularityTests`
Expected: PASS — `inventory` already allows `sales :: api`; no new dependency, no cycle.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/pos/inventory/application/ReturnCompletedListener.java \
        src/test/java/com/company/pos/inventory/ReturnRestocksTest.java
git commit -m "feat(inventory): restock + RETURN movement on ReturnCompleted"
```

---

### Task 7: `cashdrawer` — cash refund pay-out on `ReturnCompleted`

Add `recordCashRefund` (no-op when no open session, like `recordCashSale`) and a listener that pays the cash refund out of the open drawer.

**Files:**
- Modify: `src/main/java/com/company/pos/cashdrawer/api/CashDrawerService.java`
- Modify: `src/main/java/com/company/pos/cashdrawer/application/DefaultCashDrawerService.java`
- Create: `src/main/java/com/company/pos/cashdrawer/application/ReturnCompletedCashListener.java`
- Test: `src/test/java/com/company/pos/cashdrawer/ReturnCashRefundTest.java`

**Interfaces:**
- Consumes: `ReturnCompleted` (sales :: api — already allowed by `cashdrawer`); `DrawerSessionRepository`, `CashMovementRepository`.
- Produces: `CashDrawerService.recordCashRefund(String terminalId, BigDecimal amount, String reference)` (no-op when no open session; appends a `PAY_OUT` movement otherwise); `ReturnCompletedCashListener`.

- [ ] **Step 1: Add `recordCashRefund` to the interface**

In `src/main/java/com/company/pos/cashdrawer/api/CashDrawerService.java`, add after `recordCashSale`:

```java
    void recordCashRefund(String terminalId, BigDecimal amount, String reference);
```

- [ ] **Step 2: Implement `recordCashRefund`**

In `src/main/java/com/company/pos/cashdrawer/application/DefaultCashDrawerService.java`, add after `recordCashSale` (it mirrors that method's no-open-session tolerance; a refund is cash leaving the drawer, recorded as a `PAY_OUT` so reconciliation subtracts it):

```java
    @Override
    public void recordCashRefund(String terminalId, BigDecimal amount, String reference) {
        Optional<DrawerSession> open = sessions.findByTerminalIdAndStatus(terminalId, "OPEN");
        if (open.isEmpty()) {
            log.info("Cash refund {} on terminal {} not captured — no open drawer session",
                    reference, terminalId);
            return;
        }
        appendMovement(open.get().getId(), "PAY_OUT", scale(amount), reference, null);
        device.open();
    }
```

- [ ] **Step 3: Write the listener**

`src/main/java/com/company/pos/cashdrawer/application/ReturnCompletedCashListener.java`:

```java
package com.company.pos.cashdrawer.application;

import com.company.pos.cashdrawer.api.CashDrawerService;
import com.company.pos.sales.api.ReturnCompleted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Pays the cash portion of a completed return out of the terminal's open drawer (after-commit,
 * async, own transaction; mirrors {@link SaleCompletedCashListener}). {@code recordCashRefund} is a
 * no-op when no session is open, so a cash refund processed without an open drawer is simply not
 * captured (not an error) and never wedges the publication.
 */
@Component
class ReturnCompletedCashListener {

    private final CashDrawerService drawer;

    ReturnCompletedCashListener(CashDrawerService drawer) {
        this.drawer = drawer;
    }

    @ApplicationModuleListener
    void on(ReturnCompleted event) {
        if (event.cashRefundTotal() == null || event.cashRefundTotal().signum() <= 0) {
            return; // nothing refunded in cash
        }
        drawer.recordCashRefund(event.terminalId(), event.cashRefundTotal(),
                event.returnId().toString());
    }
}
```

- [ ] **Step 4: Write the failing test**

`src/test/java/com/company/pos/cashdrawer/ReturnCashRefundTest.java`:

```java
package com.company.pos.cashdrawer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.cart.api.CartService;
import com.company.pos.cashdrawer.api.CashDrawerService;
import com.company.pos.cashdrawer.api.DrawerReconciliation;
import com.company.pos.cashdrawer.api.DrawerSessionView;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.inventory.api.InventorySync;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.ReturnCommand;
import com.company.pos.sales.api.ReturnService;
import com.company.pos.sales.api.SaleView;
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

/**
 * With an open drawer on the configured terminal, a cash return pays out of it: the reconciliation's
 * pay-out total rises by the refunded amount. Non-@Transactional + DatabaseCleaner + Awaitility.
 * The drawer is opened on "T01", which is the default config TERMINAL_ID the sale/return both use.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class ReturnCashRefundTest {

    @Autowired
    SalesService sales;
    @Autowired
    ReturnService returns;
    @Autowired
    CartService carts;
    @Autowired
    CashDrawerService drawer;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    InventorySync inventorySync;
    @Autowired
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
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
    }

    @Test
    void cashReturnPaysOutOfTheOpenDrawer() {
        DrawerSessionView session = drawer.openSession("T01", new BigDecimal("100.00"), "SAR", "manager");

        var cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100")))), "cashier");

        // Wait until the cash sale has been captured into the drawer.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(drawer.reconcile(session.id()).cashSales()).isEqualByComparingTo(sale.grandTotal()));

        // Return 1 of 2 -> cash refund 5.18 (4.50 net + 0.68 tax) paid out of the drawer.
        returns.processReturn(new ReturnCommand(sale.id(), null,
                List.of(new ReturnCommand.ReturnLineRequest(1, new BigDecimal("1")))), "manager");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            DrawerReconciliation rec = drawer.reconcile(session.id());
            assertThat(rec.payOuts()).isEqualByComparingTo("5.18");
        });
    }
}
```

> Note on `DrawerReconciliation` accessors: this test uses `cashSales()` and `payOuts()`. Confirm those component names against `com.company.pos.cashdrawer.api.DrawerReconciliation` when implementing (it is a record built in `reconcileSession` with `cashSales` and `payOuts` fields). If a name differs, use the actual accessor — do not invent one.

- [ ] **Step 5: Run the test**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ReturnCashRefundTest`
Expected: PASS — pay-outs equal the refunded half after the return's async listener runs.

- [ ] **Step 6: Verify boundaries unaffected**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ModularityTests`
Expected: PASS — `cashdrawer` already allows `sales :: api`; no new dependency.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/pos/cashdrawer/ \
        src/test/java/com/company/pos/cashdrawer/ReturnCashRefundTest.java
git commit -m "feat(cashdrawer): pay cash refunds out of the open drawer on ReturnCompleted"
```

---

### Task 8: `integration` + `sync` — upload the credit note to the ERP

Add the ERP credit-note contract and an after-commit listener that uploads the return (idempotent on `returnId`), with positive `"RETURN"` stock movements.

**Files:**
- Create: `src/main/java/com/company/pos/integration/api/ReturnUpload.java`
- Modify: `src/main/java/com/company/pos/integration/api/ErpClient.java`
- Modify: `src/main/java/com/company/pos/integration/erp/FakeErpClient.java`
- Create: `src/main/java/com/company/pos/sync/application/ReturnUploadListener.java`
- Test: `src/test/java/com/company/pos/sync/ReturnUploadTest.java`

**Interfaces:**
- Consumes: `ReturnCompleted` (sales :: api — already allowed by `sync`); `ReturnService.getReturn`; `ErpClient`.
- Produces:
  - `ReturnUpload(UUID returnId, String creditNoteNumber, UUID originalSaleId, String terminalId, String locationCode, String currencyCode, BigDecimal refundSubtotal, BigDecimal refundTaxTotal, BigDecimal refundGrandTotal, Instant createdAt, List<Line> lines)` + `Line(int lineNo, int originalLineNo, String sku, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxAmount, BigDecimal lineTotal)`.
  - `ErpClient.uploadReturn(ReturnUpload)`; `FakeErpClient.uploadReturn` (idempotent on `returnId`) + `uploadedReturns()`.
  - `ReturnUploadListener` (`@ApplicationModuleListener void on(ReturnCompleted)`).

- [ ] **Step 1: Write the `ReturnUpload` contract**

`src/main/java/com/company/pos/integration/api/ReturnUpload.java`:

```java
package com.company.pos.integration.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReturnUpload(UUID returnId, String creditNoteNumber, UUID originalSaleId,
        String terminalId, String locationCode, String currencyCode, BigDecimal refundSubtotal,
        BigDecimal refundTaxTotal, BigDecimal refundGrandTotal, Instant createdAt, List<Line> lines) {

    public record Line(int lineNo, int originalLineNo, String sku, String name, BigDecimal quantity,
            BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxAmount, BigDecimal lineTotal) {
    }
}
```

- [ ] **Step 2: Add `uploadReturn` to `ErpClient`**

In `src/main/java/com/company/pos/integration/api/ErpClient.java`, add:

```java
    /** Uploads a return as a credit note. Idempotent: a repeat {@code ret.returnId()} is a no-op. */
    void uploadReturn(ReturnUpload ret);
```

- [ ] **Step 3: Implement `uploadReturn` on the fake**

In `src/main/java/com/company/pos/integration/erp/FakeErpClient.java`:

(a) Add the import `import com.company.pos.integration.api.ReturnUpload;`.

(b) Add a store field beside `uploadedSales`:

```java
    private final Map<UUID, ReturnUpload> uploadedReturns = new LinkedHashMap<>();
```

(c) Add an accessor beside `uploadedSales()`:

```java
    public List<ReturnUpload> uploadedReturns() {
        return new ArrayList<>(uploadedReturns.values());
    }
```

(d) Clear it in `clear()` (add the line inside the existing method):

```java
        uploadedReturns.clear();
```

(e) Implement the method (beside `uploadSale`):

```java
    @Override
    public void uploadReturn(ReturnUpload ret) {
        requireAvailable();
        uploadedReturns.putIfAbsent(ret.returnId(), ret); // idempotent on returnId
    }
```

- [ ] **Step 4: Write the listener**

`src/main/java/com/company/pos/sync/application/ReturnUploadListener.java`:

```java
package com.company.pos.sync.application;

import com.company.pos.integration.api.ErpClient;
import com.company.pos.integration.api.ReturnUpload;
import com.company.pos.sales.api.ReturnCompleted;
import com.company.pos.sales.api.ReturnService;
import com.company.pos.sales.api.ReturnView;
import com.company.pos.sales.api.SaleReturnLineView;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Uploads a completed return to the ERP as a credit note. Runs after the return commits,
 * asynchronously, in its own transaction, tracked by the Event Publication Registry (Phase 3a).
 * If the ERP is offline the upload throws and the publication stays incomplete for later replay
 * (Phase 3b drain/republish) — the committed return is never affected. Idempotent on returnId.
 */
@Component
class ReturnUploadListener {

    private static final Logger log = LoggerFactory.getLogger(ReturnUploadListener.class);

    private final ReturnService returns;
    private final ErpClient erp;

    ReturnUploadListener(ReturnService returns, ErpClient erp) {
        this.returns = returns;
        this.erp = erp;
    }

    @ApplicationModuleListener
    void on(ReturnCompleted event) {
        ReturnView ret = returns.getReturn(event.returnId());
        erp.uploadReturn(toUpload(event, ret));
        log.info("Uploaded return {} ({} lines) to ERP", ret.creditNoteNumber(), ret.lines().size());
    }

    private ReturnUpload toUpload(ReturnCompleted event, ReturnView ret) {
        List<ReturnUpload.Line> lines = ret.lines().stream()
                .map(this::toLine)
                .toList();
        return new ReturnUpload(ret.id(), ret.creditNoteNumber(), ret.originalSaleId(),
                event.terminalId(), event.locationCode(), ret.currencyCode(), ret.refundSubtotal(),
                ret.refundTaxTotal(), ret.refundGrandTotal(), ret.createdAt(), lines);
    }

    private ReturnUpload.Line toLine(SaleReturnLineView l) {
        return new ReturnUpload.Line(l.lineNo(), l.originalLineNo(), l.sku(), l.name(), l.quantity(),
                l.unitPrice(), l.netAmount(), l.taxAmount(), l.lineTotal());
    }
}
```

- [ ] **Step 5: Write the failing test**

`src/test/java/com/company/pos/sync/ReturnUploadTest.java`:

```java
package com.company.pos.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.api.ReturnUpload;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.inventory.api.InventorySync;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.ReturnCommand;
import com.company.pos.sales.api.ReturnService;
import com.company.pos.sales.api.SaleView;
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

/**
 * A completed return is uploaded to the ERP as a credit note (after-commit async). Non-@Transactional
 * + DatabaseCleaner + Awaitility; the FakeErpClient is reset around each test.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class ReturnUploadTest {

    @Autowired
    SalesService sales;
    @Autowired
    ReturnService returns;
    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    InventorySync inventorySync;
    @Autowired
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
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
    }

    @Test
    void completedReturnUploadsCreditNote() {
        var cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100")))), "cashier");

        var ret = returns.processReturn(new ReturnCommand(sale.id(), null,
                List.of(new ReturnCommand.ReturnLineRequest(1, new BigDecimal("1")))), "manager");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<ReturnUpload> uploaded = fake.uploadedReturns();
            assertThat(uploaded).hasSize(1);
            assertThat(uploaded.get(0).returnId()).isEqualTo(ret.id());
            assertThat(uploaded.get(0).lines()).hasSize(1);
        });
    }
}
```

- [ ] **Step 6: Run the test**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ReturnUploadTest`
Expected: PASS — the credit note appears in `fake.uploadedReturns()` after the async listener runs.

- [ ] **Step 7: Verify boundaries unaffected**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ModularityTests`
Expected: PASS — `sync` already allows `sales :: api` and `integration :: api`; no new dependency, no cycle.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/company/pos/integration/ \
        src/main/java/com/company/pos/sync/application/ReturnUploadListener.java \
        src/test/java/com/company/pos/sync/ReturnUploadTest.java
git commit -m "feat(sync): upload completed returns to the ERP as idempotent credit notes"
```

---

### Task 9: Capstone end-to-end + docs + full-suite gate

Prove the whole return flow over REST (manager-authorized), document the surface, and run the full suite as the phase gate.

**Files:**
- Test: `src/test/java/com/company/pos/ReturnsEndToEndTest.java`
- Modify: `docs/run-modes.md`

**Interfaces:**
- Consumes: REST `/auth/login`, `/sync/erp`, `/carts`, `/carts/{id}/lines`, `/sales`, `/returns`; `InMemoryNotifier`-style fakes (`FakeErpClient.uploadedReturns()`); `DatabaseCleaner`.
- Produces: nothing consumed downstream.

- [ ] **Step 1: Write the end-to-end test**

`src/test/java/com/company/pos/ReturnsEndToEndTest.java`:

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
 * End-to-end over REST: a manager rings up a sale (as cashier), then returns one line. Asserts the
 * credit note is created (201) and uploaded to the ERP, and that a cashier cannot process returns.
 * Non-@Transactional + DatabaseCleaner + Awaitility; fakes reset around each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class ReturnsEndToEndTest {

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
    void managerReturnsALineAndItUploadsAsCreditNote() throws Exception {
        String managerToken = login("manager");
        mvc.perform(post("/sync/erp").header("Authorization", managerToken))
                .andExpect(status().isOk());
        String cashierToken = login("cashier");

        String saleId = ringUpTwoColas(cashierToken);

        // Manager processes a partial return of 1 unit.
        String created = mvc.perform(post("/returns").header("Authorization", managerToken)
                        .contentType("application/json")
                        .content("{\"originalSaleId\":\"" + saleId + "\",\"lines\":[{\"lineNo\":1,\"quantity\":1}]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String returnId = JsonPath.read(created, "$.id");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(fake.uploadedReturns().stream()
                        .anyMatch(r -> r.returnId().toString().equals(returnId))).isTrue());
    }

    @Test
    void cashierCannotProcessReturns() throws Exception {
        String managerToken = login("manager");
        mvc.perform(post("/sync/erp").header("Authorization", managerToken))
                .andExpect(status().isOk());
        String cashierToken = login("cashier");
        String saleId = ringUpTwoColas(cashierToken);

        mvc.perform(post("/returns").header("Authorization", cashierToken)
                        .contentType("application/json")
                        .content("{\"originalSaleId\":\"" + saleId + "\",\"lines\":[{\"lineNo\":1,\"quantity\":1}]}"))
                .andExpect(status().isForbidden());
    }

    private String ringUpTwoColas(String token) throws Exception {
        String createdCart = mvc.perform(post("/carts").header("Authorization", token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String cartId = JsonPath.read(createdCart, "$.cartId");
        mvc.perform(post("/carts/" + cartId + "/lines").header("Authorization", token)
                        .contentType("application/json").content("{\"sku\":\"COLA\",\"quantity\":2}"))
                .andExpect(status().isOk());
        String sale = mvc.perform(post("/sales").header("Authorization", token)
                        .contentType("application/json")
                        .content("{\"cartId\":\"" + cartId + "\",\"tenders\":[{\"method\":\"CASH\",\"tendered\":100.00}]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(sale, "$.id");
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

> Note: the `SaleView` JSON exposes the sale id as `$.id` (record component `id`). The cart-create response exposes `$.cartId` (used by the Phase 3c e2e test). If a path differs, align to the actual `SaleView`/cart response shape rather than guessing.

- [ ] **Step 2: Run the end-to-end test**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ReturnsEndToEndTest`
Expected: PASS (both methods) — manager return creates a credit note that uploads to the ERP; cashier is forbidden.

- [ ] **Step 3: Document the returns surface**

Append to `docs/run-modes.md`:

```markdown
## Returns & Refunds (Phase 4)

A MANAGER processes a **receipted return** via `POST /returns` (cashiers get `403`). The request
references the original sale (by `originalSaleId` or `receiptNumber`) and the lines/quantities to
return. The `sales` module records an immutable `SalesReturn` (its own credit-note number) in one
synchronous transaction:

- **Over-return guard** — cumulative returned quantity per original line can never exceed the sold
  quantity, across repeated partial returns.
- **Proportional refund** — each returned line refunds the original line's net/tax/total scaled by
  `returnQty / soldQty`, so VAT stays exactly proportional.
- **Mirror-tender refund** — the refund is allocated across the original tenders proportionally;
  cash goes back as a drawer pay-out, card/wallet via a terminal refund (the in-memory terminal
  approves). Refunds are stored as `payment` rows tagged `REFUND`, keyed by the return id.

After the return commits, three after-commit outbox listeners reverse the sale effects (replayable,
mirroring the sale fan-out): `inventory` adds stock back (positive `RETURN` movement); `cashdrawer`
pays the cash refund out of the open drawer (a no-op, not an error, when no drawer is open);
`sync` uploads the return to the ERP as an idempotent credit note (stuck behind the Phase 3b
drain/replay when the ERP is offline).

Deferred: blind/unreferenced returns, cashier returns with manager-approval thresholds,
damaged-goods/no-restock, exchanges, refunding to a different tender, and returns reporting.
```

- [ ] **Step 4: Run the full suite (phase gate)**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test`
Expected: BUILD SUCCESS, 0 failures, 0 errors. Confirm specifically: `ModularityTests` (boundaries green, no new module, no cycle), `DatabaseStoreServerTest` (Postgres Testcontainer, Flyway V1–V17, `ddl-auto: validate`), and the new device/payment/sales/inventory/cashdrawer/sync/e2e tests. Report the actual totals line and whether `DatabaseStoreServerTest` truly ran on Docker (do not claim full verification if Docker was unavailable and it skipped).

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/company/pos/ReturnsEndToEndTest.java docs/run-modes.md
git commit -m "test(sales): e2e manager return -> credit note + ERP upload; phase 4 docs"
```

---

## Notes for the executor

- **No new module, no new dependency.** Every cross-module reference is already an allowed dependency. If `ModularityTests` flags anything for a touched module, you introduced an unintended import — fix the import, don't widen `allowedDependencies`.
- **Refund rows are keyed by `returnId`** (`sale_id = returnId`, `return_id = returnId`, `txn_type = REFUND`), so the original sale's `findBySale` stays clean. `findByReturn` reads them back.
- **Over-return is edge-checked cumulatively**: `alreadyReturned + requested > sold` ⇒ `conflict`. The guard query sums prior `SalesReturnLine.quantity` for the same original sale + original line.
- **Proportional refund keeps `lineTotal = net + tax`**: compute `refundNet` and `refundTax` each at scale 2, then `refundLineTotal = refundNet + refundTax`. Totals on the persisted record equal the sum of line totals.
- **Tender allocation sums exactly**: the last original tender absorbs the rounding remainder. Zero/negative portions are skipped (no empty refund rows, no terminal call for 0).
- **Always restock; no low-stock on increase.** The return only raises on-hand, so there is no negative-stock warning and no `LowStockDetected`.
- **Cash refund tolerates a closed drawer** (`recordCashRefund` no-ops when no open session), matching `recordCashSale`, so the publication never wedges.
- **ERP credit-note upload is idempotent on `returnId`** and replayable via the Phase 3b drain when the ERP is offline.
- **Migrations: V16 (payment) before V17 (sales).** Both locations are already in `flyway.locations`; no config change. Keep column types matching the entity mappings (`DatabaseStoreServerTest` is the source of truth on Postgres).
- **Verification before "done":** the final `./mvnw -q test` (Task 9, Step 4) must be green including `DatabaseStoreServerTest` on Docker. Report the actual output; do not claim success from a partial run.
- **Branch:** if implementing fresh, cut `phase-4-returns-refunds` from `main` (after Phase 3c merges) per the prior-phase convention.
```
