# Phase 13b — Service Charge / Auto-Gratuity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a configurable, taxable service charge auto-applied at DINE_IN dining closes, manager-waivable, excluded from retail and QUICK_SERVICE, off by default.

**Architecture:** The charge is computed in `sales` (a pricing-pipeline concern) via a separate one-line tax call inside the shared `priceDiscountTax`, gated by an `applyServiceCharge` flag on `CheckoutCommand`/`quote`. The *policy* (when to apply) lives in `dining`, which sets the flag from config + `serviceType` + a manager-gated waiver. `serviceChargeAmount` is persisted on the sale, printed on the receipt, and uploaded to the ERP.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Modulith, Spring Data JPA, Flyway (store-server) / Hibernate ddl-auto (embedded), JUnit 5 + AssertJ + Awaitility.

## Global Constraints

- **JDK 21 required.** `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` before every Maven command. Maven, not Gradle: `./mvnw`.
- **Money is `BigDecimal`**, scale 2 `HALF_UP`. Monetary columns `NUMERIC(19,2)`.
- **Flyway versions are globally sequential.** Latest existing is **V33**; this phase uses **V34** in `src/main/resources/db/migration/sales/`. Store-server only; embedded uses `ddl-auto`.
- **Off by default:** `SERVICE_CHARGE_ENABLED` defaults `false`, `SERVICE_CHARGE_PERCENT` defaults `0`. With defaults, every existing test and all current behaviour must be **unchanged** — no sale gets a charge unless the store enables it AND the caller sets `applyServiceCharge`.
- **Taxable:** the charge is part of the VAT base. Computed via a **separate one-line `tax.applyTax` call** (never a synthetic product tax line), so it never leaks into `SaleLine`s or `SaleCompleted.SoldLine`s (no phantom "SERVICE_CHARGE" stock decrement). Only its money flows into totals.
- **Invariant:** `grandTotal = subtotal + serviceChargeAmount + taxTotal` (where `taxTotal` includes VAT on the charge).
- **Even-split correctness:** `quote` must apply the same charge as the matching `checkout`, so the N even shares still sum exactly to the grand total.
- **Scope:** applied only when `applyServiceCharge` is set by the caller; retail `POST /sales` and existing callers default it `false`.
- **Authorization:** waiving the charge is manager-only (`callerIsManager`); a non-manager waiver throws `DomainException`.
- **Module boundaries** unchanged; `ModularityTests` stays green. After any change, re-run the affected module's tests and `ModularityTests`.

---

## File Structure

**Task 1 — sales pipeline (config + charge + persistence + quote):**
- Modify: `src/main/java/com/company/pos/configuration/api/SettingKey.java` (3 keys)
- Modify: `src/main/java/com/company/pos/sales/api/CheckoutCommand.java` (add `applyServiceCharge`)
- Modify: `src/main/java/com/company/pos/sales/api/SalesService.java` (add `quote(UUID, boolean)`)
- Modify: `src/main/java/com/company/pos/sales/api/QuoteView.java` (add `serviceChargeAmount`)
- Modify: `src/main/java/com/company/pos/sales/api/SaleView.java` (add `serviceChargeAmount`)
- Modify: `src/main/java/com/company/pos/sales/domain/Sale.java` (field + constructor + getter)
- Modify: `src/main/java/com/company/pos/sales/application/DefaultSalesService.java` (charge computation, totals, persistence, quote)
- Create: `src/main/resources/db/migration/sales/V34__sale_service_charge.sql`
- Test: `src/test/java/com/company/pos/sales/SalesServiceChargeTest.java`

**Task 2 — receipt + ERP carry the charge:**
- Modify: `src/main/java/com/company/pos/receipt/api/ReceiptData.java` (add `serviceChargeAmount`)
- Modify: `src/main/java/com/company/pos/receipt/application/DefaultReceiptService.java` (print charge line)
- Modify: `src/main/java/com/company/pos/sales/application/DefaultSalesService.java` (pass charge into `ReceiptData`)
- Modify: `src/main/java/com/company/pos/integration/api/SaleUpload.java` (add `serviceChargeAmount`)
- Modify: `src/main/java/com/company/pos/sync/application/SaleUploadListener.java` (map it)
- Test: `src/test/java/com/company/pos/receipt/ServiceChargeReceiptTest.java`
- Test: `src/test/java/com/company/pos/sync/ServiceChargeUploadTest.java`

**Task 3 — dining wiring (DINE_IN gate + manager waive):**
- Modify: `src/main/java/com/company/pos/dining/api/CloseOrderCommand.java` (add `waiveServiceCharge`)
- Modify: `src/main/java/com/company/pos/dining/api/SplitCloseCommand.java` (add `waiveServiceCharge`)
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java` (apply flag + waiver gate across close/by-item/even)
- Test: `src/test/java/com/company/pos/dining/DiningServiceChargeTest.java`

---

## Task 1: Sales pipeline — config, taxable charge, persistence, quote

**Interfaces:**
- Consumes: existing `priceDiscountTax`, `TaxService.applyTax`, `ConfigurationService`.
- Produces (Tasks 2 & 3 rely on these): `CheckoutCommand.applyServiceCharge()`; `SalesService.quote(UUID, boolean)`; `SaleView.serviceChargeAmount()`; `QuoteView.serviceChargeAmount()`; `Sale.getServiceChargeAmount()`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/sales/SalesServiceChargeTest.java`:

```java
package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.QuoteView;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
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

@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class SalesServiceChargeTest {

    @Autowired SalesService sales;
    @Autowired CartService carts;
    @Autowired ConfigurationService config;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        productSync.sync();
        config.put(SettingKey.SERVICE_CHARGE_ENABLED, "true");
        config.put(SettingKey.SERVICE_CHARGE_PERCENT, "10");
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private UUID cartWithTwoBurgers() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "BURGER", new BigDecimal("2")); // net 60.00
        return cart;
    }

    @Test
    void appliesTaxableServiceChargeWhenFlagSet() {
        // net 60.00; service charge 10% = 6.00 (net); VAT 15% on (60 + 6):
        // productTax 9.00, scTax 0.90 -> taxTotal 9.90; grand 60 + 6 + 9.90 = 75.90
        UUID cart = cartWithTwoBurgers();
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, new BigDecimal("75.90"),
                        new BigDecimal("75.90"))), java.util.Map.of(), null, true), "cashier");

        assertThat(sale.subtotal()).isEqualByComparingTo("60.00");
        assertThat(sale.serviceChargeAmount()).isEqualByComparingTo("6.00");
        assertThat(sale.taxTotal()).isEqualByComparingTo("9.90");
        assertThat(sale.grandTotal()).isEqualByComparingTo("75.90");
    }

    @Test
    void noChargeWhenFlagUnset() {
        UUID cart = cartWithTwoBurgers();
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, new BigDecimal("69.00"),
                        new BigDecimal("69.00")))), "cashier"); // 4-arg-less -> applyServiceCharge false

        assertThat(sale.serviceChargeAmount()).isEqualByComparingTo("0.00");
        assertThat(sale.grandTotal()).isEqualByComparingTo("69.00");
    }

    @Test
    void noChargeWhenConfigDisabledEvenIfFlagSet() {
        config.put(SettingKey.SERVICE_CHARGE_ENABLED, "false");
        UUID cart = cartWithTwoBurgers();
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, new BigDecimal("69.00"),
                        new BigDecimal("69.00"))), java.util.Map.of(), null, true), "cashier");

        assertThat(sale.serviceChargeAmount()).isEqualByComparingTo("0.00");
        assertThat(sale.grandTotal()).isEqualByComparingTo("69.00");
    }

    @Test
    void quoteWithChargeMatchesCheckoutGrandTotal() {
        UUID cart = cartWithTwoBurgers();
        QuoteView q = sales.quote(cart, true);
        assertThat(q.serviceChargeAmount()).isEqualByComparingTo("6.00");
        assertThat(q.grandTotal()).isEqualByComparingTo("75.90");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=SalesServiceChargeTest
```
Expected: FAIL to compile — the config keys, `CheckoutCommand`'s 5-arg form, `SaleView.serviceChargeAmount`, and `quote(UUID, boolean)` do not exist.

- [ ] **Step 3: Add the config keys**

In `src/main/java/com/company/pos/configuration/api/SettingKey.java`, change the last constant's trailing `;` to `,` and append:

```java
    KITCHEN_DEFAULT_STATION("kitchen.default.station", "Kitchen"),
    SERVICE_CHARGE_ENABLED("service.charge.enabled", "false"),
    SERVICE_CHARGE_PERCENT("service.charge.percent", "0"),
    SERVICE_CHARGE_LABEL("service.charge.label", "Service Charge");
```

- [ ] **Step 4: Add `applyServiceCharge` to `CheckoutCommand`**

Replace the body of `src/main/java/com/company/pos/sales/api/CheckoutCommand.java`:

```java
package com.company.pos.sales.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CheckoutCommand(UUID cartId, List<TenderInput> tenders,
        Map<String, DiscountInput> lineDiscounts, DiscountInput transactionDiscount,
        boolean applyServiceCharge) {

    public CheckoutCommand {
        if (lineDiscounts == null) {
            lineDiscounts = Map.of();
        }
    }

    /** Convenience: discounts but no service charge (used by existing callers). */
    public CheckoutCommand(UUID cartId, List<TenderInput> tenders,
            Map<String, DiscountInput> lineDiscounts, DiscountInput transactionDiscount) {
        this(cartId, tenders, lineDiscounts, transactionDiscount, false);
    }

    /** Convenience: a checkout with no discounts and no service charge. */
    public CheckoutCommand(UUID cartId, List<TenderInput> tenders) {
        this(cartId, tenders, Map.of(), null, false);
    }
}
```

- [ ] **Step 5: Add `serviceChargeAmount` to `QuoteView` and `SaleView`**

`QuoteView.java` — add `serviceChargeAmount` after `discountTotal`:

```java
package com.company.pos.sales.api;

import java.math.BigDecimal;

public record QuoteView(String currencyCode, BigDecimal subtotal, BigDecimal discountTotal,
        BigDecimal serviceChargeAmount, BigDecimal taxTotal, BigDecimal grandTotal) {
}
```

`SaleView.java` — append `serviceChargeAmount` as the last component:

```java
public record SaleView(UUID id, String receiptNumber, String status, String currencyCode,
        BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal, Instant createdAt,
        List<SaleLineView> lines, List<SalePaymentView> payments, BigDecimal discountTotal,
        BigDecimal txnDiscountAmount, String txnDiscountType, String txnDiscountReason,
        BigDecimal serviceChargeAmount) {
}
```

- [ ] **Step 6: Add the column + field to `Sale`**

In `src/main/java/com/company/pos/sales/domain/Sale.java`:

1. Add the field (after `discountTotal`):

```java
    @Column(name = "service_charge_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal serviceChargeAmount;
```

2. Change the constructor to accept `serviceChargeAmount` (insert it right after `discountTotal`, before `customerId`) and assign it:

```java
    public Sale(UUID id, String receiptNumber, String storeId, String terminalId,
            String cashierUsername, String locationCode, String currencyCode,
            BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal, Instant createdAt,
            BigDecimal txnDiscountAmount, String txnDiscountType, String txnDiscountReason,
            BigDecimal discountTotal, BigDecimal serviceChargeAmount, UUID customerId) {
        // ... existing assignments ...
        this.discountTotal = discountTotal;
        this.serviceChargeAmount = serviceChargeAmount;
        this.customerId = customerId;
        this.status = "COMPLETED";
    }
```

3. Add the getter:

```java
    public BigDecimal getServiceChargeAmount() {
        return serviceChargeAmount;
    }
```

- [ ] **Step 7: Compute the charge in `DefaultSalesService`**

In `src/main/java/com/company/pos/sales/application/DefaultSalesService.java`:

1. Change `PricedCart` to carry the charge breakdown and extend `priceDiscountTax`:

```java
    private record PricedCart(String currency, DiscountResult disc, TaxedCart taxed,
            BigDecimal serviceChargeNet, BigDecimal serviceChargeTax) {
    }

    private PricedCart priceDiscountTax(CartView cart, Map<String, DiscountInput> lineDiscounts,
            DiscountInput transactionDiscount, boolean callerIsManager, boolean applyServiceCharge) {
        // ... stages 1-3 UNCHANGED, ending with:
        TaxedCart taxed = tax.applyTax(taxInputs, rate, inclusive, currency);

        // 4. Service charge (taxable) — computed as a separate one-line tax call so it never
        //    becomes a product line. Only applied when the caller asks AND the store enables it.
        BigDecimal scNet = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal scTax = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (applyServiceCharge && config.getBoolean(SettingKey.SERVICE_CHARGE_ENABLED)) {
            BigDecimal pct = new BigDecimal(config.getString(SettingKey.SERVICE_CHARGE_PERCENT));
            if (pct.signum() > 0) {
                String label = config.getString(SettingKey.SERVICE_CHARGE_LABEL);
                BigDecimal scInput = taxed.subtotal().multiply(pct)
                        .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                if (scInput.signum() > 0) {
                    TaxedCart scTaxed = tax.applyTax(List.of(new TaxLineInput("SERVICE_CHARGE", label,
                            BigDecimal.ONE, scInput, scInput, currency)), rate, inclusive, currency);
                    scNet = scTaxed.subtotal().setScale(2, RoundingMode.HALF_UP);
                    scTax = scTaxed.taxTotal().setScale(2, RoundingMode.HALF_UP);
                }
            }
        }
        return new PricedCart(currency, disc, taxed, scNet, scTax);
    }
```

(Keep the existing stage 1-3 bodies verbatim; only the return and the new stage 4 change. `rate`, `inclusive`, and `currency` are already locals in stage 3.)

2. In `checkout(...)`, update the call and totals (replace the `PricedCart pc = ...` block through the `grandTotal` line):

```java
        PricedCart pc = priceDiscountTax(cart, command.lineDiscounts(),
                command.transactionDiscount(), callerIsManager, command.applyServiceCharge());
        DiscountResult disc = pc.disc();
        TaxedCart taxed = pc.taxed();
        String currency = pc.currency();
        BigDecimal serviceChargeNet = pc.serviceChargeNet();
        BigDecimal serviceChargeTax = pc.serviceChargeTax();
        BigDecimal taxTotal = taxed.taxTotal().add(serviceChargeTax).setScale(2, RoundingMode.HALF_UP);
        BigDecimal grandTotal = taxed.grandTotal().add(serviceChargeNet).add(serviceChargeTax)
                .setScale(2, RoundingMode.HALF_UP);
```

3. In the `Sale` construction, use the new `taxTotal`/`grandTotal` and pass `serviceChargeNet`:

```java
        Sale sale = new Sale(saleId, receiptNumber, storeId, terminalId, cashierUsername,
                location, currency, taxed.subtotal(), taxTotal, grandTotal, now,
                disc.txnDiscountAmount(),
                disc.txnDiscountType() == null ? null : disc.txnDiscountType().name(),
                disc.txnDiscountReason(), disc.discountTotal(), serviceChargeNet, cart.customerId());
```

4. In the `SaleCompleted` publish, use the new `grandTotal` (was `taxed.grandTotal()`):

```java
        events.publish(new SaleCompleted(saleId, receiptNumber, terminalId, location, currency,
                grandTotal, cashTotal, soldLines, cart.customerId(), now));
```

The sale-line loop and `soldLines` (both iterate `taxed.lines()` = product lines only) are **unchanged** — the charge is not among them.

5. Update `toView` to pass `serviceChargeAmount` (last arg):

```java
        return new SaleView(sale.getId(), sale.getReceiptNumber(), sale.getStatus(),
                sale.getCurrencyCode(), sale.getSubtotal(), sale.getTaxTotal(), sale.getGrandTotal(),
                sale.getCreatedAt(), lines, paymentViews, sale.getDiscountTotal(),
                sale.getTxnDiscountAmount(), sale.getTxnDiscountType(), sale.getTxnDiscountReason(),
                sale.getServiceChargeAmount());
```

6. Update `quote` to take the flag and include the charge in its totals:

```java
    @Override
    @Transactional(readOnly = true)
    public QuoteView quote(UUID cartId, boolean applyServiceCharge) {
        CartView cart = carts.getCart(cartId);
        if (!"OPEN".equals(cart.status())) {
            throw DomainException.conflict("Cart " + cartId + " is not open");
        }
        if (cart.lines().isEmpty()) {
            throw DomainException.validation("Cannot quote an empty cart");
        }
        PricedCart pc = priceDiscountTax(cart, Map.of(), null, false, applyServiceCharge);
        BigDecimal taxTotal = pc.taxed().taxTotal().add(pc.serviceChargeTax());
        BigDecimal grandTotal = pc.taxed().grandTotal().add(pc.serviceChargeNet())
                .add(pc.serviceChargeTax());
        return new QuoteView(pc.currency(),
                pc.taxed().subtotal().setScale(2, RoundingMode.HALF_UP),
                pc.disc().discountTotal(),
                pc.serviceChargeNet().setScale(2, RoundingMode.HALF_UP),
                taxTotal.setScale(2, RoundingMode.HALF_UP),
                grandTotal.setScale(2, RoundingMode.HALF_UP));
    }

    @Override
    @Transactional(readOnly = true)
    public QuoteView quote(UUID cartId) {
        return quote(cartId, false);
    }
```

- [ ] **Step 8: Add `quote(UUID, boolean)` to the `SalesService` interface**

In `src/main/java/com/company/pos/sales/api/SalesService.java`, add alongside the existing `quote`:

```java
    /** As {@link #quote(UUID)} but optionally applies the configured service charge. */
    QuoteView quote(UUID cartId, boolean applyServiceCharge);
```

(Keep the existing `QuoteView quote(UUID cartId);` declaration — the impl now delegates it to `quote(cartId, false)`.)

- [ ] **Step 9: Create the V34 migration**

`src/main/resources/db/migration/sales/V34__sale_service_charge.sql`:

```sql
ALTER TABLE sale ADD COLUMN service_charge_amount NUMERIC(19, 2) NOT NULL DEFAULT 0;
```

- [ ] **Step 10: Run the test to verify it passes**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=SalesServiceChargeTest
```
Expected: PASS (4 tests).

- [ ] **Step 11: Run the sales + dining suites + ModularityTests**

```bash
./mvnw test -Dtest='com.company.pos.sales.*,com.company.pos.dining.*,ModularityTests'
```
Expected: PASS. Existing checkout/quote/split tests are unaffected (charge defaults off; `SaleView`'s new trailing component and `Sale`'s new constructor arg are the only shape changes, and their sole construction sites were updated). If any other `new Sale(` or `new SaleView(` or `new QuoteView(` construction site exists, update it (grep to confirm; expected: only `DefaultSalesService`).

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/company/pos/configuration/api/SettingKey.java src/main/java/com/company/pos/sales src/main/resources/db/migration/sales/V34__sale_service_charge.sql src/test/java/com/company/pos/sales/SalesServiceChargeTest.java
git commit -m "$(cat <<'EOF'
feat(sales): taxable service charge in the checkout pipeline

Config-gated (SERVICE_CHARGE_ENABLED/PERCENT/LABEL, off by default) service
charge computed in priceDiscountTax via a separate one-line tax call (never a
product line), gated by CheckoutCommand.applyServiceCharge. Persists
service_charge_amount on the sale (V34); taxTotal/grandTotal include it;
quote(cartId, applyServiceCharge) mirrors it so even-split shares stay exact.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Receipt + ERP carry the service charge

**Interfaces:**
- Consumes: `Sale.getServiceChargeAmount()` / `SaleView.serviceChargeAmount()` (Task 1).
- Produces: `ReceiptData.serviceChargeAmount()`; `SaleUpload.serviceChargeAmount()`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/company/pos/receipt/ServiceChargeReceiptTest.java`:

```java
package com.company.pos.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.device.api.PrintLine;
import com.company.pos.device.infrastructure.InMemoryPrinter;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.api.TenderInput;
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

@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class ServiceChargeReceiptTest {

    @Autowired SalesService sales;
    @Autowired CartService carts;
    @Autowired ConfigurationService config;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired InMemoryPrinter printer;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        productSync.sync();
        config.put(SettingKey.SERVICE_CHARGE_ENABLED, "true");
        config.put(SettingKey.SERVICE_CHARGE_PERCENT, "10");
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    @Test
    void receiptShowsTheServiceChargeLine() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "BURGER", new BigDecimal("2"));
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, new BigDecimal("75.90"),
                        new BigDecimal("75.90"))), java.util.Map.of(), null, true), "cashier");

        boolean hasServiceChargeLine = printer.lastReceipt().stream()
                .map(PrintLine::text)
                .anyMatch(t -> t.contains("Service Charge"));
        assertThat(hasServiceChargeLine).isTrue();
    }
}
```

Create `src/test/java/com/company/pos/sync/ServiceChargeUploadTest.java`:

```java
package com.company.pos.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.cart.api.CartService;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
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
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class ServiceChargeUploadTest {

    @Autowired SalesService sales;
    @Autowired CartService carts;
    @Autowired ConfigurationService config;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        productSync.sync();
        config.put(SettingKey.SERVICE_CHARGE_ENABLED, "true");
        config.put(SettingKey.SERVICE_CHARGE_PERCENT, "10");
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    @Test
    void uploadCarriesTheServiceChargeAmount() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "BURGER", new BigDecimal("2"));
        sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, new BigDecimal("75.90"),
                        new BigDecimal("75.90"))), java.util.Map.of(), null, true), "cashier");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(fake.uploadedSales()).hasSize(1);
            assertThat(fake.uploadedSales().get(0).serviceChargeAmount())
                    .isEqualByComparingTo("6.00");
        });
    }
}
```

- [ ] **Step 2: Run to verify they fail**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest='ServiceChargeReceiptTest,ServiceChargeUploadTest'
```
Expected: FAIL — `SaleUpload.serviceChargeAmount()` doesn't exist (upload test won't compile), and the receipt has no service-charge line.

- [ ] **Step 3: Add `serviceChargeAmount` to `ReceiptData`**

Append `serviceChargeAmount` as the last component of the canonical record, and default it to zero in the 9-arg convenience constructor:

```java
public record ReceiptData(String receiptNumber, String cashierName, Instant timestamp,
        List<ReceiptLineData> lines, BigDecimal subtotal, BigDecimal taxTotal,
        BigDecimal grandTotal, List<ReceiptPaymentData> payments, String currencyCode,
        BigDecimal discountTotal, BigDecimal txnDiscountAmount, String txnDiscountReason,
        BigDecimal serviceChargeAmount) {

    /** Convenience for receipts without discounts (e.g. credit notes). */
    public ReceiptData(String receiptNumber, String cashierName, Instant timestamp,
            List<ReceiptLineData> lines, BigDecimal subtotal, BigDecimal taxTotal,
            BigDecimal grandTotal, List<ReceiptPaymentData> payments, String currencyCode) {
        this(receiptNumber, cashierName, timestamp, lines, subtotal, taxTotal, grandTotal, payments,
                currencyCode, BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO);
    }
}
```

- [ ] **Step 4: Print the charge line in `DefaultReceiptService`**

In `src/main/java/com/company/pos/receipt/application/DefaultReceiptService.java`, after the txn-discount block and **before** the `Tax:` line, add:

```java
        if (data.serviceChargeAmount() != null && data.serviceChargeAmount().signum() > 0) {
            lines.add(new PrintLine(config.getString(SettingKey.SERVICE_CHARGE_LABEL) + ": +"
                    + money(data.serviceChargeAmount(), currency, locale), false));
        }
```

(`config` and `SettingKey` are already imported in this class.)

- [ ] **Step 5: Pass the charge into `ReceiptData` from `DefaultSalesService.printReceipt`**

In `printReceipt(...)`, add `sale.getServiceChargeAmount()` as the final argument of the `new ReceiptData(...)` call:

```java
            receipts.print(new ReceiptData(sale.getReceiptNumber(), sale.getCashierUsername(),
                    sale.getCreatedAt(), lines, sale.getSubtotal(), sale.getTaxTotal(),
                    sale.getGrandTotal(), pays, sale.getCurrencyCode(), sale.getDiscountTotal(),
                    sale.getTxnDiscountAmount(), sale.getTxnDiscountReason(),
                    sale.getServiceChargeAmount()));
```

- [ ] **Step 6: Add `serviceChargeAmount` to `SaleUpload` and map it**

In `src/main/java/com/company/pos/integration/api/SaleUpload.java`, append `serviceChargeAmount` as the last top-level component:

```java
public record SaleUpload(UUID saleId, String receiptNumber, String terminalId, String locationCode,
        String currencyCode, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal,
        Instant createdAt, List<Line> lines, List<Payment> payments, BigDecimal discountTotal,
        BigDecimal txnDiscountAmount, String txnDiscountType, String txnDiscountReason,
        BigDecimal serviceChargeAmount) {
    // nested Line / Payment records unchanged
```

In `src/main/java/com/company/pos/sync/application/SaleUploadListener.java`, pass it in `toUpload`:

```java
        return new SaleUpload(sale.id(), sale.receiptNumber(), event.terminalId(),
                event.locationCode(), sale.currencyCode(), sale.subtotal(), sale.taxTotal(),
                sale.grandTotal(), sale.createdAt(), lines, payments, sale.discountTotal(),
                sale.txnDiscountAmount(), sale.txnDiscountType(), sale.txnDiscountReason(),
                sale.serviceChargeAmount());
```

- [ ] **Step 7: Run the tests to verify they pass**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest='ServiceChargeReceiptTest,ServiceChargeUploadTest'
```
Expected: PASS. If any other `new SaleUpload(` construction site exists, update it (grep; expected: only `SaleUploadListener`).

- [ ] **Step 8: Run the affected suites + ModularityTests**

```bash
./mvnw test -Dtest='com.company.pos.receipt.*,com.company.pos.sync.*,ModularityTests'
```
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/company/pos/receipt src/main/java/com/company/pos/integration/api/SaleUpload.java src/main/java/com/company/pos/sync/application/SaleUploadListener.java src/main/java/com/company/pos/sales/application/DefaultSalesService.java src/test/java/com/company/pos/receipt/ServiceChargeReceiptTest.java src/test/java/com/company/pos/sync/ServiceChargeUploadTest.java
git commit -m "$(cat <<'EOF'
feat(receipt,sync): show + upload the service charge

ReceiptData carries serviceChargeAmount and the renderer prints a charge line
(labeled from SERVICE_CHARGE_LABEL) between discount and total when > 0.
SaleUpload carries serviceChargeAmount so the ERP reconciles the invoice.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Dining wiring — DINE_IN gate + manager waive

Sets `applyServiceCharge` from config + `serviceType` + a manager-gated waiver, across the single close, by-item split, and even split.

**Interfaces:**
- Consumes: `CheckoutCommand.applyServiceCharge` (Task 1), `SalesService.quote(UUID, boolean)` (Task 1), existing `config`, `ServiceType`.
- Produces: `CloseOrderCommand.waiveServiceCharge()`, `SplitCloseCommand.waiveServiceCharge()`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/dining/DiningServiceChargeTest.java`:

```java
package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.CloseOrderCommand;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.ServiceType;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.TenderInput;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
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
class DiningServiceChargeTest {

    @Autowired DiningService dining;
    @Autowired ConfigurationService config;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        productSync.sync();
        config.put(SettingKey.SERVICE_CHARGE_ENABLED, "true");
        config.put(SettingKey.SERVICE_CHARGE_PERCENT, "10");
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private UUID openOrder(ServiceType type) {
        UUID tableId = dining.registerTable(new RegisterTableCommand("L" + UUID.randomUUID(), 4)).id();
        UUID orderId = dining.openOrder(new OpenOrderCommand(tableId, type), "alice").id();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("2"), null, null), "alice");
        return orderId; // net 60.00 -> with 10% SC and 15% VAT, grand 75.90
    }

    private static TenderInput cash(String amount) {
        return new TenderInput(PaymentMethod.CASH, new BigDecimal(amount), new BigDecimal(amount));
    }

    @Test
    void dineInCloseAppliesServiceCharge() {
        UUID orderId = openOrder(ServiceType.DINE_IN);
        SaleView sale = dining.closeOrder(orderId,
                new CloseOrderCommand(List.of(cash("75.90")), Map.of(), null, false), "alice", false);
        assertThat(sale.serviceChargeAmount()).isEqualByComparingTo("6.00");
        assertThat(sale.grandTotal()).isEqualByComparingTo("75.90");
    }

    @Test
    void quickServiceCloseDoesNotApplyServiceCharge() {
        UUID orderId = openOrder(ServiceType.QUICK_SERVICE);
        SaleView sale = dining.closeOrder(orderId,
                new CloseOrderCommand(List.of(cash("69.00")), Map.of(), null, false), "alice", false);
        assertThat(sale.serviceChargeAmount()).isEqualByComparingTo("0.00");
        assertThat(sale.grandTotal()).isEqualByComparingTo("69.00");
    }

    @Test
    void nonManagerWaiverIsRejected() {
        UUID orderId = openOrder(ServiceType.DINE_IN);
        assertThatThrownBy(() -> dining.closeOrder(orderId,
                new CloseOrderCommand(List.of(cash("69.00")), Map.of(), null, true), "alice", false))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void managerWaiverZeroesTheCharge() {
        UUID orderId = openOrder(ServiceType.DINE_IN);
        SaleView sale = dining.closeOrder(orderId,
                new CloseOrderCommand(List.of(cash("69.00")), Map.of(), null, true), "manager", true);
        assertThat(sale.serviceChargeAmount()).isEqualByComparingTo("0.00");
        assertThat(sale.grandTotal()).isEqualByComparingTo("69.00");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=DiningServiceChargeTest
```
Expected: FAIL to compile — `CloseOrderCommand`'s 4-arg form (`waiveServiceCharge`) does not exist.

- [ ] **Step 3: Add `waiveServiceCharge` to the dining commands**

`CloseOrderCommand.java` — add the field + a 3-arg convenience:

```java
public record CloseOrderCommand(List<TenderInput> tenders, Map<String, DiscountInput> lineDiscounts,
        DiscountInput transactionDiscount, boolean waiveServiceCharge) {

    public CloseOrderCommand {
        if (lineDiscounts == null) {
            lineDiscounts = Map.of();
        }
    }

    /** Convenience: no waiver (used by existing callers/tests). */
    public CloseOrderCommand(List<TenderInput> tenders, Map<String, DiscountInput> lineDiscounts,
            DiscountInput transactionDiscount) {
        this(tenders, lineDiscounts, transactionDiscount, false);
    }
}
```

`SplitCloseCommand.java` — add the field + a 3-arg convenience:

```java
public record SplitCloseCommand(SplitMode mode, List<BillInput> bills, EvenSplitInput even,
        boolean waiveServiceCharge) {

    /** Convenience: no waiver (used by existing callers/tests). */
    public SplitCloseCommand(SplitMode mode, List<BillInput> bills, EvenSplitInput even) {
        this(mode, bills, even, false);
    }
}
```

- [ ] **Step 4: Apply the flag in `DefaultDiningService`**

Add imports if missing: `com.company.pos.configuration.api.SettingKey`, `com.company.pos.dining.api.ServiceType`. (`config` is already an injected field.)

1. Add a private helper:

```java
    private boolean resolveApplyServiceCharge(DiningOrder order, boolean waiveServiceCharge,
            boolean callerIsManager) {
        if (waiveServiceCharge && !callerIsManager) {
            throw DomainException.validation("Only a manager can waive the service charge");
        }
        return config.getBoolean(SettingKey.SERVICE_CHARGE_ENABLED)
                && order.getServiceType() == ServiceType.DINE_IN
                && !waiveServiceCharge;
    }
```

2. In `closeOrder`, compute the flag and pass it (5-arg `CheckoutCommand`):

```java
        boolean applyServiceCharge = resolveApplyServiceCharge(order,
                command.waiveServiceCharge(), callerIsManager);
        SaleView sale = sales.checkout(
                new CheckoutCommand(cartId, command.tenders(), command.lineDiscounts(),
                        command.transactionDiscount(), applyServiceCharge),
                cashierUsername, callerIsManager);
```

3. In `closeOrderSplit`, resolve the flag once and thread it to the mode handlers:

```java
        boolean applyServiceCharge = resolveApplyServiceCharge(order,
                command.waiveServiceCharge(), callerIsManager);
        List<SaleView> results = switch (command.mode()) {
            case BY_ITEM -> closeByItem(order, command.bills(), cashierUsername, callerIsManager,
                    applyServiceCharge);
            case EVEN -> closeEven(order, command.even(), cashierUsername, callerIsManager,
                    applyServiceCharge);
        };
```

4. Update `closeByItem` to accept `boolean applyServiceCharge` and build each bill's `CheckoutCommand` with it (5-arg):

```java
    private List<SaleView> closeByItem(DiningOrder order, List<BillInput> bills,
            String cashier, boolean isManager, boolean applyServiceCharge) {
        // ... validation UNCHANGED ...
        SaleView sale = sales.checkout(
                new CheckoutCommand(cartId, bill.tenders(), bill.lineDiscounts(),
                        bill.transactionDiscount(), applyServiceCharge),
                cashier, isManager);
        // ...
    }
```

5. Update `closeEven` to accept `boolean applyServiceCharge`, use `quote(cartId, applyServiceCharge)`, and build the final `CheckoutCommand` with it:

```java
    private List<SaleView> closeEven(DiningOrder order, EvenSplitInput even,
            String cashier, boolean isManager, boolean applyServiceCharge) {
        // ... validation UNCHANGED; build cart UNCHANGED ...
        QuoteView quote = sales.quote(cartId, applyServiceCharge);
        // ... share math UNCHANGED ...
        SaleView sale = sales.checkout(
                new CheckoutCommand(cartId, tenders, Map.of(), null, applyServiceCharge),
                cashier, isManager);
        carts.close(cartId);
        return List.of(sale);
    }
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=DiningServiceChargeTest
```
Expected: PASS (4 tests).

- [ ] **Step 6: Run the dining suite + ModularityTests**

```bash
./mvnw test -Dtest='com.company.pos.dining.*,ModularityTests'
```
Expected: PASS — existing dining tests (which build `CloseOrderCommand`/`SplitCloseCommand` with the 3-arg convenience) are unaffected; the charge stays off in them because their orders don't enable the config.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/pos/dining src/test/java/com/company/pos/dining/DiningServiceChargeTest.java
git commit -m "$(cat <<'EOF'
feat(dining): apply the service charge at DINE_IN closes, manager-waivable

dining sets applyServiceCharge = enabled && serviceType==DINE_IN && !waived
across the single close, by-item split, and even split (quote uses the same
flag so even shares stay exact). Waiving requires a manager; a cashier waiver
is rejected. CloseOrderCommand/SplitCloseCommand gain waiveServiceCharge.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Final Verification (after all tasks)

- [ ] **Full build in both persistence modes:**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw verify
```
Expected: green — all module tests + `ModularityTests`; Testcontainers PostgreSQL validates V34.

---

## Self-Review

**1. Spec coverage:** config-gated taxable charge (Task 1) ✅; separate-tax-call so no phantom line (Task 1) ✅; `applyServiceCharge` on `CheckoutCommand` + `quote`, even-split-exact (Task 1) ✅; `serviceChargeAmount` persisted V34/`SaleView` (Task 1), receipt (Task 2), ERP (Task 2) ✅; DINE_IN-only + retail/QUICK_SERVICE excluded + manager waive (Task 3) ✅; off-by-default so existing behaviour unchanged ✅; deferrals (party-size, per-sale override, post-tax) not built ✅.

**2. Placeholder scan:** none — every step has complete code or an exact command with expected output.

**3. Type consistency:** `CheckoutCommand.applyServiceCharge`, `quote(UUID, boolean)`, `QuoteView.serviceChargeAmount`, `SaleView.serviceChargeAmount`, `Sale.getServiceChargeAmount`, `ReceiptData.serviceChargeAmount`, `SaleUpload.serviceChargeAmount`, `CloseOrderCommand.waiveServiceCharge`, `SplitCloseCommand.waiveServiceCharge` are named identically where produced and consumed. Convenience constructors preserve every existing call site (2-arg/4-arg `CheckoutCommand`, 3-arg `CloseOrderCommand`/`SplitCloseCommand`, 9-arg `ReceiptData`, 1-arg `quote`), so only the intended construction sites change.

**Cross-task note for the executor:** the invariant `grandTotal = subtotal + serviceChargeAmount + taxTotal` and the worked example (net 60.00 → SC 6.00, taxTotal 9.90, grand 75.90 at 10% SC / 15% VAT exclusive) recur across all three tasks' tests — if the numbers ever disagree, the pipeline math in Task 1 is the source of truth. All service-charge tests enable the config via `ConfigurationService.put(...)` in `@BeforeEach` (DB-backed; reset by `DatabaseCleaner`) and are non-`@Transactional` committing tests.
