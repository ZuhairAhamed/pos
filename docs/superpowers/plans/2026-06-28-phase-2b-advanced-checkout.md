# Phase 2b — Advanced Checkout (Card/Wallet · Split Payment · Hold/Resume) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Take card and QR/mobile-wallet payments through a semi-integrated terminal, settle one sale with multiple tenders (split/mixed payment), and let a cashier park an open cart (hold/resume) and void a line — all offline, extending the immutable cash-sale path built in Phase 2a.

**Architecture:** Extends the Tier-2 `payment` module to authorize electronic tenders through the existing `device.api` `PaymentTerminal` port (P2PE / semi-integrated — the app never sees the PAN), backed by a new in-memory fake terminal adapter. The Tier-1 `sales` orchestrator changes from a single cash tender to a **single-call multi-tender** checkout: `CheckoutCommand` carries a list of tenders, the orchestrator records each `Payment` (cash computes change; card/wallet go through the terminal), validates that the tenders exactly cover the grand total, and persists the same immutable `Sale` aggregate. The `receipt` renders every tender. The `cart` aggregate gains a `HELD` status (hold/resume) and a terminal id so held carts can be listed per terminal, plus a guarded void-line. No new modules; returns/refunds and `cashdrawer`/`shift` are out of scope (later plans).

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith 1.2.5, Spring Data JPA, Flyway (store-server), JavaMoney/Moneta, JUnit 5 + spring-security-test, Maven (`./mvnw`).

## Global Constraints

- **Root package** `com.company.pos`; one package-per-module under it, boundaries enforced by `ApplicationModules.of(PosApplication.class).verify()` (the `ModularityTests`). The application **root package is itself boundary-constrained** — any type the root references must be exposed via a module's `@NamedInterface`.
- **JDK 21 required.** The machine default `java` is 17. Run every Maven command as: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw …`.
- **Build tool is Maven** via the committed wrapper `./mvnw`. No system `mvn`.
- **Modulith named interfaces:** a module is consumed cross-module only through a sub-package marked `@org.springframework.modulith.NamedInterface("api")`, and the consumer must list it in `allowedDependencies` as `module :: api`. `configuration.api` and `device.api` are already named interfaces (added in Phase 2a).
- **UUID-as-VARCHAR convention:** entity ids are `UUID` fields annotated `@JdbcTypeCode(SqlTypes.VARCHAR)` + `@Column(length = 36)`; migrations declare `VARCHAR(36)`.
- **Money is `BigDecimal` + currency code — never `double`/`float`.** Monetary amounts (net, tax, totals, tender, change) are rounded to **scale 2, `RoundingMode.HALF_UP`**. Unit prices keep scale 4; quantities scale 3. Use `java.math.RoundingMode.HALF_UP` everywhere.
- **Single physical `pos` schema.** Entities are schema-agnostic (no `@Table(schema=…)`); the store-server profile sets `hibernate.default_schema: pos` and `flyway.default-schema: pos`.
- **Flyway versions are globally unique and ordered across all per-module locations** (V1 config, V2 auth, V3 product, V4 inventory, V5 integration, V6 cart, V7 payment, V8 sales, V9 stock_movement already exist). New migrations continue at **V10**. The `db/migration/payment` and `db/migration/cart` locations are **already registered** in `flyway.locations`, so this phase needs **no `application-store-server.yml` change**. The `embedded` profile uses Hibernate `ddl-auto: update` and **no Flyway** — tests run on `embedded`.
- **Semi-integrated payments (PCI scope):** card/wallet tenders are authorized by the `PaymentTerminal` port. The app stores only the masked PAN and an opaque token returned by the terminal — never the card number. The fake adapter mirrors this contract.
- **Offline-first:** nothing in the sell path may block on connectivity, and a sale must never be rolled back by a downstream side effect. Stock may go negative.
- **Errors** surface as `DomainException.notFound/validation/conflict(...)` → RFC-7807 `ProblemDetail` via the existing `ApiExceptionHandler`.

---

## Existing interfaces this phase consumes or changes (already implemented)

```java
// com.company.pos.common.util
public final class Identifiers { public static UUID newId(); }
public final class Monies {
    public static javax.money.MonetaryAmount of(BigDecimal amount, String currencyCode);
    public static String format(javax.money.MonetaryAmount amount, java.util.Locale locale);
}

// com.company.pos.common.exception
public class DomainException extends RuntimeException {
    public static DomainException notFound(String message);
    public static DomainException validation(String message);
    public static DomainException conflict(String message);
}

// com.company.pos.device.api  (named interface "api"; ports already defined, NO adapter yet for terminal)
public interface PaymentTerminal { PaymentResult requestPayment(PaymentRequest request); }
public record PaymentRequest(javax.money.MonetaryAmount amount, String reference) {}
public record PaymentResult(boolean approved, String maskedPan, String token) {}

// com.company.pos.configuration.api  (named interface "api")
public interface ConfigurationService { String getString(SettingKey key); /* … */ }
public enum SettingKey { /* …, */ STORE_ID, TERMINAL_ID, INVENTORY_LOCATION, CURRENCY_CODE, VAT_RATE, TAX_INCLUSIVE, STORE_NAME, LOCALE; }

// com.company.pos.cart.api  (named interface "api")
public interface CartService {
    UUID createCart(); CartView addLine(UUID cartId, String sku, BigDecimal quantity);
    CartView updateLine(UUID cartId, String sku, BigDecimal quantity);
    CartView removeLine(UUID cartId, String sku); CartView getCart(UUID cartId); void close(UUID cartId);
}
// Cart statuses today: "OPEN", "CHECKED_OUT".  This phase adds "HELD".
```

## Cross-module API surface changed/produced by this phase

| Module | Change |
|---|---|
| `device` | NEW `infrastructure.InMemoryPaymentTerminal` bean implementing `PaymentTerminal` (default adapter + test controls). No new `api` types. |
| `payment` | `PaymentMethod` gains `CARD`, `WALLET`. NEW `api.PaymentView`. `PaymentService` changes `recordCash` return type → `PaymentView`, adds `recordTerminalPayment(...)` and `findBySale(...)`. `api.CashPaymentView` **deleted**. |
| `receipt` | NEW `api.ReceiptPaymentData`. `api.ReceiptData` replaces `(amountTendered, changeDue)` with `List<ReceiptPaymentData> payments`. |
| `sales` | NEW `api.TenderInput`. `api.CheckoutCommand` becomes `(cartId, List<TenderInput> tenders)`. `api.SalePaymentView` gains `maskedPan`. `api.SaleView` replaces `SalePaymentView payment` with `List<SalePaymentView> payments`. |
| `cart` | `CartService` adds `hold`, `resume`, `listHeld`. New `Cart.terminalId`; status `"HELD"`. |

## Module dependency declarations changed

```
payment   allowedDependencies = { "common", "database", "device :: api" }            // CHANGED: add "device :: api"
cart      allowedDependencies = { "common", "database", "product :: api", "configuration :: api" }  // CHANGED: add "configuration :: api"
```
No cycle: `payment → device :: api` (device depends only on `common`); `cart → configuration :: api` (configuration depends only on `common`). `sales` is unchanged — it already depends on `payment :: api` (so it sees `PaymentView`/`PaymentMethod`) and `receipt :: api`.

## Migrations added (no `flyway.locations` change required)

| Version | Location dir | Change |
|---|---|---|
| V10 | `db/migration/payment` | `ALTER TABLE payment ADD auth_token, masked_pan` (both nullable) |
| V11 | `db/migration/cart` | `ALTER TABLE cart ADD terminal_id` (nullable) |

No new `SettingKey`s.

---

### Task 1: Device — in-memory fake `PaymentTerminal` adapter

The `device` module defines the `PaymentTerminal` port but ships no bean, so nothing can authorize a card. Add an in-memory fake adapter (the default until a real semi-integrated SDK lands) with test controls to force approval/decline and inspect the last request.

**Files:**
- Create: `src/main/java/com/company/pos/device/infrastructure/InMemoryPaymentTerminal.java`
- Test: `src/test/java/com/company/pos/device/InMemoryPaymentTerminalTest.java`

**Interfaces:**
- Consumes: `PaymentTerminal`, `PaymentRequest`, `PaymentResult` (existing `device.api`); `Monies.of` (for the test).
- Produces: `InMemoryPaymentTerminal` bean implementing `PaymentTerminal` with `void setApprove(boolean)` (default approves), `PaymentRequest lastRequest()`. Approved results return a fixed masked PAN `"**** **** **** 4242"` and a `"tok_"`-prefixed token; declined results return `new PaymentResult(false, null, null)`.

- [ ] **Step 1: Write the failing test**

```java
package com.company.pos.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.common.util.Monies;
import com.company.pos.device.api.PaymentRequest;
import com.company.pos.device.api.PaymentResult;
import com.company.pos.device.api.PaymentTerminal;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class InMemoryPaymentTerminalTest {

    @Autowired
    PaymentTerminal terminal;
    @Autowired
    InMemoryPaymentTerminal fakeTerminal;

    @AfterEach
    void reset() {
        // shared application context — leave the bean approving for other tests
        fakeTerminal.setApprove(true);
    }

    @Test
    void approvesByDefaultAndReturnsMaskedPanAndToken() {
        PaymentRequest request = new PaymentRequest(Monies.of(new BigDecimal("50.00"), "SAR"), "ref-1");

        PaymentResult result = terminal.requestPayment(request);

        assertThat(result.approved()).isTrue();
        assertThat(result.maskedPan()).isEqualTo("**** **** **** 4242");
        assertThat(result.token()).startsWith("tok_");
        assertThat(fakeTerminal.lastRequest().reference()).isEqualTo("ref-1");
    }

    @Test
    void declinesWhenConfigured() {
        fakeTerminal.setApprove(false);

        PaymentResult result = terminal.requestPayment(
                new PaymentRequest(Monies.of(new BigDecimal("50.00"), "SAR"), "ref-2"));

        assertThat(result.approved()).isFalse();
        assertThat(result.maskedPan()).isNull();
        assertThat(result.token()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=InMemoryPaymentTerminalTest`
Expected: FAIL — `InMemoryPaymentTerminal` does not exist / no `PaymentTerminal` bean to autowire.

- [ ] **Step 3: Implement `InMemoryPaymentTerminal`**

`src/main/java/com/company/pos/device/infrastructure/InMemoryPaymentTerminal.java`:

```java
package com.company.pos.device.infrastructure;

import com.company.pos.device.api.PaymentRequest;
import com.company.pos.device.api.PaymentResult;
import com.company.pos.device.api.PaymentTerminal;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Default {@link PaymentTerminal} adapter: simulates a semi-integrated terminal in memory.
 * Returns only a masked PAN and an opaque token — never a real card number — mirroring the
 * P2PE contract a real terminal SDK will fulfil later. Approval is controllable for tests.
 */
@Component
public class InMemoryPaymentTerminal implements PaymentTerminal {

    private volatile boolean approve = true;
    private volatile PaymentRequest lastRequest;

    @Override
    public PaymentResult requestPayment(PaymentRequest request) {
        this.lastRequest = request;
        if (!approve) {
            return new PaymentResult(false, null, null);
        }
        return new PaymentResult(true, "**** **** **** 4242", "tok_" + UUID.randomUUID());
    }

    /** Test control: set {@code false} to make the next requests decline. */
    public void setApprove(boolean approve) {
        this.approve = approve;
    }

    /** Test assertion helper: the most recent request the terminal saw. */
    public PaymentRequest lastRequest() {
        return lastRequest;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=InMemoryPaymentTerminalTest`
Expected: PASS (both tests).

- [ ] **Step 5: Verify module boundaries**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ModularityTests`
Expected: PASS — the new class is internal to `device.infrastructure`; no boundary change.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/device/infrastructure/InMemoryPaymentTerminal.java \
        src/test/java/com/company/pos/device/InMemoryPaymentTerminalTest.java
git commit -m "feat(device): add in-memory PaymentTerminal fake adapter (semi-integrated contract)"
```

---

### Task 2: Payment — card/wallet methods, terminal-mediated tenders, unified `PaymentView`

Extend `payment` from cash-only to three tender types. Cash keeps its tendered/change math; card and wallet are authorized through the `PaymentTerminal` port and store only the masked PAN + token. Introduce a unified `PaymentView` (replacing `CashPaymentView`) and a `findBySale` read so a sale can show all its tenders.

**Files:**
- Modify: `src/main/java/com/company/pos/payment/package-info.java`
- Modify: `src/main/java/com/company/pos/payment/api/PaymentMethod.java`
- Create: `src/main/java/com/company/pos/payment/api/PaymentView.java`
- Delete: `src/main/java/com/company/pos/payment/api/CashPaymentView.java`
- Modify: `src/main/java/com/company/pos/payment/api/PaymentService.java`
- Modify: `src/main/java/com/company/pos/payment/domain/Payment.java`
- Modify: `src/main/java/com/company/pos/payment/infrastructure/PaymentRepository.java`
- Modify: `src/main/java/com/company/pos/payment/application/DefaultPaymentService.java`
- Create: `src/main/resources/db/migration/payment/V10__payment_terminal_fields.sql`
- Test: `src/test/java/com/company/pos/payment/PaymentServiceTest.java` (rewrite)

**Interfaces:**
- Consumes: `PaymentTerminal.requestPayment(PaymentRequest) -> PaymentResult`; `Monies.of`; `Identifiers.newId()`; `DomainException`.
- Produces:
  - `enum PaymentMethod { CASH, CARD, WALLET }`
  - `record PaymentView(UUID saleId, String method, BigDecimal amount, BigDecimal amountTendered, BigDecimal changeDue, String maskedPan, String currencyCode)`
  - `PaymentService`:
    - `PaymentView recordCash(UUID saleId, String currencyCode, BigDecimal amount, BigDecimal amountTendered)` — `amount` is the portion charged to cash; `change = amountTendered − amount` (rejects `amountTendered < amount`).
    - `PaymentView recordTerminalPayment(UUID saleId, String currencyCode, BigDecimal amount, PaymentMethod method, String reference)` — `method` must be `CARD` or `WALLET`; calls the terminal; throws `DomainException.validation` if declined; stores `amountTendered = amount`, `changeDue = 0`, plus `maskedPan`/token.
    - `List<PaymentView> findBySale(UUID saleId)`.

- [ ] **Step 1: Rewrite the test (drives every change in this task)**

Replace the contents of `src/test/java/com/company/pos/payment/PaymentServiceTest.java`:

```java
package com.company.pos.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.payment.api.PaymentService;
import com.company.pos.payment.api.PaymentView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class PaymentServiceTest {

    @Autowired
    PaymentService payments;
    @Autowired
    InMemoryPaymentTerminal terminal;

    @AfterEach
    void reset() {
        terminal.setApprove(true);
    }

    @Test
    void recordsCashAndComputesChange() {
        UUID saleId = Identifiers.newId();
        PaymentView view = payments.recordCash(saleId, "SAR",
                new BigDecimal("10.35"), new BigDecimal("20.00"));

        assertThat(view.saleId()).isEqualTo(saleId);
        assertThat(view.method()).isEqualTo("CASH");
        assertThat(view.amount()).isEqualByComparingTo("10.35");
        assertThat(view.changeDue()).isEqualByComparingTo("9.65");
        assertThat(view.maskedPan()).isNull();
    }

    @Test
    void insufficientCashTenderIsRejected() {
        assertThatThrownBy(() -> payments.recordCash(Identifiers.newId(), "SAR",
                new BigDecimal("10.35"), new BigDecimal("5.00")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void recordsApprovedCardWithMaskedPanAndNoChange() {
        UUID saleId = Identifiers.newId();
        PaymentView view = payments.recordTerminalPayment(saleId, "SAR",
                new BigDecimal("50.00"), PaymentMethod.CARD, saleId.toString());

        assertThat(view.method()).isEqualTo("CARD");
        assertThat(view.amount()).isEqualByComparingTo("50.00");
        assertThat(view.amountTendered()).isEqualByComparingTo("50.00");
        assertThat(view.changeDue()).isEqualByComparingTo("0.00");
        assertThat(view.maskedPan()).isEqualTo("**** **** **** 4242");
    }

    @Test
    void recordsWalletTender() {
        PaymentView view = payments.recordTerminalPayment(Identifiers.newId(), "SAR",
                new BigDecimal("12.00"), PaymentMethod.WALLET, "ref");
        assertThat(view.method()).isEqualTo("WALLET");
        assertThat(view.amount()).isEqualByComparingTo("12.00");
    }

    @Test
    void declinedTerminalPaymentIsRejected() {
        terminal.setApprove(false);
        assertThatThrownBy(() -> payments.recordTerminalPayment(Identifiers.newId(), "SAR",
                new BigDecimal("50.00"), PaymentMethod.CARD, "ref"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void cashMethodRejectedByTerminalPath() {
        assertThatThrownBy(() -> payments.recordTerminalPayment(Identifiers.newId(), "SAR",
                new BigDecimal("50.00"), PaymentMethod.CASH, "ref"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void findBySaleReturnsAllTenders() {
        UUID saleId = Identifiers.newId();
        payments.recordTerminalPayment(saleId, "SAR", new BigDecimal("40.00"), PaymentMethod.CARD, "ref");
        payments.recordCash(saleId, "SAR", new BigDecimal("10.35"), new BigDecimal("20.00"));

        List<PaymentView> found = payments.findBySale(saleId);
        assertThat(found).hasSize(2);
        assertThat(found).extracting(PaymentView::method).containsExactlyInAnyOrder("CARD", "CASH");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=PaymentServiceTest`
Expected: FAIL — `PaymentView`, `recordTerminalPayment`, `findBySale`, `PaymentMethod.CARD/WALLET` do not exist (compile errors).

- [ ] **Step 3: Add `device :: api` to the payment module**

Replace `src/main/java/com/company/pos/payment/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "device :: api" })
package com.company.pos.payment;
```

- [ ] **Step 4: Extend `PaymentMethod`**

Replace `src/main/java/com/company/pos/payment/api/PaymentMethod.java`:

```java
package com.company.pos.payment.api;

public enum PaymentMethod {
    CASH,
    CARD,
    WALLET
}
```

- [ ] **Step 5: Add `PaymentView`, delete `CashPaymentView`**

Create `src/main/java/com/company/pos/payment/api/PaymentView.java`:

```java
package com.company.pos.payment.api;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentView(UUID saleId, String method, BigDecimal amount, BigDecimal amountTendered,
        BigDecimal changeDue, String maskedPan, String currencyCode) {
}
```

Delete `src/main/java/com/company/pos/payment/api/CashPaymentView.java`:

```bash
git rm src/main/java/com/company/pos/payment/api/CashPaymentView.java
```

- [ ] **Step 6: Update the `PaymentService` interface**

Replace `src/main/java/com/company/pos/payment/api/PaymentService.java`:

```java
package com.company.pos.payment.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface PaymentService {

    PaymentView recordCash(UUID saleId, String currencyCode, BigDecimal amount,
            BigDecimal amountTendered);

    PaymentView recordTerminalPayment(UUID saleId, String currencyCode, BigDecimal amount,
            PaymentMethod method, String reference);

    List<PaymentView> findBySale(UUID saleId);
}
```

- [ ] **Step 7: Add the new columns + getters to the `Payment` entity**

Edit `src/main/java/com/company/pos/payment/domain/Payment.java`. Add two nullable columns after `currencyCode` and before `createdAt`, a constructor parameter for them, and the read getters the service needs. The full file:

```java
package com.company.pos.payment.domain;

import com.company.pos.payment.api.PaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "sale_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID saleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentMethod method;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "amount_tendered", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountTendered;

    @Column(name = "change_due", nullable = false, precision = 19, scale = 2)
    private BigDecimal changeDue;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "masked_pan", length = 25)
    private String maskedPan;

    @Column(name = "auth_token", length = 64)
    private String authToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Payment() {
        // JPA
    }

    public Payment(UUID id, UUID saleId, PaymentMethod method, BigDecimal amount,
            BigDecimal amountTendered, BigDecimal changeDue, String currencyCode,
            String maskedPan, String authToken, Instant createdAt) {
        this.id = id;
        this.saleId = saleId;
        this.method = method;
        this.amount = amount;
        this.amountTendered = amountTendered;
        this.changeDue = changeDue;
        this.currencyCode = currencyCode;
        this.maskedPan = maskedPan;
        this.authToken = authToken;
        this.createdAt = createdAt;
    }

    public UUID getSaleId() {
        return saleId;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getAmountTendered() {
        return amountTendered;
    }

    public BigDecimal getChangeDue() {
        return changeDue;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public String getMaskedPan() {
        return maskedPan;
    }
}
```

- [ ] **Step 8: Add the `findBySaleId` query**

Replace `src/main/java/com/company/pos/payment/infrastructure/PaymentRepository.java`:

```java
package com.company.pos.payment.infrastructure;

import com.company.pos.payment.domain.Payment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findBySaleId(UUID saleId);
}
```

- [ ] **Step 9: Implement the new `DefaultPaymentService`**

Replace `src/main/java/com/company/pos/payment/application/DefaultPaymentService.java`:

```java
package com.company.pos.payment.application;

import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.common.util.Monies;
import com.company.pos.device.api.PaymentRequest;
import com.company.pos.device.api.PaymentResult;
import com.company.pos.device.api.PaymentTerminal;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.payment.api.PaymentService;
import com.company.pos.payment.api.PaymentView;
import com.company.pos.payment.domain.Payment;
import com.company.pos.payment.infrastructure.PaymentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultPaymentService implements PaymentService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final PaymentRepository payments;
    private final PaymentTerminal terminal;

    DefaultPaymentService(PaymentRepository payments, PaymentTerminal terminal) {
        this.payments = payments;
        this.terminal = terminal;
    }

    @Override
    public PaymentView recordCash(UUID saleId, String currencyCode, BigDecimal amount,
            BigDecimal amountTendered) {
        BigDecimal due = amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal tendered = amountTendered.setScale(2, RoundingMode.HALF_UP);
        if (tendered.compareTo(due) < 0) {
            throw DomainException.validation(
                    "Tendered " + tendered + " is less than amount due " + due);
        }
        BigDecimal change = tendered.subtract(due);
        Payment payment = new Payment(Identifiers.newId(), saleId, PaymentMethod.CASH,
                due, tendered, change, currencyCode, null, null, Instant.now());
        payments.save(payment);
        return new PaymentView(saleId, PaymentMethod.CASH.name(), due, tendered, change, null,
                currencyCode);
    }

    @Override
    public PaymentView recordTerminalPayment(UUID saleId, String currencyCode, BigDecimal amount,
            PaymentMethod method, String reference) {
        if (method != PaymentMethod.CARD && method != PaymentMethod.WALLET) {
            throw DomainException.validation("Method " + method + " is not terminal-mediated");
        }
        BigDecimal due = amount.setScale(2, RoundingMode.HALF_UP);
        if (due.signum() <= 0) {
            throw DomainException.validation("Terminal payment amount must be positive");
        }
        PaymentResult result = terminal.requestPayment(
                new PaymentRequest(Monies.of(due, currencyCode), reference));
        if (!result.approved()) {
            throw DomainException.validation(method + " payment was declined");
        }
        Payment payment = new Payment(Identifiers.newId(), saleId, method, due, due, ZERO,
                currencyCode, result.maskedPan(), result.token(), Instant.now());
        payments.save(payment);
        return new PaymentView(saleId, method.name(), due, due, ZERO, result.maskedPan(),
                currencyCode);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentView> findBySale(UUID saleId) {
        return payments.findBySaleId(saleId).stream()
                .map(p -> new PaymentView(p.getSaleId(), p.getMethod().name(), p.getAmount(),
                        p.getAmountTendered(), p.getChangeDue(), p.getMaskedPan(),
                        p.getCurrencyCode()))
                .toList();
    }
}
```

- [ ] **Step 10: Write the V10 migration (store-server)**

Create `src/main/resources/db/migration/payment/V10__payment_terminal_fields.sql`:

```sql
ALTER TABLE payment ADD COLUMN masked_pan VARCHAR(25);
ALTER TABLE payment ADD COLUMN auth_token VARCHAR(64);
```

> No `flyway.locations` change: `classpath:db/migration/payment` is already listed (it holds V7).

- [ ] **Step 11: Run the payment test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=PaymentServiceTest`
Expected: PASS (all seven tests).

> `DefaultSalesService` still references the deleted `CashPaymentView`/old `recordCash` return — it is fixed in Task 4. The targeted `-Dtest=PaymentServiceTest` run compiles the test classpath; if the reactor fails to compile `DefaultSalesService`, that is expected and resolved in Task 4. If you need a clean compile checkpoint now, you may temporarily proceed to Task 4 before running the full suite. Do **not** commit a non-compiling tree — sequence the commit after the sales changes if your toolchain blocks on whole-module compile. (If `-Dtest=PaymentServiceTest` compiles and passes in isolation on your setup, commit here.)

- [ ] **Step 12: Verify module boundaries**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ModularityTests`
Expected: PASS — `payment → device :: api` is declared; no cycle.

- [ ] **Step 13: Commit**

```bash
git add src/main/java/com/company/pos/payment/ \
        src/main/resources/db/migration/payment/V10__payment_terminal_fields.sql \
        src/test/java/com/company/pos/payment/PaymentServiceTest.java
git commit -m "feat(payment): add card/wallet terminal-mediated tenders, unified PaymentView, findBySale"
```

---

### Task 3: Receipt — render every tender (multi-payment receipt)

A split-payment sale must print each tender. Replace the single `(amountTendered, changeDue)` pair on `ReceiptData` with a list of `ReceiptPaymentData`, and render each — masked PAN for card/wallet, tendered/change for cash.

**Files:**
- Create: `src/main/java/com/company/pos/receipt/api/ReceiptPaymentData.java`
- Modify: `src/main/java/com/company/pos/receipt/api/ReceiptData.java`
- Modify: `src/main/java/com/company/pos/receipt/application/DefaultReceiptService.java`
- Test: `src/test/java/com/company/pos/receipt/ReceiptServiceTest.java`

**Interfaces:**
- Consumes: `Printer`, `PrintLine`, `ConfigurationService`, `Monies` (all existing in `receipt`).
- Produces:
  - `record ReceiptPaymentData(String method, BigDecimal amount, BigDecimal amountTendered, BigDecimal changeDue, String maskedPan)`
  - `record ReceiptData(String receiptNumber, String cashierName, Instant timestamp, List<ReceiptLineData> lines, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal, List<ReceiptPaymentData> payments, String currencyCode)`

- [ ] **Step 1: Update the test (drives the shape change)**

Replace `src/test/java/com/company/pos/receipt/ReceiptServiceTest.java`:

```java
package com.company.pos.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.device.api.PrintLine;
import com.company.pos.device.infrastructure.InMemoryPrinter;
import com.company.pos.receipt.api.ReceiptData;
import com.company.pos.receipt.api.ReceiptLineData;
import com.company.pos.receipt.api.ReceiptPaymentData;
import com.company.pos.receipt.api.ReceiptService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class ReceiptServiceTest {

    @Autowired
    ReceiptService receipts;
    @Autowired
    InMemoryPrinter printer;

    @Test
    void printsCashAndCardTendersWithTotals() {
        ReceiptData data = new ReceiptData("S01-T01-000001", "cashier", Instant.now(),
                List.of(new ReceiptLineData("Cola Can", new BigDecimal("2"),
                        new BigDecimal("4.50"), new BigDecimal("10.35"))),
                new BigDecimal("9.00"), new BigDecimal("1.35"), new BigDecimal("10.35"),
                List.of(
                        new ReceiptPaymentData("CARD", new BigDecimal("5.00"),
                                new BigDecimal("5.00"), new BigDecimal("0.00"), "**** **** **** 4242"),
                        new ReceiptPaymentData("CASH", new BigDecimal("5.35"),
                                new BigDecimal("10.00"), new BigDecimal("4.65"), null)),
                "SAR");

        receipts.print(data);

        List<String> text = printer.lastReceipt().stream().map(PrintLine::text).toList();
        assertThat(text).anyMatch(t -> t.contains("S01-T01-000001"));
        assertThat(text).anyMatch(t -> t.contains("Cola Can"));
        assertThat(text).anyMatch(t -> t.contains("TOTAL"));
        assertThat(text).anyMatch(t -> t.contains("CARD"));
        assertThat(text).anyMatch(t -> t.contains("**** **** **** 4242"));
        assertThat(text).anyMatch(t -> t.contains("CASH"));
        assertThat(text).anyMatch(t -> t.contains("Change"));
        assertThat(printer.cutCount()).isGreaterThanOrEqualTo(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ReceiptServiceTest`
Expected: FAIL — `ReceiptPaymentData` does not exist; `ReceiptData` constructor signature mismatch.

- [ ] **Step 3: Create `ReceiptPaymentData`**

`src/main/java/com/company/pos/receipt/api/ReceiptPaymentData.java`:

```java
package com.company.pos.receipt.api;

import java.math.BigDecimal;

public record ReceiptPaymentData(String method, BigDecimal amount, BigDecimal amountTendered,
        BigDecimal changeDue, String maskedPan) {
}
```

- [ ] **Step 4: Change `ReceiptData`**

Replace `src/main/java/com/company/pos/receipt/api/ReceiptData.java`:

```java
package com.company.pos.receipt.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ReceiptData(String receiptNumber, String cashierName, Instant timestamp,
        List<ReceiptLineData> lines, BigDecimal subtotal, BigDecimal taxTotal,
        BigDecimal grandTotal, List<ReceiptPaymentData> payments, String currencyCode) {
}
```

- [ ] **Step 5: Render the tenders**

In `src/main/java/com/company/pos/receipt/application/DefaultReceiptService.java`, replace the two trailing `Cash:`/`Change:` lines (the block after the `TOTAL:` line) with a loop over `data.payments()`. Add `import com.company.pos.receipt.api.ReceiptPaymentData;`. The replacement block:

```java
        lines.add(new PrintLine("TOTAL:    " + money(data.grandTotal(), currency, locale), true));
        for (ReceiptPaymentData payment : data.payments()) {
            String label = payment.method() + ":";
            String suffix = payment.maskedPan() != null ? "  " + payment.maskedPan() : "";
            lines.add(new PrintLine(label + "    " + money(payment.amount(), currency, locale) + suffix,
                    false));
            if (payment.changeDue() != null && payment.changeDue().signum() > 0) {
                lines.add(new PrintLine("  Tendered: " + money(payment.amountTendered(), currency, locale),
                        false));
                lines.add(new PrintLine("  Change:   " + money(payment.changeDue(), currency, locale),
                        false));
            }
        }
```

> The lines printed before `TOTAL:` (store name, receipt number, cashier, date, line items, subtotal, tax) are unchanged. Only the final cash/change pair becomes the per-tender loop.

- [ ] **Step 6: Run test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ReceiptServiceTest`
Expected: PASS.

> `DefaultSalesService` still builds the old `ReceiptData` — fixed in Task 4. See the Task 2 Step 11 note about whole-module compile; commit this task's files together with Task 4 if your toolchain blocks on it, otherwise commit now.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/pos/receipt/ src/test/java/com/company/pos/receipt/ReceiptServiceTest.java
git commit -m "feat(receipt): render multiple tenders (card masked PAN + cash change) on the receipt"
```

---

### Task 4: Sales — single-call multi-tender checkout

Change checkout from one cash tender to a list of tenders. The orchestrator records each tender (cash via `recordCash`, card/wallet via `recordTerminalPayment`), defaults an amount-less cash tender to the outstanding balance, validates the tenders exactly cover the grand total, and persists the same immutable `Sale`. `SaleView` now carries a list of payments; `getSale`/`reprint` read them back via `findBySale`. This task also updates every call site broken by the `CheckoutCommand`/`SaleView` change so the whole suite stays green.

**Files:**
- Create: `src/main/java/com/company/pos/sales/api/TenderInput.java`
- Modify: `src/main/java/com/company/pos/sales/api/CheckoutCommand.java`
- Modify: `src/main/java/com/company/pos/sales/api/SalePaymentView.java`
- Modify: `src/main/java/com/company/pos/sales/api/SaleView.java`
- Modify: `src/main/java/com/company/pos/sales/application/DefaultSalesService.java`
- Test (rewrite): `src/test/java/com/company/pos/sales/CheckoutServiceTest.java`
- Test (update): `src/test/java/com/company/pos/sales/SalesControllerTest.java`
- Test (update): `src/test/java/com/company/pos/inventory/SaleDecrementsStockTest.java`
- Test (update): `src/test/java/com/company/pos/CashSaleEndToEndTest.java`

**Interfaces:**
- Consumes: `PaymentService.recordCash/recordTerminalPayment/findBySale -> PaymentView`; `PaymentMethod`; `ReceiptData`/`ReceiptPaymentData`; `InMemoryPaymentTerminal` (in tests).
- Produces:
  - `record TenderInput(PaymentMethod method, BigDecimal amount, BigDecimal tendered)` — for `CASH`, `amount` may be null (defaults to the outstanding balance) and `tendered` is required; for `CARD`/`WALLET`, `amount` is required and `tendered` is ignored.
  - `record CheckoutCommand(UUID cartId, List<TenderInput> tenders)`
  - `record SalePaymentView(String method, BigDecimal amount, BigDecimal amountTendered, BigDecimal changeDue, String maskedPan)`
  - `record SaleView(UUID id, String receiptNumber, String status, String currencyCode, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal, Instant createdAt, List<SaleLineView> lines, List<SalePaymentView> payments)`
  - Checkout rule: process tenders in order; `Σ resolved amount` must equal `grandTotal` (scale 2) or it is a `DomainException.validation`.

- [ ] **Step 1: Rewrite the checkout service test**

Replace `src/test/java/com/company/pos/sales/CheckoutServiceTest.java`:

```java
package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.cart.api.CartService;
import com.company.pos.common.exception.DomainException;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import com.company.pos.device.infrastructure.InMemoryPrinter;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalePaymentView;
import com.company.pos.sales.api.SaleView;
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
class CheckoutServiceTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    InMemoryPrinter printer;
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

    private UUID cartWith(String qty) {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal(qty));
        return cart;
    }

    @Test
    void singleCashTenderComputesChange() {
        // 2 x 4.50 = 9.00 net, tax 1.35, total 10.35
        UUID cart = cartWith("2");
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))), "cashier");

        assertThat(sale.grandTotal()).isEqualByComparingTo("10.35");
        assertThat(sale.payments()).hasSize(1);
        assertThat(sale.payments().get(0).method()).isEqualTo("CASH");
        assertThat(sale.payments().get(0).changeDue()).isEqualByComparingTo("9.65");
        assertThat(sale.status()).isEqualTo("COMPLETED");
        assertThat(printer.lastReceipt()).isNotEmpty();
        assertThat(carts.getCart(cart).status()).isEqualTo("CHECKED_OUT");
    }

    @Test
    void splitCardThenCashSettlesAndGivesChangeOnCash() {
        // total 10.35: pay 5.00 on card, rest (5.35) on cash tendered 10.00 -> change 4.65
        UUID cart = cartWith("2");
        SaleView sale = sales.checkout(new CheckoutCommand(cart, List.of(
                new TenderInput(PaymentMethod.CARD, new BigDecimal("5.00"), null),
                new TenderInput(PaymentMethod.CASH, null, new BigDecimal("10.00")))), "cashier");

        assertThat(sale.payments()).hasSize(2);
        SalePaymentView card = sale.payments().get(0);
        SalePaymentView cash = sale.payments().get(1);
        assertThat(card.method()).isEqualTo("CARD");
        assertThat(card.amount()).isEqualByComparingTo("5.00");
        assertThat(card.maskedPan()).isEqualTo("**** **** **** 4242");
        assertThat(cash.method()).isEqualTo("CASH");
        assertThat(cash.amount()).isEqualByComparingTo("5.35");
        assertThat(cash.changeDue()).isEqualByComparingTo("4.65");
    }

    @Test
    void cardOnlyExactTotal() {
        UUID cart = cartWith("2");
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CARD, new BigDecimal("10.35"), null))), "cashier");
        assertThat(sale.payments()).hasSize(1);
        assertThat(sale.payments().get(0).method()).isEqualTo("CARD");
    }

    @Test
    void walletTenderSettles() {
        UUID cart = cartWith("2");
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.WALLET, new BigDecimal("10.35"), null))), "cashier");
        assertThat(sale.payments().get(0).method()).isEqualTo("WALLET");
    }

    @Test
    void tendersBelowTotalAreRejected() {
        UUID cart = cartWith("2");
        assertThatThrownBy(() -> sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CARD, new BigDecimal("5.00"), null))), "cashier"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void tendersAboveTotalAreRejected() {
        UUID cart = cartWith("2");
        assertThatThrownBy(() -> sales.checkout(new CheckoutCommand(cart, List.of(
                new TenderInput(PaymentMethod.CARD, new BigDecimal("10.35"), null),
                new TenderInput(PaymentMethod.CARD, new BigDecimal("1.00"), null))), "cashier"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void declinedCardFailsCheckoutAndPersistsNothing() {
        terminal.setApprove(false);
        UUID cart = cartWith("2");
        assertThatThrownBy(() -> sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CARD, new BigDecimal("10.35"), null))), "cashier"))
                .isInstanceOf(DomainException.class);
        // cart stays open — the failed checkout rolled back
        assertThat(carts.getCart(cart).status()).isEqualTo("OPEN");
    }

    @Test
    void emptyTendersRejected() {
        UUID cart = cartWith("2");
        assertThatThrownBy(() -> sales.checkout(new CheckoutCommand(cart, List.of()), "cashier"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void emptyCartCannotCheckout() {
        UUID cart = carts.createCart();
        assertThatThrownBy(() -> sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("5")))), "cashier"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void saleIsRetrievableWithItsPayments() {
        UUID cart = cartWith("2");
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))), "cashier");

        SaleView fetched = sales.getSale(sale.id());
        assertThat(fetched.receiptNumber()).isEqualTo(sale.receiptNumber());
        assertThat(fetched.payments()).hasSize(1);
        assertThat(fetched.payments().get(0).changeDue()).isEqualByComparingTo("9.65");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CheckoutServiceTest`
Expected: FAIL — `TenderInput`, the new `CheckoutCommand`/`SaleView` shapes do not exist (compile errors).

- [ ] **Step 3: Add `TenderInput`**

`src/main/java/com/company/pos/sales/api/TenderInput.java`:

```java
package com.company.pos.sales.api;

import com.company.pos.payment.api.PaymentMethod;
import java.math.BigDecimal;

/**
 * One tender in a checkout. For {@link PaymentMethod#CASH}, {@code amount} may be null (it
 * defaults to the outstanding balance) and {@code tendered} is the cash received. For
 * {@link PaymentMethod#CARD}/{@link PaymentMethod#WALLET}, {@code amount} is required and
 * {@code tendered} is ignored.
 */
public record TenderInput(PaymentMethod method, BigDecimal amount, BigDecimal tendered) {
}
```

- [ ] **Step 4: Change `CheckoutCommand`**

Replace `src/main/java/com/company/pos/sales/api/CheckoutCommand.java`:

```java
package com.company.pos.sales.api;

import java.util.List;
import java.util.UUID;

public record CheckoutCommand(UUID cartId, List<TenderInput> tenders) {
}
```

- [ ] **Step 5: Change `SalePaymentView` and `SaleView`**

Replace `src/main/java/com/company/pos/sales/api/SalePaymentView.java`:

```java
package com.company.pos.sales.api;

import java.math.BigDecimal;

public record SalePaymentView(String method, BigDecimal amount, BigDecimal amountTendered,
        BigDecimal changeDue, String maskedPan) {
}
```

Replace `src/main/java/com/company/pos/sales/api/SaleView.java`:

```java
package com.company.pos.sales.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SaleView(UUID id, String receiptNumber, String status, String currencyCode,
        BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal, Instant createdAt,
        List<SaleLineView> lines, List<SalePaymentView> payments) {
}
```

- [ ] **Step 6: Rewrite the checkout orchestration**

Replace `src/main/java/com/company/pos/sales/application/DefaultSalesService.java`:

```java
package com.company.pos.sales.application;

import com.company.pos.cart.api.CartService;
import com.company.pos.cart.api.CartView;
import com.company.pos.common.events.DomainEvents;
import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.payment.api.PaymentService;
import com.company.pos.payment.api.PaymentView;
import com.company.pos.pricing.api.PricedLine;
import com.company.pos.pricing.api.PricingInput;
import com.company.pos.pricing.api.PricingService;
import com.company.pos.receipt.api.ReceiptData;
import com.company.pos.receipt.api.ReceiptLineData;
import com.company.pos.receipt.api.ReceiptPaymentData;
import com.company.pos.receipt.api.ReceiptService;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SaleCompleted;
import com.company.pos.sales.api.SaleLineView;
import com.company.pos.sales.api.SalePaymentView;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.sales.domain.Sale;
import com.company.pos.sales.domain.SaleLine;
import com.company.pos.sales.infrastructure.SaleRepository;
import com.company.pos.tax.api.TaxLineInput;
import com.company.pos.tax.api.TaxService;
import com.company.pos.tax.api.TaxedCart;
import com.company.pos.tax.api.TaxedLine;
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
class DefaultSalesService implements SalesService {

    private static final Logger log = LoggerFactory.getLogger(DefaultSalesService.class);

    private final CartService carts;
    private final PricingService pricing;
    private final TaxService tax;
    private final PaymentService payments;
    private final ReceiptService receipts;
    private final ConfigurationService config;
    private final SaleRepository sales;
    private final ReceiptNumbering numbering;
    private final DomainEvents events;

    DefaultSalesService(CartService carts, PricingService pricing, TaxService tax,
            PaymentService payments, ReceiptService receipts, ConfigurationService config,
            SaleRepository sales, ReceiptNumbering numbering, DomainEvents events) {
        this.carts = carts;
        this.pricing = pricing;
        this.tax = tax;
        this.payments = payments;
        this.receipts = receipts;
        this.config = config;
        this.sales = sales;
        this.numbering = numbering;
        this.events = events;
    }

    @Override
    public SaleView checkout(CheckoutCommand command, String cashierUsername) {
        CartView cart = carts.getCart(command.cartId());
        if (!"OPEN".equals(cart.status())) {
            throw DomainException.conflict("Cart " + command.cartId() + " is not open");
        }
        if (cart.lines().isEmpty()) {
            throw DomainException.validation("Cannot checkout an empty cart");
        }
        if (command.tenders() == null || command.tenders().isEmpty()) {
            throw DomainException.validation("At least one tender is required");
        }

        // 1. Price the lines
        List<PricingInput> pricingInputs = cart.lines().stream()
                .map(l -> new PricingInput(l.sku(), l.name(), l.quantity(), l.unitPrice(), l.currencyCode()))
                .toList();
        List<PricedLine> priced = pricing.price(pricingInputs);

        // 2. Apply tax
        String currency = cart.currencyCode() != null
                ? cart.currencyCode()
                : config.getString(SettingKey.CURRENCY_CODE);
        BigDecimal rate = new BigDecimal(config.getString(SettingKey.VAT_RATE));
        boolean inclusive = Boolean.parseBoolean(config.getString(SettingKey.TAX_INCLUSIVE));
        List<TaxLineInput> taxInputs = priced.stream()
                .map(p -> new TaxLineInput(p.sku(), p.name(), p.quantity(), p.unitPrice(),
                        p.extendedPrice(), p.currencyCode()))
                .toList();
        TaxedCart taxed = tax.applyTax(taxInputs, rate, inclusive, currency);
        BigDecimal grandTotal = taxed.grandTotal().setScale(2, RoundingMode.HALF_UP);

        // 3. Take the tenders (cash computes change; card/wallet go through the terminal).
        UUID saleId = Identifiers.newId();
        List<PaymentView> recorded = new ArrayList<>();
        BigDecimal applied = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (TenderInput tender : command.tenders()) {
            PaymentMethod method = tender.method();
            if (method == PaymentMethod.CASH) {
                BigDecimal amount = tender.amount() != null
                        ? tender.amount().setScale(2, RoundingMode.HALF_UP)
                        : grandTotal.subtract(applied);
                if (amount.signum() <= 0) {
                    throw DomainException.validation("Cash tender amount must be positive");
                }
                if (tender.tendered() == null) {
                    throw DomainException.validation("Cash tender requires a tendered amount");
                }
                PaymentView pv = payments.recordCash(saleId, currency, amount, tender.tendered());
                applied = applied.add(pv.amount());
                recorded.add(pv);
            } else {
                if (tender.amount() == null) {
                    throw DomainException.validation(method + " tender requires an amount");
                }
                PaymentView pv = payments.recordTerminalPayment(saleId, currency, tender.amount(),
                        method, saleId.toString());
                applied = applied.add(pv.amount());
                recorded.add(pv);
            }
        }
        if (applied.compareTo(grandTotal) != 0) {
            throw DomainException.validation(
                    "Tenders " + applied + " do not match the total " + grandTotal);
        }

        // 4. Persist the immutable sale
        String storeId = config.getString(SettingKey.STORE_ID);
        String terminalId = config.getString(SettingKey.TERMINAL_ID);
        String location = config.getString(SettingKey.INVENTORY_LOCATION);
        String receiptNumber = numbering.nextReceiptNumber(storeId, terminalId);
        Instant now = Instant.now();
        Sale sale = new Sale(saleId, receiptNumber, storeId, terminalId, cashierUsername,
                location, currency, taxed.subtotal(), taxed.taxTotal(), taxed.grandTotal(), now);
        int lineNo = 1;
        for (TaxedLine t : taxed.lines()) {
            sale.addLine(new SaleLine(Identifiers.newId(), sale, lineNo++, t.sku(), t.name(),
                    t.quantity(), t.unitPrice(), t.netAmount(), t.taxAmount(), t.lineTotal(),
                    t.currencyCode()));
        }
        sales.save(sale);

        // 5. Close the cart
        carts.close(command.cartId());

        // 6. Publish SaleCompleted (in-process; inventory decrements synchronously in this tx)
        List<SaleCompleted.SoldLine> soldLines = taxed.lines().stream()
                .map(t -> new SaleCompleted.SoldLine(t.sku(), t.quantity()))
                .toList();
        events.publish(new SaleCompleted(saleId, receiptNumber, location, currency,
                taxed.grandTotal(), soldLines));

        // 7. Print the receipt (best-effort — never fails the sale)
        printReceipt(sale, recorded);

        return toView(sale, recorded);
    }

    @Override
    @Transactional(readOnly = true)
    public SaleView getSale(UUID saleId) {
        Sale sale = sales.findById(saleId)
                .orElseThrow(() -> DomainException.notFound("No sale " + saleId));
        return toView(sale, payments.findBySale(saleId));
    }

    @Override
    @Transactional(readOnly = true)
    public void reprint(UUID saleId) {
        Sale sale = sales.findById(saleId)
                .orElseThrow(() -> DomainException.notFound("No sale " + saleId));
        printReceipt(sale, payments.findBySale(saleId));
    }

    private void printReceipt(Sale sale, List<PaymentView> salePayments) {
        try {
            List<ReceiptLineData> lines = sale.getLines().stream()
                    .map(l -> new ReceiptLineData(l.getName(), l.getQuantity(), l.getUnitPrice(),
                            l.getLineTotal()))
                    .toList();
            List<ReceiptPaymentData> pays = salePayments.stream()
                    .map(p -> new ReceiptPaymentData(p.method(), p.amount(), p.amountTendered(),
                            p.changeDue(), p.maskedPan()))
                    .toList();
            receipts.print(new ReceiptData(sale.getReceiptNumber(), sale.getCashierUsername(),
                    sale.getCreatedAt(), lines, sale.getSubtotal(), sale.getTaxTotal(),
                    sale.getGrandTotal(), pays, sale.getCurrencyCode()));
        } catch (RuntimeException ex) {
            log.warn("Receipt print failed for sale {} ({}) — sale is recorded; reprint available",
                    sale.getId(), sale.getReceiptNumber(), ex);
        }
    }

    private SaleView toView(Sale sale, List<PaymentView> salePayments) {
        List<SaleLineView> lines = new ArrayList<>();
        for (SaleLine l : sale.getLines()) {
            lines.add(new SaleLineView(l.getLineNo(), l.getSku(), l.getName(), l.getQuantity(),
                    l.getUnitPrice(), l.getNetAmount(), l.getTaxAmount(), l.getLineTotal(),
                    l.getCurrencyCode()));
        }
        List<SalePaymentView> paymentViews = salePayments.stream()
                .map(p -> new SalePaymentView(p.method(), p.amount(), p.amountTendered(),
                        p.changeDue(), p.maskedPan()))
                .toList();
        return new SaleView(sale.getId(), sale.getReceiptNumber(), sale.getStatus(),
                sale.getCurrencyCode(), sale.getSubtotal(), sale.getTaxTotal(), sale.getGrandTotal(),
                sale.getCreatedAt(), lines, paymentViews);
    }
}
```

- [ ] **Step 7: Run the checkout test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CheckoutServiceTest`
Expected: PASS (all scenarios).

- [ ] **Step 8: Fix the remaining `CheckoutCommand` call site (inventory test)**

In `src/test/java/com/company/pos/inventory/SaleDecrementsStockTest.java`, line 58 constructs the old command. Add imports `com.company.pos.payment.api.PaymentMethod`, `com.company.pos.sales.api.TenderInput`, `java.util.List`, and replace the checkout call:

```java
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100")))), "cashier");
```

- [ ] **Step 9: Update the sales controller test**

In `src/test/java/com/company/pos/sales/SalesControllerTest.java`, change the two checkout request bodies from `amountTendered` to a `tenders` array, and the assertion from `$.payment.changeDue` to `$.payments[0].changeDue`:

In `checkoutViaRestReturnsSale`, replace the `.content(...)` and the `$.payment` expectation:

```java
        mvc.perform(post("/sales").with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"cartId\":\"" + cart + "\",\"tenders\":[{\"method\":\"CASH\",\"tendered\":20.00}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.grandTotal").value(10.35))
                .andExpect(jsonPath("$.payments[0].changeDue").value(9.65))
                .andExpect(jsonPath("$.receiptNumber", matchesPattern("S01-T01-\\d{6}")));
```

In `reprintReturnsNoContent`, replace the checkout body:

```java
                        .content("{\"cartId\":\"" + cart + "\",\"tenders\":[{\"method\":\"CASH\",\"tendered\":10.00}]}"))
```

- [ ] **Step 10: Update the end-to-end test**

In `src/test/java/com/company/pos/CashSaleEndToEndTest.java`, step 4, change the checkout body and the payment assertion:

```java
        String sale = mvc.perform(post("/sales").header("Authorization", token)
                        .contentType("application/json")
                        .content("{\"cartId\":\"" + cartId + "\",\"tenders\":[{\"method\":\"CASH\",\"tendered\":20.00}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotal").value(9.00))
                .andExpect(jsonPath("$.taxTotal").value(1.35))
                .andExpect(jsonPath("$.grandTotal").value(10.35))
                .andExpect(jsonPath("$.payments[0].changeDue").value(9.65))
                .andExpect(jsonPath("$.receiptNumber", matchesPattern("S01-T01-\\d{6}")))
                .andReturn().getResponse().getContentAsString();
```

- [ ] **Step 11: Run the full suite to verify everything is green**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test`
Expected: PASS — the whole suite compiles and passes (this is the first clean whole-tree compile since Task 2). If anything else references the old `SaleView.payment()` / `CheckoutCommand` / `CashPaymentView`, fix it the same way and re-run.

- [ ] **Step 12: Verify module boundaries**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ModularityTests`
Expected: PASS.

- [ ] **Step 13: Commit**

```bash
git add src/main/java/com/company/pos/sales/ \
        src/test/java/com/company/pos/sales/CheckoutServiceTest.java \
        src/test/java/com/company/pos/sales/SalesControllerTest.java \
        src/test/java/com/company/pos/inventory/SaleDecrementsStockTest.java \
        src/test/java/com/company/pos/CashSaleEndToEndTest.java
git commit -m "feat(sales): single-call multi-tender checkout (cash + card/wallet split payment)"
```

---

### Task 5: Cart — hold/resume, guarded void-line, terminal scoping

Let a cashier park an open cart and resume it later, list held carts for the terminal, and void a line with a proper not-found guard (and gap-free line numbering). Carts gain a `terminalId` (set from configuration at creation) so held carts can be listed per terminal.

**Files:**
- Modify: `src/main/java/com/company/pos/cart/package-info.java`
- Modify: `src/main/java/com/company/pos/cart/domain/Cart.java`
- Modify: `src/main/java/com/company/pos/cart/domain/CartLine.java`
- Modify: `src/main/java/com/company/pos/cart/api/CartService.java`
- Modify: `src/main/java/com/company/pos/cart/infrastructure/CartRepository.java`
- Modify: `src/main/java/com/company/pos/cart/application/DefaultCartService.java`
- Modify: `src/main/java/com/company/pos/cart/web/CartController.java`
- Create: `src/main/resources/db/migration/cart/V11__cart_terminal_id.sql`
- Test: `src/test/java/com/company/pos/cart/CartHoldResumeTest.java`

**Interfaces:**
- Consumes: `ConfigurationService.getString(SettingKey.TERMINAL_ID)`; existing `ProductCatalog`, `Identifiers`, `DomainException`.
- Produces (added to `CartService`):
  - `CartView hold(UUID cartId)` — `OPEN → HELD`; conflict if not open.
  - `CartView resume(UUID cartId)` — `HELD → OPEN`; conflict if not held.
  - `List<CartView> listHeld(String terminalId)`.
  - `removeLine` now throws `DomainException.notFound` if the sku is not on the cart, and re-sequences `lineNo` after removal.

- [ ] **Step 1: Add `configuration :: api` to the cart module**

Replace `src/main/java/com/company/pos/cart/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "product :: api", "configuration :: api" })
package com.company.pos.cart;
```

- [ ] **Step 2: Write the failing test**

`src/test/java/com/company/pos/cart/CartHoldResumeTest.java`:

```java
package com.company.pos.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.cart.api.CartService;
import com.company.pos.cart.api.CartView;
import com.company.pos.common.exception.DomainException;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class CartHoldResumeTest {

    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("CHIP", "Chips", "SNK", "Snacks", "bcCHIP",
                "EA", new BigDecimal("3.00"), "SAR", 1, true));
        productSync.sync();
    }

    @Test
    void holdThenResumeRoundTrips() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));

        assertThat(carts.hold(cart).status()).isEqualTo("HELD");
        assertThat(carts.getCart(cart).status()).isEqualTo("HELD");
        assertThat(carts.resume(cart).status()).isEqualTo("OPEN");
    }

    @Test
    void cannotAddToHeldCart() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("1"));
        carts.hold(cart);
        assertThatThrownBy(() -> carts.addLine(cart, "COLA", new BigDecimal("1")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void resumingAnOpenCartIsRejected() {
        UUID cart = carts.createCart();
        assertThatThrownBy(() -> carts.resume(cart)).isInstanceOf(DomainException.class);
    }

    @Test
    void listHeldReturnsHeldCartsForTerminal() {
        UUID held = carts.createCart();
        carts.addLine(held, "COLA", new BigDecimal("1"));
        carts.hold(held);
        UUID open = carts.createCart();
        carts.addLine(open, "COLA", new BigDecimal("1"));

        // createCart() stamps the configured terminal id (default T01)
        assertThat(carts.listHeld("T01")).extracting(CartView::cartId).contains(held).doesNotContain(open);
    }

    @Test
    void voidLineRemovesItAndRenumbersRemaining() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("1"));
        carts.addLine(cart, "CHIP", new BigDecimal("1"));

        CartView view = carts.removeLine(cart, "COLA");
        assertThat(view.lines()).hasSize(1);
        assertThat(view.lines().get(0).sku()).isEqualTo("CHIP");
    }

    @Test
    void voidingAMissingLineIsRejected() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("1"));
        assertThatThrownBy(() -> carts.removeLine(cart, "NOPE"))
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CartHoldResumeTest`
Expected: FAIL — `hold`/`resume`/`listHeld` do not exist (compile error).

- [ ] **Step 4: Add `terminalId`, `hold`/`resume`, and line re-numbering to `Cart`**

Edit `src/main/java/com/company/pos/cart/domain/Cart.java`. Add a `terminalId` column, take it in the constructor, expose a getter, add `hold()`/`resume()` (with guards), make `removeLine` re-number, and add `isHeld()`. The full file:

```java
package com.company.pos.cart.domain;

import com.company.pos.common.exception.DomainException;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "cart")
public class Cart {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "terminal_id", length = 16)
    private String terminalId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<CartLine> lines = new ArrayList<>();

    protected Cart() {
        // JPA
    }

    public Cart(UUID id, String terminalId, Instant createdAt) {
        this.id = id;
        this.terminalId = terminalId;
        this.status = "OPEN";
        this.createdAt = createdAt;
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

    public String getCurrencyCode() {
        return currencyCode;
    }

    public List<CartLine> getLines() {
        return lines;
    }

    public Optional<CartLine> findLine(String sku) {
        return lines.stream().filter(l -> l.getSku().equals(sku)).findFirst();
    }

    public void addLine(String sku, String name, BigDecimal quantity, BigDecimal unitPrice, String currency) {
        if (this.currencyCode == null) {
            this.currencyCode = currency;
        }
        findLine(sku).ifPresentOrElse(
                line -> line.addQuantity(quantity),
                () -> lines.add(new CartLine(this, lines.size() + 1, sku, name, quantity, unitPrice, currency)));
    }

    public void setLineQuantity(String sku, BigDecimal quantity) {
        lines.stream().filter(l -> l.getSku().equals(sku)).findFirst()
                .ifPresent(line -> line.setQuantity(quantity));
    }

    public void removeLine(String sku) {
        lines.removeIf(l -> l.getSku().equals(sku));
        renumber();
    }

    private void renumber() {
        int lineNo = 1;
        for (CartLine line : lines) {
            line.setLineNo(lineNo++);
        }
    }

    public void hold() {
        if (!isOpen()) {
            throw DomainException.conflict("Only an open cart can be held");
        }
        this.status = "HELD";
    }

    public void resume() {
        if (!isHeld()) {
            throw DomainException.conflict("Only a held cart can be resumed");
        }
        this.status = "OPEN";
    }

    public void close() {
        this.status = "CHECKED_OUT";
    }

    public boolean isOpen() {
        return "OPEN".equals(status);
    }

    public boolean isHeld() {
        return "HELD".equals(status);
    }
}
```

> `cart.domain` now references `common.exception.DomainException` — `cart` already allows `common`, so no boundary change.

- [ ] **Step 5: Add the `setLineNo` mutator to `CartLine`**

Edit `src/main/java/com/company/pos/cart/domain/CartLine.java` — add a package-private mutator next to `setQuantity`:

```java
    void setLineNo(int lineNo) {
        this.lineNo = lineNo;
    }
```

- [ ] **Step 6: Extend the `CartService` interface**

Replace `src/main/java/com/company/pos/cart/api/CartService.java`:

```java
package com.company.pos.cart.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface CartService {

    UUID createCart();

    CartView addLine(UUID cartId, String sku, BigDecimal quantity);

    CartView updateLine(UUID cartId, String sku, BigDecimal quantity);

    CartView removeLine(UUID cartId, String sku);

    CartView getCart(UUID cartId);

    CartView hold(UUID cartId);

    CartView resume(UUID cartId);

    List<CartView> listHeld(String terminalId);

    void close(UUID cartId);
}
```

- [ ] **Step 7: Add the held-cart query**

Replace `src/main/java/com/company/pos/cart/infrastructure/CartRepository.java`:

```java
package com.company.pos.cart.infrastructure;

import com.company.pos.cart.domain.Cart;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartRepository extends JpaRepository<Cart, UUID> {

    List<Cart> findByStatusAndTerminalId(String status, String terminalId);
}
```

- [ ] **Step 8: Implement the service changes**

Edit `src/main/java/com/company/pos/cart/application/DefaultCartService.java`. Add the `ConfigurationService` dependency, stamp the terminal id at creation, guard `removeLine`, and implement `hold`/`resume`/`listHeld`. Add imports: `com.company.pos.configuration.api.ConfigurationService`, `com.company.pos.configuration.api.SettingKey`.

Change the field/constructor block:

```java
    private final CartRepository carts;
    private final ProductCatalog catalogue;
    private final ConfigurationService config;

    DefaultCartService(CartRepository carts, ProductCatalog catalogue, ConfigurationService config) {
        this.carts = carts;
        this.catalogue = catalogue;
        this.config = config;
    }
```

Change `createCart()` to stamp the terminal id:

```java
    @Override
    public UUID createCart() {
        Cart cart = new Cart(Identifiers.newId(), config.getString(SettingKey.TERMINAL_ID), Instant.now());
        carts.save(cart);
        return cart.getId();
    }
```

Change `removeLine` to guard the missing sku:

```java
    @Override
    public CartView removeLine(UUID cartId, String sku) {
        Cart cart = openCart(cartId);
        if (cart.findLine(sku).isEmpty()) {
            throw DomainException.notFound("No line for sku " + sku);
        }
        cart.removeLine(sku);
        return toView(cart);
    }
```

Add the three new methods (place them before `close`):

```java
    @Override
    public CartView hold(UUID cartId) {
        Cart cart = load(cartId);
        cart.hold();
        return toView(cart);
    }

    @Override
    public CartView resume(UUID cartId) {
        Cart cart = load(cartId);
        cart.resume();
        return toView(cart);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CartView> listHeld(String terminalId) {
        return carts.findByStatusAndTerminalId("HELD", terminalId).stream()
                .map(this::toView)
                .toList();
    }
```

> Keep the existing `load`, `openCart`, `requirePositive`, and `toView` helpers unchanged. `hold`/`resume` use `load` (not `openCart`) because the entity methods enforce the correct source state and raise the right `conflict` message.

- [ ] **Step 9: Add the REST endpoints**

Edit `src/main/java/com/company/pos/cart/web/CartController.java`. Add imports `java.util.List`, `org.springframework.web.bind.annotation.RequestParam`. Add three endpoints (after `removeLine`):

```java
    @PutMapping("/carts/{cartId}/hold")
    CartView hold(@PathVariable UUID cartId) {
        return carts.hold(cartId);
    }

    @PutMapping("/carts/{cartId}/resume")
    CartView resume(@PathVariable UUID cartId) {
        return carts.resume(cartId);
    }

    @GetMapping("/carts/held")
    List<CartView> held(@RequestParam String terminalId) {
        return carts.listHeld(terminalId);
    }
```

> `GET /carts/held` is a fixed path; it does not collide with `GET /carts/{cartId}` because Spring prefers the literal segment over the path variable.

- [ ] **Step 10: Write the V11 migration (store-server)**

Create `src/main/resources/db/migration/cart/V11__cart_terminal_id.sql`:

```sql
ALTER TABLE cart ADD COLUMN terminal_id VARCHAR(16);
```

> No `flyway.locations` change: `classpath:db/migration/cart` is already listed (it holds V6).

- [ ] **Step 11: Run the cart test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CartHoldResumeTest`
Expected: PASS (all six tests).

- [ ] **Step 12: Run the existing cart tests + boundaries (no regressions)**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CartServiceTest,CartControllerTest,ModularityTests`
Expected: PASS — `createCart()` signature is unchanged for callers; `cart → configuration :: api` is declared.

- [ ] **Step 13: Commit**

```bash
git add src/main/java/com/company/pos/cart/ \
        src/main/resources/db/migration/cart/V11__cart_terminal_id.sql \
        src/test/java/com/company/pos/cart/CartHoldResumeTest.java
git commit -m "feat(cart): hold/resume, guarded void-line with renumber, terminal-scoped held carts"
```

---

### Task 6: Capstone — split-payment + hold/resume end-to-end, docs, full verify

A whole-stack REST scenario proving the advanced sell path, plus the documentation/ledger updates and the final full verification (including the Testcontainers store-server migration run through V11).

**Files:**
- Create: `src/test/java/com/company/pos/AdvancedCheckoutEndToEndTest.java`
- Modify: `.superpowers/sdd/progress.md`
- Modify: `docs/superpowers/plans/2026-06-28-phase-2b-advanced-checkout.md` (append an AS-BUILT note)

**Interfaces:**
- Consumes: REST endpoints `/auth/login`, `/sync/erp`, `/carts`, `/carts/{id}/lines`, `/carts/{id}/hold`, `/carts/{id}/resume`, `/carts/held`, `/sales`, `/sales/{id}`, `/inventory/{sku}`.

- [ ] **Step 1: Write the end-to-end test**

`src/test/java/com/company/pos/AdvancedCheckoutEndToEndTest.java`:

```java
package com.company.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.util.Identifiers;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import com.company.pos.device.infrastructure.InMemoryPrinter;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
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
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Transactional
class AdvancedCheckoutEndToEndTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    FakeErpClient fake;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    InMemoryPrinter printer;
    @Autowired
    InMemoryPaymentTerminal terminal;

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
        terminal.setApprove(true);
    }

    @AfterEach
    void reset() {
        terminal.setApprove(true);
    }

    @Test
    void holdResumeThenSplitCardAndCashCheckout() throws Exception {
        String managerToken = login("manager");
        mvc.perform(post("/sync/erp").header("Authorization", managerToken))
                .andExpect(status().isOk());
        String token = login("cashier");

        // Ring up 2 colas (total 10.35)
        String createdCart = mvc.perform(post("/carts").header("Authorization", token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String cartId = JsonPath.read(createdCart, "$.cartId");
        mvc.perform(post("/carts/" + cartId + "/lines").header("Authorization", token)
                        .contentType("application/json").content("{\"sku\":\"COLA\",\"quantity\":2}"))
                .andExpect(status().isOk());

        // Hold, then it appears in the terminal's held list, then resume
        mvc.perform(put("/carts/" + cartId + "/hold").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HELD"));
        mvc.perform(get("/carts/held?terminalId=T01").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cartId").value(cartId));
        mvc.perform(put("/carts/" + cartId + "/resume").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        // Split: 5.00 on card, remainder (5.35) on cash tendered 10.00 -> change 4.65
        String sale = mvc.perform(post("/sales").header("Authorization", token)
                        .contentType("application/json")
                        .content("{\"cartId\":\"" + cartId + "\",\"tenders\":["
                                + "{\"method\":\"CARD\",\"amount\":5.00},"
                                + "{\"method\":\"CASH\",\"tendered\":10.00}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.grandTotal").value(10.35))
                .andExpect(jsonPath("$.payments[0].method").value("CARD"))
                .andExpect(jsonPath("$.payments[0].maskedPan").value("**** **** **** 4242"))
                .andExpect(jsonPath("$.payments[1].method").value("CASH"))
                .andExpect(jsonPath("$.payments[1].changeDue").value(4.65))
                .andExpect(jsonPath("$.receiptNumber", matchesPattern("S01-T01-\\d{6}")))
                .andReturn().getResponse().getContentAsString();
        String receiptNumber = JsonPath.read(sale, "$.receiptNumber");

        // Receipt printed with the sale's number
        assertThat(printer.lastReceipt()).anyMatch(l -> l.text().contains(receiptNumber));

        // Stock decremented 20 -> 18
        mvc.perform(get("/inventory/COLA").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(18));
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

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=AdvancedCheckoutEndToEndTest`
Expected: PASS.

- [ ] **Step 3: Full verify (whole suite + boundaries + store-server migrations)**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -B verify`
Expected: BUILD SUCCESS. This runs the full JUnit suite, `ModularityTests` (boundary verify), and `DatabaseStoreServerTest` (Testcontainers Postgres applying Flyway V2–V11 then `ddl-auto=validate` against the entities — proves the V10 payment columns and V11 cart column match `Payment`/`Cart`). If `DatabaseStoreServerTest` fails on schema validation, reconcile the migration columns with the entity `@Column` definitions and re-run.

- [ ] **Step 4: Update the progress ledger**

Append a `=== PHASE 2b ===` section to `.superpowers/sdd/progress.md` recording: branch, the six tasks, the API changes (PaymentMethod CARD/WALLET, PaymentView replacing CashPaymentView, SaleView.payments list, CheckoutCommand tenders, Cart HELD + terminalId), migrations V10/V11, and the final `./mvnw -B verify` test count. Note the known limitation: a declined/partial terminal tender that approves at the fake terminal but then fails the total-match check rolls back the checkout tx — for a real terminal this needs a compensating void (deferred to the payment-integration adapter / Phase 3 outbox work).

- [ ] **Step 5: Append the AS-BUILT note to this plan**

At the bottom of `docs/superpowers/plans/2026-06-28-phase-2b-advanced-checkout.md`, add an "## AS-BUILT" section noting any deviations discovered during execution (e.g. additional call sites updated, any rounding/edge adjustments).

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/company/pos/AdvancedCheckoutEndToEndTest.java \
        .superpowers/sdd/progress.md \
        docs/superpowers/plans/2026-06-28-phase-2b-advanced-checkout.md
git commit -m "test(sales): advanced-checkout e2e (hold/resume + split card/cash); phase 2b docs"
```

---

## Self-Review

**1. Spec coverage** (locked scope: card+QR/wallet tenders, split/mixed payment, hold/resume + void item; cashdrawer/shift + returns deferred):
- Card + QR/wallet tenders → Task 1 (terminal fake) + Task 2 (`recordTerminalPayment`, `CARD`/`WALLET`). ✓
- Split/mixed payment → Task 4 (single-call multi-tender orchestration + validation). ✓
- Hold/resume → Task 5 (`hold`/`resume`, `HELD` status, `listHeld`). ✓
- Void item → Task 5 (guarded `removeLine` + renumber). ✓
- Receipt reflects all tenders → Task 3. ✓
- cashdrawer / shift / returns → explicitly out of scope (stated in Architecture + Global Constraints). ✓

**2. Placeholder scan:** No `TBD`/`handle edge cases`/"similar to Task N" — every code step shows full content. The Task 2 Step 11 / Task 3 Step 6 notes about whole-module compile are real sequencing guidance (the first guaranteed clean compile is Task 4 Step 11), not placeholders.

**3. Type consistency:**
- `PaymentView(saleId, method:String, amount, amountTendered, changeDue, maskedPan, currencyCode)` — produced in Task 2, consumed identically in Task 4 (`p.method()`, `p.amount()`, `p.amountTendered()`, `p.changeDue()`, `p.maskedPan()`). ✓
- `PaymentMethod{CASH,CARD,WALLET}` — Task 2; used by `TenderInput`/orchestrator in Task 4. ✓
- `ReceiptData(... List<ReceiptPaymentData> payments, currencyCode)` — Task 3; built in Task 4 `printReceipt`. ✓
- `CheckoutCommand(cartId, List<TenderInput>)` + `TenderInput(method, amount, tendered)` — Task 4; constructed in updated tests (Task 4 Steps 8–10). ✓
- `SaleView(... List<SaleLineView> lines, List<SalePaymentView> payments)` — Task 4; asserted via `$.payments[...]` in tests. ✓
- `CartService.hold/resume/listHeld` + `Cart(id, terminalId, createdAt)` — Task 5; `createCart()` signature unchanged so existing callers/tests still compile. ✓
- Migrations V10 (payment: `masked_pan VARCHAR(25)`, `auth_token VARCHAR(64)`) and V11 (cart: `terminal_id VARCHAR(16)`) match the entity `@Column(length=…)` exactly. ✓

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-28-phase-2b-advanced-checkout.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?

---

## AS-BUILT

Execution completed 2026-06-28. Final `./mvnw -B verify`: **96 tests, 0 failures, BUILD SUCCESS**. `DatabaseStoreServerTest` ran (Docker available; Testcontainers Postgres 16-alpine; Flyway V1–V11 applied; `ddl-auto=validate` passed — V10 `masked_pan`/`auth_token` and V11 `terminal_id` columns validated against `Payment`/`Cart` entities).

### Deviations from the plan

1. **Tasks 2, 3, 4 executed as one merged commit.** Plan Tasks 2 (payment), 3 (receipt), and 4 (sales) each prescribe an isolated compile-and-commit step. This single-module Maven project cannot compile them independently — `DefaultSalesService` references both the new `ReceiptPaymentData` (Task 3) and the new `PaymentView`/`recordTerminalPayment` (Task 2) simultaneously. The three tasks were implemented and committed as a single merged dispatch. The per-task commit and isolated-test-run steps in those task sections do not apply to this project structure.

2. **CartLineView intentionally does not expose `lineNo`.** The `renumber()` logic is internal to the `Cart` aggregate. `CartLineView` has no `lineNo` field by design (per brief). The renumber invariant is tested observably via `@OrderBy("sku")` assertions, not numeric sequence assertions.

3. **No additional call sites required reconciliation.** All compile-time usages of the old `CashPaymentView`, single-tender `CheckoutCommand`, and `SaleView.payment` (singular) were already updated in the merged Task 2–4 commit. No further regressions were found during the full verify run.

4. **No migration reconciliation required.** `DatabaseStoreServerTest` passed on the first run — V10 (`masked_pan VARCHAR(25)`, `auth_token VARCHAR(64)`) and V11 (`terminal_id VARCHAR(16)`) matched the entity `@Column(length=…)` annotations exactly as specified in the plan's self-review.
