# Phase 5 — Manual Discounts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an operator apply manual line-level and transaction-level discounts (percentage or fixed amount) at checkout, with a role-based cap, a required reason code, and a fully itemized audit trail through tax, persistence, receipt, ERP, and returns.

**Architecture:** Insert a discount step between pricing and tax in `DefaultSalesService.checkout`. A pure, unit-tested `DiscountCalculator` (in the `sales` module) resolves percentages to amounts, caps fixed amounts to their base, enforces the cashier cap by role, and allocates the transaction discount proportionally across lines (last line absorbs the rounding remainder). Discounts reduce each line's *extended* amount before `TaxService` runs, so VAT is computed on the discounted base with no change to the tax module, and the persisted post-discount `net_amount` makes Phase 4 returns refund the discounted amount unchanged.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith 1.2.5, Spring Data JPA, Flyway (store-server/PostgreSQL) + Hibernate `ddl-auto=update` (embedded/SQLite), Spring Security (JWT, `ROLE_` authorities), JUnit 5 + AssertJ + Awaitility + spring-security-test, Testcontainers.

## Global Constraints

- **JDK 21 is required.** Every Maven command must run as `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw …` (system default Java is 17).
- **Build tool is the committed Maven wrapper** `./mvnw` — never Gradle, never system `mvn`.
- **Money is `BigDecimal`**: amounts scale 2 HALF_UP, quantity scale 3, unit price scale 4. Compare with `compareTo`/`signum`; never `double`/`float`.
- **Errors use the existing `DomainException` taxonomy**: `validation` → HTTP 400, `conflict` → 409, `notFound` → 404 (mapped by `ApiExceptionHandler`).
- **Module boundaries must stay green** — `ModularityTests` (`ApplicationModules.of(PosApplication.class).verify()`) must pass; all new types live in the `sales` module (no new module).
- **Discounts plug in upstream of tax**: they reduce the line *extended* amount; `TaxService` is unchanged and derives net/tax/line_total for both tax-exclusive and tax-inclusive modes.
- **Reason codes and caps come from `ConfigurationService`** (keys added to the `SettingKey` enum with inline defaults; the service falls back to the enum default when no DB row exists — no seed migration needed).
- **Persisted `net_amount` is post-discount** so returns are unaffected; do not change any returns code.
- **Test isolation convention**: e2e tests that commit (after-commit listeners) are NOT `@Transactional`, use `DatabaseCleaner.clean()` in both `@BeforeEach` and `@AfterEach`, and reset singleton fakes (`fake.clear()`, `terminal.setApprove(true)`).
- **Tax/VAT** default: rate `0.15`, `tax.inclusive=false` (exclusive).

---

### Task 1: Discount API types + `CheckoutCommand` fields

**Files:**
- Create: `src/main/java/com/company/pos/sales/api/DiscountType.java`
- Create: `src/main/java/com/company/pos/sales/api/DiscountInput.java`
- Modify: `src/main/java/com/company/pos/sales/api/CheckoutCommand.java`
- Test: `src/test/java/com/company/pos/sales/CheckoutCommandTest.java`

**Interfaces:**
- Produces:
  - `enum DiscountType { PERCENT, AMOUNT }`
  - `record DiscountInput(DiscountType type, BigDecimal value, String reasonCode)`
  - `record CheckoutCommand(UUID cartId, List<TenderInput> tenders, Map<String,DiscountInput> lineDiscounts, DiscountInput transactionDiscount)` with a compact constructor that normalizes a null `lineDiscounts` to `Map.of()`, and a convenience constructor `CheckoutCommand(UUID cartId, List<TenderInput> tenders)` delegating to the canonical with `Map.of()` and `null`.
- Consumes: existing `TenderInput`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/company/pos/sales/CheckoutCommandTest.java`:

```java
package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.DiscountType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CheckoutCommandTest {

    @Test
    void convenienceConstructorDefaultsDiscountsToEmpty() {
        CheckoutCommand cmd = new CheckoutCommand(UUID.randomUUID(),
                List.of(new com.company.pos.sales.api.TenderInput(PaymentMethod.CASH, null,
                        new BigDecimal("5.00"))));

        assertThat(cmd.lineDiscounts()).isEmpty();
        assertThat(cmd.transactionDiscount()).isNull();
    }

    @Test
    void compactConstructorNormalizesNullLineDiscounts() {
        CheckoutCommand cmd = new CheckoutCommand(UUID.randomUUID(), List.of(), null, null);

        assertThat(cmd.lineDiscounts()).isEmpty();
    }

    @Test
    void carriesProvidedDiscounts() {
        DiscountInput line = new DiscountInput(DiscountType.PERCENT, new BigDecimal("10"), "LOYALTY");
        DiscountInput txn = new DiscountInput(DiscountType.AMOUNT, new BigDecimal("5.00"), "MANAGER_COMP");
        CheckoutCommand cmd = new CheckoutCommand(UUID.randomUUID(), List.of(),
                Map.of("COLA", line), txn);

        assertThat(cmd.lineDiscounts()).containsEntry("COLA", line);
        assertThat(cmd.transactionDiscount()).isEqualTo(txn);
        assertThat(txn.type()).isEqualTo(DiscountType.AMOUNT);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CheckoutCommandTest`
Expected: COMPILATION ERROR — `DiscountType`, `DiscountInput`, and the 4-arg `CheckoutCommand` do not exist.

- [ ] **Step 3: Write the implementation**

`src/main/java/com/company/pos/sales/api/DiscountType.java`:

```java
package com.company.pos.sales.api;

public enum DiscountType {
    PERCENT,
    AMOUNT
}
```

`src/main/java/com/company/pos/sales/api/DiscountInput.java`:

```java
package com.company.pos.sales.api;

import java.math.BigDecimal;

/** A manual discount as entered: a percentage or a fixed amount, with a required reason code. */
public record DiscountInput(DiscountType type, BigDecimal value, String reasonCode) {
}
```

Replace `src/main/java/com/company/pos/sales/api/CheckoutCommand.java`:

```java
package com.company.pos.sales.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CheckoutCommand(UUID cartId, List<TenderInput> tenders,
        Map<String, DiscountInput> lineDiscounts, DiscountInput transactionDiscount) {

    public CheckoutCommand {
        if (lineDiscounts == null) {
            lineDiscounts = Map.of();
        }
    }

    /** Convenience: a checkout with no discounts (used by existing callers and tests). */
    public CheckoutCommand(UUID cartId, List<TenderInput> tenders) {
        this(cartId, tenders, Map.of(), null);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CheckoutCommandTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/pos/sales/api/DiscountType.java \
        src/main/java/com/company/pos/sales/api/DiscountInput.java \
        src/main/java/com/company/pos/sales/api/CheckoutCommand.java \
        src/test/java/com/company/pos/sales/CheckoutCommandTest.java
git commit -m "feat(sales): discount API types + CheckoutCommand discount fields"
```

---

### Task 2: `DiscountCalculator` (pure logic) + unit tests

**Files:**
- Create: `src/main/java/com/company/pos/sales/application/DiscountedLine.java`
- Create: `src/main/java/com/company/pos/sales/application/DiscountResult.java`
- Create: `src/main/java/com/company/pos/sales/application/DiscountCalculator.java`
- Test: `src/test/java/com/company/pos/sales/DiscountCalculatorTest.java`

**Interfaces:**
- Consumes: `PricedLine(String sku, String name, BigDecimal quantity, BigDecimal unitPrice, String currencyCode, BigDecimal extendedPrice)` (from `pricing.api`); `DiscountInput`, `DiscountType` (Task 1); `DomainException` (`common.exception`).
- Produces (package-private, used by Task 4):
  - `record DiscountedLine(String sku, String name, BigDecimal quantity, BigDecimal unitPrice, String currencyCode, BigDecimal grossAmount, BigDecimal lineDiscountAmount, DiscountType lineDiscountType, String lineDiscountReason, BigDecimal discountedExtended)`
  - `record DiscountResult(List<DiscountedLine> lines, BigDecimal txnDiscountAmount, DiscountType txnDiscountType, String txnDiscountReason, BigDecimal discountTotal)`
  - `@Component class DiscountCalculator` with method
    `DiscountResult apply(List<PricedLine> priced, Map<String,DiscountInput> lineDiscounts, DiscountInput txnDiscount, boolean callerIsManager, BigDecimal cashierMaxPercent, BigDecimal cashierMaxAmount, Set<String> reasonCodes)`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/company/pos/sales/DiscountCalculatorTest.java`:

```java
package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.pricing.api.PricedLine;
import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.DiscountType;
import com.company.pos.sales.application.DiscountCalculator;
import com.company.pos.sales.application.DiscountResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiscountCalculatorTest {

    private final DiscountCalculator calc = new DiscountCalculator();
    private static final BigDecimal MAX_PCT = new BigDecimal("10");
    private static final BigDecimal MAX_AMT = new BigDecimal("20.00");
    private static final Set<String> REASONS = Set.of("DAMAGED", "PRICE_MATCH", "LOYALTY", "MANAGER_COMP");

    private PricedLine line(String sku, String qty, String unit, String extended) {
        return new PricedLine(sku, sku, new BigDecimal(qty), new BigDecimal(unit), "SAR",
                new BigDecimal(extended));
    }

    @Test
    void percentLineDiscountResolvesAndReducesExtended() {
        DiscountResult r = calc.apply(List.of(line("COLA", "2", "4.50", "9.00")),
                Map.of("COLA", new DiscountInput(DiscountType.PERCENT, new BigDecimal("10"), "LOYALTY")),
                null, false, MAX_PCT, MAX_AMT, REASONS);

        assertThat(r.lines()).hasSize(1);
        assertThat(r.lines().get(0).grossAmount()).isEqualByComparingTo("9.00");
        assertThat(r.lines().get(0).lineDiscountAmount()).isEqualByComparingTo("0.90");
        assertThat(r.lines().get(0).discountedExtended()).isEqualByComparingTo("8.10");
        assertThat(r.lines().get(0).lineDiscountType()).isEqualTo(DiscountType.PERCENT);
        assertThat(r.lines().get(0).lineDiscountReason()).isEqualTo("LOYALTY");
        assertThat(r.discountTotal()).isEqualByComparingTo("0.90");
        assertThat(r.txnDiscountAmount()).isEqualByComparingTo("0");
    }

    @Test
    void fixedAmountLineDiscountCapsToBase() {
        // value 8.00 exceeds the line base 5.00 -> capped to 5.00. Manager (100% of line) so no cap block.
        DiscountResult r = calc.apply(List.of(line("PEN", "1", "5.00", "5.00")),
                Map.of("PEN", new DiscountInput(DiscountType.AMOUNT, new BigDecimal("8.00"), "DAMAGED")),
                null, true, MAX_PCT, MAX_AMT, REASONS);

        assertThat(r.lines().get(0).lineDiscountAmount()).isEqualByComparingTo("5.00");
        assertThat(r.lines().get(0).discountedExtended()).isEqualByComparingTo("0.00");
    }

    @Test
    void transactionDiscountAllocatesProportionallyWithLastLineRemainder() {
        DiscountResult r = calc.apply(
                List.of(line("A", "1", "10.00", "10.00"), line("B", "1", "5.00", "5.00")),
                Map.of(),
                new DiscountInput(DiscountType.AMOUNT, new BigDecimal("3.00"), "MANAGER_COMP"),
                true, MAX_PCT, MAX_AMT, REASONS);

        // cartBase 15.00: A share = 3 * 10/15 = 2.00, B (last) = 3 - 2 = 1.00
        assertThat(r.lines().get(0).discountedExtended()).isEqualByComparingTo("8.00");
        assertThat(r.lines().get(1).discountedExtended()).isEqualByComparingTo("4.00");
        assertThat(r.txnDiscountAmount()).isEqualByComparingTo("3.00");
        assertThat(r.discountTotal()).isEqualByComparingTo("3.00");
    }

    @Test
    void lineAndTransactionDiscountsCombineAndReconcile() {
        DiscountResult r = calc.apply(
                List.of(line("COLA", "2", "4.50", "9.00"), line("WATER", "3", "2.00", "6.00")),
                Map.of("COLA", new DiscountInput(DiscountType.PERCENT, new BigDecimal("10"), "LOYALTY")),
                new DiscountInput(DiscountType.AMOUNT, new BigDecimal("4.10"), "MANAGER_COMP"),
                true, MAX_PCT, MAX_AMT, REASONS);

        // COLA: gross 9.00 - 0.90 = postLine 8.10; WATER postLine 6.00; cartBase 14.10
        // COLA txn share = 4.10 * 8.10/14.10 = 2.36; WATER (last) = 4.10 - 2.36 = 1.74
        assertThat(r.lines().get(0).discountedExtended()).isEqualByComparingTo("5.74");
        assertThat(r.lines().get(1).discountedExtended()).isEqualByComparingTo("4.26");
        assertThat(r.discountTotal()).isEqualByComparingTo("5.00"); // 0.90 + 4.10
    }

    @Test
    void unknownReasonCodeIsRejected() {
        assertThatThrownBy(() -> calc.apply(List.of(line("COLA", "1", "4.50", "4.50")),
                Map.of("COLA", new DiscountInput(DiscountType.PERCENT, new BigDecimal("5"), "BOGUS")),
                null, false, MAX_PCT, MAX_AMT, REASONS))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void unknownSkuLineDiscountIsRejected() {
        assertThatThrownBy(() -> calc.apply(List.of(line("COLA", "1", "4.50", "4.50")),
                Map.of("PEPSI", new DiscountInput(DiscountType.PERCENT, new BigDecimal("5"), "LOYALTY")),
                null, false, MAX_PCT, MAX_AMT, REASONS))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void cashierOverPercentCapIsRejected() {
        assertThatThrownBy(() -> calc.apply(List.of(line("COLA", "2", "4.50", "9.00")),
                Map.of("COLA", new DiscountInput(DiscountType.PERCENT, new BigDecimal("20"), "LOYALTY")),
                null, false, MAX_PCT, MAX_AMT, REASONS))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void cashierOverAmountCapIsRejected() {
        // 25.00 on a 1000.00 line is only 2.5% (under the percent cap) but exceeds the 20.00 amount cap.
        assertThatThrownBy(() -> calc.apply(List.of(line("TV", "1", "1000.00", "1000.00")),
                Map.of("TV", new DiscountInput(DiscountType.AMOUNT, new BigDecimal("25.00"), "PRICE_MATCH")),
                null, false, MAX_PCT, MAX_AMT, REASONS))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void managerBypassesBothCaps() {
        DiscountResult r = calc.apply(List.of(line("COLA", "2", "4.50", "9.00")),
                Map.of("COLA", new DiscountInput(DiscountType.PERCENT, new BigDecimal("50"), "MANAGER_COMP")),
                null, true, MAX_PCT, MAX_AMT, REASONS);

        assertThat(r.lines().get(0).lineDiscountAmount()).isEqualByComparingTo("4.50");
    }

    @Test
    void percentageOver100IsRejected() {
        assertThatThrownBy(() -> calc.apply(List.of(line("COLA", "1", "4.50", "4.50")),
                Map.of("COLA", new DiscountInput(DiscountType.PERCENT, new BigDecimal("150"), "LOYALTY")),
                null, true, MAX_PCT, MAX_AMT, REASONS))
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=DiscountCalculatorTest`
Expected: COMPILATION ERROR — `DiscountCalculator`, `DiscountResult`, `DiscountedLine` do not exist.

- [ ] **Step 3: Write the implementation**

`src/main/java/com/company/pos/sales/application/DiscountedLine.java`:

```java
package com.company.pos.sales.application;

import com.company.pos.sales.api.DiscountType;
import java.math.BigDecimal;

/** A priced line after manual discounts: original gross, the line-level discount, and the
 *  post-discount extended amount handed to {@code TaxService}. */
record DiscountedLine(String sku, String name, BigDecimal quantity, BigDecimal unitPrice,
        String currencyCode, BigDecimal grossAmount, BigDecimal lineDiscountAmount,
        DiscountType lineDiscountType, String lineDiscountReason, BigDecimal discountedExtended) {
}
```

`src/main/java/com/company/pos/sales/application/DiscountResult.java`:

```java
package com.company.pos.sales.application;

import com.company.pos.sales.api.DiscountType;
import java.math.BigDecimal;
import java.util.List;

/** The outcome of applying manual discounts: per-line results plus the transaction-level
 *  discount summary and the grand discount total. */
record DiscountResult(List<DiscountedLine> lines, BigDecimal txnDiscountAmount,
        DiscountType txnDiscountType, String txnDiscountReason, BigDecimal discountTotal) {
}
```

`src/main/java/com/company/pos/sales/application/DiscountCalculator.java`:

```java
package com.company.pos.sales.application;

import com.company.pos.common.exception.DomainException;
import com.company.pos.pricing.api.PricedLine;
import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.DiscountType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Pure manual-discount math. Resolves each discount to an amount, caps a fixed amount to its base,
 * enforces the cashier cap by role, and allocates the transaction discount proportionally across
 * lines (last line absorbs the rounding remainder — the same pattern as Phase 4 refund allocation).
 * Discounts reduce the line extended amount before tax, so VAT is computed on the discounted base.
 */
@Component
class DiscountCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    DiscountResult apply(List<PricedLine> priced, Map<String, DiscountInput> lineDiscounts,
            DiscountInput txnDiscount, boolean callerIsManager, BigDecimal cashierMaxPercent,
            BigDecimal cashierMaxAmount, Set<String> reasonCodes) {

        Map<String, DiscountInput> lineMap = lineDiscounts == null ? Map.of() : lineDiscounts;
        Set<String> skus = new HashSet<>();
        for (PricedLine p : priced) {
            skus.add(p.sku());
        }
        for (String sku : lineMap.keySet()) {
            if (!skus.contains(sku)) {
                throw DomainException.validation("Line discount references unknown sku " + sku);
            }
        }

        // Stage 1: line-level discounts.
        List<BigDecimal> gross = new ArrayList<>();
        List<BigDecimal> lineDiscAmt = new ArrayList<>();
        List<DiscountType> lineDiscType = new ArrayList<>();
        List<String> lineDiscReason = new ArrayList<>();
        List<BigDecimal> postLine = new ArrayList<>();
        for (PricedLine p : priced) {
            BigDecimal g = p.extendedPrice().setScale(2, RoundingMode.HALF_UP);
            DiscountInput in = lineMap.get(p.sku());
            BigDecimal d = zero();
            DiscountType type = null;
            String reason = null;
            if (in != null) {
                d = resolve(in, g, reasonCodes);
                enforceCap(d, g, callerIsManager, cashierMaxPercent, cashierMaxAmount);
                type = in.type();
                reason = in.reasonCode();
            }
            gross.add(g);
            lineDiscAmt.add(d);
            lineDiscType.add(type);
            lineDiscReason.add(reason);
            postLine.add(g.subtract(d));
        }

        // Stage 2: transaction discount, allocated across the post-line extended amounts.
        BigDecimal cartBase = postLine.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal txnAmt = zero();
        DiscountType txnType = null;
        String txnReason = null;
        List<BigDecimal> shares = new ArrayList<>();
        for (int i = 0; i < priced.size(); i++) {
            shares.add(zero());
        }
        if (txnDiscount != null) {
            if (cartBase.signum() <= 0) {
                throw DomainException.validation("No discountable amount for a transaction discount");
            }
            txnAmt = resolve(txnDiscount, cartBase, reasonCodes);
            enforceCap(txnAmt, cartBase, callerIsManager, cashierMaxPercent, cashierMaxAmount);
            txnType = txnDiscount.type();
            txnReason = txnDiscount.reasonCode();
            BigDecimal allocated = zero();
            for (int i = 0; i < priced.size(); i++) {
                BigDecimal share = (i == priced.size() - 1)
                        ? txnAmt.subtract(allocated)
                        : txnAmt.multiply(postLine.get(i)).divide(cartBase, 2, RoundingMode.HALF_UP);
                allocated = allocated.add(share);
                shares.set(i, share);
            }
        }

        // Stage 3: assemble per-line results.
        List<DiscountedLine> lines = new ArrayList<>();
        BigDecimal discountTotal = zero();
        for (int i = 0; i < priced.size(); i++) {
            PricedLine p = priced.get(i);
            BigDecimal discountedExtended = postLine.get(i).subtract(shares.get(i));
            if (discountedExtended.signum() < 0) {
                throw DomainException.validation("Discount exceeds line value for sku " + p.sku());
            }
            discountTotal = discountTotal.add(lineDiscAmt.get(i));
            lines.add(new DiscountedLine(p.sku(), p.name(), p.quantity(), p.unitPrice(),
                    p.currencyCode(), gross.get(i), lineDiscAmt.get(i), lineDiscType.get(i),
                    lineDiscReason.get(i), discountedExtended));
        }
        discountTotal = discountTotal.add(txnAmt);

        return new DiscountResult(lines, txnAmt, txnType, txnReason, discountTotal);
    }

    private BigDecimal resolve(DiscountInput in, BigDecimal base, Set<String> reasonCodes) {
        if (in.reasonCode() == null || !reasonCodes.contains(in.reasonCode())) {
            throw DomainException.validation("Unknown discount reason code " + in.reasonCode());
        }
        if (in.value() == null || in.value().signum() <= 0) {
            throw DomainException.validation("Discount value must be positive");
        }
        if (in.type() == DiscountType.PERCENT) {
            if (in.value().compareTo(HUNDRED) > 0) {
                throw DomainException.validation("Percentage discount cannot exceed 100");
            }
            return base.multiply(in.value()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        }
        // AMOUNT: cap to the base so a fixed discount can never exceed its target.
        return in.value().setScale(2, RoundingMode.HALF_UP).min(base);
    }

    private void enforceCap(BigDecimal d, BigDecimal base, boolean callerIsManager,
            BigDecimal maxPercent, BigDecimal maxAmount) {
        if (callerIsManager) {
            return;
        }
        if (d.compareTo(maxAmount) > 0) {
            throw DomainException.validation("Discount exceeds cashier limit; manager approval required");
        }
        if (base.signum() > 0) {
            BigDecimal effectivePercent = d.multiply(HUNDRED).divide(base, 2, RoundingMode.HALF_UP);
            if (effectivePercent.compareTo(maxPercent) > 0) {
                throw DomainException.validation(
                        "Discount exceeds cashier limit; manager approval required");
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=DiscountCalculatorTest`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/pos/sales/application/DiscountedLine.java \
        src/main/java/com/company/pos/sales/application/DiscountResult.java \
        src/main/java/com/company/pos/sales/application/DiscountCalculator.java \
        src/test/java/com/company/pos/sales/DiscountCalculatorTest.java
git commit -m "feat(sales): DiscountCalculator — resolve, cap-by-role, proportional txn allocation"
```

---

### Task 3: `Sale`/`SaleLine` discount columns + V18 migration + persistence test

**Files:**
- Modify: `src/main/java/com/company/pos/sales/domain/SaleLine.java`
- Modify: `src/main/java/com/company/pos/sales/domain/Sale.java`
- Create: `src/main/resources/db/migration/sales/V18__sales_discount.sql`
- Test: `src/test/java/com/company/pos/sales/SaleDiscountPersistenceTest.java`

**Interfaces:**
- Produces:
  - `SaleLine` canonical constructor appends `BigDecimal grossAmount, BigDecimal lineDiscountAmount, String lineDiscountType, String lineDiscountReason`; getters `getGrossAmount()`, `getLineDiscountAmount()`, `getLineDiscountType()`, `getLineDiscountReason()`.
  - `Sale` canonical constructor appends `BigDecimal txnDiscountAmount, String txnDiscountType, String txnDiscountReason, BigDecimal discountTotal`; getters `getTxnDiscountAmount()`, `getTxnDiscountType()`, `getTxnDiscountReason()`, `getDiscountTotal()`.

> NOTE: the existing `SaleLine`/`Sale` constructors are called only by `DefaultSalesService` (updated in Task 4). This task changes the constructor signatures; the only non-test caller is fixed in Task 4. The persistence test below uses the new signatures directly.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/company/pos/sales/SaleDiscountPersistenceTest.java`:

```java
package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.common.util.Identifiers;
import com.company.pos.sales.domain.Sale;
import com.company.pos.sales.domain.SaleLine;
import com.company.pos.sales.infrastructure.SaleRepository;
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
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
@Transactional
class SaleDiscountPersistenceTest {

    @Autowired
    SaleRepository sales;
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
    void discountFieldsRoundTrip() {
        UUID saleId = Identifiers.newId();
        Sale sale = new Sale(saleId, "R-DISC-1", "S01", "T01", "cashier", "MAIN", "SAR",
                new BigDecimal("8.10"), new BigDecimal("1.22"), new BigDecimal("9.32"), Instant.now(),
                new BigDecimal("0.00"), null, null, new BigDecimal("0.90"));
        sale.addLine(new SaleLine(Identifiers.newId(), sale, 1, "COLA", "Cola Can",
                new BigDecimal("2.000"), new BigDecimal("4.5000"), new BigDecimal("8.10"),
                new BigDecimal("1.22"), new BigDecimal("9.32"), "SAR",
                new BigDecimal("9.00"), new BigDecimal("0.90"), "PERCENT", "LOYALTY"));
        sales.save(sale);

        Sale loaded = sales.findById(saleId).orElseThrow();
        assertThat(loaded.getDiscountTotal()).isEqualByComparingTo("0.90");
        assertThat(loaded.getTxnDiscountAmount()).isEqualByComparingTo("0.00");
        SaleLine line = loaded.getLines().get(0);
        assertThat(line.getGrossAmount()).isEqualByComparingTo("9.00");
        assertThat(line.getLineDiscountAmount()).isEqualByComparingTo("0.90");
        assertThat(line.getLineDiscountType()).isEqualTo("PERCENT");
        assertThat(line.getLineDiscountReason()).isEqualTo("LOYALTY");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=SaleDiscountPersistenceTest`
Expected: COMPILATION ERROR — the new constructor parameters and getters do not exist.

- [ ] **Step 3a: Add fields + getters to `SaleLine`**

In `src/main/java/com/company/pos/sales/domain/SaleLine.java`, add these fields after `currencyCode` (before the `protected SaleLine()` constructor):

```java
    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal grossAmount;

    @Column(name = "line_discount_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal lineDiscountAmount;

    @Column(name = "line_discount_type", length = 8)
    private String lineDiscountType;

    @Column(name = "line_discount_reason", length = 32)
    private String lineDiscountReason;
```

Replace the public constructor with:

```java
    public SaleLine(UUID id, Sale sale, int lineNo, String sku, String name, BigDecimal quantity,
            BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxAmount, BigDecimal lineTotal,
            String currencyCode, BigDecimal grossAmount, BigDecimal lineDiscountAmount,
            String lineDiscountType, String lineDiscountReason) {
        this.id = id;
        this.sale = sale;
        this.lineNo = lineNo;
        this.sku = sku;
        this.name = name;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.netAmount = netAmount;
        this.taxAmount = taxAmount;
        this.lineTotal = lineTotal;
        this.currencyCode = currencyCode;
        this.grossAmount = grossAmount;
        this.lineDiscountAmount = lineDiscountAmount;
        this.lineDiscountType = lineDiscountType;
        this.lineDiscountReason = lineDiscountReason;
    }
```

Add getters after `getCurrencyCode()`:

```java
    public BigDecimal getGrossAmount() {
        return grossAmount;
    }

    public BigDecimal getLineDiscountAmount() {
        return lineDiscountAmount;
    }

    public String getLineDiscountType() {
        return lineDiscountType;
    }

    public String getLineDiscountReason() {
        return lineDiscountReason;
    }
```

- [ ] **Step 3b: Add fields + getters to `Sale`**

In `src/main/java/com/company/pos/sales/domain/Sale.java`, add these fields after `createdAt` (before the `@OneToMany` lines field):

```java
    @Column(name = "txn_discount_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal txnDiscountAmount;

    @Column(name = "txn_discount_type", length = 8)
    private String txnDiscountType;

    @Column(name = "txn_discount_reason", length = 32)
    private String txnDiscountReason;

    @Column(name = "discount_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal discountTotal;
```

Replace the public constructor with:

```java
    public Sale(UUID id, String receiptNumber, String storeId, String terminalId,
            String cashierUsername, String locationCode, String currencyCode,
            BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal, Instant createdAt,
            BigDecimal txnDiscountAmount, String txnDiscountType, String txnDiscountReason,
            BigDecimal discountTotal) {
        this.id = id;
        this.receiptNumber = receiptNumber;
        this.storeId = storeId;
        this.terminalId = terminalId;
        this.cashierUsername = cashierUsername;
        this.locationCode = locationCode;
        this.currencyCode = currencyCode;
        this.subtotal = subtotal;
        this.taxTotal = taxTotal;
        this.grandTotal = grandTotal;
        this.createdAt = createdAt;
        this.txnDiscountAmount = txnDiscountAmount;
        this.txnDiscountType = txnDiscountType;
        this.txnDiscountReason = txnDiscountReason;
        this.discountTotal = discountTotal;
        this.status = "COMPLETED";
    }
```

Add getters after `getGrandTotal()`:

```java
    public BigDecimal getTxnDiscountAmount() {
        return txnDiscountAmount;
    }

    public String getTxnDiscountType() {
        return txnDiscountType;
    }

    public String getTxnDiscountReason() {
        return txnDiscountReason;
    }

    public BigDecimal getDiscountTotal() {
        return discountTotal;
    }
```

- [ ] **Step 3c: Create the Flyway migration**

`src/main/resources/db/migration/sales/V18__sales_discount.sql`:

```sql
ALTER TABLE sale_line ADD COLUMN gross_amount NUMERIC(19, 2) NOT NULL DEFAULT 0;
ALTER TABLE sale_line ADD COLUMN line_discount_amount NUMERIC(19, 2) NOT NULL DEFAULT 0;
ALTER TABLE sale_line ADD COLUMN line_discount_type VARCHAR(8);
ALTER TABLE sale_line ADD COLUMN line_discount_reason VARCHAR(32);

-- Existing rows predate discounts: their gross equals their (undiscounted) net.
UPDATE sale_line SET gross_amount = net_amount;

ALTER TABLE sale ADD COLUMN txn_discount_amount NUMERIC(19, 2) NOT NULL DEFAULT 0;
ALTER TABLE sale ADD COLUMN txn_discount_type VARCHAR(8);
ALTER TABLE sale ADD COLUMN txn_discount_reason VARCHAR(32);
ALTER TABLE sale ADD COLUMN discount_total NUMERIC(19, 2) NOT NULL DEFAULT 0;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=SaleDiscountPersistenceTest`
Expected: PASS (1 test). (On the embedded profile Hibernate `ddl-auto=update` adds the columns; the migration is exercised later by `DatabaseStoreServerTest` against PostgreSQL.)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/pos/sales/domain/SaleLine.java \
        src/main/java/com/company/pos/sales/domain/Sale.java \
        src/main/resources/db/migration/sales/V18__sales_discount.sql \
        src/test/java/com/company/pos/sales/SaleDiscountPersistenceTest.java
git commit -m "feat(sales): itemized discount columns on sale/sale_line + V18 migration"
```

---

### Task 4: Wire discounts into checkout + extend `SaleView` + config keys

**Files:**
- Modify: `src/main/java/com/company/pos/configuration/api/SettingKey.java`
- Modify: `src/main/java/com/company/pos/sales/api/SalesService.java`
- Modify: `src/main/java/com/company/pos/sales/api/SaleLineView.java`
- Modify: `src/main/java/com/company/pos/sales/api/SaleView.java`
- Modify: `src/main/java/com/company/pos/sales/application/DefaultSalesService.java`
- Test: `src/test/java/com/company/pos/sales/CheckoutDiscountTest.java`

**Interfaces:**
- Consumes: `DiscountCalculator.apply(...)`, `DiscountResult`, `DiscountedLine` (Task 2); new `Sale`/`SaleLine` constructors (Task 3); `CheckoutCommand.lineDiscounts()` / `.transactionDiscount()` (Task 1).
- Produces:
  - `SettingKey.DISCOUNT_REASON_CODES` (`"discount.reason.codes"`, default `"DAMAGED,PRICE_MATCH,LOYALTY,MANAGER_COMP"`), `DISCOUNT_CASHIER_MAX_PERCENT` (`"discount.cashier.max.percent"`, default `"10"`), `DISCOUNT_CASHIER_MAX_AMOUNT` (`"discount.cashier.max.amount"`, default `"20.00"`).
  - `SalesService.checkout(CheckoutCommand, String, boolean callerIsManager)` (the 2-arg overload becomes a default delegating with `false`).
  - `SaleLineView` appends `BigDecimal grossAmount, BigDecimal lineDiscountAmount, String lineDiscountType, String lineDiscountReason`.
  - `SaleView` appends `BigDecimal discountTotal, BigDecimal txnDiscountAmount, String txnDiscountType, String txnDiscountReason`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/company/pos/sales/CheckoutDiscountTest.java`:

```java
package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.cart.api.CartService;
import com.company.pos.common.exception.DomainException;
import com.company.pos.device.infrastructure.InMemoryPaymentTerminal;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.DiscountType;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
class CheckoutDiscountTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
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
        fake.addProduct(new ErpProduct("WATER", "Water Bottle", "BEV", "Beverages", "bcWATER",
                "EA", new BigDecimal("2.00"), "SAR", 2, true));
        productSync.sync();
        terminal.setApprove(true);
    }

    @AfterEach
    void reset() {
        terminal.setApprove(true);
    }

    private DiscountInput pct(String v, String reason) {
        return new DiscountInput(DiscountType.PERCENT, new BigDecimal(v), reason);
    }

    private DiscountInput amt(String v, String reason) {
        return new DiscountInput(DiscountType.AMOUNT, new BigDecimal(v), reason);
    }

    @Test
    void cashierLineDiscountTaxedOnDiscountedBase() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // gross 9.00
        // 10% off (cashier cap is exactly 10%) -> 0.90 off; net 8.10, tax 1.22, total 9.32
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00"))),
                Map.of("COLA", pct("10", "LOYALTY")), null), "cashier", false);

        assertThat(sale.subtotal()).isEqualByComparingTo("8.10");
        assertThat(sale.taxTotal()).isEqualByComparingTo("1.22");
        assertThat(sale.grandTotal()).isEqualByComparingTo("9.32");
        assertThat(sale.discountTotal()).isEqualByComparingTo("0.90");
        assertThat(sale.lines().get(0).grossAmount()).isEqualByComparingTo("9.00");
        assertThat(sale.lines().get(0).lineDiscountAmount()).isEqualByComparingTo("0.90");
        assertThat(sale.lines().get(0).lineDiscountReason()).isEqualTo("LOYALTY");
        assertThat(sale.payments().get(0).changeDue()).isEqualByComparingTo("10.68");
    }

    @Test
    void managerTransactionDiscountAllocatedAndTaxed() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // gross 9.00, single line
        // 5.00 off the cart (55% of base -> manager only); discounted extended 4.00, tax 0.60, total 4.60
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("10.00"))),
                Map.of(), amt("5.00", "MANAGER_COMP")), "manager", true);

        assertThat(sale.subtotal()).isEqualByComparingTo("4.00");
        assertThat(sale.taxTotal()).isEqualByComparingTo("0.60");
        assertThat(sale.grandTotal()).isEqualByComparingTo("4.60");
        assertThat(sale.txnDiscountAmount()).isEqualByComparingTo("5.00");
        assertThat(sale.txnDiscountReason()).isEqualTo("MANAGER_COMP");
        assertThat(sale.discountTotal()).isEqualByComparingTo("5.00");
    }

    @Test
    void mixedLineAndTransactionDiscountReconciles() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));   // gross 9.00
        carts.addLine(cart, "WATER", new BigDecimal("3"));  // gross 6.00
        // COLA 10% line (0.90) -> postLine 8.10; WATER 6.00; cartBase 14.10
        // txn 4.10: COLA share 2.36 -> ext 5.74; WATER share 1.74 -> ext 4.26
        // nets 5.74 + 4.26 = 10.00; tax 0.86 + 0.64 = 1.50; grand 11.50
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("11.50"))),
                Map.of("COLA", pct("10", "LOYALTY")), amt("4.10", "MANAGER_COMP")), "manager", true);

        assertThat(sale.subtotal()).isEqualByComparingTo("10.00");
        assertThat(sale.taxTotal()).isEqualByComparingTo("1.50");
        assertThat(sale.grandTotal()).isEqualByComparingTo("11.50");
        assertThat(sale.discountTotal()).isEqualByComparingTo("5.00");
        // reconciliation: gross 15.00 - line 0.90 - txn 4.10 = 10.00 subtotal
        BigDecimal gross = sale.lines().stream().map(l -> l.grossAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(gross).isEqualByComparingTo("15.00");
    }

    @Test
    void cashierOverCapIsRejected() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        assertThatThrownBy(() -> sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00"))),
                Map.of("COLA", pct("20", "LOYALTY")), null), "cashier", false))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void managerMayExceedTheCashierCap() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // gross 9.00
        // 20% off -> 1.80; net 7.20, tax 1.08, total 8.28
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("10.00"))),
                Map.of("COLA", pct("20", "LOYALTY")), null), "manager", true);

        assertThat(sale.grandTotal()).isEqualByComparingTo("8.28");
        assertThat(sale.lines().get(0).lineDiscountAmount()).isEqualByComparingTo("1.80");
    }

    @Test
    void noDiscountCheckoutStillWorks() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00")))), "cashier");

        assertThat(sale.grandTotal()).isEqualByComparingTo("10.35");
        assertThat(sale.discountTotal()).isEqualByComparingTo("0");
        assertThat(sale.lines().get(0).grossAmount()).isEqualByComparingTo("9.00");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CheckoutDiscountTest`
Expected: COMPILATION ERROR — the 3-arg `checkout`, the new `SaleView`/`SaleLineView` accessors, and the new `SettingKey`s do not exist.

- [ ] **Step 3a: Add the config keys**

In `src/main/java/com/company/pos/configuration/api/SettingKey.java`, add these entries to the enum (after `INVENTORY_LOCATION(...)`, before the closing `;`):

```java
    INVENTORY_LOCATION("inventory.location", "MAIN"),
    DISCOUNT_REASON_CODES("discount.reason.codes", "DAMAGED,PRICE_MATCH,LOYALTY,MANAGER_COMP"),
    DISCOUNT_CASHIER_MAX_PERCENT("discount.cashier.max.percent", "10"),
    DISCOUNT_CASHIER_MAX_AMOUNT("discount.cashier.max.amount", "20.00");
```

(Replace the trailing `;` that currently follows `INVENTORY_LOCATION("inventory.location", "MAIN")` — the last enum constant now ends with `;`.)

- [ ] **Step 3b: Extend the `SalesService` interface**

Replace `src/main/java/com/company/pos/sales/api/SalesService.java`. Declare BOTH overloads as **abstract** (do NOT make the 2-arg an interface `default` method — Spring's `@Transactional` CGLIB proxy does not advise interface default methods, so a self-delegating default would run checkout outside a transaction and silently skip every after-commit `@ApplicationModuleListener`). The 2-arg overload is implemented concretely on `DefaultSalesService` in Step 3d so the class-level `@Transactional` advises it:

```java
package com.company.pos.sales.api;

import java.util.UUID;

public interface SalesService {

    /** Checkout as a non-manager (cashier). Discounts are subject to the cashier cap. */
    SaleView checkout(CheckoutCommand command, String cashierUsername);

    /**
     * Checkout. {@code callerIsManager} lifts the cashier discount cap (manager = unlimited).
     */
    SaleView checkout(CheckoutCommand command, String cashierUsername, boolean callerIsManager);

    SaleView getSale(UUID saleId);

    void reprint(UUID saleId);
}
```

- [ ] **Step 3c: Extend the view records**

Replace `src/main/java/com/company/pos/sales/api/SaleLineView.java`:

```java
package com.company.pos.sales.api;

import java.math.BigDecimal;

public record SaleLineView(int lineNo, String sku, String name, BigDecimal quantity,
        BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxAmount, BigDecimal lineTotal,
        String currencyCode, BigDecimal grossAmount, BigDecimal lineDiscountAmount,
        String lineDiscountType, String lineDiscountReason) {
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
        List<SaleLineView> lines, List<SalePaymentView> payments, BigDecimal discountTotal,
        BigDecimal txnDiscountAmount, String txnDiscountType, String txnDiscountReason) {
}
```

- [ ] **Step 3d: Wire the calculator into `DefaultSalesService`**

In `src/main/java/com/company/pos/sales/application/DefaultSalesService.java`:

Add imports (only these three are newly referenced; `List`, `BigDecimal`, `RoundingMode` are already imported):

```java
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
```

Add the field and constructor parameter (append `DiscountCalculator discounts` last):

```java
    private final DiscountCalculator discounts;
```

Update the constructor signature and body to accept and assign `DiscountCalculator discounts` (append it as the final parameter and `this.discounts = discounts;`).

Add a concrete 2-arg overload (it is advised by the class-level `@Transactional`; the inner call to the 3-arg then runs inside that active transaction, so the after-commit `SaleCompleted` listeners fire). Place it just before the 3-arg method:

```java
    @Override
    public SaleView checkout(CheckoutCommand command, String cashierUsername) {
        return checkout(command, cashierUsername, false);
    }
```

Then replace the `checkout` (3-arg) method signature and its pricing→tax section. The method becomes:

```java
    @Override
    public SaleView checkout(CheckoutCommand command, String cashierUsername, boolean callerIsManager) {
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

        // 2. Apply manual discounts (line, then transaction) BEFORE tax.
        BigDecimal maxPct = new BigDecimal(config.getString(SettingKey.DISCOUNT_CASHIER_MAX_PERCENT));
        BigDecimal maxAmt = new BigDecimal(config.getString(SettingKey.DISCOUNT_CASHIER_MAX_AMOUNT));
        Set<String> reasonCodes = Arrays.stream(
                        config.getString(SettingKey.DISCOUNT_REASON_CODES).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        DiscountResult disc = discounts.apply(priced, command.lineDiscounts(),
                command.transactionDiscount(), callerIsManager, maxPct, maxAmt, reasonCodes);

        // 3. Apply tax on the discounted extended amounts
        String currency = cart.currencyCode() != null
                ? cart.currencyCode()
                : config.getString(SettingKey.CURRENCY_CODE);
        BigDecimal rate = new BigDecimal(config.getString(SettingKey.VAT_RATE));
        boolean inclusive = Boolean.parseBoolean(config.getString(SettingKey.TAX_INCLUSIVE));
        List<TaxLineInput> taxInputs = disc.lines().stream()
                .map(d -> new TaxLineInput(d.sku(), d.name(), d.quantity(), d.unitPrice(),
                        d.discountedExtended(), d.currencyCode()))
                .toList();
        TaxedCart taxed = tax.applyTax(taxInputs, rate, inclusive, currency);
        BigDecimal grandTotal = taxed.grandTotal().setScale(2, RoundingMode.HALF_UP);
```

Keep the existing tender loop (step "3. Take the tenders" in the current code) unchanged — it follows directly. Then replace the persistence block so the `Sale` and each `SaleLine` carry the discount fields. The `Sale` construction becomes:

```java
        // 4. Persist the immutable sale
        String storeId = config.getString(SettingKey.STORE_ID);
        String terminalId = config.getString(SettingKey.TERMINAL_ID);
        String location = config.getString(SettingKey.INVENTORY_LOCATION);
        String receiptNumber = numbering.nextReceiptNumber(storeId, terminalId);
        Instant now = Instant.now();
        Sale sale = new Sale(saleId, receiptNumber, storeId, terminalId, cashierUsername,
                location, currency, taxed.subtotal(), taxed.taxTotal(), taxed.grandTotal(), now,
                disc.txnDiscountAmount(),
                disc.txnDiscountType() == null ? null : disc.txnDiscountType().name(),
                disc.txnDiscountReason(), disc.discountTotal());
        int lineNo = 1;
        for (int i = 0; i < taxed.lines().size(); i++) {
            TaxedLine t = taxed.lines().get(i);
            DiscountedLine d = disc.lines().get(i);
            sale.addLine(new SaleLine(Identifiers.newId(), sale, lineNo++, t.sku(), t.name(),
                    t.quantity(), t.unitPrice(), t.netAmount(), t.taxAmount(), t.lineTotal(),
                    t.currencyCode(), d.grossAmount(), d.lineDiscountAmount(),
                    d.lineDiscountType() == null ? null : d.lineDiscountType().name(),
                    d.lineDiscountReason()));
        }
        sales.save(sale);
```

(The `SaleCompleted` publication, receipt print, and `return toView(...)` that follow stay as-is — `SaleCompleted.SoldLine` is still built from `taxed.lines()`.)

Finally, update `toView` to populate the new view fields:

```java
    private SaleView toView(Sale sale, List<PaymentView> salePayments) {
        List<SaleLineView> lines = new ArrayList<>();
        for (SaleLine l : sale.getLines()) {
            lines.add(new SaleLineView(l.getLineNo(), l.getSku(), l.getName(), l.getQuantity(),
                    l.getUnitPrice(), l.getNetAmount(), l.getTaxAmount(), l.getLineTotal(),
                    l.getCurrencyCode(), l.getGrossAmount(), l.getLineDiscountAmount(),
                    l.getLineDiscountType(), l.getLineDiscountReason()));
        }
        List<SalePaymentView> paymentViews = salePayments.stream()
                .map(p -> new SalePaymentView(p.method(), p.amount(), p.amountTendered(),
                        p.changeDue(), p.maskedPan()))
                .toList();
        return new SaleView(sale.getId(), sale.getReceiptNumber(), sale.getStatus(),
                sale.getCurrencyCode(), sale.getSubtotal(), sale.getTaxTotal(), sale.getGrandTotal(),
                sale.getCreatedAt(), lines, paymentViews, sale.getDiscountTotal(),
                sale.getTxnDiscountAmount(), sale.getTxnDiscountType(), sale.getTxnDiscountReason());
    }
```

> NOTE: `DiscountCalculator`, `DiscountResult`, and `DiscountedLine` are in the same package `com.company.pos.sales.application` as `DefaultSalesService`, so no import is required for them. `DiscountInput` arrives via `command.lineDiscounts()` / `command.transactionDiscount()` and is passed straight through to `discounts.apply(...)` — the symbol is never named in this file, so no `DiscountInput` import is needed either.

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CheckoutDiscountTest`
Expected: PASS (6 tests).

Then run the existing sales tests to confirm the 2-arg overload and view changes did not break callers:

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CheckoutServiceTest,SalesControllerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/pos/configuration/api/SettingKey.java \
        src/main/java/com/company/pos/sales/api/SalesService.java \
        src/main/java/com/company/pos/sales/api/SaleLineView.java \
        src/main/java/com/company/pos/sales/api/SaleView.java \
        src/main/java/com/company/pos/sales/application/DefaultSalesService.java \
        src/test/java/com/company/pos/sales/CheckoutDiscountTest.java
git commit -m "feat(sales): apply manual discounts in checkout, taxed on discounted base"
```

---

### Task 5: Controller derives `callerIsManager` + authorization e2e

**Files:**
- Modify: `src/main/java/com/company/pos/sales/web/SalesController.java`
- Test: `src/test/java/com/company/pos/sales/CheckoutDiscountSecurityTest.java`

**Interfaces:**
- Consumes: `SalesService.checkout(CheckoutCommand, String, boolean)` (Task 4); `Authentication` (Spring Security).
- Produces: `POST /sales` now passes `callerIsManager` derived from whether the authenticated principal holds `ROLE_MANAGER`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/company/pos/sales/CheckoutDiscountSecurityTest.java`:

```java
package com.company.pos.sales;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.cart.api.CartService;
import com.company.pos.common.util.Identifiers;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.support.DatabaseCleaner;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
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
 * The cashier discount cap is enforced from the authenticated principal's role: a cashier applying
 * an over-cap discount gets 400 (validation), while a manager applying the same discount succeeds
 * (201). Proves the controller derives callerIsManager from ROLE_MANAGER end-to-end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class CheckoutDiscountSecurityTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
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

    // gross 9.00, 20% off -> 1.80; discounted 7.20, tax 1.08, total 8.28
    private String body(UUID cartId) {
        return "{\"cartId\":\"" + cartId + "\","
                + "\"tenders\":[{\"method\":\"CASH\",\"amount\":null,\"tendered\":10.00}],"
                + "\"lineDiscounts\":{\"COLA\":{\"type\":\"PERCENT\",\"value\":20,\"reasonCode\":\"LOYALTY\"}},"
                + "\"transactionDiscount\":null}";
    }

    private UUID cartWithCola() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        return cart;
    }

    private String login(String username) throws Exception {
        String resp = mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(resp, "$.token");
    }

    @Test
    void cashierOverCapDiscountIsRejected() throws Exception {
        String token = login("cashier");
        mvc.perform(post("/sales").header("Authorization", token)
                        .contentType("application/json").content(body(cartWithCola())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void managerOverCapDiscountSucceeds() throws Exception {
        String token = login("manager");
        mvc.perform(post("/sales").header("Authorization", token)
                        .contentType("application/json").content(body(cartWithCola())))
                .andExpect(status().isCreated());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CheckoutDiscountSecurityTest`
Expected: FAIL — the manager case currently returns the same result as the cashier (the controller still calls the 2-arg overload → `callerIsManager=false`), so the manager checkout is rejected (400) instead of 201.

- [ ] **Step 3: Update the controller**

Replace `src/main/java/com/company/pos/sales/web/SalesController.java`:

```java
package com.company.pos.sales.web;

import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SalesController {

    private final SalesService sales;

    SalesController(SalesService sales) {
        this.sales = sales;
    }

    @PostMapping("/sales")
    @ResponseStatus(HttpStatus.CREATED)
    SaleView checkout(@RequestBody CheckoutCommand command, Authentication authentication) {
        boolean isManager = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_MANAGER".equals(a.getAuthority()));
        return sales.checkout(command, authentication.getName(), isManager);
    }

    @GetMapping("/sales/{saleId}")
    SaleView get(@PathVariable UUID saleId) {
        return sales.getSale(saleId);
    }

    @PostMapping("/sales/{saleId}/reprint")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reprint(@PathVariable UUID saleId) {
        sales.reprint(saleId);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CheckoutDiscountSecurityTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/pos/sales/web/SalesController.java \
        src/test/java/com/company/pos/sales/CheckoutDiscountSecurityTest.java
git commit -m "feat(sales): derive manager discount override from the authenticated role"
```

---

### Task 6: Show discounts on the receipt

**Files:**
- Modify: `src/main/java/com/company/pos/receipt/api/ReceiptLineData.java`
- Modify: `src/main/java/com/company/pos/receipt/api/ReceiptData.java`
- Modify: `src/main/java/com/company/pos/receipt/application/DefaultReceiptService.java`
- Modify: `src/main/java/com/company/pos/sales/application/DefaultSalesService.java` (`printReceipt`)
- Test: `src/test/java/com/company/pos/sales/ReceiptShowsDiscountTest.java`

**Interfaces:**
- Produces:
  - `ReceiptLineData` canonical constructor `(String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineTotal, BigDecimal grossAmount, BigDecimal lineDiscountAmount)`, plus convenience `(name, quantity, unitPrice, lineTotal)` delegating with `grossAmount=lineTotal, lineDiscountAmount=ZERO` (keeps `DefaultReturnService` unchanged).
  - `ReceiptData` canonical constructor appends `BigDecimal discountTotal, BigDecimal txnDiscountAmount, String txnDiscountReason`, plus convenience without those three delegating with `ZERO, ZERO, null` (keeps `DefaultReturnService` unchanged).
- Consumes: `Sale` discount getters (Task 3).

- [ ] **Step 1: Write the failing test**

`src/test/java/com/company/pos/sales/ReceiptShowsDiscountTest.java`:

```java
package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.device.api.PrintLine;
import com.company.pos.device.infrastructure.InMemoryPrinter;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.DiscountType;
import com.company.pos.sales.api.TenderInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
class ReceiptShowsDiscountTest {

    @Autowired
    com.company.pos.sales.api.SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    InMemoryPrinter printer;

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
    }

    @Test
    void receiptShowsTheDiscountTotal() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00"))),
                Map.of("COLA", new DiscountInput(DiscountType.PERCENT, new BigDecimal("10"), "LOYALTY")),
                null), "cashier", false);

        List<PrintLine> receipt = printer.lastReceipt();
        assertThat(receipt).isNotEmpty();
        assertThat(receipt.stream().anyMatch(l -> l.text().contains("Discount"))).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ReceiptShowsDiscountTest`
Expected: FAIL — no receipt line contains "Discount" (the renderer doesn't print discounts yet).

- [ ] **Step 3a: Extend the receipt records**

Replace `src/main/java/com/company/pos/receipt/api/ReceiptLineData.java`:

```java
package com.company.pos.receipt.api;

import java.math.BigDecimal;

public record ReceiptLineData(String name, BigDecimal quantity, BigDecimal unitPrice,
        BigDecimal lineTotal, BigDecimal grossAmount, BigDecimal lineDiscountAmount) {

    /** Convenience for lines without a discount (e.g. credit notes): gross == lineTotal. */
    public ReceiptLineData(String name, BigDecimal quantity, BigDecimal unitPrice,
            BigDecimal lineTotal) {
        this(name, quantity, unitPrice, lineTotal, lineTotal, BigDecimal.ZERO);
    }
}
```

Replace `src/main/java/com/company/pos/receipt/api/ReceiptData.java`:

```java
package com.company.pos.receipt.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ReceiptData(String receiptNumber, String cashierName, Instant timestamp,
        List<ReceiptLineData> lines, BigDecimal subtotal, BigDecimal taxTotal,
        BigDecimal grandTotal, List<ReceiptPaymentData> payments, String currencyCode,
        BigDecimal discountTotal, BigDecimal txnDiscountAmount, String txnDiscountReason) {

    /** Convenience for receipts without discounts (e.g. credit notes). */
    public ReceiptData(String receiptNumber, String cashierName, Instant timestamp,
            List<ReceiptLineData> lines, BigDecimal subtotal, BigDecimal taxTotal,
            BigDecimal grandTotal, List<ReceiptPaymentData> payments, String currencyCode) {
        this(receiptNumber, cashierName, timestamp, lines, subtotal, taxTotal, grandTotal, payments,
                currencyCode, BigDecimal.ZERO, BigDecimal.ZERO, null);
    }
}
```

- [ ] **Step 3b: Render discounts in `DefaultReceiptService`**

In `src/main/java/com/company/pos/receipt/application/DefaultReceiptService.java`, inside the per-line loop, after the existing `"  qty x unit = lineTotal"` line, add a discount line when the line was discounted:

```java
        for (ReceiptLineData line : data.lines()) {
            lines.add(new PrintLine(line.name(), false));
            lines.add(new PrintLine("  " + line.quantity().stripTrailingZeros().toPlainString()
                    + " x " + money(line.unitPrice(), currency, locale)
                    + " = " + money(line.lineTotal(), currency, locale), false));
            if (line.lineDiscountAmount() != null && line.lineDiscountAmount().signum() > 0) {
                lines.add(new PrintLine("  Discount: -" + money(line.lineDiscountAmount(), currency, locale),
                        false));
            }
        }
```

Then, in the totals block, between the `"Subtotal: ..."` line and the `"Tax: ..."` line, add the discount totals when present:

```java
        lines.add(new PrintLine("Subtotal: " + money(data.subtotal(), currency, locale), false));
        if (data.discountTotal() != null && data.discountTotal().signum() > 0) {
            lines.add(new PrintLine("Discount: -" + money(data.discountTotal(), currency, locale), false));
        }
        if (data.txnDiscountAmount() != null && data.txnDiscountAmount().signum() > 0) {
            String reason = data.txnDiscountReason() != null ? " (" + data.txnDiscountReason() + ")" : "";
            lines.add(new PrintLine("  Transaction discount" + reason + ": -"
                    + money(data.txnDiscountAmount(), currency, locale), false));
        }
        lines.add(new PrintLine("Tax:      " + money(data.taxTotal(), currency, locale), false));
```

- [ ] **Step 3c: Pass discount data from `DefaultSalesService.printReceipt`**

In `src/main/java/com/company/pos/sales/application/DefaultSalesService.java`, replace the `printReceipt` line-mapping and `ReceiptData` construction so they carry the discount fields:

```java
    private void printReceipt(Sale sale, List<PaymentView> salePayments) {
        try {
            List<ReceiptLineData> lines = sale.getLines().stream()
                    .map(l -> new ReceiptLineData(l.getName(), l.getQuantity(), l.getUnitPrice(),
                            l.getLineTotal(), l.getGrossAmount(), l.getLineDiscountAmount()))
                    .toList();
            List<ReceiptPaymentData> pays = salePayments.stream()
                    .map(p -> new ReceiptPaymentData(p.method(), p.amount(), p.amountTendered(),
                            p.changeDue(), p.maskedPan()))
                    .toList();
            receipts.print(new ReceiptData(sale.getReceiptNumber(), sale.getCashierUsername(),
                    sale.getCreatedAt(), lines, sale.getSubtotal(), sale.getTaxTotal(),
                    sale.getGrandTotal(), pays, sale.getCurrencyCode(), sale.getDiscountTotal(),
                    sale.getTxnDiscountAmount(), sale.getTxnDiscountReason()));
        } catch (RuntimeException ex) {
            log.warn("Receipt print failed for sale {} ({}) — sale is recorded; reprint available",
                    sale.getId(), sale.getReceiptNumber(), ex);
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ReceiptShowsDiscountTest`
Expected: PASS (1 test).

Confirm the returns credit-note path still compiles/runs (it uses the convenience constructors):

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ReturnServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/pos/receipt/api/ReceiptLineData.java \
        src/main/java/com/company/pos/receipt/api/ReceiptData.java \
        src/main/java/com/company/pos/receipt/application/DefaultReceiptService.java \
        src/main/java/com/company/pos/sales/application/DefaultSalesService.java \
        src/test/java/com/company/pos/sales/ReceiptShowsDiscountTest.java
git commit -m "feat(receipt): print line and transaction discounts; reconcile totals"
```

---

### Task 7: Carry discounts in the ERP sale upload

**Files:**
- Modify: `src/main/java/com/company/pos/integration/api/SaleUpload.java`
- Modify: `src/main/java/com/company/pos/sync/application/SaleUploadListener.java`
- Modify: `src/test/java/com/company/pos/integration/FakeErpClientUploadTest.java` (existing test builds `SaleUpload` with the old signature)
- Test: `src/test/java/com/company/pos/sync/SaleUploadCarriesDiscountTest.java`

**Interfaces:**
- Produces:
  - `SaleUpload.Line` appends `BigDecimal grossAmount, BigDecimal lineDiscountAmount, String lineDiscountReason`.
  - `SaleUpload` (header) appends `BigDecimal discountTotal, BigDecimal txnDiscountAmount, String txnDiscountType, String txnDiscountReason`.
- Consumes: `SaleView`/`SaleLineView` discount accessors (Task 4).

- [ ] **Step 1: Write the failing test**

`src/test/java/com/company/pos/sync/SaleUploadCarriesDiscountTest.java`:

```java
package com.company.pos.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.SaleUpload;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.DiscountType;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
class SaleUploadCarriesDiscountTest {

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
    void uploadedSaleCarriesLineAndTransactionDiscounts() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // gross 9.00
        // 10% line discount (0.90), tendered cash; grand 9.32
        UUID saleId = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00"))),
                Map.of("COLA", new DiscountInput(DiscountType.PERCENT, new BigDecimal("10"), "LOYALTY")),
                null), "cashier", false).id();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(fake.uploadedSales()).hasSize(1);
            SaleUpload up = fake.uploadedSales().get(0);
            assertThat(up.saleId()).isEqualTo(saleId);
            assertThat(up.discountTotal()).isEqualByComparingTo("0.90");
            assertThat(up.txnDiscountAmount()).isEqualByComparingTo("0.00");
            assertThat(up.lines()).hasSize(1);
            SaleUpload.Line line = up.lines().get(0);
            assertThat(line.grossAmount()).isEqualByComparingTo("9.00");
            assertThat(line.lineDiscountAmount()).isEqualByComparingTo("0.90");
            assertThat(line.lineDiscountReason()).isEqualTo("LOYALTY");
        });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=SaleUploadCarriesDiscountTest`
Expected: COMPILATION ERROR — `SaleUpload.discountTotal()`, `SaleUpload.Line.grossAmount()`, etc. do not exist.

- [ ] **Step 3a: Extend `SaleUpload`**

Replace `src/main/java/com/company/pos/integration/api/SaleUpload.java`:

```java
package com.company.pos.integration.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SaleUpload(UUID saleId, String receiptNumber, String terminalId, String locationCode,
        String currencyCode, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal,
        Instant createdAt, List<Line> lines, List<Payment> payments, BigDecimal discountTotal,
        BigDecimal txnDiscountAmount, String txnDiscountType, String txnDiscountReason) {

    public record Line(int lineNo, String sku, String name, BigDecimal quantity,
            BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxAmount, BigDecimal lineTotal,
            BigDecimal grossAmount, BigDecimal lineDiscountAmount, String lineDiscountReason) {
    }

    public record Payment(String method, BigDecimal amount, BigDecimal amountTendered,
            BigDecimal changeDue, String maskedPan) {
    }
}
```

- [ ] **Step 3b: Map discounts in `SaleUploadListener.toUpload`**

In `src/main/java/com/company/pos/sync/application/SaleUploadListener.java`, replace `toUpload`:

```java
    private SaleUpload toUpload(SaleCompleted event, SaleView sale) {
        List<SaleUpload.Line> lines = sale.lines().stream()
                .map(l -> new SaleUpload.Line(l.lineNo(), l.sku(), l.name(), l.quantity(),
                        l.unitPrice(), l.netAmount(), l.taxAmount(), l.lineTotal(),
                        l.grossAmount(), l.lineDiscountAmount(), l.lineDiscountReason()))
                .toList();
        List<SaleUpload.Payment> payments = sale.payments().stream()
                .map(p -> new SaleUpload.Payment(p.method(), p.amount(), p.amountTendered(),
                        p.changeDue(), p.maskedPan()))
                .toList();
        return new SaleUpload(sale.id(), sale.receiptNumber(), event.terminalId(),
                event.locationCode(), sale.currencyCode(), sale.subtotal(), sale.taxTotal(),
                sale.grandTotal(), sale.createdAt(), lines, payments, sale.discountTotal(),
                sale.txnDiscountAmount(), sale.txnDiscountType(), sale.txnDiscountReason());
    }
```

- [ ] **Step 3c: Fix the existing `FakeErpClientUploadTest` sample builder**

The `SaleUpload` canonical-constructor change breaks this test's `sampleSale(...)`. In `src/test/java/com/company/pos/integration/FakeErpClientUploadTest.java`, replace the `sampleSale` method with the new signatures (no-discount sample: per-line gross = `9.00`, line discount `0.00`/no reason; header discount totals `0.00`):

```java
    private SaleUpload sampleSale(UUID id) {
        return new SaleUpload(id, "S01-T01-000001", "T01", "MAIN", "SAR",
                new BigDecimal("9.00"), new BigDecimal("1.35"), new BigDecimal("10.35"), Instant.EPOCH,
                List.of(new SaleUpload.Line(1, "COLA", "Cola Can", new BigDecimal("2"),
                        new BigDecimal("4.50"), new BigDecimal("9.00"), new BigDecimal("1.35"),
                        new BigDecimal("10.35"), new BigDecimal("9.00"), new BigDecimal("0.00"), null)),
                List.of(new SaleUpload.Payment("CASH", new BigDecimal("10.35"),
                        new BigDecimal("20.00"), new BigDecimal("9.65"), null)),
                new BigDecimal("0.00"), new BigDecimal("0.00"), null, null);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=SaleUploadCarriesDiscountTest,FakeErpClientUploadTest`
Expected: PASS.

Confirm the existing e2e upload test still passes (no-discount path; `discountTotal` will be `0`):

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=SaleUploadedToErpTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/pos/integration/api/SaleUpload.java \
        src/main/java/com/company/pos/sync/application/SaleUploadListener.java \
        src/test/java/com/company/pos/integration/FakeErpClientUploadTest.java \
        src/test/java/com/company/pos/sync/SaleUploadCarriesDiscountTest.java
git commit -m "feat(sync): carry line + transaction discount detail in the ERP sale upload"
```

---

### Task 8: Returns of a discounted line refund the discounted amount (regression)

**Files:**
- Test: `src/test/java/com/company/pos/sales/ReturnOfDiscountedSaleTest.java` (no production code — proves the "no returns change needed" claim)

**Interfaces:**
- Consumes: `SalesService.checkout(..., callerIsManager)` (Task 4); `ReturnService.processReturn(ReturnCommand, String)` (Phase 4); `ReturnCommand`, `ReturnView` (Phase 4 API).

> Phase 4 API (verified): `ReturnCommand(UUID originalSaleId, String receiptNumber, List<ReturnLineRequest> lines)` with `ReturnLineRequest(int lineNo, BigDecimal quantity)`; `ReturnView` exposes `refundSubtotal()`, `refundTaxTotal()`, `refundGrandTotal()`. The code below uses these exact names.

- [ ] **Step 1: Write the test (expected to pass immediately — it verifies existing behavior)**

`src/test/java/com/company/pos/sales/ReturnOfDiscountedSaleTest.java`:

```java
package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.DiscountType;
import com.company.pos.sales.api.ReturnCommand;
import com.company.pos.sales.api.ReturnService;
import com.company.pos.sales.api.ReturnView;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
class ReturnOfDiscountedSaleTest {

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

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
    }

    @Test
    void returningOneOfTwoDiscountedUnitsRefundsHalfTheDiscountedLine() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2")); // gross 9.00
        // 10% off -> net 8.10, tax 1.22, line total 9.32
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("20.00"))),
                Map.of("COLA", new DiscountInput(DiscountType.PERCENT, new BigDecimal("10"), "LOYALTY")),
                null), "cashier", false);
        assertThat(sale.lines().get(0).netAmount()).isEqualByComparingTo("8.10");

        // Return 1 of 2 units. Proportional refund off the DISCOUNTED line:
        // net 8.10/2 = 4.05, tax 1.22/2 = 0.61, line total 4.66
        ReturnView ret = returns.processReturn(new ReturnCommand(sale.id(), null,
                List.of(new ReturnCommand.ReturnLineRequest(1, new BigDecimal("1")))), "manager");

        assertThat(ret.refundSubtotal()).isEqualByComparingTo("4.05");
        assertThat(ret.refundTaxTotal()).isEqualByComparingTo("0.61");
        assertThat(ret.refundGrandTotal()).isEqualByComparingTo("4.66");
    }
}
```

> The point of the test is the refund amounts: they must come off the discounted net (`8.10`), not the undiscounted `9.00`. No production code changes in this task.

- [ ] **Step 2: Run the test**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ReturnOfDiscountedSaleTest`
Expected: PASS (1 test) with no production changes — confirming returns refund the discounted amount automatically.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/company/pos/sales/ReturnOfDiscountedSaleTest.java
git commit -m "test(sales): returns refund the discounted amount (no returns change needed)"
```

---

### Task 9: Documentation + full-suite verification

**Files:**
- Modify: `docs/run-modes.md`

- [ ] **Step 1: Append a "Manual Discounts (Phase 5)" section to `docs/run-modes.md`**

Add a section documenting: the new `CheckoutCommand` fields (`lineDiscounts` keyed by SKU, `transactionDiscount`); `DiscountType` (`PERCENT`/`AMOUNT`) and the required `reasonCode`; the cashier cap config keys (`discount.cashier.max.percent` default `10`, `discount.cashier.max.amount` default `20.00`, `discount.reason.codes`) and that a manager (authenticated `ROLE_MANAGER`) is uncapped; that discounts are applied before tax (VAT on the discounted base) and itemized on the receipt and ERP upload; and that returns refund the discounted amount. Keep the prose style and depth consistent with the existing "Returns & Refunds (Phase 4)" section.

- [ ] **Step 2: Commit the docs**

```bash
git add docs/run-modes.md
git commit -m "docs(run-modes): document manual discounts (phase 5)"
```

- [ ] **Step 3: Run the FULL test suite (final gate)**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test`
Expected: BUILD SUCCESS, 0 failures, 0 errors. This includes:
- `ModularityTests` (5/5) — module boundaries intact.
- `DatabaseStoreServerTest` (Testcontainers/PostgreSQL) — Flyway applies `V1`…`V18` cleanly, including the discount migration.
- All Phase 0–4 tests still green (the 2-arg `checkout` overload and receipt convenience constructors preserved back-compat).

> Docker must be running for `DatabaseStoreServerTest`. If it is not available in the environment, note that explicitly in the task report rather than skipping silently.

---

## Notes for the implementer

- **Money discipline:** every computed amount is `BigDecimal` at scale 2 HALF_UP; compare with `compareTo`/`signum`. Quantity is scale 3, unit price scale 4 (matches existing columns).
- **Why discounts go before tax:** reducing the line *extended* amount keeps `TaxService` and both tax modes correct without change, and makes the persisted `net_amount` post-discount so Phase 4 returns need no change (Task 8 proves it).
- **Allocation remainder:** the transaction discount's last-line remainder rule mirrors Phase 4's refund allocation — do not "fix" it to even rounding; the remainder guarantees the shares sum exactly to the resolved transaction discount.
- **Back-compat:** the 2-arg `checkout`, the `ReceiptLineData`/`ReceiptData` convenience constructors, and the additive `SaleView`/`SaleUpload` fields are deliberate so Phase 0–4 code and tests compile unchanged. Do not remove them.
```
