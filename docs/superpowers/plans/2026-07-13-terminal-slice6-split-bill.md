# Terminal Slice 6 — Split Bill by Guest Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dine-in split billing in the terminal — assign items to guests (BY_ITEM) or divide N ways (EVEN) — with every guest amount coming from a new server-authoritative `POST /dining/orders/{id}/quote-split` endpoint, closed atomically via the existing `close-split`.

**Architecture:** Backend first: extract the partition/cart/share helpers out of `closeByItem`/`closeEven` in `DefaultDiningService`, add two pure-calculator quote methods that reuse them (no drift by construction), expose them on one new endpoint. Then terminal: DTOs + `DiningApi` methods, a synchronous `SplitViewModel` (plain fields = control-flow truth), and a new full-scene split screen reached via `Navigator.toSplit(orderId)`.

**Tech Stack:** Backend: Java 21, Spring Boot 3.3 / Spring Modulith, JUnit 5 + AssertJ, `@SpringBootTest @ActiveProfiles("embedded")`. Terminal: standalone Maven module `pos-terminal/`, JavaFX 21 FXML + MVVM, `java.net.http` + Jackson, headless JUnit tests (StubServer, sync VMs, resource-contract tests — no TestFX).

**Spec:** `docs/superpowers/specs/2026-07-13-terminal-slice6-split-bill-design.md`

## Global Constraints

- JDK 21 required; system default is 17 — every shell must first run `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`.
- The repo path contains `&` and spaces — always quote it: `cd "/Users/zuhairahamed/Desktop/Research & Development/POS"`.
- Backend build: `./mvnw test -Dtest=<Class>`; the terminal is NOT in the root reactor — build it with `./mvnw -f pos-terminal/pom.xml test`.
- Money is `BigDecimal`, never `double`. EVEN share math: base = `grandTotal / ways` HALF_UP scale 2, **last share absorbs the remainder** (40.25 / 3 → 13.42, 13.42, 13.41).
- The terminal imports NO server code; terminal DTOs mirror server JSON field-for-field. Enum-valued fields cross the wire as `String` (e.g. `mode` is `"BY_ITEM"`/`"EVEN"`, methods are `"CASH"`/`"CARD"`/`"WALLET"`).
- Terminal MVVM rule (recurring Critical bug class): plain (volatile/final) fields are the synchronous control-flow source of truth; JavaFX observables are mirror views updated only inside `ui.accept(...)`. Control flow must NEVER read an observable it just wrote through a deferred dispatcher. Every VM gets an async-dispatcher regression test.
- Never tender an estimate: every amount shown at tender time comes from the quote-split response; the terminal computes NO bill/share amounts client-side.
- No discounts anywhere in the split flow: requests always carry `lineDiscounts = Map.of()`, `transactionDiscount = null`, `waiveServiceCharge = false`.
- Quote paths are plain `@Transactional`, NOT `readOnly` (ephemeral carts are writes; readOnly throws on PostgreSQL).
- New CSS uses existing emerald tokens / `derive()` only; touch targets ≥ 48px (guest tabs and line rows ≥ 56px).
- After any backend cross-module-shaped change, run `./mvnw test -Dtest=ModularityTests` (no new `allowedDependencies` are needed — `dining` already depends on `sales :: api` and `cart :: api`).
- Commit messages end with: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`

## File Structure

Backend (main reactor):
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java` — extract `validatePartition` / `cartForLines` / `evenShares` helpers; add `quoteSplitByItem` / `quoteSplitEven`.
- Create: `src/main/java/com/company/pos/dining/api/SplitQuoteView.java`
- Modify: `src/main/java/com/company/pos/dining/api/DiningService.java` — two new quote-split methods.
- Modify: `src/main/java/com/company/pos/dining/web/DiningController.java` — `POST /dining/orders/{orderId}/quote-split` + request records.
- Create: `src/test/java/com/company/pos/dining/DiningQuoteSplitTest.java` (service), `src/test/java/com/company/pos/dining/DiningQuoteSplitControllerTest.java` (web).
- Modify: `docs/run-modes.md` — quote-split paragraph.

Terminal (`pos-terminal/`):
- Create DTOs in `src/main/java/com/company/pos/terminal/api/dto/`: `QuoteSplitRequest.java`, `QuoteBillInput.java`, `QuoteEvenInput.java`, `SplitQuoteView.java`, `SplitCloseRequest.java`, `BillRequest.java`, `EvenSplitRequest.java`.
- Modify: `src/main/java/com/company/pos/terminal/api/DiningApi.java` — `quoteSplit` + `closeSplit`.
- Create: `src/main/java/com/company/pos/terminal/viewmodel/SplitViewModel.java`
- Create: `src/main/java/com/company/pos/terminal/view/SplitController.java`, `src/main/resources/fxml/split.fxml`
- Modify: `src/main/java/com/company/pos/terminal/app/Navigator.java` (`toSplit`), `src/main/java/com/company/pos/terminal/view/OrderController.java` + `src/main/resources/fxml/order.fxml` (Split bill button), `src/main/resources/css/app.css` (split classes).
- Tests: modify `api/DiningApiTest.java`, `FxmlContractTest.java`, `AppCssTest.java`; create `viewmodel/SplitViewModelTest.java`.
- Modify: `pos-terminal/README.md` — manual GUI E2E additions.

---

### Task 1: Extract shared split helpers in DefaultDiningService (pure refactor)

**Files:**
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java`

**Interfaces:**
- Consumes: existing `closeByItem` / `closeEven` internals (lines ~360–449).
- Produces (used by Task 2): private `Map<UUID, OrderLine> validatePartition(DiningOrder order, List<List<UUID>> billLineIds)`; private `UUID cartForLines(DiningOrder order, List<UUID> lineIds)`; package-private `static List<BigDecimal> evenShares(BigDecimal grandTotal, int ways)`.

This task changes NO behaviour. The existing split tests are the safety net; there is no new test.

- [ ] **Step 1: Refactor `closeByItem` and `closeEven` onto extracted helpers**

In `DefaultDiningService.java`, replace the bodies of `closeByItem` and `closeEven`, and add the three helpers, exactly as follows (keep `priceCartFor`, `closeOrderSplit`, and everything else untouched):

```java
    private List<SaleView> closeByItem(DiningOrder order, List<BillInput> bills,
            String cashier, boolean isManager, boolean applyServiceCharge) {
        if (bills == null) {
            throw DomainException.validation("At least one bill is required for a by-item split");
        }
        validatePartition(order, bills.stream().map(BillInput::lineIds).toList());

        List<SaleView> results = new ArrayList<>();
        for (BillInput bill : bills) {
            UUID cartId = cartForLines(order, bill.lineIds());
            SaleView sale = sales.checkout(
                    new CheckoutCommand(cartId, bill.tenders(), bill.lineDiscounts(),
                            bill.transactionDiscount(), applyServiceCharge),
                    cashier, isManager);
            carts.close(cartId);
            results.add(sale);
        }
        return results;
    }

    /** Validates a by-item partition: ≥1 bill, ≥1 line per bill, every order line in exactly
     *  one bill, no unknown ids. Shared by close and quote so mistakes fail the same way at
     *  quote time as at close time. Returns the order's lines keyed by id. */
    private Map<UUID, OrderLine> validatePartition(DiningOrder order, List<List<UUID>> billLineIds) {
        if (billLineIds == null || billLineIds.isEmpty()) {
            throw DomainException.validation("At least one bill is required for a by-item split");
        }
        Map<UUID, OrderLine> byId = new LinkedHashMap<>();
        for (OrderLine line : order.getLines()) {
            byId.put(line.getId(), line);
        }
        Set<UUID> assigned = new HashSet<>();
        for (List<UUID> lineIds : billLineIds) {
            if (lineIds == null || lineIds.isEmpty()) {
                throw DomainException.validation("Each bill must contain at least one line");
            }
            for (UUID lineId : lineIds) {
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
        return byId;
    }

    /** Builds an ephemeral priced cart from a SUBSET of an order's lines (one split bill),
     *  snapshotting modifier deltas at add-time exactly like {@link #priceCartFor}. */
    private UUID cartForLines(DiningOrder order, List<UUID> lineIds) {
        UUID cartId = carts.createCart();
        for (UUID lineId : lineIds) {
            OrderLine line = requireLine(order, lineId);
            List<com.company.pos.cart.api.CartLineModifierInput> mods = line.getModifiers().stream()
                    .map(m -> new com.company.pos.cart.api.CartLineModifierInput(
                            m.getOptionId(), m.getName(), m.getPriceDelta()))
                    .toList();
            carts.addLinePreResolved(cartId, line.getSku(), line.getQty(), mods);
        }
        return cartId;
    }

    private List<SaleView> closeEven(DiningOrder order, EvenSplitInput even,
            String cashier, boolean isManager, boolean applyServiceCharge) {
        if (even == null) {
            throw DomainException.validation("Even split details are required");
        }
        if (even.ways() < 2) {
            throw DomainException.validation("Even split requires at least 2 ways");
        }
        if (even.methods() == null || even.methods().size() != even.ways()) {
            throw DomainException.validation("Even split requires one payment method per share");
        }

        UUID cartId = priceCartFor(order);
        QuoteView quote = sales.quote(cartId, applyServiceCharge);
        BigDecimal grandTotal = quote.grandTotal();
        if (grandTotal.signum() <= 0) {
            throw DomainException.validation("Cannot evenly split a non-positive total");
        }
        List<BigDecimal> shares = evenShares(grandTotal, even.ways());
        List<TenderInput> tenders = new ArrayList<>();
        for (int i = 0; i < even.ways(); i++) {
            BigDecimal share = shares.get(i);
            tenders.add(new TenderInput(even.methods().get(i), share, share));
        }

        SaleView sale = sales.checkout(new CheckoutCommand(cartId, tenders, Map.of(), null, applyServiceCharge),
                cashier, isManager);
        carts.close(cartId);
        return List.of(sale);
    }

    /** Splits {@code grandTotal} into {@code ways} shares: base = HALF_UP scale-2 division,
     *  the LAST share absorbs the rounding remainder so shares sum exactly to the total
     *  (40.25 / 3 → 13.42, 13.42, 13.41). Shared by closeEven and quoteSplitEven. */
    static List<BigDecimal> evenShares(BigDecimal grandTotal, int ways) {
        BigDecimal base = grandTotal.divide(new BigDecimal(ways), 2, RoundingMode.HALF_UP);
        List<BigDecimal> shares = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (int i = 0; i < ways; i++) {
            BigDecimal share = (i == ways - 1) ? grandTotal.subtract(allocated) : base;
            allocated = allocated.add(share);
            shares.add(share);
        }
        return shares;
    }
```

Note: `priceCartFor`'s per-line body and `cartForLines` are intentionally near-identical — `priceCartFor` iterates ALL lines, `cartForLines` a subset. Do not merge them; `priceCartFor` is shared with `closeOrder`/`quoteOrder` and its javadoc documents that contract. `closeEven`'s cart build switches to `priceCartFor(order)` (it prices all lines — identical behaviour, one less duplicate block).

- [ ] **Step 2: Run the existing split + close + quote tests to prove no behaviour change**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd "/Users/zuhairahamed/Desktop/Research & Development/POS"
./mvnw test -Dtest='DiningSplitByItemTest,DiningEvenSplitTest,DiningCloseServiceTest,DiningQuoteServiceTest,DiningServiceChargeTest' 2>&1 | grep -E "Tests run: [0-9]+, Fail|BUILD"
```

Expected: all green, `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/company/pos/dining/application/DefaultDiningService.java
git commit -m "refactor(dining): extract validatePartition/cartForLines/evenShares from close-split

Pure refactor ahead of the quote-split endpoint: the quote paths will reuse
these helpers so a quote can never drift from what close charges.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: quote-split service methods + SplitQuoteView + DiningQuoteSplitTest

**Files:**
- Create: `src/main/java/com/company/pos/dining/api/SplitQuoteView.java`
- Modify: `src/main/java/com/company/pos/dining/api/DiningService.java`
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java`
- Test: `src/test/java/com/company/pos/dining/DiningQuoteSplitTest.java`

**Interfaces:**
- Consumes (Task 1): `validatePartition(order, billLineIds)`, `cartForLines(order, lineIds)`, `evenShares(grandTotal, ways)`, plus existing `priceCartFor(order)`, `resolveApplyServiceCharge(order, false, false)`, `sales.quote(UUID, boolean)`.
- Produces (Tasks 3+): `dining.api` record `SplitQuoteView(List<QuoteView> bills, QuoteView order, List<BigDecimal> shares)`; `DiningService` methods `SplitQuoteView quoteSplitByItem(UUID orderId, List<List<UUID>> billLineIds)` and `SplitQuoteView quoteSplitEven(UUID orderId, int ways)`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/company/pos/dining/DiningQuoteSplitTest.java`:

```java
package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.BillInput;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.EvenSplitInput;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OrderStatus;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.SplitCloseCommand;
import com.company.pos.dining.api.SplitMode;
import com.company.pos.dining.api.SplitQuoteView;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.SalePaymentView;
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
class DiningQuoteSplitTest {

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

    private UUID addLine(UUID orderId, String sku) {
        return dining.addLine(orderId,
                new AddLineCommand(sku, BigDecimal.ONE, null, null), "alice")
                .lines().stream().filter(l -> l.sku().equals(sku)).findFirst().orElseThrow().id();
    }

    private static TenderInput cash(BigDecimal amount) {
        return new TenderInput(PaymentMethod.CASH, amount, amount);
    }

    // --- BY_ITEM ---

    @Test
    void quoteSplitByItemPricesEachBillAndLeavesTheOrderOpen() {
        UUID orderId = openOrderOnFreshTable();
        UUID burger = addLine(orderId, "BURGER");   // 30.00 -> grand 34.50 (15% VAT, no SC)
        UUID fries = addLine(orderId, "FRIES");     // 12.00
        UUID water = addLine(orderId, "WATER");     // 5.00  -> fries+water grand 19.55

        SplitQuoteView q = dining.quoteSplitByItem(orderId,
                List.of(List.of(burger), List.of(fries, water)));

        assertThat(q.bills()).hasSize(2);
        assertThat(q.bills().get(0).grandTotal()).isEqualByComparingTo("34.50");
        assertThat(q.bills().get(1).grandTotal()).isEqualByComparingTo("19.55");
        assertThat(q.order()).isNull();
        assertThat(q.shares()).isNull();
        assertThat(dining.getOrder(orderId).status()).isEqualTo(OrderStatus.OPEN);
        assertThat(dining.listOrderSaleIds(orderId)).isEmpty();
    }

    @Test
    void quoteSplitByItemMatchesCloseSplitWithServiceChargeOn() {
        config.put(SettingKey.SERVICE_CHARGE_ENABLED, "true");
        config.put(SettingKey.SERVICE_CHARGE_PERCENT, "10");
        UUID orderId = openOrderOnFreshTable();
        UUID burger = addLine(orderId, "BURGER");
        UUID fries = addLine(orderId, "FRIES");
        UUID water = addLine(orderId, "WATER");

        SplitQuoteView q = dining.quoteSplitByItem(orderId,
                List.of(List.of(burger), List.of(fries, water)));

        // Close with EXACTLY the quoted amounts — service charge applied per bill on both paths.
        List<SaleView> sales = dining.closeOrderSplit(orderId,
                new SplitCloseCommand(SplitMode.BY_ITEM, List.of(
                        new BillInput(List.of(burger),
                                List.of(cash(q.bills().get(0).grandTotal())), Map.of(), null),
                        new BillInput(List.of(fries, water),
                                List.of(cash(q.bills().get(1).grandTotal())), Map.of(), null)),
                        null),
                "alice", false);

        assertThat(sales.get(0).grandTotal()).isEqualByComparingTo(q.bills().get(0).grandTotal());
        assertThat(sales.get(1).grandTotal()).isEqualByComparingTo(q.bills().get(1).grandTotal());
        assertThat(sales.get(0).serviceChargeAmount()).isEqualByComparingTo(q.bills().get(0).serviceChargeAmount());
        assertThat(sales.get(1).serviceChargeAmount()).isEqualByComparingTo(q.bills().get(1).serviceChargeAmount());
    }

    @Test
    void quoteSplitByItemValidationParityWithClose() {
        UUID orderId = openOrderOnFreshTable();
        UUID burger = addLine(orderId, "BURGER");
        addLine(orderId, "FRIES"); // present but unassigned in the first case

        // incomplete partition
        assertThatThrownBy(() -> dining.quoteSplitByItem(orderId, List.of(List.of(burger))))
                .isInstanceOf(DomainException.class);
        // duplicate assignment
        assertThatThrownBy(() -> dining.quoteSplitByItem(orderId,
                List.of(List.of(burger), List.of(burger))))
                .isInstanceOf(DomainException.class);
        // empty bill
        assertThatThrownBy(() -> dining.quoteSplitByItem(orderId,
                List.of(List.of(burger), List.of())))
                .isInstanceOf(DomainException.class);
        // unknown line id
        assertThatThrownBy(() -> dining.quoteSplitByItem(orderId,
                List.of(List.of(UUID.randomUUID()))))
                .isInstanceOf(DomainException.class);
        assertThat(dining.getOrder(orderId).status()).isEqualTo(OrderStatus.OPEN);
    }

    // --- EVEN ---

    @Test
    void quoteSplitEvenReturnsSharesWithLastAbsorbingRemainder() {
        UUID orderId = openOrderOnFreshTable();
        addLine(orderId, "BURGER");
        addLine(orderId, "WATER");  // subtotal 35.00, 15% VAT -> grand 40.25

        SplitQuoteView q = dining.quoteSplitEven(orderId, 3);

        assertThat(q.order().grandTotal()).isEqualByComparingTo("40.25");
        assertThat(q.shares()).containsExactly(new BigDecimal("13.42"),
                new BigDecimal("13.42"), new BigDecimal("13.41"));
        assertThat(q.bills()).isNull();
        assertThat(dining.getOrder(orderId).status()).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void quoteSplitEvenSharesEqualCloseEvenPayments() {
        UUID orderId = openOrderOnFreshTable();
        addLine(orderId, "BURGER");
        addLine(orderId, "WATER");

        SplitQuoteView q = dining.quoteSplitEven(orderId, 3);

        List<SaleView> sales = dining.closeOrderSplit(orderId,
                new SplitCloseCommand(SplitMode.EVEN, null,
                        new EvenSplitInput(3, List.of(PaymentMethod.CASH, PaymentMethod.CASH,
                                PaymentMethod.CASH))),
                "alice", false);

        List<BigDecimal> paid = sales.get(0).payments().stream()
                .map(SalePaymentView::amount).map(a -> a.setScale(2)).toList();
        assertThat(paid).containsExactlyInAnyOrderElementsOf(
                q.shares().stream().map(s -> s.setScale(2)).toList());
    }

    @Test
    void quoteSplitEvenRejectsFewerThanTwoWays() {
        UUID orderId = openOrderOnFreshTable();
        addLine(orderId, "BURGER");
        assertThatThrownBy(() -> dining.quoteSplitEven(orderId, 1))
                .isInstanceOf(DomainException.class);
        assertThat(dining.getOrder(orderId).status()).isEqualTo(OrderStatus.OPEN);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd "/Users/zuhairahamed/Desktop/Research & Development/POS"
./mvnw test -Dtest=DiningQuoteSplitTest 2>&1 | grep -E "Tests run: [0-9]+, Fail|ERROR|BUILD" | head -5
```

Expected: COMPILATION ERROR — `SplitQuoteView` and `quoteSplitByItem` do not exist.

- [ ] **Step 3: Implement**

Create `src/main/java/com/company/pos/dining/api/SplitQuoteView.java`:

```java
package com.company.pos.dining.api;

import com.company.pos.sales.api.QuoteView;
import java.math.BigDecimal;
import java.util.List;

/**
 * Authoritative pricing for a proposed split, priced exactly as {@code closeOrderSplit} would
 * charge it. BY_ITEM populates {@code bills} (one quote per bill, in request order; {@code order}
 * and {@code shares} are null). EVEN populates {@code order} (the whole-order quote) and
 * {@code shares} (per-share amounts, the LAST absorbing the rounding remainder; {@code bills}
 * is null).
 */
public record SplitQuoteView(List<QuoteView> bills, QuoteView order, List<BigDecimal> shares) {
}
```

In `src/main/java/com/company/pos/dining/api/DiningService.java`, add after the `quoteOrder` overloads:

```java
    /** Prices a BY_ITEM partition exactly as {@link #closeOrderSplit} would (per-bill ephemeral
     *  carts, service charge per bill). Pure calculator: creates no sale, closes nothing, the
     *  order stays OPEN. Runs the same partition validation as close so mistakes fail at quote
     *  time. Assumes the no-discount, no-waiver close path (the split UI carries neither). */
    SplitQuoteView quoteSplitByItem(UUID orderId, List<List<UUID>> billLineIds);

    /** Prices an EVEN split: the whole-order quote plus the exact per-share amounts
     *  {@link #closeOrderSplit} would tender ({@code ways ≥ 2}; last share absorbs the
     *  rounding remainder). Pure calculator — the order stays OPEN. */
    SplitQuoteView quoteSplitEven(UUID orderId, int ways);
```

Add the import `com.company.pos.dining.api.SplitQuoteView` is not needed (same package); in `DefaultDiningService.java` add `import com.company.pos.dining.api.SplitQuoteView;` and implement, directly after `closeOrderSplit`:

```java
    @Override
    public SplitQuoteView quoteSplitByItem(UUID orderId, List<List<UUID>> billLineIds) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        if (order.getLines().isEmpty()) {
            throw DomainException.validation("Cannot quote an empty order");
        }
        validatePartition(order, billLineIds);
        boolean applyServiceCharge = resolveApplyServiceCharge(order, false, false);
        List<QuoteView> bills = new ArrayList<>();
        for (List<UUID> lineIds : billLineIds) {
            UUID cartId = cartForLines(order, lineIds);
            bills.add(sales.quote(cartId, applyServiceCharge));
            carts.close(cartId);
        }
        return new SplitQuoteView(bills, null, null);
    }

    @Override
    public SplitQuoteView quoteSplitEven(UUID orderId, int ways) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        if (order.getLines().isEmpty()) {
            throw DomainException.validation("Cannot quote an empty order");
        }
        if (ways < 2) {
            throw DomainException.validation("Even split requires at least 2 ways");
        }
        UUID cartId = priceCartFor(order);
        boolean applyServiceCharge = resolveApplyServiceCharge(order, false, false);
        QuoteView quote = sales.quote(cartId, applyServiceCharge);
        carts.close(cartId);
        if (quote.grandTotal().signum() <= 0) {
            throw DomainException.validation("Cannot evenly split a non-positive total");
        }
        return new SplitQuoteView(null, quote, evenShares(quote.grandTotal(), ways));
    }
```

(Both inherit the class-level plain `@Transactional` — correct: ephemeral carts are writes, so NOT readOnly.)

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./mvnw test -Dtest='DiningQuoteSplitTest,DiningSplitByItemTest,DiningEvenSplitTest' 2>&1 | grep -E "Tests run: [0-9]+, Fail|BUILD"
```

Expected: all green, `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/pos/dining/api/SplitQuoteView.java \
        src/main/java/com/company/pos/dining/api/DiningService.java \
        src/main/java/com/company/pos/dining/application/DefaultDiningService.java \
        src/test/java/com/company/pos/dining/DiningQuoteSplitTest.java
git commit -m "feat(dining): quote-split pure calculators sharing close-split's pricing paths

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: `POST /dining/orders/{id}/quote-split` endpoint + web tests

**Files:**
- Modify: `src/main/java/com/company/pos/dining/web/DiningController.java`
- Test: `src/test/java/com/company/pos/dining/DiningQuoteSplitControllerTest.java`

**Interfaces:**
- Consumes (Task 2): `dining.quoteSplitByItem(UUID, List<List<UUID>>)`, `dining.quoteSplitEven(UUID, int)`, `SplitQuoteView`.
- Produces (Task 4 mirrors this JSON): request `{"mode":"BY_ITEM"|"EVEN","bills":[{"lineIds":[...]}],"even":{"ways":N}}`; response `SplitQuoteView` JSON `{"bills":[QuoteView...]|null,"order":QuoteView|null,"shares":[13.42,...]|null}`.

- [ ] **Step 1: Write the failing web tests**

Create `src/test/java/com/company/pos/dining/DiningQuoteSplitControllerTest.java` (pattern: `DiningQuoteControllerTest` — `@Transactional` rollback, `jwt()` post-processor):

```java
package com.company.pos.dining;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.ServiceType;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import java.math.BigDecimal;
import java.util.UUID;
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
class DiningQuoteSplitControllerTest {

    @Autowired MockMvc mvc;
    @Autowired DiningService dining;
    @Autowired FakeErpClient fake;
    @Autowired ProductSync productSync;

    private UUID orderId;
    private UUID burgerLine;
    private UUID waterLine;

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("WATER", "Water", "BEV", "Beverages", "bcWATER",
                "EA", new BigDecimal("5.00"), "SAR", 1, true));
        productSync.sync();
        UUID tableId = dining.registerTable(new RegisterTableCommand("L" + UUID.randomUUID(), 4)).id();
        orderId = dining.openOrder(new OpenOrderCommand(tableId, ServiceType.DINE_IN), "alice").id();
        dining.addLine(orderId, new AddLineCommand("BURGER", BigDecimal.ONE, null, null), "alice");
        dining.addLine(orderId, new AddLineCommand("WATER", BigDecimal.ONE, null, null), "alice");
        burgerLine = dining.getOrder(orderId).lines().stream()
                .filter(l -> l.sku().equals("BURGER")).findFirst().orElseThrow().id();
        waterLine = dining.getOrder(orderId).lines().stream()
                .filter(l -> l.sku().equals("WATER")).findFirst().orElseThrow().id();
    }

    @Test
    void quoteSplitByItemReturnsOneQuotePerBill() throws Exception {
        // No service charge configured: BURGER 30.00*1.15=34.50, WATER 5.00*1.15=5.75
        mvc.perform(post("/dining/orders/" + orderId + "/quote-split")
                        .with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"mode\":\"BY_ITEM\",\"bills\":["
                                + "{\"lineIds\":[\"" + burgerLine + "\"]},"
                                + "{\"lineIds\":[\"" + waterLine + "\"]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bills[0].grandTotal").value(34.50))
                .andExpect(jsonPath("$.bills[1].grandTotal").value(5.75))
                .andExpect(jsonPath("$.order").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.shares").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void quoteSplitEvenReturnsOrderQuoteAndShares() throws Exception {
        // (30+5)*1.15 = 40.25; /2 -> 20.13, last absorbs -> 20.12
        mvc.perform(post("/dining/orders/" + orderId + "/quote-split")
                        .with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"mode\":\"EVEN\",\"even\":{\"ways\":2}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.grandTotal").value(40.25))
                .andExpect(jsonPath("$.shares[0]").value(20.13))
                .andExpect(jsonPath("$.shares[1]").value(20.12))
                .andExpect(jsonPath("$.bills").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void invalidPartitionIsRejectedAsValidation() throws Exception {
        mvc.perform(post("/dining/orders/" + orderId + "/quote-split")
                        .with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"mode\":\"BY_ITEM\",\"bills\":["
                                + "{\"lineIds\":[\"" + burgerLine + "\"]}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anonymousQuoteSplitRejected() throws Exception {
        mvc.perform(post("/dining/orders/" + UUID.randomUUID() + "/quote-split")
                        .contentType("application/json")
                        .content("{\"mode\":\"EVEN\",\"even\":{\"ways\":2}}"))
                .andExpect(status().isUnauthorized());
    }
}
```

**Note on the validation status:** `DomainException.validation` maps to HTTP 400 via `ErrorCode.VALIDATION(HttpStatus.BAD_REQUEST)` + `ApiExceptionHandler` (verified in `src/main/java/com/company/pos/common/exception/ErrorCode.java`) — hence `isBadRequest()`. Do NOT change the handler.

- [ ] **Step 2: Run to verify failure**

```bash
./mvnw test -Dtest=DiningQuoteSplitControllerTest 2>&1 | grep -E "Tests run: [0-9]+, Fail|ERROR|BUILD" | head -5
```

Expected: FAIL — 404s (`No mapping for POST .../quote-split`).

- [ ] **Step 3: Implement the endpoint**

In `DiningController.java`, add the import `com.company.pos.dining.api.SplitMode`, `com.company.pos.dining.api.SplitQuoteView`, and `com.company.pos.common.exception.DomainException`, then add after the `closeSplit` mapping:

```java
    /** Body for the split quote. BY_ITEM populates {@code bills}; EVEN populates {@code even}. */
    record QuoteSplitRequest(SplitMode mode, List<QuoteBillInput> bills, QuoteEvenInput even) {
    }

    record QuoteBillInput(List<UUID> lineIds) {
    }

    record QuoteEvenInput(int ways) {
    }

    @PostMapping("/dining/orders/{orderId}/quote-split")
    SplitQuoteView quoteSplit(@PathVariable UUID orderId, @RequestBody QuoteSplitRequest body) {
        if (body.mode() == null) {
            throw DomainException.validation("Split mode is required");
        }
        return switch (body.mode()) {
            case BY_ITEM -> dining.quoteSplitByItem(orderId,
                    body.bills() == null ? List.of()
                            : body.bills().stream().map(QuoteBillInput::lineIds).toList());
            case EVEN -> {
                if (body.even() == null) {
                    throw DomainException.validation("Even split details are required");
                }
                yield dining.quoteSplitEven(orderId, body.even().ways());
            }
        };
    }
```

Note: the API serializes nulls (see `"closedAt":null` in the terminal `DiningApiTest`'s recorded server JSON), hence the `nullValue()` assertions above rather than `doesNotExist()`. Do not add per-record Jackson `@JsonInclude` config to hide them.

- [ ] **Step 4: Run web tests + ModularityTests**

```bash
./mvnw test -Dtest='DiningQuoteSplitControllerTest,ModularityTests' 2>&1 | grep -E "Tests run: [0-9]+, Fail|BUILD"
```

Expected: green (no new module dependencies were introduced — `SplitQuoteView` lives in `dining.api` and only imports `sales.api.QuoteView`, already allowed).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/pos/dining/web/DiningController.java \
        src/test/java/com/company/pos/dining/DiningQuoteSplitControllerTest.java
git commit -m "feat(dining): POST /dining/orders/{id}/quote-split web endpoint

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Terminal split DTOs + DiningApi.quoteSplit/closeSplit + DiningApiTest

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/QuoteSplitRequest.java`, `QuoteBillInput.java`, `QuoteEvenInput.java`, `SplitQuoteView.java`, `SplitCloseRequest.java`, `BillRequest.java`, `EvenSplitRequest.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/DiningApiTest.java`

**Interfaces:**
- Consumes: existing terminal `ApiClient.post(path, body, TypeReference)`, DTOs `QuoteView`, `SaleView`, `TenderInput`, `DiscountInput`.
- Produces (Tasks 5–6): all seven DTO records below; `DiningApi.quoteSplit(UUID, QuoteSplitRequest) → SplitQuoteView`; `DiningApi.closeSplit(UUID, SplitCloseRequest) → List<SaleView>`.

- [ ] **Step 1: Write the failing tests**

Append to `DiningApiTest.java`:

```java
    @Test
    void quoteSplitByItemPostsModeAndBills() throws Exception {
        String json = "{\"bills\":[{\"currencyCode\":\"SAR\",\"subtotal\":30.00,\"discountTotal\":0,"
                + "\"serviceChargeAmount\":0,\"taxTotal\":4.50,\"grandTotal\":34.50}],"
                + "\"order\":null,\"shares\":null}";
        try (StubServer stub = new StubServer(200, json, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            SplitQuoteView v = api.quoteSplit(ORDER_ID, new QuoteSplitRequest("BY_ITEM",
                    List.of(new QuoteBillInput(List.of(LINE_ID))), null));
            assertEquals(1, v.bills().size());
            assertEquals(0, new BigDecimal("34.50").compareTo(v.bills().get(0).grandTotal()));
            assertNull(v.order());
            assertNull(v.shares());
            assertEquals("POST", stub.lastMethod);
            assertEquals("/dining/orders/33333333-3333-3333-3333-333333333333/quote-split", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"mode\":\"BY_ITEM\""));
            assertTrue(stub.lastBody.contains("\"lineIds\":[\"66666666-6666-6666-6666-666666666666\"]"));
        }
    }

    @Test
    void quoteSplitEvenPostsWaysAndParsesShares() throws Exception {
        String json = "{\"bills\":null,"
                + "\"order\":{\"currencyCode\":\"SAR\",\"subtotal\":35.00,\"discountTotal\":0,"
                + "\"serviceChargeAmount\":0,\"taxTotal\":5.25,\"grandTotal\":40.25},"
                + "\"shares\":[13.42,13.42,13.41]}";
        try (StubServer stub = new StubServer(200, json, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            SplitQuoteView v = api.quoteSplit(ORDER_ID,
                    new QuoteSplitRequest("EVEN", null, new QuoteEvenInput(3)));
            assertEquals(0, new BigDecimal("40.25").compareTo(v.order().grandTotal()));
            assertEquals(3, v.shares().size());
            assertEquals(0, new BigDecimal("13.41").compareTo(v.shares().get(2)));
            assertTrue(stub.lastBody.contains("\"mode\":\"EVEN\""));
            assertTrue(stub.lastBody.contains("\"ways\":3"));
        }
    }

    @Test
    void closeSplitPostsBillsAndParsesSaleList() throws Exception {
        String sales = "[{\"id\":\"55555555-5555-5555-5555-555555555555\",\"receiptNumber\":\"S01-T01-1\","
                + "\"currencyCode\":\"SAR\",\"subtotal\":30.00,\"taxTotal\":4.50,\"grandTotal\":34.50,"
                + "\"discountTotal\":0.00,\"serviceChargeAmount\":0.00,\"lines\":[],\"payments\":[]},"
                + "{\"id\":\"88888888-8888-8888-8888-888888888888\",\"receiptNumber\":\"S01-T01-2\","
                + "\"currencyCode\":\"SAR\",\"subtotal\":17.00,\"taxTotal\":2.55,\"grandTotal\":19.55,"
                + "\"discountTotal\":0.00,\"serviceChargeAmount\":0.00,\"lines\":[],\"payments\":[]}]";
        try (StubServer stub = new StubServer(201, sales, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            SplitCloseRequest req = new SplitCloseRequest("BY_ITEM", List.of(
                    new BillRequest(List.of(LINE_ID),
                            List.of(new TenderInput("CASH", new BigDecimal("34.50"), new BigDecimal("50.00"))),
                            Map.of(), null)),
                    null, false);
            List<SaleView> result = api.closeSplit(ORDER_ID, req);
            assertEquals(2, result.size());
            assertEquals("S01-T01-2", result.get(1).receiptNumber());
            assertEquals("POST", stub.lastMethod);
            assertEquals("/dining/orders/33333333-3333-3333-3333-333333333333/close-split", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"mode\":\"BY_ITEM\""));
            assertTrue(stub.lastBody.contains("\"method\":\"CASH\""));
            assertTrue(stub.lastBody.contains("\"tendered\":50.00"));
            assertTrue(stub.lastBody.contains("\"waiveServiceCharge\":false"));
        }
    }

    @Test
    void closeSplitEvenPostsWaysAndMethods() throws Exception {
        String sales = "[{\"id\":\"55555555-5555-5555-5555-555555555555\",\"receiptNumber\":\"S01-T01-1\","
                + "\"currencyCode\":\"SAR\",\"subtotal\":35.00,\"taxTotal\":5.25,\"grandTotal\":40.25,"
                + "\"discountTotal\":0.00,\"serviceChargeAmount\":0.00,\"lines\":[],\"payments\":[]}]";
        try (StubServer stub = new StubServer(201, sales, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            SplitCloseRequest req = new SplitCloseRequest("EVEN", null,
                    new EvenSplitRequest(3, List.of("CASH", "CARD", "CASH")), false);
            List<SaleView> result = api.closeSplit(ORDER_ID, req);
            assertEquals(1, result.size());
            assertTrue(stub.lastBody.contains("\"mode\":\"EVEN\""));
            assertTrue(stub.lastBody.contains("\"ways\":3"));
            assertTrue(stub.lastBody.contains("\"methods\":[\"CASH\",\"CARD\",\"CASH\"]"));
        }
    }
```

Note: `assertTrue(stub.lastBody.contains("\"tendered\":50.00"))` assumes Jackson writes `BigDecimal("50.00")` as `50.00`. That is Jackson's default for BigDecimal (it calls `writeNumber(BigDecimal)` preserving scale) and other tests in this file already rely on exact-body substrings; if it fails, relax to `"tendered\":50"` prefix matching — check the recorded body first.

- [ ] **Step 2: Run to verify failure**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd "/Users/zuhairahamed/Desktop/Research & Development/POS"
./mvnw -f pos-terminal/pom.xml test -Dtest=DiningApiTest 2>&1 | grep -E "Tests run: [0-9]+, Fail|ERROR|BUILD" | head -5
```

Expected: COMPILATION ERROR — the DTOs and methods do not exist.

- [ ] **Step 3: Create the DTOs and API methods**

Create each DTO in `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/`:

`QuoteSplitRequest.java`:
```java
package com.company.pos.terminal.api.dto;

import java.util.List;

/** POST /dining/orders/{id}/quote-split body. {@code mode} is "BY_ITEM" (populate {@code bills})
 *  or "EVEN" (populate {@code even}), mirroring the server's SplitMode enum names. */
public record QuoteSplitRequest(String mode, List<QuoteBillInput> bills, QuoteEvenInput even) {
}
```

`QuoteBillInput.java`:
```java
package com.company.pos.terminal.api.dto;

import java.util.List;

/** One proposed bill of a by-item split quote: the order-line ids it covers. */
public record QuoteBillInput(List<java.util.UUID> lineIds) {
}
```

`QuoteEvenInput.java`:
```java
package com.company.pos.terminal.api.dto;

/** Even-split quote input: how many ways to divide the order total. */
public record QuoteEvenInput(int ways) {
}
```

`SplitQuoteView.java`:
```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

/**
 * Mirrors the server's dining SplitQuoteView. BY_ITEM: {@code bills} holds one authoritative
 * quote per bill in request order ({@code order}/{@code shares} null). EVEN: {@code order} is
 * the whole-order quote and {@code shares} the per-guest amounts, the LAST absorbing the
 * rounding remainder ({@code bills} null).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SplitQuoteView(List<QuoteView> bills, QuoteView order, List<BigDecimal> shares) {
}
```

`SplitCloseRequest.java`:
```java
package com.company.pos.terminal.api.dto;

import java.util.List;

/**
 * Body for {@code POST /dining/orders/{id}/close-split}. Mirrors the server's SplitCloseCommand
 * (field order: mode, bills, even, waiveServiceCharge). The split UI never sends discounts or a
 * waiver.
 */
public record SplitCloseRequest(String mode, List<BillRequest> bills, EvenSplitRequest even,
        boolean waiveServiceCharge) {
}
```

`BillRequest.java`:
```java
package com.company.pos.terminal.api.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One bill of a by-item split close — mirrors the server's BillInput. The split UI always
 *  sends {@code lineDiscounts = Map.of()} and {@code transactionDiscount = null}. */
public record BillRequest(List<UUID> lineIds, List<TenderInput> tenders,
        Map<String, DiscountInput> lineDiscounts, DiscountInput transactionDiscount) {
}
```

`EvenSplitRequest.java`:
```java
package com.company.pos.terminal.api.dto;

import java.util.List;

/** Even split close — mirrors the server's EvenSplitInput: one payment-method name per share
 *  ({@code methods.size() == ways}); the server fixes each tender to its exact share. */
public record EvenSplitRequest(int ways, List<String> methods) {
}
```

In `DiningApi.java`, add imports (`QuoteSplitRequest`, `SplitQuoteView`, `SplitCloseRequest`) and two methods after `close(...)`:

```java
    /** POST /dining/orders/{id}/quote-split — authoritative per-bill / per-share amounts for a
     *  proposed split. Pure calculator: the order stays OPEN. */
    public SplitQuoteView quoteSplit(UUID orderId, QuoteSplitRequest req) {
        return client.post("/dining/orders/" + orderId + "/quote-split", req,
                new TypeReference<SplitQuoteView>() {});
    }

    /** POST /dining/orders/{id}/close-split — closes ALL bills in one atomic call; any failing
     *  bill rolls back the whole split and the order stays OPEN. */
    public List<SaleView> closeSplit(UUID orderId, SplitCloseRequest req) {
        return client.post("/dining/orders/" + orderId + "/close-split", req,
                new TypeReference<List<SaleView>>() {});
    }
```

- [ ] **Step 4: Run to verify pass**

```bash
./mvnw -f pos-terminal/pom.xml test -Dtest=DiningApiTest 2>&1 | grep -E "Tests run: [0-9]+, Fail|BUILD"
```

Expected: green.

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/dto/QuoteSplitRequest.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/QuoteBillInput.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/QuoteEvenInput.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/SplitQuoteView.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/SplitCloseRequest.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/BillRequest.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/EvenSplitRequest.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/DiningApiTest.java
git commit -m "feat(terminal): split DTOs + DiningApi quoteSplit/closeSplit

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: SplitViewModel + SplitViewModelTest

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/SplitViewModel.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/SplitViewModelTest.java`

**Interfaces:**
- Consumes (Task 4): `DiningApi.order(UUID)`, `quoteSplit(UUID, QuoteSplitRequest)`, `closeSplit(UUID, SplitCloseRequest)`; DTOs `OrderLineView`, `QuoteView`, `SplitQuoteView`, `SaleView`, `TenderInput`, `BillRequest`, `EvenSplitRequest`.
- Produces (Task 6 controller calls exactly these): see the full class below — the controller uses `load`, `lines`, `setMode`/`mode`, `setWays`/`ways`, `addGuest`/`guestCount`, `setActiveGuest`/`activeGuest`, `toggleAssign`, `guestOf`, `unassignedCount`, `canContinue`, `quoteSplit`, `quoted`, `billCount`, `billLabel`, `billAmount`, `setMethod`/`methodOf`, `setCashTendered`/`cashTenderedOf`, `changeFor`, `cashChangeAllowed`, `canCloseAll`, `closeAll`, `results`, `errorMessage`.

All amounts exposed to the UI come ONLY from the stored `SplitQuoteView`. Every method is synchronous; plain fields are the control-flow truth; the only observable is `errorMessage` (mirrored via `ui.accept`).

- [ ] **Step 1: Write the failing tests**

Create `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/SplitViewModelTest.java`:

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.DiningApi;
import com.company.pos.terminal.api.dto.OrderLineView;
import com.company.pos.terminal.api.dto.OrderView;
import com.company.pos.terminal.api.dto.QuoteSplitRequest;
import com.company.pos.terminal.api.dto.QuoteView;
import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.api.dto.SplitCloseRequest;
import com.company.pos.terminal.api.dto.SplitQuoteView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SplitViewModelTest {

    private static final UUID ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID L1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID L2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID L3 = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static OrderLineView line(UUID id, String sku) {
        return new OrderLineView(id, sku, BigDecimal.ONE, null, "MAIN", null, List.of());
    }

    private static OrderView order(OrderLineView... lines) {
        return new OrderView(ORDER_ID, UUID.randomUUID(), "DINE_IN", "OPEN", "alice",
                null, null, null, List.of(lines));
    }

    private static QuoteView quote(String grand) {
        return new QuoteView("SAR", new BigDecimal("30.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("4.50"), new BigDecimal(grand));
    }

    private static SaleView sale(String receipt, String grand) {
        return new SaleView(UUID.randomUUID(), receipt, new BigDecimal("30.00"),
                new BigDecimal("4.50"), BigDecimal.ZERO, new BigDecimal(grand), "SAR",
                BigDecimal.ZERO, List.of(), List.of());
    }

    /** Records requests, returns canned results. DiningApi methods are non-final instance
     *  methods, so a null-client subclass works (pattern: PaymentViewModelTest's SalesApi). */
    private static final class FakeDiningApi extends DiningApi {
        OrderView orderResult;
        SplitQuoteView quoteResult;
        List<SaleView> closeResult;
        RuntimeException closeError;
        QuoteSplitRequest lastQuoteReq;
        SplitCloseRequest lastCloseReq;

        FakeDiningApi() { super(null); }

        @Override public OrderView order(UUID id) { return orderResult; }

        @Override public SplitQuoteView quoteSplit(UUID id, QuoteSplitRequest req) {
            lastQuoteReq = req;
            return quoteResult;
        }

        @Override public List<SaleView> closeSplit(UUID id, SplitCloseRequest req) {
            lastCloseReq = req;
            if (closeError != null) throw closeError;
            return closeResult;
        }
    }

    private static SplitViewModel loadedVm(FakeDiningApi api) {
        api.orderResult = order(line(L1, "BURGER"), line(L2, "FRIES"), line(L3, "WATER"));
        SplitViewModel vm = new SplitViewModel(api);
        vm.load(ORDER_ID);
        return vm;
    }

    @Test
    void toggleAssignAssignsToActiveGuestAndTogglesOff() {
        SplitViewModel vm = loadedVm(new FakeDiningApi());
        assertEquals(3, vm.unassignedCount());
        vm.toggleAssign(L1);                       // active guest 0
        assertEquals(0, vm.guestOf(L1));
        assertEquals(2, vm.unassignedCount());
        vm.setActiveGuest(1);
        vm.toggleAssign(L1);                       // different guest: reassign, not unassign
        assertEquals(1, vm.guestOf(L1));
        vm.toggleAssign(L1);                       // same guest: unassign
        assertNull(vm.guestOf(L1));
        assertEquals(3, vm.unassignedCount());
    }

    @Test
    void canContinueOnlyWhenFullyPartitioned() {
        SplitViewModel vm = loadedVm(new FakeDiningApi());
        assertFalse(vm.canContinue());
        vm.toggleAssign(L1);
        vm.toggleAssign(L2);
        assertFalse(vm.canContinue());
        vm.toggleAssign(L3);
        assertTrue(vm.canContinue());
    }

    @Test
    void quoteRequestDropsEmptyGuestsAndKeepsGuestOrder() {
        FakeDiningApi api = new FakeDiningApi();
        SplitViewModel vm = loadedVm(api);
        vm.addGuest();                              // 3 guests: 0,1,2
        vm.setActiveGuest(2);
        vm.toggleAssign(L1);
        vm.toggleAssign(L2);
        vm.setActiveGuest(0);
        vm.toggleAssign(L3);                        // guest 1 stays empty
        api.quoteResult = new SplitQuoteView(List.of(quote("34.50"), quote("19.55")), null, null);

        assertTrue(vm.quoteSplit(ORDER_ID));

        assertEquals("BY_ITEM", api.lastQuoteReq.mode());
        assertEquals(2, api.lastQuoteReq.bills().size(), "empty guest dropped");
        assertEquals(List.of(L3), api.lastQuoteReq.bills().get(0).lineIds());
        assertEquals(List.of(L1, L2), api.lastQuoteReq.bills().get(1).lineIds());
        assertEquals(2, vm.billCount());
        assertEquals("Guest 1", vm.billLabel(0));
        assertEquals("Guest 3", vm.billLabel(1));
        assertEquals(0, new BigDecimal("34.50").compareTo(vm.billAmount(0)));
        assertEquals(0, new BigDecimal("19.55").compareTo(vm.billAmount(1)));
    }

    @Test
    void evenQuoteUsesWaysAndShares() {
        FakeDiningApi api = new FakeDiningApi();
        SplitViewModel vm = loadedVm(api);
        vm.setMode("EVEN");
        assertTrue(vm.setWays(3));
        api.quoteResult = new SplitQuoteView(null, quote("40.25"),
                List.of(new BigDecimal("13.42"), new BigDecimal("13.42"), new BigDecimal("13.41")));

        assertTrue(vm.quoteSplit(ORDER_ID));

        assertEquals("EVEN", api.lastQuoteReq.mode());
        assertEquals(3, api.lastQuoteReq.even().ways());
        assertNull(api.lastQuoteReq.bills());
        assertEquals(3, vm.billCount());
        assertEquals("Guest 3", vm.billLabel(2));
        assertEquals(0, new BigDecimal("13.41").compareTo(vm.billAmount(2)));
        assertFalse(vm.cashChangeAllowed(), "EVEN cash is exact-amount");
    }

    @Test
    void waysAreBounded2To8() {
        SplitViewModel vm = loadedVm(new FakeDiningApi());
        vm.setMode("EVEN");
        assertFalse(vm.setWays(1));
        assertFalse(vm.setWays(9));
        assertEquals(2, vm.ways());
        assertTrue(vm.setWays(8));
        assertEquals(8, vm.ways());
    }

    @Test
    void guestCountBounded2To6() {
        SplitViewModel vm = loadedVm(new FakeDiningApi());
        assertEquals(2, vm.guestCount());
        assertTrue(vm.addGuest());  // 3
        assertTrue(vm.addGuest());  // 4
        assertTrue(vm.addGuest());  // 5
        assertTrue(vm.addGuest());  // 6
        assertFalse(vm.addGuest()); // capped
        assertEquals(6, vm.guestCount());
    }

    @Test
    void partitionChangeInvalidatesTheQuote() {
        FakeDiningApi api = new FakeDiningApi();
        SplitViewModel vm = loadedVm(api);
        vm.toggleAssign(L1);
        vm.setActiveGuest(1);
        vm.toggleAssign(L2);
        vm.toggleAssign(L3);
        api.quoteResult = new SplitQuoteView(List.of(quote("34.50"), quote("19.55")), null, null);
        assertTrue(vm.quoteSplit(ORDER_ID));
        assertTrue(vm.quoted());

        vm.toggleAssign(L3);                        // partition changed
        assertFalse(vm.quoted(), "any partition change invalidates the quote");

        vm.toggleAssign(L3);
        assertTrue(vm.quoteSplit(ORDER_ID));
        vm.setMode("EVEN");                         // mode change invalidates too
        assertFalse(vm.quoted());
    }

    @Test
    void cashShortRejectedPerGuestAndCloseBuildsByItemRequest() {
        FakeDiningApi api = new FakeDiningApi();
        SplitViewModel vm = loadedVm(api);
        vm.toggleAssign(L1);
        vm.setActiveGuest(1);
        vm.toggleAssign(L2);
        vm.toggleAssign(L3);
        api.quoteResult = new SplitQuoteView(List.of(quote("34.50"), quote("19.55")), null, null);
        assertTrue(vm.quoteSplit(ORDER_ID));

        vm.setMethod(0, "CASH");
        vm.setCashTendered(0, new BigDecimal("20.00"));  // short
        vm.setMethod(1, "CARD");
        assertFalse(vm.canCloseAll(), "short cash blocks close");
        assertEquals(0, new BigDecimal("0").compareTo(vm.changeFor(0).max(BigDecimal.ZERO)));

        vm.setCashTendered(0, new BigDecimal("50.00"));
        assertTrue(vm.canCloseAll());
        assertEquals(0, new BigDecimal("15.50").compareTo(vm.changeFor(0)));

        api.closeResult = List.of(sale("S01-T01-1", "34.50"), sale("S01-T01-2", "19.55"));
        assertTrue(vm.closeAll(ORDER_ID));

        SplitCloseRequest req = api.lastCloseReq;
        assertEquals("BY_ITEM", req.mode());
        assertFalse(req.waiveServiceCharge());
        assertEquals(List.of(L1), req.bills().get(0).lineIds());
        assertEquals("CASH", req.bills().get(0).tenders().get(0).method());
        assertEquals(0, new BigDecimal("34.50").compareTo(req.bills().get(0).tenders().get(0).amount()));
        assertEquals(0, new BigDecimal("50.00").compareTo(req.bills().get(0).tenders().get(0).tendered()));
        assertEquals("CARD", req.bills().get(1).tenders().get(0).method());
        assertNull(req.bills().get(1).tenders().get(0).tendered());
        assertTrue(req.bills().get(0).lineDiscounts().isEmpty());
        assertNull(req.bills().get(0).transactionDiscount());
        assertEquals(2, vm.results().size());
    }

    @Test
    void closeBuildsEvenRequestWithOneMethodPerShare() {
        FakeDiningApi api = new FakeDiningApi();
        SplitViewModel vm = loadedVm(api);
        vm.setMode("EVEN");
        vm.setWays(3);
        api.quoteResult = new SplitQuoteView(null, quote("40.25"),
                List.of(new BigDecimal("13.42"), new BigDecimal("13.42"), new BigDecimal("13.41")));
        assertTrue(vm.quoteSplit(ORDER_ID));

        vm.setMethod(0, "CASH");
        vm.setMethod(1, "CARD");
        assertFalse(vm.canCloseAll(), "every share needs a method");
        vm.setMethod(2, "WALLET");
        assertTrue(vm.canCloseAll(), "EVEN cash needs no tendered amount (exact)");

        api.closeResult = List.of(sale("S01-T01-1", "40.25"));
        assertTrue(vm.closeAll(ORDER_ID));
        assertEquals("EVEN", api.lastCloseReq.mode());
        assertEquals(3, api.lastCloseReq.even().ways());
        assertEquals(List.of("CASH", "CARD", "WALLET"), api.lastCloseReq.even().methods());
        assertNull(api.lastCloseReq.bills());
    }

    @Test
    void closeFailureSurfacesErrorAndKeepsNoResults() {
        FakeDiningApi api = new FakeDiningApi();
        SplitViewModel vm = loadedVm(api);
        vm.setMode("EVEN");
        api.quoteResult = new SplitQuoteView(null, quote("40.25"),
                List.of(new BigDecimal("20.13"), new BigDecimal("20.12")));
        assertTrue(vm.quoteSplit(ORDER_ID));
        vm.setMethod(0, "CARD");
        vm.setMethod(1, "CARD");
        api.closeError = new ApiException(422, null, "Insufficient tender for bill 2");

        assertFalse(vm.closeAll(ORDER_ID));
        assertTrue(vm.results().isEmpty());
        assertEquals("Insufficient tender for bill 2", vm.errorMessage().get());
    }

    @Test
    void closeAllWorksUnderDeferredDispatcher() {
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        FakeDiningApi api = new FakeDiningApi();
        api.orderResult = order(line(L1, "BURGER"), line(L2, "FRIES"), line(L3, "WATER"));
        SplitViewModel vm = new SplitViewModel(api, queue::add);   // defer, don't run
        vm.load(ORDER_ID);
        vm.toggleAssign(L1);
        vm.setActiveGuest(1);
        vm.toggleAssign(L2);
        vm.toggleAssign(L3);
        api.quoteResult = new SplitQuoteView(List.of(quote("34.50"), quote("19.55")), null, null);
        assertTrue(vm.quoteSplit(ORDER_ID), "quoteSplit must read plain fields, not observables");
        vm.setMethod(0, "CARD");
        vm.setMethod(1, "CARD");
        assertTrue(vm.canCloseAll());
        api.closeResult = List.of(sale("S01-T01-1", "34.50"), sale("S01-T01-2", "19.55"));

        assertTrue(vm.closeAll(ORDER_ID),
                "closeAll must build the request from synchronous state before any UI runnable runs");
        assertEquals(2, api.lastCloseReq.bills().size());
        assertEquals(2, vm.results().size(), "results is a plain field, readable pre-drain");

        while (!queue.isEmpty()) queue.poll().run();
        assertEquals("", vm.errorMessage().get());
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./mvnw -f pos-terminal/pom.xml test -Dtest=SplitViewModelTest 2>&1 | grep -E "Tests run: [0-9]+, Fail|ERROR|BUILD" | head -5
```

Expected: COMPILATION ERROR — `SplitViewModel` does not exist.

- [ ] **Step 3: Implement SplitViewModel**

Create `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/SplitViewModel.java`:

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.DiningApi;
import com.company.pos.terminal.api.dto.BillRequest;
import com.company.pos.terminal.api.dto.EvenSplitRequest;
import com.company.pos.terminal.api.dto.OrderLineView;
import com.company.pos.terminal.api.dto.QuoteBillInput;
import com.company.pos.terminal.api.dto.QuoteEvenInput;
import com.company.pos.terminal.api.dto.QuoteSplitRequest;
import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.api.dto.SplitCloseRequest;
import com.company.pos.terminal.api.dto.SplitQuoteView;
import com.company.pos.terminal.api.dto.TenderInput;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the split-bill screen. Synchronous like the other VMs — the controller runs it
 * off the FX thread via FxTasks. Plain fields hold ALL control-flow state (the recurring
 * off-FX-thread lesson); the only observable is {@link #errorMessage()}, mirrored through the
 * injected {@code ui} dispatcher.
 *
 * <p><b>Amounts are never computed client-side.</b> {@link #billAmount} and the close request
 * come exclusively from the stored quote-split response; a partition/mode/ways change clears the
 * quote so stale amounts can never be tendered ({@link #canCloseAll} requires {@link #quoted()}).
 */
public class SplitViewModel {

    public static final String BY_ITEM = "BY_ITEM";
    public static final String EVEN = "EVEN";

    private static final int MIN_WAYS = 2;
    private static final int MAX_WAYS = 8;
    private static final int MIN_GUESTS = 2;
    private static final int MAX_GUESTS = 6;

    private final DiningApi dining;
    private final Consumer<Runnable> ui;

    // --- partition state (plain fields = truth) ---
    private volatile String mode = BY_ITEM;
    private volatile int ways = MIN_WAYS;
    private volatile int guestCount = MIN_GUESTS;
    private volatile int activeGuest = 0;
    private volatile List<OrderLineView> lines = List.of();
    private final Map<UUID, Integer> assignment = new LinkedHashMap<>(); // lineId -> guest index

    // --- quote state (captured at quote time so close always matches the quote) ---
    private volatile SplitQuoteView quote;
    private volatile List<List<UUID>> quotedBills;   // BY_ITEM: lineIds per bill
    private volatile List<Integer> quotedGuests;     // BY_ITEM: original guest index per bill
    private volatile String quotedMode;
    private volatile int quotedWays;

    // --- tender state (index-aligned with bills/shares) ---
    private final List<String> methods = new ArrayList<>();
    private final List<BigDecimal> cashTendered = new ArrayList<>();

    private volatile List<SaleView> results = List.of();

    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public SplitViewModel(DiningApi dining) {
        this(dining, Runnable::run);
    }

    public SplitViewModel(DiningApi dining, Consumer<Runnable> ui) {
        this.dining = dining;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() { return errorMessage.getReadOnlyProperty(); }

    // --- load ---

    /** Fetches the order's lines. Synchronous; run off the FX thread. */
    public void load(UUID orderId) {
        lines = dining.order(orderId).lines();
    }

    public List<OrderLineView> lines() { return lines; }

    // --- partition phase ---

    public String mode() { return mode; }

    public void setMode(String newMode) {
        if (!BY_ITEM.equals(newMode) && !EVEN.equals(newMode)) {
            return;
        }
        if (!newMode.equals(mode)) {
            mode = newMode;
            invalidateQuote();
        }
    }

    public int ways() { return ways; }

    /** Sets the EVEN way count; rejects values outside [2, 8]. */
    public boolean setWays(int n) {
        if (n < MIN_WAYS || n > MAX_WAYS) {
            return false;
        }
        if (n != ways) {
            ways = n;
            invalidateQuote();
        }
        return true;
    }

    public int guestCount() { return guestCount; }

    /** Adds a guest tab (max 6). Adding a guest alone changes no assignment, so no invalidate. */
    public boolean addGuest() {
        if (guestCount >= MAX_GUESTS) {
            return false;
        }
        guestCount++;
        return true;
    }

    public int activeGuest() { return activeGuest; }

    public void setActiveGuest(int guest) {
        if (guest >= 0 && guest < guestCount) {
            activeGuest = guest;
        }
    }

    /** Tap a line: unassigned or other-guest → assign to the active guest; already the active
     *  guest's → unassign. Any change invalidates the quote. */
    public void toggleAssign(UUID lineId) {
        Integer current = assignment.get(lineId);
        if (current != null && current == activeGuest) {
            assignment.remove(lineId);
        } else {
            assignment.put(lineId, activeGuest);
        }
        invalidateQuote();
    }

    /** The guest index a line is assigned to, or null. */
    public Integer guestOf(UUID lineId) { return assignment.get(lineId); }

    public int unassignedCount() {
        int n = 0;
        for (OrderLineView line : lines) {
            if (!assignment.containsKey(line.id())) {
                n++;
            }
        }
        return n;
    }

    public boolean canContinue() {
        if (lines.isEmpty()) {
            return false;
        }
        return EVEN.equals(mode) || unassignedCount() == 0;
    }

    // --- quote ---

    /** Prices the current partition on the server. Captures the partition it quoted so the
     *  close request is built from exactly what was priced. Returns false (with the error
     *  surfaced) on rejection. */
    public boolean quoteSplit(UUID orderId) {
        if (!canContinue()) {
            ui.accept(() -> errorMessage.set(EVEN.equals(mode)
                    ? "Nothing to split" : "Assign every item to a guest first"));
            return false;
        }
        try {
            SplitQuoteView q;
            if (BY_ITEM.equals(mode)) {
                List<List<UUID>> bills = new ArrayList<>();
                List<Integer> guests = new ArrayList<>();
                for (int g = 0; g < guestCount; g++) {
                    List<UUID> lineIds = linesOfGuest(g);
                    if (!lineIds.isEmpty()) {          // empty guests are dropped
                        bills.add(lineIds);
                        guests.add(g);
                    }
                }
                q = dining.quoteSplit(orderId, new QuoteSplitRequest(BY_ITEM,
                        bills.stream().map(QuoteBillInput::new).toList(), null));
                quotedBills = bills;
                quotedGuests = guests;
            } else {
                q = dining.quoteSplit(orderId, new QuoteSplitRequest(EVEN, null,
                        new QuoteEvenInput(ways)));
                quotedBills = null;
                quotedGuests = null;
            }
            quotedMode = mode;
            quotedWays = ways;
            quote = q;
            resetTenders(billCount());
            ui.accept(() -> errorMessage.set(""));
            return true;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return false;
        }
    }

    private List<UUID> linesOfGuest(int guest) {
        List<UUID> ids = new ArrayList<>();
        for (OrderLineView line : lines) {           // order-of-lines, stable across renders
            Integer g = assignment.get(line.id());
            if (g != null && g == guest) {
                ids.add(line.id());
            }
        }
        return ids;
    }

    public boolean quoted() { return quote != null; }

    private void invalidateQuote() {
        quote = null;
        quotedBills = null;
        quotedGuests = null;
        results = List.of();
    }

    private void resetTenders(int bills) {
        methods.clear();
        cashTendered.clear();
        for (int i = 0; i < bills; i++) {
            methods.add(null);
            cashTendered.add(null);
        }
    }

    // --- tender phase (all amounts from the quote only) ---

    /** Number of bills to tender: BY_ITEM bill count, or EVEN ways. */
    public int billCount() {
        SplitQuoteView q = quote;
        if (q == null) {
            return 0;
        }
        return BY_ITEM.equals(quotedMode) ? q.bills().size() : q.shares().size();
    }

    /** "Guest N" — BY_ITEM keeps the original guest number even after empty guests are
     *  dropped; EVEN numbers shares 1..ways. */
    public String billLabel(int billIdx) {
        if (BY_ITEM.equals(quotedMode)) {
            return "Guest " + (quotedGuests.get(billIdx) + 1);
        }
        return "Guest " + (billIdx + 1);
    }

    /** The server-authoritative amount this guest pays. */
    public BigDecimal billAmount(int billIdx) {
        SplitQuoteView q = quote;
        return BY_ITEM.equals(quotedMode)
                ? q.bills().get(billIdx).grandTotal()
                : q.shares().get(billIdx);
    }

    public String currencyCode() {
        SplitQuoteView q = quote;
        if (q == null) {
            return "";
        }
        return BY_ITEM.equals(quotedMode) ? q.bills().get(0).currencyCode()
                : q.order().currencyCode();
    }

    public void setMethod(int billIdx, String method) { methods.set(billIdx, method); }

    public String methodOf(int billIdx) { return methods.get(billIdx); }

    public void setCashTendered(int billIdx, BigDecimal tendered) {
        cashTendered.set(billIdx, tendered);
    }

    public BigDecimal cashTenderedOf(int billIdx) { return cashTendered.get(billIdx); }

    /** Change preview for a cash bill: tendered − amount (may be negative while short). */
    public BigDecimal changeFor(int billIdx) {
        BigDecimal tendered = cashTendered.get(billIdx);
        if (tendered == null) {
            return BigDecimal.ZERO;
        }
        return tendered.setScale(2, RoundingMode.HALF_UP).subtract(billAmount(billIdx));
    }

    /** Cash-with-change is only possible on BY_ITEM bills; EVEN shares are exact-amount by
     *  design (the server fixes tendered == share). */
    public boolean cashChangeAllowed() { return BY_ITEM.equals(quotedMode); }

    /** Every bill has a method; BY_ITEM cash bills additionally need tendered ≥ amount. */
    public boolean canCloseAll() {
        if (quote == null || billCount() == 0) {
            return false;
        }
        for (int i = 0; i < billCount(); i++) {
            String method = methods.get(i);
            if (method == null) {
                return false;
            }
            if (cashChangeAllowed() && "CASH".equals(method)) {
                BigDecimal tendered = cashTendered.get(i);
                if (tendered == null || tendered.compareTo(billAmount(i)) < 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Closes ALL bills in one atomic server call. On failure the server rolls back everything
     *  (order stays OPEN) and the error is surfaced; tender state is preserved for retry. */
    public boolean closeAll(UUID orderId) {
        if (!canCloseAll()) {
            ui.accept(() -> errorMessage.set("Choose a payment for every guest"));
            return false;
        }
        SplitCloseRequest req;
        if (BY_ITEM.equals(quotedMode)) {
            List<BillRequest> bills = new ArrayList<>();
            for (int i = 0; i < quotedBills.size(); i++) {
                String method = methods.get(i);
                BigDecimal amount = billAmount(i);
                BigDecimal tendered = "CASH".equals(method)
                        ? cashTendered.get(i).setScale(2, RoundingMode.HALF_UP) : null;
                bills.add(new BillRequest(quotedBills.get(i),
                        List.of(new TenderInput(method, amount, tendered)), Map.of(), null));
            }
            req = new SplitCloseRequest(BY_ITEM, bills, null, false);
        } else {
            req = new SplitCloseRequest(EVEN, null,
                    new EvenSplitRequest(quotedWays, new ArrayList<>(methods)), false);
        }
        try {
            results = dining.closeSplit(orderId, req);   // SYNCHRONOUS — control-flow state
            ui.accept(() -> errorMessage.set(""));
            return true;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return false;
        }
    }

    public List<SaleView> results() { return results; }

    private String messageOf(ApiException e) {
        if (e.problem() != null) {
            if (e.problem().detail() != null && !e.problem().detail().isBlank()) {
                return e.problem().detail();
            }
            if (e.problem().title() != null && !e.problem().title().isBlank()) {
                return e.problem().title();
            }
        }
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        return "Request failed";
    }
}
```

- [ ] **Step 4: Run to verify pass**

```bash
./mvnw -f pos-terminal/pom.xml test -Dtest=SplitViewModelTest 2>&1 | grep -E "Tests run: [0-9]+, Fail|BUILD"
```

Expected: green.

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/SplitViewModel.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/SplitViewModelTest.java
git commit -m "feat(terminal): SplitViewModel — partition/quote/tender state, amounts server-only

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Split screen — split.fxml + SplitController + Navigator.toSplit + Order-screen button + CSS

**Files:**
- Create: `pos-terminal/src/main/resources/fxml/split.fxml`, `pos-terminal/src/main/java/com/company/pos/terminal/view/SplitController.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java`, `pos-terminal/src/main/java/com/company/pos/terminal/view/OrderController.java`, `pos-terminal/src/main/resources/fxml/order.fxml`, `pos-terminal/src/main/resources/css/app.css`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/FxmlContractTest.java`, `pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java`

**Interfaces:**
- Consumes (Task 5): the full `SplitViewModel` surface; (existing) `Services.diningApi/productApi/config`, `MenuCache(products)` + `nameFor(sku)`, `FxTasks.run(work, onDone, onError)`, `Navigator.setScene`, `Navigator.toOrder(UUID)`, `Navigator.toTableMap()`.
- Produces: `Navigator.toSplit(UUID orderId)`; `order.fxml` gains `fx:id="splitButton"`; new CSS classes `.mode-toggle`, `.guest-tab`, `.split-line`, `.split-line-assigned`, `.guest-badge`, `.split-amount-row`.

- [ ] **Step 1: Write the failing contract tests**

Append to `FxmlContractTest.java`:

```java
    @Test
    void orderDeclaresSplitButton() throws Exception {
        assertTrue(resource("/fxml/order.fxml").contains("fx:id=\"splitButton\""),
                "order.fxml must declare the Split bill button");
    }

    @Test
    void splitDeclaresPartitionPhaseNodes() throws Exception {
        String fxml = resource("/fxml/split.fxml");
        for (String id : new String[] {
            "byItemToggle", "evenToggle", "guestTabs", "addGuestButton", "lineRows",
            "waysMinusButton", "waysLabel", "waysPlusButton", "unassignedLabel",
            "continueButton", "cancelButton", "partitionBox", "byItemBox", "evenBox"
        }) {
            assertTrue(fxml.contains("fx:id=\"" + id + "\""), "missing partition node: " + id);
        }
    }

    @Test
    void splitDeclaresTenderAndResultPhaseNodes() throws Exception {
        String fxml = resource("/fxml/split.fxml");
        for (String id : new String[] {
            "tenderBox", "guestRows", "backButton", "closeAllButton", "quoteBadge",
            "resultBox", "successBanner", "successCheck", "paidLabel", "resultRows",
            "doneButton", "errorLabel"
        }) {
            assertTrue(fxml.contains("fx:id=\"" + id + "\""), "missing tender/result node: " + id);
        }
    }
```

Append to `AppCssTest.java`:

```java
    @Test
    void definesSliceSixSplitClasses() throws Exception {
        String css = css();
        for (String cls : new String[] {
            ".mode-toggle", ".guest-tab", ".split-line", ".split-line-assigned",
            ".guest-badge", ".split-amount-row"
        }) {
            assertTrue(css.contains(cls), "missing style class: " + cls);
        }
    }
```

- [ ] **Step 2: Run to verify failure**

```bash
./mvnw -f pos-terminal/pom.xml test -Dtest='FxmlContractTest,AppCssTest' 2>&1 | grep -E "Tests run: [0-9]+, Fail|BUILD" | head -3
```

Expected: FAIL — split.fxml missing, splitButton missing, CSS classes missing.

- [ ] **Step 3: Create split.fxml**

Create `pos-terminal/src/main/resources/fxml/split.fxml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.geometry.Insets?>
<?import javafx.scene.control.Button?>
<?import javafx.scene.control.Label?>
<?import javafx.scene.control.ScrollPane?>
<?import javafx.scene.control.ToggleButton?>
<?import javafx.scene.layout.BorderPane?>
<?import javafx.scene.layout.FlowPane?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.layout.Pane?>
<?import javafx.scene.layout.StackPane?>
<?import javafx.scene.layout.VBox?>

<!-- Split bill: phase 1 partitions the order (by item, or evenly N ways); phase 2 shows one
     server-authoritative amount per guest and takes one tender each; phase 3 shows the per-guest
     results. The three phase boxes swap via visible/managed (payment.fxml pattern). -->
<BorderPane styleClass="screen" xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml">
  <top>
    <HBox spacing="12" alignment="CENTER_LEFT">
      <padding><Insets bottom="16"/></padding>
      <Label text="Split bill" styleClass="title"/>
      <Label fx:id="quoteBadge" text="✓ server" styleClass="quote-badge" visible="false" managed="false"/>
      <Pane HBox.hgrow="ALWAYS"/>
      <Button fx:id="cancelButton" text="Cancel" styleClass="btn-secondary"/>
    </HBox>
  </top>

  <center>
    <StackPane>
      <!-- Phase 1: partition -->
      <VBox fx:id="partitionBox" spacing="16" maxWidth="720" alignment="TOP_CENTER">
        <HBox spacing="12" maxWidth="Infinity">
          <ToggleButton fx:id="byItemToggle" text="By item" selected="true" styleClass="mode-toggle" maxWidth="Infinity" HBox.hgrow="ALWAYS"/>
          <ToggleButton fx:id="evenToggle" text="Split evenly" styleClass="mode-toggle" maxWidth="Infinity" HBox.hgrow="ALWAYS"/>
        </HBox>

        <!-- BY_ITEM: guest tabs + tap-to-assign line rows -->
        <VBox fx:id="byItemBox" spacing="12" VBox.vgrow="ALWAYS">
          <HBox spacing="8">
            <FlowPane fx:id="guestTabs" hgap="8" vgap="8" HBox.hgrow="ALWAYS"/>
            <Button fx:id="addGuestButton" text="+ Guest" styleClass="btn-secondary"/>
          </HBox>
          <ScrollPane fitToWidth="true" styleClass="menu-scroll" VBox.vgrow="ALWAYS">
            <VBox fx:id="lineRows" spacing="8"/>
          </ScrollPane>
          <Label fx:id="unassignedLabel" styleClass="subtitle"/>
        </VBox>

        <!-- EVEN: ways stepper -->
        <VBox fx:id="evenBox" spacing="12" alignment="CENTER" visible="false" managed="false">
          <Label text="Split into" styleClass="subtitle"/>
          <HBox spacing="16" alignment="CENTER">
            <Button fx:id="waysMinusButton" text="−" styleClass="denom-chip"/>
            <Label fx:id="waysLabel" text="2" styleClass="pay-total,money"/>
            <Button fx:id="waysPlusButton" text="+" styleClass="denom-chip"/>
          </HBox>
          <Label text="equal shares (last share absorbs the rounding)" styleClass="estimate-line"/>
        </VBox>

        <Button fx:id="continueButton" text="Continue" defaultButton="true" styleClass="btn-primary" maxWidth="Infinity"/>
      </VBox>

      <!-- Phase 2: one tender per guest -->
      <VBox fx:id="tenderBox" spacing="16" maxWidth="720" alignment="TOP_CENTER" visible="false" managed="false">
        <ScrollPane fitToWidth="true" styleClass="menu-scroll" VBox.vgrow="ALWAYS">
          <VBox fx:id="guestRows" spacing="12"/>
        </ScrollPane>
        <HBox spacing="12" maxWidth="Infinity">
          <Button fx:id="backButton" text="Back" styleClass="btn-secondary"/>
          <Pane HBox.hgrow="ALWAYS"/>
          <Button fx:id="closeAllButton" text="Close all bills" styleClass="btn-primary"/>
        </HBox>
      </VBox>

      <!-- Phase 3: per-guest results -->
      <VBox fx:id="resultBox" spacing="12" maxWidth="720" alignment="TOP_CENTER" visible="false" managed="false">
        <VBox fx:id="successBanner" alignment="CENTER" spacing="4" styleClass="success-banner" maxWidth="Infinity">
          <Label fx:id="successCheck" text="✓" styleClass="success-check"/>
          <Label fx:id="paidLabel" styleClass="pay-grand-label,money"/>
        </VBox>
        <VBox fx:id="resultRows" spacing="8" maxWidth="Infinity"/>
        <Button fx:id="doneButton" text="Done ▸ Tables" styleClass="btn-primary" maxWidth="Infinity"/>
      </VBox>
    </StackPane>
  </center>

  <bottom>
    <Label fx:id="errorLabel" styleClass="error-banner" wrapText="true" maxWidth="Infinity"/>
  </bottom>
</BorderPane>
```

- [ ] **Step 4: Add the CSS classes**

Append to `pos-terminal/src/main/resources/css/app.css` (after the slice-5 `.reason-chip` block):

```css
/* ---- Split-bill screen (slice 6) ----------------------------------------- */

/* Mode toggle + guest tabs: large ToggleButtons; selected = primary fill.
   Same shape family as .reason-chip but 56px — they carry the screen's flow. */
.mode-toggle, .guest-tab {
    -fx-min-height: 56px;
    -fx-font-size: 16px;
    -fx-font-weight: bold;
    -fx-background-color: -fx-canvas;
    -fx-text-fill: -fx-ink;
    -fx-border-color: -fx-border;
    -fx-border-radius: 8;
    -fx-background-radius: 8;
    -fx-padding: 8 20 8 20;
}
.mode-toggle:selected, .guest-tab:selected {
    -fx-background-color: -fx-primary;
    -fx-text-fill: white;
    -fx-border-color: -fx-primary;
}
.mode-toggle:hover, .guest-tab:hover { -fx-border-color: -fx-primary; }
.mode-toggle:focused, .guest-tab:focused { -fx-border-width: 2; }
.guest-tab { -fx-min-width: 112px; }

/* Tap-to-assign order line: card row; assigned rows tint toward the primary. */
.split-line {
    -fx-min-height: 56px;
    -fx-alignment: CENTER_LEFT;
    -fx-font-size: 16px;
    -fx-background-color: -fx-card;
    -fx-text-fill: -fx-ink;
    -fx-border-color: -fx-border;
    -fx-border-radius: 8;
    -fx-background-radius: 8;
    -fx-padding: 8 16 8 16;
}
.split-line:hover { -fx-border-color: -fx-primary; }
.split-line:focused { -fx-border-color: -fx-primary; -fx-border-width: 2; }
.split-line-assigned {
    -fx-background-color: derive(-fx-primary, 92%);
    -fx-border-color: -fx-primary;
}

/* Guest-number badge on an assigned line. */
.guest-badge {
    -fx-background-color: -fx-primary;
    -fx-text-fill: white;
    -fx-font-size: 13px;
    -fx-font-weight: bold;
    -fx-padding: 2 10 2 10;
    -fx-background-radius: 10;
}

/* One guest's tender row in phase 2: label + authoritative amount + method + cash field. */
.split-amount-row {
    -fx-background-color: -fx-card;
    -fx-border-color: -fx-border;
    -fx-border-radius: 8;
    -fx-background-radius: 8;
    -fx-padding: 12 16 12 16;
}
```

- [ ] **Step 5: Add Navigator.toSplit and the order-screen Split button**

In `Navigator.java`, after `toOrder`:

```java
    // Reached from the order screen's "Split bill" action. Builds the split screen; Cancel
    // returns to the order, Done (after a successful close) returns to the table map.
    public void toSplit(UUID orderId) {
        com.company.pos.terminal.view.SplitController controller =
                new com.company.pos.terminal.view.SplitController(services, this, orderId);
        setScene("/fxml/split.fxml", controller);
    }
```

In `order.fxml`, change the bottom action row to include the split button (beside Pay):

```xml
      <HBox spacing="12">
        <Button fx:id="backButton" text="Back to tables" styleClass="btn-secondary"/>
        <Pane HBox.hgrow="ALWAYS"/>
        <Button fx:id="splitButton" text="Split bill" styleClass="btn-secondary"/>
        <Button fx:id="payButton" text="Pay" defaultButton="true" styleClass="btn-primary"/>
      </HBox>
```

In `OrderController.java`:
- add the field `@FXML private Button splitButton;`
- in `initialize()`, next to the other handlers: `splitButton.setOnAction(e -> navigator.toSplit(orderId));` and with the other pre-load disables: `splitButton.setDisable(true);`
- in `afterCatalogLoaded()`, next to `payButton.setDisable(false)` do NOT yet enable it; instead gate on lines (spec: order + catalog loaded AND the order has lines). Add inside the existing `vm.lines()` listener and after the initial `vm.load` completes:

```java
        // in afterCatalogLoaded(), replace the existing lines listener with:
        vm.lines().addListener((ListChangeListener<OrderLineView>) c -> {
            lineList.getItems().setAll(vm.lines());
            splitButton.setDisable(vm.lines().isEmpty());
        });
        ...
        // and in the vm.load onDone callback, replace with:
        FxTasks.run(
                () -> vm.load(orderId),
                () -> {
                    lineList.getItems().setAll(vm.lines());
                    splitButton.setDisable(vm.lines().isEmpty());
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to load order " + orderId, err));
```

- [ ] **Step 6: Create SplitController**

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/SplitController.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.OrderLineView;
import com.company.pos.terminal.api.dto.SalePaymentView;
import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.order.MenuCache;
import com.company.pos.terminal.viewmodel.SplitViewModel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Thin controller for the split-bill screen. Three phases on one scene (partition → tender →
 * result), swapped via visible/managed. All state lives in {@link SplitViewModel} (synchronous,
 * plain-field truth); this class only renders and dispatches VM calls off the FX thread via
 * {@link FxTasks}. Every amount shown in phase 2 comes from the quote-split response — the
 * screen computes no money beyond the cash-change preview difference the VM provides.
 */
public class SplitController {

    private static final System.Logger LOG = System.getLogger(SplitController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final UUID orderId;
    private final SplitViewModel vm;
    private MenuCache cache;

    @FXML private Label quoteBadge;
    @FXML private Button cancelButton;
    @FXML private VBox partitionBox;
    @FXML private ToggleButton byItemToggle;
    @FXML private ToggleButton evenToggle;
    @FXML private VBox byItemBox;
    @FXML private FlowPane guestTabs;
    @FXML private Button addGuestButton;
    @FXML private VBox lineRows;
    @FXML private Label unassignedLabel;
    @FXML private VBox evenBox;
    @FXML private Button waysMinusButton;
    @FXML private Label waysLabel;
    @FXML private Button waysPlusButton;
    @FXML private Button continueButton;
    @FXML private VBox tenderBox;
    @FXML private VBox guestRows;
    @FXML private Button backButton;
    @FXML private Button closeAllButton;
    @FXML private VBox resultBox;
    @FXML private VBox successBanner;
    @FXML private Label successCheck;
    @FXML private Label paidLabel;
    @FXML private VBox resultRows;
    @FXML private Button doneButton;
    @FXML private Label errorLabel;

    private final ToggleGroup modeGroup = new ToggleGroup();
    private final ToggleGroup guestGroup = new ToggleGroup();

    public SplitController(Services services, Navigator navigator, UUID orderId) {
        this.services = services;
        this.navigator = navigator;
        this.orderId = orderId;
        this.vm = new SplitViewModel(services.diningApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        errorLabel.textProperty().bind(vm.errorMessage());
        errorLabel.visibleProperty().bind(errorLabel.textProperty().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        byItemToggle.setToggleGroup(modeGroup);
        evenToggle.setToggleGroup(modeGroup);
        // A ToggleGroup allows deselect-by-reclick; force one mode to stay selected.
        modeGroup.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null && was != null) {
                was.setSelected(true);
                return;
            }
            vm.setMode(now == evenToggle ? SplitViewModel.EVEN : SplitViewModel.BY_ITEM);
            boolean even = now == evenToggle;
            byItemBox.setVisible(!even);
            byItemBox.setManaged(!even);
            evenBox.setVisible(even);
            evenBox.setManaged(even);
            updatePartitionState();
        });

        cancelButton.setOnAction(e -> navigator.toOrder(orderId));
        addGuestButton.setOnAction(e -> { if (vm.addGuest()) renderGuestTabs(); });
        waysMinusButton.setOnAction(e -> { if (vm.setWays(vm.ways() - 1)) updateWays(); });
        waysPlusButton.setOnAction(e -> { if (vm.setWays(vm.ways() + 1)) updateWays(); });
        continueButton.setOnAction(e -> quoteAndShowTender());
        backButton.setOnAction(e -> showPhase(partitionBox));
        closeAllButton.setOnAction(e -> closeAll());
        doneButton.setOnAction(e -> navigator.toTableMap());

        continueButton.setDisable(true);
        updateWays();

        // Catalog (for line names) + order lines, off the FX thread; then render phase 1.
        FxTasks.run(
                () -> {
                    cache = new MenuCache(services.productApi.list());
                    vm.load(orderId);
                },
                () -> {
                    renderGuestTabs();
                    renderLines();
                    updatePartitionState();
                },
                err -> {
                    LOG.log(System.Logger.Level.ERROR, "Failed to load order " + orderId, err);
                    vmSetError();
                });
    }

    private void vmSetError() {
        // Error label is bound to the VM; a load failure has no VM path, so unbind-free
        // fallback: navigate back to the order screen (the order screen re-fetches).
        navigator.toOrder(orderId);
    }

    // --- phase 1: partition ---

    private void renderGuestTabs() {
        guestTabs.getChildren().clear();
        for (int g = 0; g < vm.guestCount(); g++) {
            final int guest = g;
            ToggleButton tab = new ToggleButton("Guest " + (g + 1));
            tab.getStyleClass().add("guest-tab");
            tab.setToggleGroup(guestGroup);
            tab.setSelected(g == vm.activeGuest());
            tab.setOnAction(e -> {
                tab.setSelected(true);              // no deselect on re-tap
                vm.setActiveGuest(guest);
            });
            guestTabs.getChildren().add(tab);
        }
        addGuestButton.setDisable(vm.guestCount() >= 6);
    }

    private void renderLines() {
        lineRows.getChildren().clear();
        for (OrderLineView line : vm.lines()) {
            Button row = new Button();
            row.getStyleClass().add("split-line");
            row.setMaxWidth(Double.MAX_VALUE);
            Label name = new Label(qtyText(line.qty()) + " × " + cache.nameFor(line.sku()));
            Label badge = new Label();
            badge.getStyleClass().add("guest-badge");
            Integer guest = vm.guestOf(line.id());
            badge.setVisible(guest != null);
            if (guest != null) {
                badge.setText("Guest " + (guest + 1));
                row.getStyleClass().add("split-line-assigned");
            }
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox content = new HBox(8, name, gap, badge);
            content.setMaxWidth(Double.MAX_VALUE);
            row.setGraphic(content);
            row.setOnAction(e -> {
                vm.toggleAssign(line.id());
                renderLines();                       // re-render badges + assigned styling
                updatePartitionState();
            });
            lineRows.getChildren().add(row);
        }
    }

    private void updatePartitionState() {
        unassignedLabel.setText("Unassigned: " + vm.unassignedCount());
        continueButton.setDisable(!vm.canContinue());
        // Any partition/mode/ways change invalidated the quote — hide the stale badge.
        quoteBadge.setVisible(false);
        quoteBadge.setManaged(false);
    }

    private void updateWays() {
        waysLabel.setText(String.valueOf(vm.ways()));
        waysMinusButton.setDisable(vm.ways() <= 2);
        waysPlusButton.setDisable(vm.ways() >= 8);
        updatePartitionState();
    }

    // --- phase 2: tender ---

    /** Continue: fetch the authoritative quote-split off-thread; only on success move to the
     *  tender phase (the tender phase can never show a number the server hasn't confirmed). */
    private void quoteAndShowTender() {
        continueButton.setDisable(true);
        final boolean[] ok = new boolean[1];
        FxTasks.run(
                () -> ok[0] = vm.quoteSplit(orderId),
                () -> {
                    continueButton.setDisable(!vm.canContinue());
                    if (ok[0]) {
                        quoteBadge.setVisible(true);
                        quoteBadge.setManaged(true);
                        renderGuestRows();
                        showPhase(tenderBox);
                    }
                },
                err -> {
                    continueButton.setDisable(!vm.canContinue());
                    LOG.log(System.Logger.Level.ERROR, "quote-split failed", err);
                });
    }

    private void renderGuestRows() {
        guestRows.getChildren().clear();
        String cur = vm.currencyCode();
        for (int i = 0; i < vm.billCount(); i++) {
            final int bill = i;
            Label who = new Label(vm.billLabel(i));
            who.getStyleClass().add("pay-line-label");
            Label amount = new Label(money(vm.billAmount(i), cur));
            amount.getStyleClass().addAll("pay-grand-value", "money");

            ToggleGroup methodGroup = new ToggleGroup();
            ToggleButton cash = methodToggle("Cash", "CASH", methodGroup);
            ToggleButton card = methodToggle("Card", "CARD", methodGroup);
            ToggleButton wallet = methodToggle("Wallet", "WALLET", methodGroup);

            TextField tenderedField = new TextField();
            tenderedField.setPromptText("Cash tendered");
            tenderedField.getStyleClass().add("money");
            Label changeLabel = new Label();
            changeLabel.getStyleClass().addAll("pay-change-preview", "money");
            HBox cashRow = new HBox(8, tenderedField, changeLabel);
            cashRow.setVisible(false);
            cashRow.setManaged(false);
            Label exactHint = new Label("exact amount");
            exactHint.getStyleClass().add("estimate-line");
            exactHint.setVisible(false);
            exactHint.setManaged(false);

            tenderedField.textProperty().addListener((o, was, now) -> {
                vm.setCashTendered(bill, parseMoney(now));
                BigDecimal change = vm.changeFor(bill);
                changeLabel.setText(change.signum() >= 0 && parseMoney(now) != null
                        ? "Change: " + change.toPlainString() : "");
                updateCloseAllState();
            });
            methodGroup.selectedToggleProperty().addListener((o, was, now) -> {
                if (now == null && was != null) {
                    was.setSelected(true);          // keep one method selected once chosen
                    return;
                }
                String method = (String) now.getUserData();
                vm.setMethod(bill, method);
                boolean cashChosen = "CASH".equals(method);
                boolean withChange = cashChosen && vm.cashChangeAllowed();
                cashRow.setVisible(withChange);
                cashRow.setManaged(withChange);
                exactHint.setVisible(cashChosen && !vm.cashChangeAllowed());
                exactHint.setManaged(cashChosen && !vm.cashChangeAllowed());
                updateCloseAllState();
            });

            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox header = new HBox(12, who, gap, amount);
            HBox methodRow = new HBox(8, cash, card, wallet, exactHint);
            VBox row = new VBox(8, header, methodRow, cashRow);
            row.getStyleClass().add("split-amount-row");
            row.setMaxWidth(Double.MAX_VALUE);
            guestRows.getChildren().add(row);
        }
        updateCloseAllState();
    }

    private ToggleButton methodToggle(String text, String method, ToggleGroup group) {
        ToggleButton b = new ToggleButton(text);
        b.setUserData(method);
        b.setToggleGroup(group);
        b.getStyleClass().add("mode-toggle");
        return b;
    }

    private void updateCloseAllState() {
        closeAllButton.setDisable(!vm.canCloseAll());
    }

    private void closeAll() {
        closeAllButton.setDisable(true);
        backButton.setDisable(true);
        final boolean[] ok = new boolean[1];
        FxTasks.run(
                () -> ok[0] = vm.closeAll(orderId),
                () -> {
                    backButton.setDisable(false);
                    if (ok[0]) {
                        showResults();
                    } else {
                        // Atomic failure: server rolled back everything; phase 2 stays editable.
                        updateCloseAllState();
                    }
                },
                err -> {
                    backButton.setDisable(false);
                    updateCloseAllState();
                    LOG.log(System.Logger.Level.ERROR, "close-split failed", err);
                });
    }

    // --- phase 3: results ---

    private void showResults() {
        List<SaleView> sales = vm.results();
        String cur = sales.isEmpty() ? "" : sales.get(0).currencyCode();
        BigDecimal paid = BigDecimal.ZERO;
        resultRows.getChildren().clear();
        List<String> rows = new ArrayList<>();
        if (sales.size() > 1 || SplitViewModel.BY_ITEM.equals(vm.mode())) {
            // BY_ITEM: one sale per guest, aligned with the bill order.
            for (int i = 0; i < sales.size(); i++) {
                SaleView s = sales.get(i);
                paid = paid.add(s.grandTotal());
                String change = changeText(s);
                rows.add(vm.billLabel(i) + " · Receipt " + s.receiptNumber()
                        + " · " + money(s.grandTotal(), cur) + change);
            }
        } else {
            // EVEN: one sale with one payment per guest (exact amounts, no change).
            SaleView s = sales.get(0);
            paid = s.grandTotal();
            for (int i = 0; i < s.payments().size(); i++) {
                SalePaymentView p = s.payments().get(i);
                rows.add("Guest " + (i + 1) + " · " + p.method() + " · " + money(p.amount(), cur));
            }
            rows.add("Receipt " + s.receiptNumber());
        }
        for (String text : rows) {
            Label row = new Label(text);
            row.getStyleClass().addAll("pay-line-value", "money");
            resultRows.getChildren().add(row);
        }
        paidLabel.setText("Paid · " + money(paid, cur));
        showPhase(resultBox);
        doneButton.setDefaultButton(true);
        if (!services.config.reducedMotion()) {
            ScaleTransition pop = new ScaleTransition(Duration.millis(200), successCheck);
            pop.setFromX(0.6);
            pop.setFromY(0.6);
            pop.setToX(1.0);
            pop.setToY(1.0);
            pop.play();
        }
    }

    private static String changeText(SaleView s) {
        BigDecimal change = BigDecimal.ZERO;
        if (s.payments() != null) {
            for (SalePaymentView p : s.payments()) {
                if (p.changeGiven() != null) {
                    change = change.add(p.changeGiven());
                }
            }
        }
        return change.signum() > 0 ? " · change " + change.toPlainString() : "";
    }

    private void showPhase(VBox phase) {
        for (VBox box : List.of(partitionBox, tenderBox, resultBox)) {
            boolean show = box == phase;
            box.setVisible(show);
            box.setManaged(show);
        }
    }

    private static String qtyText(BigDecimal qty) {
        return (qty == null ? BigDecimal.ZERO : qty).stripTrailingZeros().toPlainString();
    }

    private static BigDecimal parseMoney(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String money(BigDecimal amount, String currency) {
        BigDecimal value = (amount == null ? BigDecimal.ZERO : amount)
                .setScale(2, RoundingMode.HALF_UP);
        return (value.toPlainString() + " " + currency).trim();
    }
}
```

Implementation notes for this step:
- `vm.changeFor(bill)` with a null tendered returns ZERO — the change label additionally checks `parseMoney(now) != null` so an empty field shows no "Change: 0.00".
- The `Back` button preserves assignments (phase boxes swap; VM state is untouched). Returning and changing nothing then tapping Continue simply re-quotes — acceptable and simpler than caching.
- `showResults` reads `vm.results()` inside an `FxTasks` onDone callback — VM plain state written before the task completed, safe per the FxTasks FIFO ordering established in slice 4.

- [ ] **Step 7: Run the terminal suite**

```bash
./mvnw -f pos-terminal/pom.xml test 2>&1 | grep -E "Tests run: [0-9]+, Fail|BUILD"
```

Expected: all green (FxmlContractTest + AppCssTest now pass; nothing else regresses).

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/resources/fxml/split.fxml \
        pos-terminal/src/main/resources/fxml/order.fxml \
        pos-terminal/src/main/resources/css/app.css \
        pos-terminal/src/main/java/com/company/pos/terminal/view/SplitController.java \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/OrderController.java \
        pos-terminal/src/test/java/com/company/pos/terminal/FxmlContractTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java
git commit -m "feat(terminal): split-bill screen — partition, per-guest tender, atomic close

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: Docs + full-suite verification

**Files:**
- Modify: `docs/run-modes.md` (quote-split paragraph, next to the existing discount-quotes section)
- Modify: `pos-terminal/README.md` (slice-6 section + manual GUI E2E)

**Interfaces:**
- Consumes: everything above. Produces: documentation only.

- [ ] **Step 1: Document the endpoint in docs/run-modes.md**

Find the section documenting `POST /dining/orders/{id}/quote` (added in slice 5) and add after it:

```markdown
- `POST /dining/orders/{orderId}/quote-split` — authoritative pricing for a proposed split,
  priced by the same code paths `close-split` charges (any authenticated user). Body:
  `{"mode":"BY_ITEM","bills":[{"lineIds":[...]}, ...]}` or `{"mode":"EVEN","even":{"ways":N}}`.
  BY_ITEM returns `bills` (one `QuoteView` per bill, request order; service charge per bill);
  EVEN returns `order` (the whole-order quote) plus `shares` (HALF_UP scale-2 shares, the LAST
  absorbing the rounding remainder — 40.25/3 → 13.42, 13.42, 13.41 — matching the exact-amount
  tenders `close-split` creates). Pure calculator: creates no sale, the order stays OPEN, and the
  partition is validated exactly as at close (every line in exactly one bill, no duplicates,
  ≥1 line per bill) so mistakes fail at quote time. No discounts/waiver in the body — the split
  UI doesn't carry them; a discounted split close would bypass the quote and is out of scope.
```

(Adjust wording/placement to match the surrounding document's style — it is a facts file, keep it terse.)

- [ ] **Step 2: Document the terminal slice + manual E2E in pos-terminal/README.md**

Read the README's existing slice sections and manual E2E checklist, then append a slice-6 section in the same voice, covering:
- Split bill entry: order screen → **Split bill** (enabled once the order has lines).
- Phase 1: mode toggle (By item / Split evenly); BY_ITEM guest tabs (2–6) + tap-to-assign rows + "Unassigned: N" (Continue gated on 0); EVEN ways stepper (2–8).
- Phase 2: per-guest **server-authoritative** amounts ("✓ server" badge), one tender each; cash change only on BY_ITEM bills; EVEN cash is "exact amount"; **Close all bills** is one atomic call — any failure rolls everything back and the order stays OPEN.
- Phase 3: per-guest receipts/amounts + Done ▸ Tables.

Manual GUI E2E additions (backend running with `embedded,dev`, login `manager`/`manager` — see the README's existing E2E preamble):
1. BY_ITEM, 2 guests: seat a table, add 3 items, Split bill → assign 1 item to Guest 1, 2 to Guest 2 → Continue → amounts match a hand-check of the two bills (badge shows ✓ server) → Guest 1 CASH with over-tender (change previews), Guest 2 CARD → Close all bills → both receipts listed with change on Guest 1 → Done ▸ Tables → table is free.
2. EVEN, 3 ways: order → Split bill → Split evenly, ways 3 → Continue → three shares, last differs by the rounding remainder when the total isn't divisible → one CASH share shows "exact amount" (no tendered field) → Close all bills → one receipt, three payments.
3. Partition error path: leave an item unassigned → Continue stays disabled; assign the same item to another guest (reassign) and verify the badge moves.
4. Atomic-failure path: hard to trigger from the UI alone (the UI validates first); acceptable to document as covered by backend tests (`aFailingBillRollsBackTheWholeSplit`).

- [ ] **Step 3: Full verification — both suites + modularity**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd "/Users/zuhairahamed/Desktop/Research & Development/POS"
./mvnw test 2>&1 | tail -20 | grep -E "Tests run:|BUILD"
./mvnw -f pos-terminal/pom.xml test 2>&1 | grep -E "Tests run: [0-9]+, Fail|BUILD"
```

Expected: backend all green (was 368 + new quote-split tests), terminal all green (was 140 + new tests), `BUILD SUCCESS` twice. (Full `./mvnw test` includes `ModularityTests`.)

- [ ] **Step 4: Commit**

```bash
git add docs/run-modes.md pos-terminal/README.md
git commit -m "docs: quote-split endpoint + terminal split-bill slice and manual E2E

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Execution notes

- Backend tasks (1–3) and terminal tasks (4–6) touch disjoint trees but MUST run in order — Task 4's tests mirror Task 3's JSON, Task 5 consumes Task 4, Task 6 consumes Task 5.
- Do not stage or commit the user's pre-existing `M CLAUDE.md` modification.
- Out of scope (do not add): discounts in any split path, multi-tender per bill, service-charge waiver, mixed mode, retail cart splits, per-guest receipt printing UI.
