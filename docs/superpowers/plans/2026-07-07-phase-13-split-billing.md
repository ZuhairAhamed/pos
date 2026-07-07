# Phase 13 — Split Billing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let one dine-in order close as multiple bills in a single atomic operation — by item (whole-line partition → N itemized sales) or even split (one sale, N equal payments).

**Architecture:** `dining` already orchestrates the single close (throwaway cart → `sales.checkout` → stamp `CLOSED`). This generalizes it to a loop over bills, all in one transaction. `sales` gains one small read-only `quote(cartId)` (so `dining` can learn the grand total before building even-split tenders); everything else is reused. A `dining_order_sale` link table records a split's N sale ids.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Modulith, Spring Data JPA, Flyway (store-server) / Hibernate ddl-auto (embedded), JUnit 5 + AssertJ.

## Global Constraints

- **JDK 21 required.** Run `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` before every Maven command.
- **Maven, not Gradle:** `./mvnw`.
- **Money is `BigDecimal`**, never `double`; monetary scale is 2 with `RoundingMode.HALF_UP`. Proportional/even allocation uses **last-share-absorbs-the-remainder** (the existing `DiscountCalculator` convention) so per-share amounts sum exactly.
- **UUID PKs stored as `VARCHAR(36)`** via `@JdbcTypeCode(SqlTypes.VARCHAR)` (see `dining/domain/OrderLine`).
- **Flyway versions are globally sequential.** Latest existing is **V32**; this phase uses **V33**. Per-module dir `src/main/resources/db/migration/dining/`. Only store-server runs Flyway; embedded uses `ddl-auto`.
- **Authorization is method security.** The split-close endpoint is authenticated (cashier and up), matching the existing single close — no `@PreAuthorize`. `callerIsManager` (derived from JWT authorities exactly as the close endpoint does) is passed into each bill's checkout so an over-cap discount on a bill still requires a manager.
- **Module boundaries enforced** by `ModularityTests`. `dining` gains **`payment :: api`** (for `PaymentMethod` in `EvenSplitInput`) — acyclic (`payment` has no `dining` dependency). `quote`/`QuoteView` live in `sales :: api`; new split DTOs live in `dining :: api`. Never import another module's domain/infrastructure.
- **Atomic settlement:** all N checkouts + the order close run in one `@Transactional` boundary (`DefaultDiningService` is `@Transactional`; `sales.checkout` is REQUIRED and joins it). Any failure rolls the whole split back — no partial sales, order stays `OPEN`.
- **The existing single-bill `closeOrder` / `POST .../close` stays untouched.**
- After any change, re-run the affected module's tests **and** `ModularityTests`.

---

## File Structure

**Task 1 — `sales.quote` (modify `sales`):**
- Create: `src/main/java/com/company/pos/sales/api/QuoteView.java`
- Modify: `src/main/java/com/company/pos/sales/api/SalesService.java` (add `quote`)
- Modify: `src/main/java/com/company/pos/sales/application/DefaultSalesService.java` (extract `priceDiscountTax`; add `quote`)
- Test: `src/test/java/com/company/pos/sales/SalesQuoteTest.java`

**Task 2 — by-item split close (modify `dining`):**
- Create: `src/main/java/com/company/pos/dining/api/SplitMode.java`
- Create: `src/main/java/com/company/pos/dining/api/BillInput.java`
- Create: `src/main/java/com/company/pos/dining/api/EvenSplitInput.java`
- Create: `src/main/java/com/company/pos/dining/api/SplitCloseCommand.java`
- Create: `src/main/java/com/company/pos/dining/domain/DiningOrderSale.java`
- Create: `src/main/java/com/company/pos/dining/infrastructure/DiningOrderSaleRepository.java`
- Modify: `src/main/java/com/company/pos/dining/api/DiningService.java` (add `closeOrderSplit`, `listOrderSaleIds`)
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java` (inject repo; `closeOrderSplit` with BY_ITEM path + `closeEven` stub; `listOrderSaleIds`)
- Modify: `src/main/java/com/company/pos/dining/web/DiningController.java` (inject `SalesService`; `close-split` + `GET .../sales` endpoints)
- Modify: `src/main/java/com/company/pos/dining/package-info.java` (add `payment :: api`)
- Create: `src/main/resources/db/migration/dining/V33__create_dining_order_sale.sql`
- Test: `src/test/java/com/company/pos/dining/DiningSplitByItemTest.java`

**Task 3 — even split (modify `dining`):**
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java` (implement `closeEven`)
- Test: `src/test/java/com/company/pos/dining/DiningEvenSplitTest.java`

---

## Task 1: `sales.quote` (read-only pricing pass)

Extract the pricing → discount → tax computation inside `checkout` into a shared private method, then add a read-only `quote(cartId)` that runs it with no discounts and returns the totals — no `Sale`, no payment, no event. Behaviour of `checkout` is unchanged (pure extraction).

**Files:**
- Create: `src/main/java/com/company/pos/sales/api/QuoteView.java`
- Modify: `src/main/java/com/company/pos/sales/api/SalesService.java`
- Modify: `src/main/java/com/company/pos/sales/application/DefaultSalesService.java`
- Test: `src/test/java/com/company/pos/sales/SalesQuoteTest.java`

**Interfaces:**
- Consumes: existing `DefaultSalesService` collaborators (`carts`, `pricing`, `discounts`, `tax`, `config`) and `DiscountResult`/`TaxedCart`.
- Produces (Task 3 relies on these): `SalesService.quote(UUID cartId) : QuoteView`; `QuoteView(String currencyCode, BigDecimal subtotal, BigDecimal discountTotal, BigDecimal taxTotal, BigDecimal grandTotal)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/sales/SalesQuoteTest.java`:

```java
package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
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
class SalesQuoteTest {

    @Autowired SalesService sales;
    @Autowired CartService carts;
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
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    @Test
    void quoteReturnsPricedTotalsWithoutCreatingASale() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "BURGER", new BigDecimal("2")); // 2 × 30.00, VAT 15% exclusive

        QuoteView q = sales.quote(cart);

        assertThat(q.subtotal()).isEqualByComparingTo("60.00");
        assertThat(q.taxTotal()).isEqualByComparingTo("9.00");
        assertThat(q.grandTotal()).isEqualByComparingTo("69.00");
        // the cart is left OPEN — quote neither pays nor closes it
        assertThat(carts.getCart(cart).status()).isEqualTo("OPEN");
    }

    @Test
    void quoteMatchesTheTotalsCheckoutProduces() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "BURGER", new BigDecimal("2"));

        QuoteView q = sales.quote(cart);
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, new BigDecimal("69.00"),
                        new BigDecimal("69.00")))), "cashier");

        assertThat(q.grandTotal()).isEqualByComparingTo(sale.grandTotal());
        assertThat(q.subtotal()).isEqualByComparingTo(sale.subtotal());
        assertThat(q.taxTotal()).isEqualByComparingTo(sale.taxTotal());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=SalesQuoteTest
```
Expected: FAIL to compile — `SalesService.quote` and `QuoteView` do not exist.

- [ ] **Step 3: Create `QuoteView`**

`src/main/java/com/company/pos/sales/api/QuoteView.java`:

```java
package com.company.pos.sales.api;

import java.math.BigDecimal;

public record QuoteView(String currencyCode, BigDecimal subtotal, BigDecimal discountTotal,
        BigDecimal taxTotal, BigDecimal grandTotal) {
}
```

- [ ] **Step 4: Add `quote` to the `SalesService` interface**

In `src/main/java/com/company/pos/sales/api/SalesService.java`, add:

```java
    /**
     * Read-only pricing pass: prices the cart with NO discounts, applies tax, returns the totals.
     * Creates no sale, takes no payment, fires no event. Used to learn a cart's total up front
     * (e.g. to split it evenly).
     */
    QuoteView quote(UUID cartId);
```

- [ ] **Step 5: Extract `priceDiscountTax` and implement `quote` in `DefaultSalesService`**

In `src/main/java/com/company/pos/sales/application/DefaultSalesService.java`:

1. Add imports:

```java
import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.QuoteView;
import java.util.Map;
```

2. Add a private carrier record and the extracted method (place them below the constructor):

```java
    private record PricedCart(String currency, DiscountResult disc, TaxedCart taxed) {
    }

    private PricedCart priceDiscountTax(CartView cart, Map<String, DiscountInput> lineDiscounts,
            DiscountInput transactionDiscount, boolean callerIsManager) {
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
        DiscountResult disc = discounts.apply(priced, lineDiscounts, transactionDiscount,
                callerIsManager, maxPct, maxAmt, reasonCodes);

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
        return new PricedCart(currency, disc, taxed);
    }
```

3. In `checkout(...)`, **replace** the existing stages 1–3 (the block that starts `// 1. Price the lines` and ends with `BigDecimal grandTotal = taxed.grandTotal().setScale(2, RoundingMode.HALF_UP);`) with a call to the extracted method:

```java
        PricedCart pc = priceDiscountTax(cart, command.lineDiscounts(),
                command.transactionDiscount(), callerIsManager);
        DiscountResult disc = pc.disc();
        TaxedCart taxed = pc.taxed();
        String currency = pc.currency();
        BigDecimal grandTotal = taxed.grandTotal().setScale(2, RoundingMode.HALF_UP);
```

Everything after (tenders, persist, publish, receipt, `toView`) stays exactly as-is — `disc`, `taxed`, `currency`, `grandTotal` are the same locals it already used.

4. Add the `quote` method (place near `getSale`):

```java
    @Override
    @Transactional(readOnly = true)
    public QuoteView quote(UUID cartId) {
        CartView cart = carts.getCart(cartId);
        if (!"OPEN".equals(cart.status())) {
            throw DomainException.conflict("Cart " + cartId + " is not open");
        }
        if (cart.lines().isEmpty()) {
            throw DomainException.validation("Cannot quote an empty cart");
        }
        PricedCart pc = priceDiscountTax(cart, Map.of(), null, false);
        return new QuoteView(pc.currency(),
                pc.taxed().subtotal().setScale(2, RoundingMode.HALF_UP),
                pc.disc().discountTotal(),
                pc.taxed().taxTotal().setScale(2, RoundingMode.HALF_UP),
                pc.taxed().grandTotal().setScale(2, RoundingMode.HALF_UP));
    }
```

- [ ] **Step 6: Run the test to verify it passes**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=SalesQuoteTest
```
Expected: PASS (2 tests).

- [ ] **Step 7: Run the sales suite to confirm the extraction is behaviour-preserving**

```bash
./mvnw test -Dtest='com.company.pos.sales.*,ModularityTests'
```
Expected: PASS — all existing sales/checkout tests unchanged.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/company/pos/sales/api/QuoteView.java src/main/java/com/company/pos/sales/api/SalesService.java src/main/java/com/company/pos/sales/application/DefaultSalesService.java src/test/java/com/company/pos/sales/SalesQuoteTest.java
git commit -m "$(cat <<'EOF'
feat(sales): read-only quote(cartId) for pre-checkout totals

Extracts checkout's pricing→discount→tax stages into a shared private
method (checkout behaviour unchanged) and adds SalesService.quote, which
runs it with no discounts and returns the totals without creating a sale,
taking payment, or firing an event. Enables dining's even split.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: By-item split close (`dining`)

Adds the split DTOs, the `closeOrderSplit` orchestration with the **by-item** path (whole-line partition → N itemized sales), the `dining_order_sale` link table, `listOrderSaleIds`, and the HTTP endpoints. The `EVEN` branch is stubbed to throw (implemented in Task 3).

**Files:** see File Structure above.

**Interfaces:**
- Consumes: `sales.checkout` / `sales.getSale`, `carts.createCart`/`addLinePreResolved`/`close`, existing `OrderLine` getters, `TenderInput`/`DiscountInput`/`PaymentMethod`.
- Produces (Task 3 relies on these): `DiningService.closeOrderSplit(UUID, SplitCloseCommand, String, boolean) : List<SaleView>`; the `private List<SaleView> closeEven(DiningOrder, EvenSplitInput, String, boolean)` seam (throws in this task); `SplitCloseCommand`/`SplitMode`/`BillInput`/`EvenSplitInput`; `DiningOrderSale` + repo.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/dining/DiningSplitByItemTest.java`:

```java
package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.BillInput;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OrderStatus;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.SplitCloseCommand;
import com.company.pos.dining.api.SplitMode;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.DiscountInput;
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
class DiningSplitByItemTest {

    @Autowired DiningService dining;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("FRIES", "Fries", "FOOD", "Food", "bcFRIES",
                "EA", new BigDecimal("12.00"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("WATER", "Water", "BEV", "Beverages", "bcWATER",
                "EA", new BigDecimal("5.00"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private UUID openOrderOnFreshTable() {
        UUID tableId = dining.registerTable(new RegisterTableCommand("L" + UUID.randomUUID(), 4)).id();
        return dining.openOrder(new OpenOrderCommand(tableId, null), "alice").id();
    }

    private UUID addLine(UUID orderId, String sku, String qty) {
        return dining.addLine(orderId,
                new AddLineCommand(sku, new BigDecimal(qty), null, null), "alice")
                .lines().stream().filter(l -> l.sku().equals(sku)).findFirst().orElseThrow().id();
    }

    private static TenderInput cash(String amount) {
        return new TenderInput(PaymentMethod.CASH, new BigDecimal(amount), new BigDecimal(amount));
    }

    @Test
    void closesTwoItemizedBillsFromOnePartition() {
        UUID orderId = openOrderOnFreshTable();
        UUID burger = addLine(orderId, "BURGER", "1"); // 30.00 -> grand 34.50
        addLine(orderId, "FRIES", "1");                // 12.00
        addLine(orderId, "WATER", "1");                // 5.00  -> fries+water grand 19.55
        UUID fries = dining.getOrder(orderId).lines().stream()
                .filter(l -> l.sku().equals("FRIES")).findFirst().orElseThrow().id();
        UUID water = dining.getOrder(orderId).lines().stream()
                .filter(l -> l.sku().equals("WATER")).findFirst().orElseThrow().id();

        SplitCloseCommand cmd = new SplitCloseCommand(SplitMode.BY_ITEM, List.of(
                new BillInput(List.of(burger), List.of(cash("34.50")), Map.of(), null),
                new BillInput(List.of(fries, water), List.of(cash("19.55")), Map.of(), null)),
                null);

        List<SaleView> sales = dining.closeOrderSplit(orderId, cmd, "alice", false);

        assertThat(sales).hasSize(2);
        assertThat(sales.get(0).grandTotal()).isEqualByComparingTo("34.50");
        assertThat(sales.get(1).grandTotal()).isEqualByComparingTo("19.55");
        assertThat(dining.getOrder(orderId).status()).isEqualTo(OrderStatus.CLOSED);
        assertThat(dining.listOrderSaleIds(orderId)).hasSize(2);
    }

    @Test
    void rejectsIncompletePartition() {
        UUID orderId = openOrderOnFreshTable();
        UUID burger = addLine(orderId, "BURGER", "1");
        addLine(orderId, "FRIES", "1"); // left unassigned

        SplitCloseCommand cmd = new SplitCloseCommand(SplitMode.BY_ITEM, List.of(
                new BillInput(List.of(burger), List.of(cash("34.50")), Map.of(), null)), null);

        assertThatThrownBy(() -> dining.closeOrderSplit(orderId, cmd, "alice", false))
                .isInstanceOf(DomainException.class);
        assertThat(dining.getOrder(orderId).status()).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void rejectsLineOnTwoBills() {
        UUID orderId = openOrderOnFreshTable();
        UUID burger = addLine(orderId, "BURGER", "1");

        SplitCloseCommand cmd = new SplitCloseCommand(SplitMode.BY_ITEM, List.of(
                new BillInput(List.of(burger), List.of(cash("34.50")), Map.of(), null),
                new BillInput(List.of(burger), List.of(cash("34.50")), Map.of(), null)), null);

        assertThatThrownBy(() -> dining.closeOrderSplit(orderId, cmd, "alice", false))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsUnknownLineId() {
        UUID orderId = openOrderOnFreshTable();
        addLine(orderId, "BURGER", "1");

        SplitCloseCommand cmd = new SplitCloseCommand(SplitMode.BY_ITEM, List.of(
                new BillInput(List.of(UUID.randomUUID()), List.of(cash("34.50")), Map.of(), null)), null);

        assertThatThrownBy(() -> dining.closeOrderSplit(orderId, cmd, "alice", false))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsEmptyBill() {
        UUID orderId = openOrderOnFreshTable();
        addLine(orderId, "BURGER", "1");

        SplitCloseCommand cmd = new SplitCloseCommand(SplitMode.BY_ITEM, List.of(
                new BillInput(List.of(), List.of(cash("34.50")), Map.of(), null)), null);

        assertThatThrownBy(() -> dining.closeOrderSplit(orderId, cmd, "alice", false))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void aFailingBillRollsBackTheWholeSplit() {
        UUID orderId = openOrderOnFreshTable();
        UUID burger = addLine(orderId, "BURGER", "1");
        UUID fries = addLine(orderId, "FRIES", "1");

        // second bill's tender is short -> checkout throws -> whole split rolls back
        SplitCloseCommand cmd = new SplitCloseCommand(SplitMode.BY_ITEM, List.of(
                new BillInput(List.of(burger), List.of(cash("34.50")), Map.of(), null),
                new BillInput(List.of(fries), List.of(cash("1.00")), Map.of(), null)), null);

        assertThatThrownBy(() -> dining.closeOrderSplit(orderId, cmd, "alice", false))
                .isInstanceOf(DomainException.class);
        assertThat(dining.getOrder(orderId).status()).isEqualTo(OrderStatus.OPEN);
        assertThat(dining.listOrderSaleIds(orderId)).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=DiningSplitByItemTest
```
Expected: FAIL to compile — the split DTOs and `closeOrderSplit`/`listOrderSaleIds` do not exist.

- [ ] **Step 3: Create the split DTOs**

`src/main/java/com/company/pos/dining/api/SplitMode.java`:

```java
package com.company.pos.dining.api;

public enum SplitMode {
    BY_ITEM,
    EVEN
}
```

`src/main/java/com/company/pos/dining/api/BillInput.java`:

```java
package com.company.pos.dining.api;

import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.TenderInput;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One bill of a by-item split: the order lines it covers, plus its own tenders and optional discounts. */
public record BillInput(List<UUID> lineIds, List<TenderInput> tenders,
        Map<String, DiscountInput> lineDiscounts, DiscountInput transactionDiscount) {
}
```

`src/main/java/com/company/pos/dining/api/EvenSplitInput.java`:

```java
package com.company.pos.dining.api;

import com.company.pos.payment.api.PaymentMethod;
import java.util.List;

/** An even N-way split: {@code methods.size()} must equal {@code ways} (one payment method per share). */
public record EvenSplitInput(int ways, List<PaymentMethod> methods) {
}
```

`src/main/java/com/company/pos/dining/api/SplitCloseCommand.java`:

```java
package com.company.pos.dining.api;

import java.util.List;

/** BY_ITEM populates {@code bills} (and {@code even} is null); EVEN populates {@code even} (and {@code bills} is null). */
public record SplitCloseCommand(SplitMode mode, List<BillInput> bills, EvenSplitInput even) {
}
```

- [ ] **Step 4: Create the link entity and repository**

`src/main/java/com/company/pos/dining/domain/DiningOrderSale.java`:

```java
package com.company.pos.dining.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Links a dine-in order to a sale produced by a split close (one row per sale). */
@Entity
@Table(name = "dining_order_sale")
public class DiningOrderSale {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "order_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID orderId;

    @Column(name = "sale_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID saleId;

    protected DiningOrderSale() {
        // JPA
    }

    public DiningOrderSale(UUID id, UUID orderId, UUID saleId) {
        this.id = id;
        this.orderId = orderId;
        this.saleId = saleId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getSaleId() {
        return saleId;
    }
}
```

`src/main/java/com/company/pos/dining/infrastructure/DiningOrderSaleRepository.java`:

```java
package com.company.pos.dining.infrastructure;

import com.company.pos.dining.domain.DiningOrderSale;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiningOrderSaleRepository extends JpaRepository<DiningOrderSale, UUID> {

    List<DiningOrderSale> findByOrderId(UUID orderId);
}
```

- [ ] **Step 5: Add the facade methods to `DiningService`**

In `src/main/java/com/company/pos/dining/api/DiningService.java`, add under the close/void section:

```java
    List<SaleView> closeOrderSplit(UUID orderId, SplitCloseCommand command, String cashierUsername,
            boolean callerIsManager);

    List<UUID> listOrderSaleIds(UUID orderId);
```

(`List` and `UUID` are already imported; `SaleView` is already imported.)

- [ ] **Step 6: Add `payment :: api` to the dining module**

Replace `src/main/java/com/company/pos/dining/package-info.java` with:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
            "common", "database",
            "product :: api", "cart :: api", "sales :: api", "configuration :: api",
            "menu :: api", "payment :: api" })
package com.company.pos.dining;
```

- [ ] **Step 7: Implement the orchestration in `DefaultDiningService`**

In `src/main/java/com/company/pos/dining/application/DefaultDiningService.java`:

1. Add imports:

```java
import com.company.pos.dining.api.BillInput;
import com.company.pos.dining.api.EvenSplitInput;
import com.company.pos.dining.api.SplitCloseCommand;
import com.company.pos.dining.domain.DiningOrderSale;
import com.company.pos.dining.infrastructure.DiningOrderSaleRepository;
import com.company.pos.sales.api.SaleView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
```

2. Add a `DiningOrderSaleRepository orderSales` field, add it as a constructor parameter, and assign it.

3. Add the orchestration methods (place near `closeOrder`):

```java
    @Override
    public List<SaleView> closeOrderSplit(UUID orderId, SplitCloseCommand command,
            String cashierUsername, boolean callerIsManager) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        if (order.getLines().isEmpty()) {
            throw DomainException.validation("Cannot close an empty order");
        }
        if (command == null || command.mode() == null) {
            throw DomainException.validation("Split mode is required");
        }
        List<SaleView> results = switch (command.mode()) {
            case BY_ITEM -> closeByItem(order, command.bills(), cashierUsername, callerIsManager);
            case EVEN -> closeEven(order, command.even(), cashierUsername, callerIsManager);
        };
        order.close(null, Instant.now()); // CLOSED + closedAt; the N sale ids live in dining_order_sale
        for (SaleView sale : results) {
            orderSales.save(new DiningOrderSale(Identifiers.newId(), order.getId(), sale.id()));
        }
        return results;
    }

    private List<SaleView> closeByItem(DiningOrder order, List<BillInput> bills,
            String cashier, boolean isManager) {
        if (bills == null || bills.isEmpty()) {
            throw DomainException.validation("At least one bill is required for a by-item split");
        }
        Map<UUID, OrderLine> byId = new LinkedHashMap<>();
        for (OrderLine line : order.getLines()) {
            byId.put(line.getId(), line);
        }
        Set<UUID> assigned = new HashSet<>();
        for (BillInput bill : bills) {
            if (bill.lineIds() == null || bill.lineIds().isEmpty()) {
                throw DomainException.validation("Each bill must contain at least one line");
            }
            for (UUID lineId : bill.lineIds()) {
                if (!byId.containsKey(lineId)) {
                    throw DomainException.validation("Line " + lineId + " is not on order " + order.getId());
                }
                if (!assigned.add(lineId)) {
                    throw DomainException.validation("Line " + lineId + " assigned to more than one bill");
                }
            }
        }
        if (assigned.size() != byId.size()) {
            throw DomainException.validation("Every order line must be assigned to exactly one bill");
        }

        List<SaleView> results = new ArrayList<>();
        for (BillInput bill : bills) {
            UUID cartId = carts.createCart();
            for (UUID lineId : bill.lineIds()) {
                OrderLine line = byId.get(lineId);
                List<com.company.pos.cart.api.CartLineModifierInput> mods = line.getModifiers().stream()
                        .map(m -> new com.company.pos.cart.api.CartLineModifierInput(
                                m.getOptionId(), m.getName(), m.getPriceDelta()))
                        .toList();
                carts.addLinePreResolved(cartId, line.getSku(), line.getQty(), mods);
            }
            SaleView sale = sales.checkout(
                    new CheckoutCommand(cartId, bill.tenders(), bill.lineDiscounts(),
                            bill.transactionDiscount()),
                    cashier, isManager);
            carts.close(cartId);
            results.add(sale);
        }
        return results;
    }

    private List<SaleView> closeEven(DiningOrder order, EvenSplitInput even,
            String cashier, boolean isManager) {
        throw DomainException.validation("Even split is not yet supported");
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> listOrderSaleIds(UUID orderId) {
        DiningOrder order = load(orderId);
        List<UUID> ids = new ArrayList<>();
        if (order.getSaleId() != null) {
            ids.add(order.getSaleId());
        }
        for (DiningOrderSale link : orderSales.findByOrderId(orderId)) {
            ids.add(link.getSaleId());
        }
        return ids;
    }
```

- [ ] **Step 8: Add the HTTP endpoints**

In `src/main/java/com/company/pos/dining/web/DiningController.java`:

1. Add imports:

```java
import com.company.pos.dining.api.SplitCloseCommand;
import com.company.pos.sales.api.SalesService;
```

2. Add a `SalesService sales` field, add it to the constructor, and assign it (the controller resolves linked sale ids to `SaleView`s for the GET endpoint).

3. Add the endpoints (near the existing close endpoint):

```java
    @PostMapping("/dining/orders/{orderId}/close-split")
    @ResponseStatus(HttpStatus.CREATED)
    List<SaleView> closeSplit(@PathVariable UUID orderId, @RequestBody SplitCloseCommand body,
            Authentication authentication) {
        boolean isManager = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_MANAGER".equals(a.getAuthority()));
        return dining.closeOrderSplit(orderId, body, authentication.getName(), isManager);
    }

    @GetMapping("/dining/orders/{orderId}/sales")
    List<SaleView> orderSales(@PathVariable UUID orderId) {
        return dining.listOrderSaleIds(orderId).stream().map(sales::getSale).toList();
    }
```

(`List`, `SaleView`, `Authentication`, and the mapping annotations are already imported.)

- [ ] **Step 9: Create the V33 migration**

`src/main/resources/db/migration/dining/V33__create_dining_order_sale.sql`:

```sql
CREATE TABLE dining_order_sale (
    id       VARCHAR(36) PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL,
    sale_id  VARCHAR(36) NOT NULL
);
CREATE INDEX idx_dining_order_sale_order ON dining_order_sale (order_id);
```

- [ ] **Step 10: Run the test to verify it passes**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=DiningSplitByItemTest
```
Expected: PASS (6 tests).

- [ ] **Step 11: Run the dining suite + ModularityTests**

```bash
./mvnw test -Dtest='com.company.pos.dining.*,ModularityTests'
```
Expected: PASS — existing dining tests unchanged; `ModularityTests` green with the new `payment :: api` dependency (acyclic).

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/company/pos/dining src/main/resources/db/migration/dining/V33__create_dining_order_sale.sql src/test/java/com/company/pos/dining/DiningSplitByItemTest.java
git commit -m "$(cat <<'EOF'
feat(dining): by-item split close — one order to N itemized sales

closeOrderSplit(BY_ITEM) validates a whole-line partition of the order and
checks out one cart per bill (each its own Sale/receipt/SaleCompleted), all
in one transaction (any failure rolls the whole split back, order stays
OPEN). Records the N sale ids in a new dining_order_sale link table (V33);
listOrderSaleIds unions it with the single-close scalar saleId. dining gains
payment::api for EvenSplitInput. Even branch stubbed for the next task.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Even split (`dining`)

Implements the `closeEven` seam: build one cart from all order lines, `quote` it, divide the grand total into N equal shares (last absorbs the remainder), and settle one sale with N payments.

**Files:**
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java`
- Test: `src/test/java/com/company/pos/dining/DiningEvenSplitTest.java`

**Interfaces:**
- Consumes: `sales.quote(cartId) : QuoteView` (Task 1), `sales.checkout`, `carts.*`, `PaymentMethod`, `TenderInput`.
- Produces: the working EVEN branch of `closeOrderSplit`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/dining/DiningEvenSplitTest.java`:

```java
package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.EvenSplitInput;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OrderStatus;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.SplitCloseCommand;
import com.company.pos.dining.api.SplitMode;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalePaymentView;
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
class DiningEvenSplitTest {

    @Autowired DiningService dining;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("WATER", "Water", "BEV", "Beverages", "bcWATER",
                "EA", new BigDecimal("5.00"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private UUID openWithBurgerAndWater() {
        UUID tableId = dining.registerTable(new RegisterTableCommand("L" + UUID.randomUUID(), 4)).id();
        UUID orderId = dining.openOrder(new OpenOrderCommand(tableId, null), "alice").id();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("1"), null, null), "alice");
        dining.addLine(orderId, new AddLineCommand("WATER", new BigDecimal("1"), null, null), "alice");
        return orderId; // subtotal 35.00, VAT 15% -> grand 40.25
    }

    @Test
    void evenlySplitsIntoThreePaymentsWithLastAbsorbingTheRemainder() {
        UUID orderId = openWithBurgerAndWater();

        SplitCloseCommand cmd = new SplitCloseCommand(SplitMode.EVEN, null,
                new EvenSplitInput(3, List.of(PaymentMethod.CASH, PaymentMethod.CASH, PaymentMethod.CASH)));

        List<SaleView> sales = dining.closeOrderSplit(orderId, cmd, "alice", false);

        assertThat(sales).hasSize(1);
        SaleView sale = sales.get(0);
        assertThat(sale.grandTotal()).isEqualByComparingTo("40.25");
        List<SalePaymentView> pays = sale.payments();
        assertThat(pays).hasSize(3);
        BigDecimal sum = pays.stream().map(SalePaymentView::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("40.25");
        // 40.25 / 3 -> 13.42, 13.42, 13.41 (last absorbs the remainder)
        assertThat(pays.stream().map(p -> p.amount().setScale(2)).toList())
                .containsExactlyInAnyOrder(new BigDecimal("13.42"), new BigDecimal("13.42"),
                        new BigDecimal("13.41"));
        assertThat(dining.getOrder(orderId).status()).isEqualTo(OrderStatus.CLOSED);
        assertThat(dining.listOrderSaleIds(orderId)).hasSize(1);
    }

    @Test
    void rejectsFewerThanTwoWays() {
        UUID orderId = openWithBurgerAndWater();
        SplitCloseCommand cmd = new SplitCloseCommand(SplitMode.EVEN, null,
                new EvenSplitInput(1, List.of(PaymentMethod.CASH)));
        assertThatThrownBy(() -> dining.closeOrderSplit(orderId, cmd, "alice", false))
                .isInstanceOf(DomainException.class);
        assertThat(dining.getOrder(orderId).status()).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void rejectsMethodCountNotMatchingWays() {
        UUID orderId = openWithBurgerAndWater();
        SplitCloseCommand cmd = new SplitCloseCommand(SplitMode.EVEN, null,
                new EvenSplitInput(3, List.of(PaymentMethod.CASH, PaymentMethod.CASH)));
        assertThatThrownBy(() -> dining.closeOrderSplit(orderId, cmd, "alice", false))
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=DiningEvenSplitTest
```
Expected: FAIL — `evenlySplits...` fails because `closeEven` throws "not yet supported" (the validation-rejection tests may already pass since the stub throws, but the happy-path test fails).

- [ ] **Step 3: Implement `closeEven`**

In `src/main/java/com/company/pos/dining/application/DefaultDiningService.java`:

1. Add imports:

```java
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.sales.api.QuoteView;
import com.company.pos.sales.api.TenderInput;
import java.math.BigDecimal;
import java.math.RoundingMode;
```

(If `java.math.BigDecimal` is already imported, keep the single import.)

2. Replace the `closeEven` stub body with:

```java
    private List<SaleView> closeEven(DiningOrder order, EvenSplitInput even,
            String cashier, boolean isManager) {
        if (even == null) {
            throw DomainException.validation("Even split details are required");
        }
        if (even.ways() < 2) {
            throw DomainException.validation("Even split requires at least 2 ways");
        }
        if (even.methods() == null || even.methods().size() != even.ways()) {
            throw DomainException.validation("Even split requires one payment method per share");
        }

        UUID cartId = carts.createCart();
        for (OrderLine line : order.getLines()) {
            List<com.company.pos.cart.api.CartLineModifierInput> mods = line.getModifiers().stream()
                    .map(m -> new com.company.pos.cart.api.CartLineModifierInput(
                            m.getOptionId(), m.getName(), m.getPriceDelta()))
                    .toList();
            carts.addLinePreResolved(cartId, line.getSku(), line.getQty(), mods);
        }

        QuoteView quote = sales.quote(cartId);
        BigDecimal grandTotal = quote.grandTotal();
        if (grandTotal.signum() <= 0) {
            throw DomainException.validation("Cannot evenly split a non-positive total");
        }
        int ways = even.ways();
        BigDecimal base = grandTotal.divide(new BigDecimal(ways), 2, RoundingMode.HALF_UP);
        List<TenderInput> tenders = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (int i = 0; i < ways; i++) {
            BigDecimal share = (i == ways - 1) ? grandTotal.subtract(allocated) : base;
            allocated = allocated.add(share);
            PaymentMethod method = even.methods().get(i);
            tenders.add(new TenderInput(method, share, share));
        }

        SaleView sale = sales.checkout(new CheckoutCommand(cartId, tenders, Map.of(), null),
                cashier, isManager);
        carts.close(cartId);
        return List.of(sale);
    }
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=DiningEvenSplitTest
```
Expected: PASS (3 tests).

- [ ] **Step 5: Run the dining suite + ModularityTests**

```bash
./mvnw test -Dtest='com.company.pos.dining.*,ModularityTests'
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/dining/application/DefaultDiningService.java src/test/java/com/company/pos/dining/DiningEvenSplitTest.java
git commit -m "$(cat <<'EOF'
feat(dining): even N-way split close — one sale, N equal payments

closeEven builds one cart from all order lines, prices it via sales.quote,
divides the grand total into N equal shares (last share absorbs the rounding
remainder), and settles one sale with N payments. Validates ways >= 2 and one
method per share.

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
Expected: green — all module tests + `ModularityTests`. Testcontainers PostgreSQL validates V33 applies cleanly (needs a running Docker daemon).

---

## Self-Review

**1. Spec coverage:**
- By-item → N itemized sales, whole-line partition → Task 2. ✅
- Even split → one sale, N equal payments, last absorbs remainder → Task 3. ✅
- Atomic settlement / full rollback on failure → Task 2 (one `@Transactional`; rollback test). ✅
- `sales.quote` → Task 1. ✅
- `dining_order_sale` link table + `listOrderSaleIds` → Task 2 (V33). ✅
- `payment :: api` dependency for `PaymentMethod` → Task 2 (package-info). ✅
- Endpoints `close-split` + `GET .../sales`; existing `close` untouched → Task 2. ✅
- Deferrals (service charge, pay-as-you-go, qty-splitting, per-payer slips, even-with-discounts) → not built. ✅

**2. Placeholder scan:** No TBD/TODO; every step shows complete code or an exact command with expected output.

**3. Type consistency:** `closeOrderSplit(UUID, SplitCloseCommand, String, boolean) : List<SaleView>`, `listOrderSaleIds : List<UUID>`, `SplitCloseCommand(mode, bills, even)`, `BillInput(lineIds, tenders, lineDiscounts, transactionDiscount)`, `EvenSplitInput(ways, methods)`, `quote(UUID) : QuoteView(currencyCode, subtotal, discountTotal, taxTotal, grandTotal)` are named identically where produced (Task 1/2) and consumed (Task 2/3). The `closeEven` private signature is defined (stub) in Task 2 and implemented in Task 3 unchanged. `SaleView.payments()` / `SalePaymentView.amount()` are used as they exist today.

**One cross-task note for the executor:** Task 2 defines the full `SplitCloseCommand` (including `EvenSplitInput`) and stubs `closeEven` to throw; Task 3 fills it in. This keeps the command record stable across both tasks. The by-item rollback test in Task 2 and all Task 3 tests are non-`@Transactional` committing tests using `DatabaseCleaner` (the established pattern for tests that drive a real `checkout`).
