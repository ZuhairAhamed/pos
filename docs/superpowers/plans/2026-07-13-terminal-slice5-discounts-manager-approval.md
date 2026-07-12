# Slice 5 — Transaction Discount + Manager Approval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A cashier applies one whole-bill discount (percent or amount + reason) on the payment screen for both retail and dine-in; the big total is always the discounted server quote; over-cap discounts are approved by a one-shot manager PIN whose token rides exactly one HTTP call.

**Architecture:** Backend first — the quote path (`SalesService.quote`, dining `quoteOrder`) becomes discount-aware as a *pure calculator* (cap NOT enforced at quote; checkout stays the sole enforcement point), plus a tiny `GET /sales/discount-policy` endpoint. Terminal second — typed discount DTOs, a per-request bearer override in `ApiClient`, a session-untouched `pinLoginForToken`, two pure-view dialogs (Discount, ManagerPin), and PaymentController wiring (apply → re-quote → tender, with approval interception).

**Tech Stack:** Spring Modulith (Java 21, Maven root reactor) for the backend; standalone JavaFX Maven module `pos-terminal/` (FXML + MVVM + `java.net.http`) for the terminal. Headless tests only (StubServer / MockMvc / resource-contract) — no TestFX.

**Spec:** `docs/superpowers/specs/2026-07-13-terminal-slice5-discounts-manager-approval-design.md`

## Global Constraints

- **JDK 21 required; system default is 17.** Every backend command starts with `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`. Working directory is the repo root `/Users/zuhairahamed/Desktop/Research & Development/POS` (quote the path — it contains `&` and spaces).
- Backend build: `./mvnw test -Dtest=<Class>`; terminal build: `./mvnw -f pos-terminal/pom.xml test` (the terminal module is NOT in the root reactor).
- **Money is `BigDecimal`, scale 2, `RoundingMode.HALF_UP` — never `double`.**
- **Quote is a pure calculator**: the new quote overloads pass `callerIsManager = true` internally so the cashier cap is never enforced at quote time. Checkout/close remain the only enforcement point. Reason codes ARE still validated at quote time.
- **The one-shot manager token is never stored in `SessionManager`** — it lives in a local/field variable for one checkout call and is discarded. A 401 on a token-overridden call must NOT clear the cashier session.
- Terminal MVVM rule: plain (non-observable) fields are the synchronous control-flow source of truth; ObservableLists/properties are ui-dispatcher mirror views. Controller state that background lambdas read (`discount`, `policy`, `pendingApprovalToken`) is `volatile`.
- CSS uses existing emerald tokens only (`-fx-primary`, `-fx-accent`, `-fx-canvas`, `-fx-ink`, `-fx-border`, `-fx-muted`, derive(...)); no new hex colors. Tap targets ≥ 48px.
- The payment-screen UI must never imply the client estimate is final: tenders (and now the Discount button) stay disabled until the server quote loads; re-quoting re-locks them.
- After any cross-module backend change, run `./mvnw test -Dtest=ModularityTests`.
- Commits: `feat(...)`/`test(...)`/`docs(...)` style, each ending with the trailer line `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Test seed pattern (backend): `fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER", "EA", new BigDecimal("30.00"), "SAR", 1, true)); productSync.sync();` — 2×BURGER = 60.00 net, default VAT 15% exclusive. Web tests authenticate with `.with(jwt().jwt(j -> j.subject("cashier1")))`.

---

### Task 1: Backend — discount-aware `SalesService.quote` + `POST /sales/quote` body

**Files:**
- Modify: `src/main/java/com/company/pos/sales/api/SalesService.java`
- Modify: `src/main/java/com/company/pos/sales/application/DefaultSalesService.java:255-281`
- Modify: `src/main/java/com/company/pos/sales/web/SalesController.java:38-46`
- Create: `src/test/java/com/company/pos/sales/SalesQuoteDiscountTest.java`
- Modify: `src/test/java/com/company/pos/sales/SalesQuoteControllerTest.java`

**Interfaces:**
- Consumes: existing `priceDiscountTax(CartView, Map<String,DiscountInput>, DiscountInput, boolean callerIsManager, boolean applyServiceCharge)` in `DefaultSalesService`; `DiscountInput(DiscountType type, BigDecimal value, String reasonCode)`; `QuoteView(currencyCode, subtotal, discountTotal, serviceChargeAmount, taxTotal, grandTotal)`.
- Produces: `QuoteView quote(UUID cartId, Map<String, DiscountInput> lineDiscounts, DiscountInput transactionDiscount, boolean applyServiceCharge)` on `SalesService` — Task 2 (dining) and the terminal depend on it. `POST /sales/quote` body: `{cartId, lineDiscounts?, transactionDiscount?}`.

Expected numbers (2×BURGER, VAT 15% exclusive): 10% discount → discountTotal 6.00, tax 8.10, grand 62.10. AMOUNT 5.00 → discountTotal 5.00, grand 63.25. PERCENT 50 → discountTotal 30.00, grand 34.50.

- [ ] **Step 1: Write the failing service test**

Create `src/test/java/com/company/pos/sales/SalesQuoteDiscountTest.java`:

```java
package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.cart.api.CartService;
import com.company.pos.common.exception.DomainException;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.DiscountType;
import com.company.pos.sales.api.QuoteView;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
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

/**
 * The discount-aware quote overload: a pure calculator that prices any discount (no cashier
 * cap — checkout is the enforcement point) but still validates reason codes.
 */
@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class SalesQuoteDiscountTest {

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

    private UUID cartWithTwoBurgers() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "BURGER", new BigDecimal("2")); // 60.00 net, 15% VAT exclusive
        return cart;
    }

    @Test
    void percentDiscountQuotesDiscountedTotals() {
        UUID cart = cartWithTwoBurgers();
        QuoteView q = sales.quote(cart, Map.of(),
                new DiscountInput(DiscountType.PERCENT, new BigDecimal("10"), "LOYALTY"), false);
        assertThat(q.discountTotal()).isEqualByComparingTo("6.00");
        assertThat(q.taxTotal()).isEqualByComparingTo("8.10");
        assertThat(q.grandTotal()).isEqualByComparingTo("62.10");
        assertThat(carts.getCart(cart).status()).isEqualTo("OPEN"); // quote commits nothing
    }

    @Test
    void amountDiscountQuotesDiscountedTotals() {
        UUID cart = cartWithTwoBurgers();
        QuoteView q = sales.quote(cart, Map.of(),
                new DiscountInput(DiscountType.AMOUNT, new BigDecimal("5.00"), "PRICE_MATCH"), false);
        assertThat(q.discountTotal()).isEqualByComparingTo("5.00");
        assertThat(q.grandTotal()).isEqualByComparingTo("63.25");
    }

    @Test
    void overCapDiscountStillQuotes() {
        // 50% is far over the 10%/20.00 cashier caps — quote is a pure calculator and prices it.
        UUID cart = cartWithTwoBurgers();
        QuoteView q = sales.quote(cart, Map.of(),
                new DiscountInput(DiscountType.PERCENT, new BigDecimal("50"), "MANAGER_COMP"), false);
        assertThat(q.discountTotal()).isEqualByComparingTo("30.00");
        assertThat(q.grandTotal()).isEqualByComparingTo("34.50");
    }

    @Test
    void unknownReasonCodeRejectedAtQuoteTime() {
        UUID cart = cartWithTwoBurgers();
        assertThatThrownBy(() -> sales.quote(cart, Map.of(),
                new DiscountInput(DiscountType.PERCENT, new BigDecimal("10"), "NOT_A_CODE"), false))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("reason code");
    }

    @Test
    void quoteWithDiscountMatchesManagerCheckoutTotals() {
        UUID cart = cartWithTwoBurgers();
        DiscountInput d = new DiscountInput(DiscountType.PERCENT, new BigDecimal("10"), "LOYALTY");
        QuoteView q = sales.quote(cart, Map.of(), d, false);
        SaleView sale = sales.checkout(new CheckoutCommand(cart,
                List.of(new TenderInput(PaymentMethod.CASH, q.grandTotal(), q.grandTotal())),
                Map.of(), d, false), "manager1", true);
        assertThat(q.grandTotal()).isEqualByComparingTo(sale.grandTotal());
        assertThat(q.discountTotal()).isEqualByComparingTo(sale.discountTotal());
        assertThat(q.taxTotal()).isEqualByComparingTo(sale.taxTotal());
    }
}
```

- [ ] **Step 2: Run it to verify it fails to compile**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd "/Users/zuhairahamed/Desktop/Research & Development/POS"
./mvnw test -Dtest=SalesQuoteDiscountTest
```

Expected: COMPILE ERROR — no `quote(UUID, Map, DiscountInput, boolean)` on `SalesService`.

- [ ] **Step 3: Add the overload to the API and implementation**

In `src/main/java/com/company/pos/sales/api/SalesService.java`, add `java.util.Map` import and replace the two quote declarations' section with:

```java
    /**
     * Read-only pricing pass: prices the cart with NO discounts, applies tax, returns the totals.
     * Creates no sale, takes no payment, fires no event. Used to learn a cart's total up front
     * (e.g. to split it evenly).
     */
    QuoteView quote(UUID cartId);

    /** As {@link #quote(UUID)} but optionally applies the configured service charge. */
    QuoteView quote(UUID cartId, boolean applyServiceCharge);

    /**
     * As {@link #quote(UUID, boolean)} but priced WITH the given manual discounts, exactly as
     * checkout would apply them. Pure calculator: the cashier discount cap is NOT enforced here
     * (quote commits nothing; checkout is the sole enforcement point) but reason codes are still
     * validated. Null discount arguments mean "none".
     */
    QuoteView quote(UUID cartId, Map<String, DiscountInput> lineDiscounts,
            DiscountInput transactionDiscount, boolean applyServiceCharge);
```

(Interface import block becomes `java.util.Map` + `java.util.UUID`.)

In `src/main/java/com/company/pos/sales/application/DefaultSalesService.java`, replace the existing `quote(UUID, boolean)` method (lines 255-275) with:

```java
    @Override
    @Transactional(readOnly = true)
    public QuoteView quote(UUID cartId, boolean applyServiceCharge) {
        return quote(cartId, Map.of(), null, applyServiceCharge);
    }

    @Override
    @Transactional(readOnly = true)
    public QuoteView quote(UUID cartId, Map<String, DiscountInput> lineDiscounts,
            DiscountInput transactionDiscount, boolean applyServiceCharge) {
        CartView cart = carts.getCart(cartId);
        if (!"OPEN".equals(cart.status())) {
            throw DomainException.conflict("Cart " + cartId + " is not open");
        }
        if (cart.lines().isEmpty()) {
            throw DomainException.validation("Cannot quote an empty cart");
        }
        // Pure calculator: callerIsManager=true so the cashier cap never rejects a quote —
        // checkout is the sole enforcement point. Reason codes ARE validated (fail fast on a
        // typo at quote time rather than at tender time). Any DiscountOverride entries the
        // calculator records are discarded; only checkout publishes DiscountOverridden.
        PricedCart pc = priceDiscountTax(cart, lineDiscounts == null ? Map.of() : lineDiscounts,
                transactionDiscount, true, applyServiceCharge);
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
```

(The `quote(UUID)` one-arg method below stays exactly as-is.)

- [ ] **Step 4: Run the service test to verify it passes**

```bash
./mvnw test -Dtest=SalesQuoteDiscountTest
```

Expected: 5/5 PASS.

- [ ] **Step 5: Write the failing web test**

Add to `src/test/java/com/company/pos/sales/SalesQuoteControllerTest.java` (inside the class):

```java
    @Test
    void quoteAppliesTransactionDiscountFromBody() throws Exception {
        UUID cart = carts.createCart();
        carts.addLine(cart, "BURGER", new BigDecimal("2"));

        mvc.perform(post("/sales/quote").with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"cartId\":\"" + cart + "\",\"transactionDiscount\":"
                                + "{\"type\":\"PERCENT\",\"value\":10,\"reasonCode\":\"LOYALTY\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountTotal").value(6.00))
                .andExpect(jsonPath("$.grandTotal").value(62.10));
    }
```

Run: `./mvnw test -Dtest=SalesQuoteControllerTest`
Expected: the new test FAILS — `discountTotal` is 0.00 (the controller still drops the discount fields).

- [ ] **Step 6: Extend the controller request body**

In `src/main/java/com/company/pos/sales/web/SalesController.java`, add imports `com.company.pos.sales.api.DiscountInput` and `java.util.Map`, then replace the `QuoteRequest` record and `quote` method with:

```java
    /** Retail cart quote body. Discount fields are optional; nulls mean "no discount". */
    record QuoteRequest(java.util.UUID cartId, Map<String, DiscountInput> lineDiscounts,
            DiscountInput transactionDiscount) {
    }

    @PostMapping("/sales/quote")
    QuoteView quote(@RequestBody QuoteRequest body) {
        // Retail quote: service charge is DINE_IN only, so applyServiceCharge is false.
        return sales.quote(body.cartId(), body.lineDiscounts(), body.transactionDiscount(), false);
    }
```

- [ ] **Step 7: Run web + regression tests**

```bash
./mvnw test -Dtest='SalesQuoteControllerTest,SalesQuoteTest,SalesQuoteDiscountTest,CheckoutDiscountTest,ModularityTests'
```

Expected: all PASS (old no-discount quote requests `{"cartId":"..."}` still deserialize — the new fields are simply null).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/company/pos/sales src/test/java/com/company/pos/sales
git commit -m "feat(sales): discount-aware quote — pure calculator, cap enforced only at checkout

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Backend — dining `POST /dining/orders/{id}/quote` with discounts

**Files:**
- Modify: `src/main/java/com/company/pos/dining/api/DiningService.java:36-43`
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java:301-314`
- Modify: `src/main/java/com/company/pos/dining/web/DiningController.java:78-81`
- Create: `src/test/java/com/company/pos/dining/DiningQuoteDiscountTest.java`
- Modify: `src/test/java/com/company/pos/dining/DiningQuoteControllerTest.java`

**Interfaces:**
- Consumes: Task 1's `sales.quote(UUID, Map<String,DiscountInput>, DiscountInput, boolean)`.
- Produces: `QuoteView quoteOrder(UUID orderId, Map<String, DiscountInput> lineDiscounts, DiscountInput transactionDiscount)` on `DiningService`; endpoint `POST /dining/orders/{orderId}/quote` with body `{lineDiscounts?, transactionDiscount?}`. The existing GET stays as the no-discount convenience.

Expected numbers (2×BURGER 60.00, SC 10%, VAT 15% exclusive, 10% discount): discounted base 54.00 → line tax 8.10; SC = 10% of 54.00 = 5.40, SC tax 0.81 → discountTotal 6.00, serviceChargeAmount 5.40, taxTotal 8.91, grandTotal 68.31.

- [ ] **Step 1: Write the failing service test**

Create `src/test/java/com/company/pos/dining/DiningQuoteDiscountTest.java`:

```java
package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.CloseOrderCommand;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.ServiceType;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.DiscountType;
import com.company.pos.sales.api.QuoteView;
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

/** Discount-aware dine-in quote: prices exactly as close would (SC on the discounted base). */
@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class DiningQuoteDiscountTest {

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

    private UUID openDineIn() {
        UUID tableId = dining.registerTable(new RegisterTableCommand("L" + UUID.randomUUID(), 4)).id();
        UUID orderId = dining.openOrder(new OpenOrderCommand(tableId, ServiceType.DINE_IN), "alice").id();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("2"), null, null), "alice");
        return orderId; // 60.00 net; 10% discount -> base 54.00, SC 5.40, VAT 8.91, grand 68.31
    }

    @Test
    void quoteWithDiscountAppliesServiceChargeOnDiscountedBase() {
        UUID orderId = openDineIn();
        QuoteView q = dining.quoteOrder(orderId, Map.of(),
                new DiscountInput(DiscountType.PERCENT, new BigDecimal("10"), "LOYALTY"));
        assertThat(q.discountTotal()).isEqualByComparingTo("6.00");
        assertThat(q.serviceChargeAmount()).isEqualByComparingTo("5.40");
        assertThat(q.taxTotal()).isEqualByComparingTo("8.91");
        assertThat(q.grandTotal()).isEqualByComparingTo("68.31");
        assertThat(dining.getOrder(orderId).status().name()).isEqualTo("OPEN"); // read-only
    }

    @Test
    void quoteWithDiscountMatchesManagerCloseTotals() {
        UUID orderId = openDineIn();
        DiscountInput d = new DiscountInput(DiscountType.PERCENT, new BigDecimal("10"), "LOYALTY");
        QuoteView q = dining.quoteOrder(orderId, Map.of(), d);
        SaleView sale = dining.closeOrder(orderId,
                new CloseOrderCommand(
                        List.of(new TenderInput(PaymentMethod.CASH, q.grandTotal(), q.grandTotal())),
                        Map.of(), d, false),
                "manager1", true);
        assertThat(q.grandTotal()).isEqualByComparingTo(sale.grandTotal());
        assertThat(q.serviceChargeAmount()).isEqualByComparingTo(sale.serviceChargeAmount());
        assertThat(q.discountTotal()).isEqualByComparingTo(sale.discountTotal());
    }
}
```

- [ ] **Step 2: Run it to verify it fails to compile**

Run: `./mvnw test -Dtest=DiningQuoteDiscountTest`
Expected: COMPILE ERROR — no `quoteOrder(UUID, Map, DiscountInput)` on `DiningService`.

- [ ] **Step 3: Add the overload**

In `src/main/java/com/company/pos/dining/api/DiningService.java`, add imports `com.company.pos.sales.api.DiscountInput` and `java.util.Map`, and replace the `quoteOrder` declaration (with its javadoc, lines 36-43) with:

```java
    /** Read-only pricing pass for an open order: prices it exactly as {@link #closeOrder} would
     *  (same lines, same service-charge decision), returns the authoritative totals. Creates no
     *  sale, closes no order.
     *  <p>Assumes the no-discount, no-waiver close path. For a discounted close, use
     *  {@link #quoteOrder(UUID, Map, DiscountInput)} with the same inputs the close will carry.
     *  A manager service-charge waiver at close still has no quote counterpart (waiver UI is
     *  out of scope) — thread {@code waiveServiceCharge} through here if that ever lands. */
    QuoteView quoteOrder(UUID orderId);

    /** As {@link #quoteOrder(UUID)} but priced WITH the given manual discounts, exactly as a
     *  close carrying the same discounts would price them (cap not enforced — quote is a pure
     *  calculator; the close still requires a manager for over-cap discounts). */
    QuoteView quoteOrder(UUID orderId, Map<String, DiscountInput> lineDiscounts,
            DiscountInput transactionDiscount);
```

In `src/main/java/com/company/pos/dining/application/DefaultDiningService.java`, add import `com.company.pos.sales.api.DiscountInput`, and replace the existing `quoteOrder` method (lines 301-314) with:

```java
    @Override
    @Transactional
    public QuoteView quoteOrder(UUID orderId) {
        return quoteOrder(orderId, Map.of(), null);
    }

    @Override
    @Transactional
    public QuoteView quoteOrder(UUID orderId, Map<String, DiscountInput> lineDiscounts,
            DiscountInput transactionDiscount) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        if (order.getLines().isEmpty()) {
            throw DomainException.validation("Cannot quote an empty order");
        }
        UUID cartId = priceCartFor(order);
        boolean applyServiceCharge = resolveApplyServiceCharge(order, false, false);
        QuoteView quote = sales.quote(cartId, lineDiscounts, transactionDiscount, applyServiceCharge);
        carts.close(cartId);
        return quote;
    }
```

(Keep the method plain `@Transactional`, NOT `readOnly` — `priceCartFor` writes an ephemeral cart; `readOnly` throws on PostgreSQL.)

- [ ] **Step 4: Run the service test to verify it passes**

Run: `./mvnw test -Dtest=DiningQuoteDiscountTest`
Expected: 2/2 PASS.

- [ ] **Step 5: Write the failing web test**

Add to `src/test/java/com/company/pos/dining/DiningQuoteControllerTest.java` — a `post` import is needed: extend the existing static import line for `MockMvcRequestBuilders` to include `post` (`import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;` gains a sibling `...MockMvcRequestBuilders.post;`). Then add inside the class:

```java
    @Test
    void postQuoteAppliesTransactionDiscount() throws Exception {
        UUID tableId = dining.registerTable(new RegisterTableCommand("L" + UUID.randomUUID(), 4)).id();
        UUID orderId = dining.openOrder(new OpenOrderCommand(tableId, ServiceType.DINE_IN), "alice").id();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("2"), null, null), "alice");

        mvc.perform(post("/dining/orders/" + orderId + "/quote")
                        .with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"transactionDiscount\":"
                                + "{\"type\":\"PERCENT\",\"value\":10,\"reasonCode\":\"LOYALTY\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountTotal").value(6.00))
                .andExpect(jsonPath("$.serviceChargeAmount").value(5.40))
                .andExpect(jsonPath("$.grandTotal").value(68.31));
    }
```

Run: `./mvnw test -Dtest=DiningQuoteControllerTest`
Expected: new test FAILS with 405 Method Not Allowed (only GET is mapped on that path).

- [ ] **Step 6: Add the POST endpoint**

In `src/main/java/com/company/pos/dining/web/DiningController.java`, add imports `com.company.pos.sales.api.DiscountInput` and `java.util.Map`, then insert directly below the existing GET `quoteOrder` method (line 78-81):

```java
    /** Body for the discount-aware quote. Nulls mean "no discounts". */
    record QuoteOrderRequest(Map<String, DiscountInput> lineDiscounts,
            DiscountInput transactionDiscount) {
    }

    @PostMapping("/dining/orders/{orderId}/quote")
    QuoteView quoteOrderWithDiscounts(@PathVariable UUID orderId,
            @RequestBody QuoteOrderRequest body) {
        return dining.quoteOrder(orderId, body.lineDiscounts(), body.transactionDiscount());
    }
```

- [ ] **Step 7: Run web + regression + modularity tests**

```bash
./mvnw test -Dtest='DiningQuoteControllerTest,DiningQuoteDiscountTest,DiningQuoteServiceTest,DiningCloseServiceTest,ModularityTests'
```

Expected: all PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/company/pos/dining src/test/java/com/company/pos/dining
git commit -m "feat(dining): POST quote endpoint threads discounts through quoteOrder

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Backend — `GET /sales/discount-policy` + run-modes doc

**Files:**
- Create: `src/main/java/com/company/pos/sales/api/DiscountPolicyView.java`
- Modify: `src/main/java/com/company/pos/sales/web/SalesController.java`
- Create: `src/test/java/com/company/pos/sales/DiscountPolicyControllerTest.java`
- Modify: `docs/run-modes.md` (Manual Discounts section, after line 228)

**Interfaces:**
- Consumes: `ConfigurationService.getString(SettingKey)` with `DISCOUNT_CASHIER_MAX_PERCENT` (default "10"), `DISCOUNT_CASHIER_MAX_AMOUNT` (default "20.00"), `DISCOUNT_REASON_CODES` (default "DAMAGED,PRICE_MATCH,LOYALTY,MANAGER_COMP"). The `sales` module already depends on `configuration :: api`.
- Produces: `GET /sales/discount-policy` → `DiscountPolicyView(BigDecimal cashierMaxPercent, BigDecimal cashierMaxAmount, List<String> reasonCodes)` — the terminal's Task 4 consumes this JSON shape.

- [ ] **Step 1: Write the failing web test**

Create `src/test/java/com/company/pos/sales/DiscountPolicyControllerTest.java`:

```java
package com.company.pos.sales;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class DiscountPolicyControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void policyExposesCapsAndReasonCodes() throws Exception {
        mvc.perform(get("/sales/discount-policy").with(jwt().jwt(j -> j.subject("cashier1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashierMaxPercent").value(10))
                .andExpect(jsonPath("$.cashierMaxAmount").value(20.00))
                .andExpect(jsonPath("$.reasonCodes.length()").value(4))
                .andExpect(jsonPath("$.reasonCodes[0]").value("DAMAGED"))
                .andExpect(jsonPath("$.reasonCodes[2]").value("LOYALTY"));
    }

    @Test
    void anonymousPolicyRejected() throws Exception {
        mvc.perform(get("/sales/discount-policy")).andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=DiscountPolicyControllerTest`
Expected: FAIL — first test gets 401/404 (no such endpoint; an unmatched authenticated route 404s).

- [ ] **Step 3: Add the view record and the endpoint**

Create `src/main/java/com/company/pos/sales/api/DiscountPolicyView.java`:

```java
package com.company.pos.sales.api;

import java.math.BigDecimal;
import java.util.List;

/** The store's discount policy for terminal UIs: cashier caps + the valid reason codes. */
public record DiscountPolicyView(BigDecimal cashierMaxPercent, BigDecimal cashierMaxAmount,
        List<String> reasonCodes) {
}
```

In `src/main/java/com/company/pos/sales/web/SalesController.java`: add imports `com.company.pos.configuration.api.ConfigurationService`, `com.company.pos.configuration.api.SettingKey`, `com.company.pos.sales.api.DiscountPolicyView`, `java.math.BigDecimal`, `java.util.Arrays`, `java.util.List`; inject the config service and add the endpoint:

```java
    private final SalesService sales;
    private final ConfigurationService config;

    SalesController(SalesService sales, ConfigurationService config) {
        this.sales = sales;
        this.config = config;
    }
```

and (below the `quote` method):

```java
    /** Discount policy for terminal UIs: reason-code chips + a local "needs approval" hint.
     *  Advisory only — checkout re-enforces the cap server-side regardless. */
    @GetMapping("/sales/discount-policy")
    DiscountPolicyView discountPolicy() {
        List<String> codes = Arrays.stream(
                        config.getString(SettingKey.DISCOUNT_REASON_CODES).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new DiscountPolicyView(
                new BigDecimal(config.getString(SettingKey.DISCOUNT_CASHIER_MAX_PERCENT)),
                new BigDecimal(config.getString(SettingKey.DISCOUNT_CASHIER_MAX_AMOUNT)),
                codes);
    }
```

- [ ] **Step 4: Run tests**

```bash
./mvnw test -Dtest='DiscountPolicyControllerTest,SalesControllerTest,SalesQuoteControllerTest,ModularityTests'
```

Expected: all PASS.

- [ ] **Step 5: Document the three new surfaces in run-modes.md**

In `docs/run-modes.md`, append to the end of the "Manual Discounts (Phase 5)" section (after the line "cap gets HTTP 400.", line 228):

```markdown

**Discount-aware quotes (terminal slice 5)** — both quote surfaces accept the same optional
discount fields as their checkout counterparts, and price them identically (shared code path):

- `POST /sales/quote` body: `{ cartId, lineDiscounts?, transactionDiscount? }`.
- `POST /dining/orders/{orderId}/quote` body: `{ lineDiscounts?, transactionDiscount? }`
  (the discount-less `GET /dining/orders/{orderId}/quote` remains).

Quotes are pure calculators: the cashier cap is **not** enforced at quote time (a quote commits
nothing), but reason codes are validated. Checkout/close remain the sole enforcement point.

**`GET /sales/discount-policy`** returns `{ cashierMaxPercent, cashierMaxAmount, reasonCodes }`
so terminals can render reason-code choices and prompt for manager approval before tendering.
Advisory only — the server still enforces the cap at checkout.
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/sales src/test/java/com/company/pos/sales docs/run-modes.md
git commit -m "feat(sales): GET /sales/discount-policy exposes cashier caps + reason codes

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Terminal — typed discount DTOs + discount-aware API methods

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/DiscountInput.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/DiscountPolicyView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/QuoteRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/QuoteOrderRequest.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/CheckoutRequest.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/CloseOrderRequest.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/SalesApi.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/QuoteApiTest.java` (extend), `pos-terminal/src/test/java/com/company/pos/terminal/api/SalesApiTest.java` (extend)

**Interfaces:**
- Consumes: server JSON shapes from Tasks 1-3 (`DiscountInput {type,value,reasonCode}` with type string `"PERCENT"|"AMOUNT"`; policy `{cashierMaxPercent,cashierMaxAmount,reasonCodes}`).
- Produces: terminal `DiscountInput(String type, BigDecimal value, String reasonCode)`; `DiscountPolicyView(BigDecimal cashierMaxPercent, BigDecimal cashierMaxAmount, List<String> reasonCodes)`; `SalesApi.quote(UUID cartId, DiscountInput transactionDiscount)`; `SalesApi.discountPolicy()`; `DiningApi.quoteOrder(UUID orderId, DiscountInput transactionDiscount)`; `CheckoutRequest`/`CloseOrderRequest` with typed `Map<String, DiscountInput> lineDiscounts, DiscountInput transactionDiscount`. Tasks 6-9 depend on these exact names.

- [ ] **Step 1: Write the failing tests**

Add to `pos-terminal/src/test/java/com/company/pos/terminal/api/QuoteApiTest.java` (needs import `com.company.pos.terminal.api.dto.DiscountInput` and `com.company.pos.terminal.api.dto.DiscountPolicyView`):

```java
    @Test
    void salesQuoteSerializesTransactionDiscount() throws Exception {
        UUID cart = UUID.fromString("11111111-1111-1111-1111-111111111111");
        try (StubServer stub = new StubServer(200, QUOTE_JSON, "application/json")) {
            SalesApi api = new SalesApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            api.quote(cart, new DiscountInput("PERCENT", new BigDecimal("10"), "LOYALTY"));
            assertEquals("POST", stub.lastMethod);
            assertEquals("/sales/quote", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"cartId\":\"11111111-1111-1111-1111-111111111111\""));
            assertTrue(stub.lastBody.contains("\"type\":\"PERCENT\""));
            assertTrue(stub.lastBody.contains("\"value\":10"));
            assertTrue(stub.lastBody.contains("\"reasonCode\":\"LOYALTY\""));
        }
    }

    @Test
    void diningQuotePostsDiscountBodyToQuoteEndpoint() throws Exception {
        UUID order = UUID.fromString("22222222-2222-2222-2222-222222222222");
        try (StubServer stub = new StubServer(200, QUOTE_JSON, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            api.quoteOrder(order, new DiscountInput("AMOUNT", new BigDecimal("5.00"), "PRICE_MATCH"));
            assertEquals("POST", stub.lastMethod);
            assertEquals("/dining/orders/22222222-2222-2222-2222-222222222222/quote", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"type\":\"AMOUNT\""));
            assertTrue(stub.lastBody.contains("\"reasonCode\":\"PRICE_MATCH\""));
        }
    }

    @Test
    void discountPolicyParsesCapsAndReasonCodes() throws Exception {
        String json = "{\"cashierMaxPercent\":10,\"cashierMaxAmount\":20.00,"
                + "\"reasonCodes\":[\"DAMAGED\",\"PRICE_MATCH\",\"LOYALTY\",\"MANAGER_COMP\"]}";
        try (StubServer stub = new StubServer(200, json, "application/json")) {
            SalesApi api = new SalesApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            DiscountPolicyView p = api.discountPolicy();
            assertEquals(0, new BigDecimal("10").compareTo(p.cashierMaxPercent()));
            assertEquals(0, new BigDecimal("20.00").compareTo(p.cashierMaxAmount()));
            assertEquals(4, p.reasonCodes().size());
            assertEquals("GET", stub.lastMethod);
            assertEquals("/sales/discount-policy", stub.lastPath);
        }
    }
```

Add to `pos-terminal/src/test/java/com/company/pos/terminal/api/SalesApiTest.java` (the file already imports `BigDecimal`, `List`, `Map` and defines the `CART_ID` and `SALE_JSON` constants; add the import `com.company.pos.terminal.api.dto.DiscountInput`):

```java
    @Test
    void checkoutSerializesTypedTransactionDiscount() throws Exception {
        try (StubServer stub = new StubServer(201, SALE_JSON, "application/json")) {
            SalesApi api = new SalesApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            api.checkout(new CheckoutRequest(CART_ID,
                    List.of(new TenderInput("CARD", new BigDecimal("62.10"), null)),
                    Map.of(),
                    new DiscountInput("PERCENT", new BigDecimal("10"), "LOYALTY"),
                    false));
            assertTrue(stub.lastBody.contains("\"transactionDiscount\":{"));
            assertTrue(stub.lastBody.contains("\"reasonCode\":\"LOYALTY\""));
        }
    }
```

- [ ] **Step 2: Run to verify compile failure**

```bash
cd "/Users/zuhairahamed/Desktop/Research & Development/POS"
./mvnw -f pos-terminal/pom.xml test -Dtest='QuoteApiTest,SalesApiTest'
```

Expected: COMPILE ERROR — `DiscountInput`/`DiscountPolicyView` don't exist.

- [ ] **Step 3: Create the DTOs and API methods**

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/DiscountInput.java`:

```java
package com.company.pos.terminal.api.dto;

import java.math.BigDecimal;

/**
 * Mirrors the server's sales DiscountInput field-for-field. {@code type} is the server enum
 * name: "PERCENT" (value = percentage, e.g. 10) or "AMOUNT" (value = absolute deduction).
 * {@code reasonCode} is required and must be one of the policy's reason codes.
 */
public record DiscountInput(String type, BigDecimal value, String reasonCode) {
}
```

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/DiscountPolicyView.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

/** Server discount policy (GET /sales/discount-policy): cashier caps + valid reason codes.
 *  Advisory only — the server re-enforces the cap at checkout. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DiscountPolicyView(BigDecimal cashierMaxPercent, BigDecimal cashierMaxAmount,
        List<String> reasonCodes) {
}
```

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/QuoteRequest.java`:

```java
package com.company.pos.terminal.api.dto;

import java.util.Map;
import java.util.UUID;

/** POST /sales/quote body. lineDiscounts stays empty in this slice (transaction discount only). */
public record QuoteRequest(UUID cartId, Map<String, DiscountInput> lineDiscounts,
        DiscountInput transactionDiscount) {
}
```

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/QuoteOrderRequest.java`:

```java
package com.company.pos.terminal.api.dto;

import java.util.Map;

/** POST /dining/orders/{id}/quote body — the discount-aware dine-in quote. */
public record QuoteOrderRequest(Map<String, DiscountInput> lineDiscounts,
        DiscountInput transactionDiscount) {
}
```

Replace `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/CheckoutRequest.java`:

```java
package com.company.pos.terminal.api.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * POST /sales body. Mirrors the backend CheckoutCommand. As of slice 5 the transaction
 * discount is typed; lineDiscounts stays empty (per-line discounts are a later slice).
 * applyServiceCharge is forced false server-side for retail; we send false for honesty.
 */
public record CheckoutRequest(UUID cartId, List<TenderInput> tenders,
        Map<String, DiscountInput> lineDiscounts, DiscountInput transactionDiscount,
        boolean applyServiceCharge) {
}
```

Replace `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/CloseOrderRequest.java`:

```java
package com.company.pos.terminal.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Body for {@code POST /dining/orders/{id}/close}. Mirrors the server's {@code CloseOrderCommand}
 * (field order: tenders, lineDiscounts, transactionDiscount, waiveServiceCharge). As of slice 5
 * the transaction discount is typed; lineDiscounts stays empty.
 */
public record CloseOrderRequest(List<TenderInput> tenders, Map<String, DiscountInput> lineDiscounts,
        DiscountInput transactionDiscount, boolean waiveServiceCharge) {
}
```

In `pos-terminal/src/main/java/com/company/pos/terminal/api/SalesApi.java`: add imports `com.company.pos.terminal.api.dto.DiscountInput`, `com.company.pos.terminal.api.dto.DiscountPolicyView`, `com.company.pos.terminal.api.dto.QuoteRequest`; replace the `quote` method with:

```java
    /** POST /sales/quote — authoritative totals for a retail cart (service charge off). */
    public QuoteView quote(UUID cartId) {
        return quote(cartId, null);
    }

    /** As {@link #quote(UUID)} but priced with a whole-sale discount ({@code null} = none). */
    public QuoteView quote(UUID cartId, DiscountInput transactionDiscount) {
        return client.post("/sales/quote", new QuoteRequest(cartId, Map.of(), transactionDiscount),
                new TypeReference<QuoteView>() {});
    }

    /** GET /sales/discount-policy — cashier caps + reason codes for the discount dialog. */
    public DiscountPolicyView discountPolicy() {
        return client.get("/sales/discount-policy", new TypeReference<DiscountPolicyView>() {});
    }
```

In `pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java`: add imports `com.company.pos.terminal.api.dto.DiscountInput`, `com.company.pos.terminal.api.dto.QuoteOrderRequest`, `java.util.Map`; add below the existing `quoteOrder`:

```java
    /** POST /dining/orders/{id}/quote — quote priced with a whole-sale discount ({@code null} = none). */
    public QuoteView quoteOrder(UUID orderId, DiscountInput transactionDiscount) {
        return client.post("/dining/orders/" + orderId + "/quote",
                new QuoteOrderRequest(Map.of(), transactionDiscount),
                new TypeReference<QuoteView>() {});
    }
```

No other files need edits: all four existing `new CheckoutRequest(...)`/`new CloseOrderRequest(...)` sites (PaymentController, SalesApiTest, DiningApiTest) pass `Map.of()` and `null`, which infer/widen to the typed signature.

- [ ] **Step 4: Run the terminal suite**

Run: `./mvnw -f pos-terminal/pom.xml test`
Expected: all PASS (115 existing + 4 new). The pre-existing `salesQuotePostsCartIdAndParsesGrandTotal` still passes: the new body shape still contains `"cartId":"..."`.

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src
git commit -m "feat(terminal): typed discount DTOs + discount-aware quote/policy API methods

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Terminal — per-request bearer override + one-shot `pinLoginForToken`

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/ApiClient.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ManagerAuth.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/AuthApi.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/SalesApi.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/ApiClientTest.java` (extend), `pos-terminal/src/test/java/com/company/pos/terminal/api/AuthApiTest.java` (extend)

**Interfaces:**
- Produces: `ApiClient.get(String, TypeReference<T>, String bearerToken)` and `ApiClient.post(String, Object, TypeReference<T>, String bearerToken)` — `bearerToken == null` means session semantics (session token attached, 401 clears the session); non-null means that token is attached and **401 never clears the session**. `AuthApi.pinLoginForToken(String cashierCode, String pin)` → `ManagerAuth(String token, String username, Set<String> roles)` with `isManager()`; never mutates `SessionManager`. `SalesApi.checkout(CheckoutRequest, String bearerToken)`, `DiningApi.close(UUID, CloseOrderRequest, String bearerToken)` — null token = session path. Task 9 depends on these.

- [ ] **Step 1: Write the failing tests**

Add to `pos-terminal/src/test/java/com/company/pos/terminal/api/ApiClientTest.java` (follow its existing imports; it already uses StubServer + SessionManager):

```java
    @Test
    void postWithTokenOverrideSendsThatBearerInsteadOfSession() throws Exception {
        try (StubServer stub = new StubServer(200, "{}", "application/json")) {
            SessionManager session = new SessionManager();
            session.setToken("cashier-jwt");
            ApiClient client = new ApiClient(stub.baseUrl(), session);
            client.post("/sales", java.util.Map.of(),
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {},
                    "manager-jwt");
            assertEquals("Bearer manager-jwt", stub.lastAuth);
        }
    }

    @Test
    void unauthorizedOnOverriddenCallDoesNotClearTheCashierSession() throws Exception {
        try (StubServer stub = new StubServer(401, null, null)) {
            SessionManager session = new SessionManager();
            session.setToken("cashier-jwt");
            ApiClient client = new ApiClient(stub.baseUrl(), session);
            ApiException ex = assertThrows(ApiException.class, () -> client.post("/sales",
                    java.util.Map.of(),
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {},
                    "bad-manager-jwt"));
            assertEquals(401, ex.status());
            assertEquals("cashier-jwt", session.token()); // the cashier stays signed in
            assertTrue(session.isAuthenticated());
        }
    }

    @Test
    void unauthorizedOnSessionCallStillClearsTheSession() throws Exception {
        try (StubServer stub = new StubServer(401, null, null)) {
            SessionManager session = new SessionManager();
            session.setToken("stale-jwt");
            ApiClient client = new ApiClient(stub.baseUrl(), session);
            assertThrows(ApiException.class, () -> client.get("/products",
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}));
            assertNull(session.token());
        }
    }
```

Add to `pos-terminal/src/test/java/com/company/pos/terminal/api/AuthApiTest.java` (add import `com.company.pos.terminal.api.dto.ManagerAuth` if the wildcard dto import doesn't already cover it):

```java
    @Test
    void pinLoginForTokenDoesNotTouchTheSession() throws Exception {
        // Stub returns the same body for POST /auth/pin-login and GET /auth/me.
        String body = "{\"token\":\"mgr-jwt\",\"username\":\"boss\",\"roles\":[\"MANAGER\"]}";
        try (StubServer stub = new StubServer(200, body, "application/json")) {
            SessionManager session = new SessionManager();
            session.setToken("cashier-jwt");
            session.setUser("alice", java.util.Set.of("CASHIER"));
            AuthApi auth = new AuthApi(new ApiClient(stub.baseUrl(), session), session);

            ManagerAuth mgr = auth.pinLoginForToken("M01", "9999");

            assertEquals("mgr-jwt", mgr.token());
            assertTrue(mgr.isManager());
            // The cashier session is untouched:
            assertEquals("cashier-jwt", session.token());
            assertEquals("alice", session.username());
            assertFalse(session.isManager());
            // /auth/me was called with the MANAGER token, not the session token:
            StubServer.RecordedRequest me = stub.requestTo("GET", "/auth/me");
            assertNotNull(me);
            assertEquals("Bearer mgr-jwt", me.authorization());
        }
    }

    @Test
    void pinLoginForTokenReportsNonManagerRoles() throws Exception {
        String body = "{\"token\":\"jwt-c\",\"username\":\"carl\",\"roles\":[\"CASHIER\"]}";
        try (StubServer stub = new StubServer(200, body, "application/json")) {
            SessionManager session = new SessionManager();
            AuthApi auth = new AuthApi(new ApiClient(stub.baseUrl(), session), session);
            assertFalse(auth.pinLoginForToken("C01", "1234").isManager());
        }
    }
```

- [ ] **Step 2: Run to verify compile failure**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest='ApiClientTest,AuthApiTest'`
Expected: COMPILE ERROR — no 4-arg `post`, no `ManagerAuth`, no `pinLoginForToken`.

- [ ] **Step 3: Implement**

In `pos-terminal/src/main/java/com/company/pos/terminal/api/ApiClient.java`, replace the four public verb methods and `send` with:

```java
    public <T> T get(String path, TypeReference<T> type) { return send("GET", path, null, type, null); }
    public <T> T post(String path, Object body, TypeReference<T> type) { return send("POST", path, body, type, null); }
    public <T> T put(String path, Object body, TypeReference<T> type) { return send("PUT", path, body, type, null); }
    public void delete(String path) { send("DELETE", path, null, null, null); }

    /** As {@link #get(String, TypeReference)} but authenticated with {@code bearerToken} instead of
     *  the session token ({@code null} = session semantics). A 401 on an overridden call never
     *  clears the cashier session. */
    public <T> T get(String path, TypeReference<T> type, String bearerToken) {
        return send("GET", path, null, type, bearerToken);
    }

    /** As {@link #post(String, Object, TypeReference)} with the same override semantics as
     *  {@link #get(String, TypeReference, String)}. Used for one-shot manager-approved calls. */
    public <T> T post(String path, Object body, TypeReference<T> type, String bearerToken) {
        return send("POST", path, body, type, bearerToken);
    }

    private <T> T send(String method, String path, Object body, TypeReference<T> type,
            String tokenOverride) {
        try {
            HttpRequest.BodyPublisher pub = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8);
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .method(method, pub);
            if (body != null) b.header("Content-Type", "application/json");
            if (tokenOverride != null) b.header("Authorization", "Bearer " + tokenOverride);
            else if (session.token() != null) b.header("Authorization", "Bearer " + session.token());

            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            // Only a SESSION-authenticated 401 signs the cashier out; a rejected one-shot
            // override token is the override's problem, not the session's.
            if (sc == 401 && tokenOverride == null) session.clear();
            if (sc < 200 || sc >= 300) throw toApiException(sc, resp.body());
            if (type == null || resp.body() == null || resp.body().isBlank()) return null;
            return mapper.readValue(resp.body(), type);
        } catch (ApiException e) {
            throw e;
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ApiException(0, null, "Cannot reach store server: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new ApiException(-1, null, "Client error: " + e.getMessage());
        }
    }
```

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ManagerAuth.java`:

```java
package com.company.pos.terminal.api.dto;

import java.util.Set;

/** One-shot elevation result: a manager's JWT used for a single call, never stored in the session. */
public record ManagerAuth(String token, String username, Set<String> roles) {
    public boolean isManager() {
        return roles != null && (roles.contains("MANAGER") || roles.contains("ADMIN"));
    }
}
```

In `pos-terminal/src/main/java/com/company/pos/terminal/api/AuthApi.java`, add below `pinLogin` (the wildcard dto import already covers `ManagerAuth`):

```java
    /**
     * One-shot PIN login for manager approval: exchanges credentials for the manager's token +
     * roles WITHOUT touching the {@link SessionManager} — the signed-in cashier stays signed in.
     * The caller attaches the returned token to exactly one request and discards it. A wrong PIN
     * is HTTP 400 from the server (surfaced as ApiException), never a session-clearing 401.
     */
    public ManagerAuth pinLoginForToken(String cashierCode, String pin) {
        TokenResponse t = client.post("/auth/pin-login", new PinLoginRequest(cashierCode, pin),
                new TypeReference<TokenResponse>() {});
        MeResponse me = client.get("/auth/me", new TypeReference<MeResponse>() {}, t.token());
        return new ManagerAuth(t.token(), me.username(), me.roles());
    }
```

In `pos-terminal/src/main/java/com/company/pos/terminal/api/SalesApi.java`, add below the 1-arg `checkout`:

```java
    /** As {@link #checkout(CheckoutRequest)} but authenticated with a one-shot manager token
     *  ({@code null} = the signed-in cashier's session token). */
    public SaleView checkout(CheckoutRequest req, String bearerToken) {
        return client.post("/sales", req, new TypeReference<SaleView>() {}, bearerToken);
    }
```

In `pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java`, add below the 2-arg `close`:

```java
    /** As {@link #close(UUID, CloseOrderRequest)} but authenticated with a one-shot manager token
     *  ({@code null} = the signed-in cashier's session token). */
    public SaleView close(UUID orderId, CloseOrderRequest req, String bearerToken) {
        return client.post("/dining/orders/" + orderId + "/close", req,
                new TypeReference<SaleView>() {}, bearerToken);
    }
```

- [ ] **Step 4: Run the terminal suite**

Run: `./mvnw -f pos-terminal/pom.xml test`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src
git commit -m "feat(terminal): one-shot manager token — bearer override that never clears the session

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Terminal — `DiscountRules` (client-side cap mirror)

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/DiscountRules.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/DiscountRulesTest.java`

**Interfaces:**
- Consumes: Task 4's `DiscountInput`, `DiscountPolicyView`.
- Produces: `DiscountRules.needsApproval(DiscountInput d, BigDecimal base, DiscountPolicyView policy, boolean isManager)` and `DiscountRules.amountOf(DiscountInput d, BigDecimal base)`. Tasks 7 and 9 depend on `needsApproval`. `base` is the **undiscounted** quoted subtotal — the same base the server's `DiscountCalculator` uses (the terminal has no line discounts, so pre-discount subtotal == the server's `cartBase`).

- [ ] **Step 1: Write the failing test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/DiscountRulesTest.java`:

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.DiscountInput;
import com.company.pos.terminal.api.dto.DiscountPolicyView;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Mirrors the server's DiscountCalculator cap check (amount > maxAmount OR pct > maxPercent). */
class DiscountRulesTest {

    private static final DiscountPolicyView POLICY = new DiscountPolicyView(
            new BigDecimal("10"), new BigDecimal("20.00"),
            List.of("DAMAGED", "PRICE_MATCH", "LOYALTY", "MANAGER_COMP"));
    private static final BigDecimal BASE = new BigDecimal("60.00");

    @Test
    void percentExactlyAtCapNeedsNoApproval() {
        // 10% of 60.00 = 6.00: pct == maxPercent, amount <= maxAmount — the cap is exceeded, not met.
        assertFalse(DiscountRules.needsApproval(
                new DiscountInput("PERCENT", new BigDecimal("10"), "LOYALTY"), BASE, POLICY, false));
    }

    @Test
    void percentJustOverCapNeedsApproval() {
        assertTrue(DiscountRules.needsApproval(
                new DiscountInput("PERCENT", new BigDecimal("11"), "LOYALTY"), BASE, POLICY, false));
    }

    @Test
    void amountOverAbsoluteCapNeedsApproval() {
        // 25.00 > maxAmount 20.00 even though 25/60 = 41.67% is also over — either trips it.
        assertTrue(DiscountRules.needsApproval(
                new DiscountInput("AMOUNT", new BigDecimal("25.00"), "DAMAGED"), BASE, POLICY, false));
    }

    @Test
    void amountUnderAbsoluteCapCanStillTripThePercentCap() {
        // 10.00 <= 20.00 but 10/60 = 16.67% > 10% — mirrors the server's dual check.
        assertTrue(DiscountRules.needsApproval(
                new DiscountInput("AMOUNT", new BigDecimal("10.00"), "DAMAGED"), BASE, POLICY, false));
    }

    @Test
    void smallAmountNeedsNoApproval() {
        // 5.00 <= 20.00 and 5/60 = 8.33% <= 10%.
        assertFalse(DiscountRules.needsApproval(
                new DiscountInput("AMOUNT", new BigDecimal("5.00"), "DAMAGED"), BASE, POLICY, false));
    }

    @Test
    void managerNeverNeedsApproval() {
        assertFalse(DiscountRules.needsApproval(
                new DiscountInput("PERCENT", new BigDecimal("50"), "MANAGER_COMP"), BASE, POLICY, true));
    }

    @Test
    void nullDiscountOrPolicyNeedsNoApproval() {
        // No policy (fetch failed) -> no local prompt; the server still rejects at checkout and
        // the controller's defensive retry path opens the approval dialog then.
        assertFalse(DiscountRules.needsApproval(null, BASE, POLICY, false));
        assertFalse(DiscountRules.needsApproval(
                new DiscountInput("PERCENT", new BigDecimal("50"), "LOYALTY"), BASE, null, false));
    }

    @Test
    void amountOfResolvesPercentAndCapsAmountAtBase() {
        assertEquals(0, new BigDecimal("6.00").compareTo(DiscountRules.amountOf(
                new DiscountInput("PERCENT", new BigDecimal("10"), "LOYALTY"), BASE)));
        assertEquals(0, new BigDecimal("60.00").compareTo(DiscountRules.amountOf(
                new DiscountInput("AMOUNT", new BigDecimal("99.00"), "LOYALTY"), BASE)));
    }
}
```

- [ ] **Step 2: Run to verify compile failure**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=DiscountRulesTest`
Expected: COMPILE ERROR — `DiscountRules` does not exist.

- [ ] **Step 3: Implement**

Create `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/DiscountRules.java`:

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.dto.DiscountInput;
import com.company.pos.terminal.api.dto.DiscountPolicyView;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Client-side mirror of the server's cashier discount cap, used only to decide WHEN to ask for
 * manager approval — never authoritative (the server re-enforces the cap at checkout).
 * Mirrors DiscountCalculator: the cap is exceeded when the resolved amount is over the absolute
 * cap OR its effective percentage of the pre-discount base is over the percent cap.
 */
public final class DiscountRules {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private DiscountRules() {}

    /** The monetary amount {@code d} resolves to against {@code base} (the pre-discount subtotal).
     *  AMOUNT discounts cap at the base, matching the server. */
    public static BigDecimal amountOf(DiscountInput d, BigDecimal base) {
        if (d == null || d.value() == null || base == null) {
            return BigDecimal.ZERO;
        }
        if ("PERCENT".equals(d.type())) {
            return base.multiply(d.value()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        }
        return d.value().setScale(2, RoundingMode.HALF_UP).min(base);
    }

    /** True when the discount exceeds the cashier cap and the signed-in user is not a manager.
     *  A null policy (fetch failed) yields false — the server's checkout rejection then drives
     *  the controller's defensive approval path. */
    public static boolean needsApproval(DiscountInput d, BigDecimal base, DiscountPolicyView policy,
            boolean isManager) {
        if (d == null || policy == null || isManager) {
            return false;
        }
        BigDecimal amount = amountOf(d, base);
        if (amount.compareTo(policy.cashierMaxAmount()) > 0) {
            return true;
        }
        if (base != null && base.signum() > 0) {
            BigDecimal effectivePercent = amount.multiply(HUNDRED)
                    .divide(base, 2, RoundingMode.HALF_UP);
            return effectivePercent.compareTo(policy.cashierMaxPercent()) > 0;
        }
        return false;
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=DiscountRulesTest`
Expected: 8/8 PASS.

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src
git commit -m "feat(terminal): DiscountRules mirrors the server cashier-cap check for approval prompts

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: Terminal — `Keypads` extraction + `DiscountDialog` + `ManagerPinDialog`

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/Keypads.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/StartShiftDialog.java` (replace its private `pinPad` with `Keypads.numericPad`)
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/DiscountDialog.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/ManagerPinDialog.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/view/DiscountDialogBuildTest.java`, `pos-terminal/src/test/java/com/company/pos/terminal/view/ManagerPinDialogBuildTest.java`

**Interfaces:**
- Consumes: Task 4's `DiscountInput`/`DiscountPolicyView`; Task 6's `DiscountRules.needsApproval`.
- Produces: `DiscountDialog.promptForDiscount(DiscountPolicyView policy, BigDecimal base, boolean isManager)` → `Optional<DiscountInput>`; package-private `static DiscountInput build(String type, String rawValue, String reasonCode)`. `ManagerPinDialog.promptForApproval(String message)` → `Optional<ManagerPinDialog.Credentials>` where `Credentials(String cashierCode, String pin)`; package-private `static Credentials build(String code, String pin)`. Task 9 opens both. Dialogs are pure views (no server access) in the StartShiftDialog pattern; only the static `build` rules are unit-tested (headless CI can't show a Dialog).

- [ ] **Step 1: Write the failing build-rule tests**

Create `pos-terminal/src/test/java/com/company/pos/terminal/view/DiscountDialogBuildTest.java`:

```java
package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.DiscountInput;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Validation rule for the discount modal's Apply button (mirrors server-side constraints). */
class DiscountDialogBuildTest {

    @Test
    void validPercentBuilds() {
        DiscountInput d = DiscountDialog.build("PERCENT", "10", "LOYALTY");
        assertNotNull(d);
        assertEquals("PERCENT", d.type());
        assertEquals(0, new BigDecimal("10").compareTo(d.value()));
        assertEquals("LOYALTY", d.reasonCode());
    }

    @Test
    void validAmountBuilds() {
        DiscountInput d = DiscountDialog.build("AMOUNT", "5.50", "PRICE_MATCH");
        assertNotNull(d);
        assertEquals("AMOUNT", d.type());
        assertEquals(0, new BigDecimal("5.50").compareTo(d.value()));
    }

    @Test
    void garbageZeroAndNegativeValuesRejected() {
        assertNull(DiscountDialog.build("PERCENT", "abc", "LOYALTY"));
        assertNull(DiscountDialog.build("PERCENT", "", "LOYALTY"));
        assertNull(DiscountDialog.build("PERCENT", null, "LOYALTY"));
        assertNull(DiscountDialog.build("PERCENT", "0", "LOYALTY"));
        assertNull(DiscountDialog.build("AMOUNT", "-5", "LOYALTY"));
    }

    @Test
    void percentOverOneHundredRejectedButAmountIsNot() {
        assertNull(DiscountDialog.build("PERCENT", "101", "LOYALTY"));
        assertNotNull(DiscountDialog.build("AMOUNT", "101", "LOYALTY")); // server caps at base
    }

    @Test
    void missingReasonRejected() {
        assertNull(DiscountDialog.build("PERCENT", "10", null));
        assertNull(DiscountDialog.build("PERCENT", "10", " "));
    }
}
```

Create `pos-terminal/src/test/java/com/company/pos/terminal/view/ManagerPinDialogBuildTest.java`:

```java
package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ManagerPinDialogBuildTest {

    @Test
    void bothFieldsRequiredAndTrimmed() {
        assertNull(ManagerPinDialog.build(null, "1234"));
        assertNull(ManagerPinDialog.build("", "1234"));
        assertNull(ManagerPinDialog.build("M01", null));
        assertNull(ManagerPinDialog.build("M01", "  "));
        ManagerPinDialog.Credentials c = ManagerPinDialog.build(" M01 ", " 1234 ");
        assertNotNull(c);
        assertEquals("M01", c.cashierCode());
        assertEquals("1234", c.pin());
    }
}
```

- [ ] **Step 2: Run to verify compile failure**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest='DiscountDialogBuildTest,ManagerPinDialogBuildTest'`
Expected: COMPILE ERROR — the dialog classes don't exist.

- [ ] **Step 3: Implement Keypads + refactor StartShiftDialog**

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/Keypads.java`:

```java
package com.company.pos.terminal.view;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.GridPane;

/** Shared 3×4 numeric pad appending to a target field — the modal dialogs' common form language. */
final class Keypads {

    private Keypads() {}

    static GridPane numericPad(TextInputControl target) {
        GridPane pad = new GridPane();
        pad.getStyleClass().add("pin-pad");
        pad.setAlignment(Pos.CENTER);
        String[][] keys = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {".", "0", "⌫"}};
        for (int r = 0; r < keys.length; r++) {
            for (int c = 0; c < keys[r].length; c++) {
                String key = keys[r][c];
                Button b = new Button(key);
                b.getStyleClass().add("pin-key");
                if (".".equals(key) || "⌫".equals(key)) {
                    b.getStyleClass().add("pin-key-alt");
                }
                b.setOnAction(e -> {
                    String t = target.getText() == null ? "" : target.getText();
                    if ("⌫".equals(key)) {
                        if (!t.isEmpty()) {
                            target.setText(t.substring(0, t.length() - 1));
                        }
                    } else {
                        target.setText(t + key);
                    }
                });
                pad.add(b, c, r);
            }
        }
        return pad;
    }
}
```

In `pos-terminal/src/main/java/com/company/pos/terminal/view/StartShiftDialog.java`: delete the whole private `pinPad(TextField)` method (lines 84-112) and change the call site (line 57) from `pinPad(floatField)` to `Keypads.numericPad(floatField)`. Remove the now-unused imports `javafx.scene.layout.GridPane` (keep the rest — `Button` is still used by `denomRow`).

- [ ] **Step 4: Implement DiscountDialog**

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/DiscountDialog.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.DiscountInput;
import com.company.pos.terminal.api.dto.DiscountPolicyView;
import com.company.pos.terminal.viewmodel.DiscountRules;
import java.math.BigDecimal;
import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Modal whole-sale discount entry: percent/amount toggle, pin-pad value, and a required reason
 * chip from the store policy. Shows a live amber hint when the entry would exceed the cashier
 * cap (approval is then collected at tender time, not here). Pure view with no server access,
 * mirroring StartShiftDialog: the caller re-quotes with the returned discount.
 *
 * <p>Display-dependent (constructs a JavaFX Dialog) — exercised by the manual E2E; only the
 * static {@link #build} rule is unit-tested.
 */
public final class DiscountDialog {

    private DiscountDialog() {}

    /** {@code base} is the UNDISCOUNTED quoted subtotal (the server's cap base). */
    public static Optional<DiscountInput> promptForDiscount(DiscountPolicyView policy,
            BigDecimal base, boolean isManager) {
        Dialog<DiscountInput> dialog = new Dialog<>();
        dialog.setTitle("Discount");
        dialog.setHeaderText("Whole-sale discount");
        ButtonType apply = new ButtonType("Apply discount", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(apply, cancel);
        dialog.getDialogPane().getStyleClass().add("discount-modal");

        ToggleGroup typeGroup = new ToggleGroup();
        ToggleButton percent = new ToggleButton("% Percent");
        ToggleButton amount = new ToggleButton("SAR Amount");
        percent.setToggleGroup(typeGroup);
        amount.setToggleGroup(typeGroup);
        percent.getStyleClass().add("reason-chip");
        amount.getStyleClass().add("reason-chip");
        percent.setSelected(true);
        HBox typeRow = new HBox(8, percent, amount);
        typeRow.setAlignment(Pos.CENTER);

        Label valueLabel = new Label("Discount value");
        valueLabel.getStyleClass().add("field-label");
        TextField valueField = new TextField();
        valueField.setPromptText("0");
        valueField.getStyleClass().add("money");
        VBox valueBox = new VBox(6, valueLabel, valueField);
        valueBox.getStyleClass().add("field");

        ToggleGroup reasonGroup = new ToggleGroup();
        FlowPane reasons = new FlowPane(8, 8);
        reasons.setAlignment(Pos.CENTER);
        for (String code : policy.reasonCodes()) {
            ToggleButton chip = new ToggleButton(code);
            chip.setToggleGroup(reasonGroup);
            chip.getStyleClass().add("reason-chip");
            reasons.getChildren().add(chip);
        }

        Label hint = new Label("⚠ Needs manager approval at payment");
        hint.getStyleClass().add("approval-hint");
        hint.setVisible(false);
        hint.setManaged(false);

        VBox box = new VBox(16, typeRow, valueBox, Keypads.numericPad(valueField), reasons, hint);
        box.setAlignment(Pos.TOP_CENTER);
        dialog.getDialogPane().setContent(box);

        // Apply stays disabled until the entry builds; the hint tracks the cap live.
        javafx.scene.Node applyNode = dialog.getDialogPane().lookupButton(apply);
        Runnable revalidate = () -> {
            DiscountInput d = build(percent.isSelected() ? "PERCENT" : "AMOUNT",
                    valueField.getText(), selectedReason(reasonGroup));
            applyNode.setDisable(d == null);
            boolean needs = DiscountRules.needsApproval(d, base, policy, isManager);
            hint.setVisible(needs);
            hint.setManaged(needs);
        };
        valueField.textProperty().addListener((o, was, now) -> revalidate.run());
        typeGroup.selectedToggleProperty().addListener((o, was, now) -> revalidate.run());
        reasonGroup.selectedToggleProperty().addListener((o, was, now) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> bt == apply
                ? build(percent.isSelected() ? "PERCENT" : "AMOUNT", valueField.getText(),
                        selectedReason(reasonGroup))
                : null);
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    private static String selectedReason(ToggleGroup group) {
        Toggle t = group.getSelectedToggle();
        return t == null ? null : ((ToggleButton) t).getText();
    }

    /** A valid discount or null: positive numeric value, PERCENT ≤ 100, reason required. */
    static DiscountInput build(String type, String rawValue, String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return null;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(rawValue == null ? "" : rawValue.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (value.signum() <= 0) {
            return null;
        }
        if ("PERCENT".equals(type) && value.compareTo(new BigDecimal("100")) > 0) {
            return null;
        }
        return new DiscountInput(type, value, reasonCode);
    }
}
```

- [ ] **Step 5: Implement ManagerPinDialog**

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/ManagerPinDialog.java`:

```java
package com.company.pos.terminal.view;

import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Modal manager approval: collects the manager's code + PIN. Pure view — the CALLER exchanges
 * the credentials for a one-shot token off the FX thread (AuthApi.pinLoginForToken) and attaches
 * it to exactly one checkout call. The cashier session is never touched.
 *
 * <p>Display-dependent — exercised by the manual E2E; only the static {@link #build} rule is
 * unit-tested.
 */
public final class ManagerPinDialog {

    /** What the manager typed; exchanged for a one-shot token by the caller. */
    public record Credentials(String cashierCode, String pin) {}

    private ManagerPinDialog() {}

    public static Optional<Credentials> promptForApproval(String message) {
        Dialog<Credentials> dialog = new Dialog<>();
        dialog.setTitle("Manager approval");
        dialog.setHeaderText("Manager approval required");
        ButtonType approve = new ButtonType("Approve", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(approve, cancel);
        dialog.getDialogPane().getStyleClass().add("approval-modal");

        Label why = new Label(message);
        why.getStyleClass().add("approval-hint");
        why.setWrapText(true);

        Label codeLabel = new Label("Manager code");
        codeLabel.getStyleClass().add("field-label");
        TextField codeField = new TextField();
        codeField.setPromptText("e.g. M01");
        VBox codeBox = new VBox(6, codeLabel, codeField);
        codeBox.getStyleClass().add("field");

        Label pinLabel = new Label("PIN");
        pinLabel.getStyleClass().add("field-label");
        PasswordField pinField = new PasswordField();
        VBox pinBox = new VBox(6, pinLabel, pinField);
        pinBox.getStyleClass().add("field");

        VBox box = new VBox(16, why, codeBox, pinBox, Keypads.numericPad(pinField));
        box.setAlignment(Pos.TOP_CENTER);
        dialog.getDialogPane().setContent(box);

        javafx.scene.Node approveNode = dialog.getDialogPane().lookupButton(approve);
        Runnable revalidate = () -> approveNode.setDisable(
                build(codeField.getText(), pinField.getText()) == null);
        codeField.textProperty().addListener((o, was, now) -> revalidate.run());
        pinField.textProperty().addListener((o, was, now) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> bt == approve
                ? build(codeField.getText(), pinField.getText())
                : null);
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    /** Both fields required (trimmed); otherwise null (Approve stays disabled). */
    static Credentials build(String code, String pin) {
        if (code == null || code.isBlank() || pin == null || pin.isBlank()) {
            return null;
        }
        return new Credentials(code.trim(), pin.trim());
    }
}
```

- [ ] **Step 6: Run the terminal suite**

Run: `./mvnw -f pos-terminal/pom.xml test`
Expected: all PASS (including the existing `StartShiftDialogParseTest` — the Keypads refactor must not change `parse`).

- [ ] **Step 7: Commit**

```bash
git add pos-terminal/src
git commit -m "feat(terminal): discount + manager-PIN modals (pure views), shared Keypads pad

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: Terminal — payment screen: Discount button, chip row, re-quote

**Files:**
- Modify: `pos-terminal/src/main/resources/fxml/payment.fxml`
- Modify: `pos-terminal/src/main/resources/css/app.css` (append)
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/PaymentController.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/FxmlContractTest.java` (extend), `pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java` (extend)

**Interfaces:**
- Consumes: Task 4's `SalesApi.quote(UUID, DiscountInput)`, `DiningApi.quoteOrder(UUID, DiscountInput)`, `SalesApi.discountPolicy()`; Task 7's `DiscountDialog.promptForDiscount`.
- Produces: `PaymentController` fields `volatile DiscountInput discount`, `volatile DiscountPolicyView policy`, `volatile BigDecimal baseSubtotal` and methods `applyDiscount()/removeDiscount()/requote()/updateDiscountControls()` — Task 9 builds approval on top. New fx:ids: `discountButton`, `discountChipRow`, `discountChipLabel`, `removeDiscountButton`. New CSS classes: `.discount-chip`, `.approval-hint`, `.reason-chip`.

- [ ] **Step 1: Write the failing contract tests**

Add to `pos-terminal/src/test/java/com/company/pos/terminal/FxmlContractTest.java`:

```java
    @Test
    void paymentDeclaresDiscountControls() throws Exception {
        String fxml = resource("/fxml/payment.fxml");
        for (String id : new String[] {
            "discountButton", "discountChipRow", "discountChipLabel", "removeDiscountButton"
        }) {
            assertTrue(fxml.contains("fx:id=\"" + id + "\""), "missing discount control: " + id);
        }
    }
```

Add to `pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java`:

```java
    @Test
    void definesSliceFiveClasses() throws Exception {
        String css = css();
        for (String cls : new String[] {
            ".discount-chip", ".approval-hint", ".reason-chip"
        }) {
            assertTrue(css.contains(cls), "missing style class: " + cls);
        }
    }
```

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest='FxmlContractTest,AppCssTest'`
Expected: both new tests FAIL.

- [ ] **Step 2: Extend payment.fxml**

In `pos-terminal/src/main/resources/fxml/payment.fxml`, insert directly after the `remainingLabel` line (`<Label fx:id="remainingLabel" .../>`):

```xml
    <!-- Applied whole-sale discount (authoritative amount from the re-quote) + remove. -->
    <HBox fx:id="discountChipRow" spacing="8" alignment="CENTER" visible="false" managed="false" maxWidth="Infinity">
      <Label fx:id="discountChipLabel" styleClass="discount-chip,money"/>
      <Button fx:id="removeDiscountButton" text="✕" styleClass="btn-secondary"/>
    </HBox>
```

and replace the "Add partial tender / Cancel" HBox with a three-button row:

```xml
      <HBox spacing="12" alignment="CENTER" maxWidth="Infinity">
        <Button fx:id="discountButton" text="Discount" styleClass="btn-secondary" maxWidth="Infinity" HBox.hgrow="ALWAYS"/>
        <Button fx:id="addTenderButton" text="Add partial tender" styleClass="btn-secondary" maxWidth="Infinity" HBox.hgrow="ALWAYS"/>
        <Button fx:id="cancelButton" text="Cancel" styleClass="btn-secondary" maxWidth="Infinity" HBox.hgrow="ALWAYS"/>
      </HBox>
```

- [ ] **Step 3: Append the slice-5 styles to app.css**

Append to `pos-terminal/src/main/resources/css/app.css`:

```css

/* ---- Slice 5: whole-sale discount + manager approval ----------------------- */

/* Applied-discount chip under the total: "Discount −6.00 SAR · LOYALTY". */
.discount-chip {
    -fx-background-color: derive(-fx-accent, 82%);
    -fx-text-fill: -fx-ink;
    -fx-font-size: 15px;
    -fx-font-weight: bold;
    -fx-padding: 6 12 6 12;
    -fx-background-radius: 12;
}

/* Amber "needs manager approval" hint in the discount/approval modals. */
.approval-hint {
    -fx-text-fill: derive(-fx-accent, -25%);
    -fx-font-size: 15px;
    -fx-font-weight: bold;
}

/* Selectable type/reason chips in the discount modal (48px touch minimum). */
.reason-chip {
    -fx-min-height: 48px;
    -fx-font-size: 15px;
    -fx-background-color: -fx-canvas;
    -fx-text-fill: -fx-ink;
    -fx-border-color: -fx-border;
    -fx-border-radius: 8;
    -fx-background-radius: 8;
    -fx-cursor: hand;
}
.reason-chip:selected {
    -fx-background-color: -fx-primary;
    -fx-text-fill: white;
    -fx-border-color: -fx-primary;
}
.reason-chip:disabled { -fx-opacity: 0.55; }
```

- [ ] **Step 4: Wire the controller**

In `pos-terminal/src/main/java/com/company/pos/terminal/view/PaymentController.java`:

(a) Add imports `com.company.pos.terminal.api.dto.DiscountInput` and `com.company.pos.terminal.api.dto.DiscountPolicyView`.

(b) Add fields (below the `denomButtons` field):

```java
    @FXML private Button discountButton;
    @FXML private HBox discountChipRow;
    @FXML private Label discountChipLabel;
    @FXML private Button removeDiscountButton;
    // Read by background lambdas (quote fetch, checkout gateway) — hence volatile. Plain fields
    // are the synchronous source of truth; observables/labels are FX-thread mirrors.
    private volatile DiscountInput discount;
    private volatile DiscountPolicyView policy;
    private volatile BigDecimal baseSubtotal;
```

(c) In `gatewayFor`, pass the discount field (read at execution time, on the background thread):

```java
    private CheckoutGateway gatewayFor(Mode m, UUID id) {
        if (m == Mode.RETAIL) {
            return tenders -> services.salesApi.checkout(
                    new CheckoutRequest(id, tenders, Map.of(), discount, false));
        }
        return tenders -> services.diningApi.close(id,
                new CloseOrderRequest(tenders, Map.of(), discount, false));
    }
```

(d) In `initialize()`, add (next to the other button handlers):

```java
        discountButton.setOnAction(e -> applyDiscount());
        removeDiscountButton.setOnAction(e -> removeDiscount());
```

(e) Replace `loadQuote()` and `onQuoteLoaded(...)` with:

```java
    /** Fetch the authoritative quote off the FX thread — WITH the applied discount, if any.
     *  Also fetches the discount policy once (advisory: chips + approval hint only). */
    private void loadQuote() {
        final QuoteView[] holder = new QuoteView[1];
        FxTasks.run(
                () -> {
                    holder[0] = (mode == Mode.RETAIL)
                            ? services.salesApi.quote(id, discount)
                            : services.diningApi.quoteOrder(id, discount);
                    if (policy == null) {
                        try {
                            policy = services.salesApi.discountPolicy();
                        } catch (RuntimeException e) {
                            // Advisory only: without it the Discount button stays disabled and
                            // the server still enforces the cap at checkout.
                            LOG.log(System.Logger.Level.WARNING, "Discount policy unavailable", e);
                        }
                    }
                },
                () -> onQuoteLoaded(holder[0]),
                err -> {
                    vm.setError("Couldn't load the total — go back and try again");
                    LOG.log(System.Logger.Level.ERROR, "Quote fetch failed", err);
                });
    }

    private void onQuoteLoaded(QuoteView q) {
        String cur = q.currencyCode();
        totalLabel.setText("Total due: " + money(q.grandTotal(), cur));
        quoteBadge.setVisible(true);
        quoteBadge.setManaged(true);
        if (discount == null) {
            // The undiscounted subtotal is the server's cap base (no line discounts here);
            // captured only from discount-less quotes so re-quotes don't shrink it.
            baseSubtotal = q.subtotal();
        }
        boolean hasDiscount = discount != null && q.discountTotal() != null
                && q.discountTotal().signum() > 0;
        discountChipRow.setVisible(hasDiscount);
        discountChipRow.setManaged(hasDiscount);
        if (hasDiscount) {
            discountChipLabel.setText("Discount −" + money(q.discountTotal(), cur)
                    + " · " + discount.reasonCode());
        }
        vm.setAuthoritativeTotal(q.grandTotal());
        setTendersEnabled(true);
    }
```

(f) Add the discount actions (below `addPartial()`):

```java
    /** Opens the discount modal; applying re-fetches the authoritative quote WITH the discount. */
    private void applyDiscount() {
        if (policy == null || !quoteLoaded) {
            return;
        }
        DiscountDialog.promptForDiscount(policy, baseSubtotal, services.session.isManager())
                .ifPresent(d -> {
                    discount = d;
                    requote();
                });
    }

    private void removeDiscount() {
        discount = null;
        requote();
    }

    /** Re-lock tenders and fetch the quote again — the same gate as the initial load, so the
     *  big total is never a number the server hasn't confirmed. */
    private void requote() {
        setTendersEnabled(false);
        quoteBadge.setVisible(false);
        quoteBadge.setManaged(false);
        totalLabel.setText("Fetching total…");
        loadQuote();
    }
```

(g) Gate the new controls. Append to the end of `setTendersEnabled(boolean enabled)`:

```java
        updateDiscountControls(!enabled);
```

Append to the end of `setBusy(boolean busy)`:

```java
        updateDiscountControls(busy || !quoteLoaded);
```

Add the helper:

```java
    /** Discount can change only while quoted, idle, and before any tender exists — the total
     *  must not move under a partially-tendered split. */
    private void updateDiscountControls(boolean lockedByState) {
        boolean tendered = !vm.tenders().isEmpty();
        if (discountButton != null) {
            discountButton.setDisable(lockedByState || tendered || policy == null);
        }
        if (removeDiscountButton != null) {
            removeDiscountButton.setDisable(lockedByState || tendered);
        }
    }
```

And in `initialize()`, extend the tenders listener so the first tender locks the discount:

```java
        vm.tenders().addListener((javafx.collections.ListChangeListener<Object>) c -> {
            renderChips();
            updateDiscountControls(!quoteLoaded);
        });
```

(replacing the existing single-statement listener).

- [ ] **Step 5: Run the terminal suite**

Run: `./mvnw -f pos-terminal/pom.xml test`
Expected: all PASS (contract tests now green; controller compiles; no VM changes).

- [ ] **Step 6: Commit**

```bash
git add pos-terminal/src
git commit -m "feat(terminal): payment-screen discount — apply/remove re-quotes the authoritative total

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 9: Terminal — manager approval interception at tender time

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/PaymentController.java`

**Interfaces:**
- Consumes: Task 5's `AuthApi.pinLoginForToken` / `ManagerAuth` / `SalesApi.checkout(req, token)` / `DiningApi.close(id, req, token)`; Task 6's `DiscountRules.needsApproval`; Task 7's `ManagerPinDialog`.
- Produces: the complete approval flow. No new headless tests are possible for this glue (dialog + controller threading — the project's established manual-E2E territory); the covering evidence is the full-suite regression plus Task 10's documented manual E2E. All the logic underneath (rules, token plumbing, dialogs' build) is already unit-tested in Tasks 5-7.

- [ ] **Step 1: Wire the approval flow**

In `pos-terminal/src/main/java/com/company/pos/terminal/view/PaymentController.java`:

(a) Add imports `com.company.pos.terminal.api.ApiException` and `com.company.pos.terminal.api.dto.ManagerAuth`.

(b) Add the token field next to the other slice-5 fields:

```java
    // One-shot manager approval token: attached to exactly one checkout call, discarded on
    // success. Never stored in SessionManager — the cashier stays signed in.
    private volatile String pendingApprovalToken;
```

(c) Route the gateway through the token (replace `gatewayFor` from Task 8):

```java
    private CheckoutGateway gatewayFor(Mode m, UUID id) {
        if (m == Mode.RETAIL) {
            return tenders -> services.salesApi.checkout(
                    new CheckoutRequest(id, tenders, Map.of(), discount, false),
                    pendingApprovalToken);
        }
        return tenders -> services.diningApi.close(id,
                new CloseOrderRequest(tenders, Map.of(), discount, false),
                pendingApprovalToken);
    }
```

(d) Replace `pay(Runnable action)` with the intercepting version:

```java
    private void pay(Runnable action) {
        if (approvalNeeded()) {
            requestApprovalThen(action);
            return;
        }
        runPayment(action);
    }

    private void runPayment(Runnable action) {
        setBusy(true);
        FxTasks.run(action, () -> setBusy(false), err -> {
            setBusy(false);
            LOG.log(System.Logger.Level.ERROR, "Unexpected error taking payment", err);
        });
    }

    /**
     * Approval is needed when the applied discount exceeds the cashier cap (local policy
     * mirror) — or, defensively, when the server has already rejected this checkout for the
     * cap (stale local policy: the error text is the server's own validation message). A token
     * already collected for this sale is reused.
     */
    private boolean approvalNeeded() {
        if (pendingApprovalToken != null) {
            return false;
        }
        if (DiscountRules.needsApproval(discount, baseSubtotal, policy,
                services.session.isManager())) {
            return true;
        }
        String err = vm.errorMessage().get();
        return err != null && err.contains("manager approval required");
    }

    /** Collects manager credentials (modal), exchanges them for a ONE-SHOT token off the FX
     *  thread, then runs the payment. Cancel returns to the payment screen untouched. */
    private void requestApprovalThen(Runnable action) {
        var creds = ManagerPinDialog.promptForApproval(
                "Discount exceeds the cashier limit — manager approval required");
        if (creds.isEmpty()) {
            return;
        }
        setBusy(true);
        FxTasks.run(() -> {
            ManagerAuth auth = services.authApi.pinLoginForToken(
                    creds.get().cashierCode(), creds.get().pin());
            if (!auth.isManager()) {
                throw new ApiException(403, null, "This account is not a manager");
            }
            pendingApprovalToken = auth.token();
        }, () -> {
            setBusy(false);
            runPayment(action);
        }, err -> {
            setBusy(false);
            String msg = err.getMessage();
            vm.setError(msg == null || msg.isBlank() ? "Manager approval failed" : msg);
        });
    }
```

(e) Discard the token once the sale lands — add as the first line of `showResult(SaleView sale)`:

```java
        pendingApprovalToken = null; // the one shot has been fired
```

- [ ] **Step 2: Run the full terminal suite**

Run: `./mvnw -f pos-terminal/pom.xml test`
Expected: all PASS (this task is controller glue over already-tested parts; the suite guards against regressions).

- [ ] **Step 3: Commit**

```bash
git add pos-terminal/src
git commit -m "feat(terminal): manager PIN approval — one-shot token rides only the checkout call

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 10: Docs + full verification

**Files:**
- Modify: `pos-terminal/README.md`
- Verify: full backend suite + full terminal suite

- [ ] **Step 1: Document slice 5 in the terminal README**

In `pos-terminal/README.md`, locate the slice-4 features section and append a sibling section after it:

```markdown
### Slice 5 — whole-sale discount + manager approval

- **Discount** (payment screen, both retail and dine-in): one whole-bill discount — percent or
  amount plus a required reason chip (reasons come from `GET /sales/discount-policy`). Applying
  re-fetches the server quote WITH the discount, so the big total is always the discounted
  authoritative number; a removable chip shows `Discount −6.00 SAR · LOYALTY`. The discount
  locks once any tender is added.
- **Manager approval**: a discount over the cashier cap (policy `cashierMaxPercent` /
  `cashierMaxAmount`) shows an amber hint in the modal and, at tender time, a manager code+PIN
  modal. The manager's token (via `POST /auth/pin-login`) rides exactly ONE call — the
  checkout/close POST — and is discarded; the cashier stays signed in. A rejected override
  never signs the cashier out.

Manual E2E (requires `--spring.profiles.active=embedded,dev` backend, login `manager`/`manager`
or a seeded cashier):

1. Retail sale → payment: apply 5% LOYALTY → total re-quotes lower, chip appears, tenders
   re-enable only after the re-quote lands. Pay cash. Receipt shows the discount row.
2. Apply 15% as a CASHIER (over the 10% cap): the modal shows the amber approval hint; tapping
   a tender opens the manager PIN modal; a manager approves; the sale closes; the toolbar user
   is still the cashier.
3. Wrong PIN → error surfaces on the payment screen, cashier still signed in, retry works.
4. Remove discount (✕) → total re-quotes back up.
5. Dine-in order → close via payment screen with a 15% discount → same approval path, service
   charge visibly computed on the discounted base.
```

- [ ] **Step 2: Run both full suites**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd "/Users/zuhairahamed/Desktop/Research & Development/POS"
./mvnw test
./mvnw -f pos-terminal/pom.xml test
```

Expected: both suites fully green.

- [ ] **Step 3: Commit**

```bash
git add pos-terminal/README.md
git commit -m "docs(terminal): slice 5 — discount + manager approval features and manual E2E

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```
