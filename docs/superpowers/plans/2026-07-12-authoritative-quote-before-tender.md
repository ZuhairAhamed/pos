# Authoritative Quote Before Tender — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the terminal tender against the authoritative tax/service-charge-inclusive `grandTotal` by exposing the already-implemented `SalesService.quote` over HTTP (retail cart + dine-in order) and fetching it on the payment screen before charging.

**Architecture:** No new pricing logic. Backend exposes `quote`: `POST /sales/quote` (cart, in the `sales` module) and `GET /dining/orders/{id}/quote` (order, in the `dining` module — reusing dining's existing ephemeral-cart-then-`sales.quote` pattern via a shared helper so quote can't drift from close). Terminal adds a `QuoteView` DTO + two facade calls, makes `PaymentViewModel`'s tendered "due" an authoritative total set after the quote returns, and `PaymentController` fetches the quote on entry with tenders disabled until it loads.

**Tech Stack:** Java 21, Spring Boot 3.3 / Spring Modulith (backend), JavaFX 21 (terminal), JUnit 5 + AssertJ + MockMvc. Maven.

## Global Constraints

- **JDK 21 required.** `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` before any Maven command.
- **Backend build/test:** from repo root, `./mvnw test -Dtest=<Class>` (embedded profile, no Docker) for focused runs; `./mvnw verify` runs the full build incl `ModularityTests`. **Terminal build/test:** `./mvnw -f pos-terminal/pom.xml <goal>`.
- **Money is `BigDecimal`, never `double`;** compare with `isEqualByComparingTo` / `compareTo`, not `equals`.
- **Module boundaries are enforced** (`ModularityTests`). This change stays within existing dependencies: the `sales` quote endpoint is in-module; the `dining` quote returns `com.company.pos.sales.api.QuoteView`, and `dining` already depends on `sales :: api`. Do **not** add cross-module imports beyond `api` packages. Do not import another module's JPA entity/repository.
- **Terminal DTOs** use `@JsonIgnoreProperties(ignoreUnknown = true)`; controllers never call `ApiClient` directly; VM/network calls run off the FX thread via `FxTasks.run`; VM observable writes route through the injected `Consumer<Runnable> ui`; `FxTasks` `onError` LOGs via `System.Logger` and never `setText` a bound label.
- **Backend web tests** use `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("embedded") @Transactional` and authenticate with `.with(jwt().jwt(j -> j.subject("cashier1")))`. **Backend service tests** use `@SpringBootTest @ActiveProfiles("embedded") @Import(DatabaseCleaner.class)` and seed via `fake.addProduct(...)` + `productSync.sync()`.
- **The backend default tax is 15% VAT** (exclusive), so a 2×30.00 cart is subtotal 60.00, tax 9.00, grand 69.00. Use these in assertions.

---

### Task 1: Retail quote endpoint (`POST /sales/quote`)

Expose the existing `SalesService.quote(cartId)` (service charge off — retail). The service-level invariant `quote.grandTotal == checkout.grandTotal` is already proven by `SalesQuoteTest.quoteMatchesTheTotalsCheckoutProduces`, so this task only adds the HTTP surface + a web test.

**Files:**
- Modify: `src/main/java/com/company/pos/sales/web/SalesController.java`
- Test: `src/test/java/com/company/pos/sales/SalesQuoteControllerTest.java` (create)

**Interfaces:**
- Produces: `POST /sales/quote` with JSON body `{"cartId":"<uuid>"}` → 200 `QuoteView` `{currencyCode, subtotal, discountTotal, serviceChargeAmount, taxTotal, grandTotal}`.

- [ ] **Step 1: Write the failing web test**

Create `src/test/java/com/company/pos/sales/SalesQuoteControllerTest.java`:

```java
package com.company.pos.sales;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.cart.api.CartService;
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
class SalesQuoteControllerTest {

    @Autowired MockMvc mvc;
    @Autowired CartService carts;
    @Autowired FakeErpClient fake;
    @Autowired ProductSync productSync;

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        productSync.sync();
    }

    @Test
    void quoteReturnsAuthoritativeTaxInclusiveTotal() throws Exception {
        UUID cart = carts.createCart();
        carts.addLine(cart, "BURGER", new BigDecimal("2")); // 60.00 net, 15% VAT

        mvc.perform(post("/sales/quote").with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"cartId\":\"" + cart + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(60.00))
                .andExpect(jsonPath("$.taxTotal").value(9.00))
                .andExpect(jsonPath("$.serviceChargeAmount").value(0.00))
                .andExpect(jsonPath("$.grandTotal").value(69.00));
    }

    @Test
    void anonymousQuoteRejected() throws Exception {
        mvc.perform(post("/sales/quote").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=SalesQuoteControllerTest
```
Expected: FAIL — `POST /sales/quote` returns 404/405 (no such mapping), so the 200 + jsonPath assertions fail.

- [ ] **Step 3: Add the endpoint to `SalesController`**

Add the import `import com.company.pos.sales.api.QuoteView;` and a request record + handler. Insert the handler after the `checkout` method:

```java
    /** Retail cart quote body. */
    record QuoteRequest(java.util.UUID cartId) {
    }

    @PostMapping("/sales/quote")
    QuoteView quote(@RequestBody QuoteRequest body) {
        // Retail quote: service charge is DINE_IN only, so quote(cartId) applies none.
        return sales.quote(body.cartId());
    }
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./mvnw test -Dtest=SalesQuoteControllerTest
```
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/pos/sales/web/SalesController.java \
        src/test/java/com/company/pos/sales/SalesQuoteControllerTest.java
git commit -m "feat(sales): expose POST /sales/quote for retail cart totals

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Dine-in order quote (`GET /dining/orders/{id}/quote`)

Add `DiningService.quoteOrder(orderId)` that prices an order exactly the way `closeOrder` does (shared ephemeral-cart helper + same service-charge decision), and expose it. Prove the invariant `quoteOrder.grandTotal == closeOrder.grandTotal`.

**Files:**
- Modify: `src/main/java/com/company/pos/dining/api/DiningService.java`
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java`
- Modify: `src/main/java/com/company/pos/dining/web/DiningController.java`
- Test: `src/test/java/com/company/pos/dining/DiningQuoteServiceTest.java` (create)
- Test: `src/test/java/com/company/pos/dining/DiningQuoteControllerTest.java` (create)

**Interfaces:**
- Consumes: `SalesService.quote(UUID cartId, boolean applyServiceCharge)` (exists); `CartService.createCart/addLinePreResolved/close` (exists).
- Produces: `DiningService.quoteOrder(UUID orderId)` → `com.company.pos.sales.api.QuoteView`; `GET /dining/orders/{orderId}/quote` → 200 `QuoteView`.

- [ ] **Step 1: Write the failing service invariant test**

Create `src/test/java/com/company/pos/dining/DiningQuoteServiceTest.java`:

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

@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class DiningQuoteServiceTest {

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
        return orderId; // net 60.00 -> +10% SC 6.00, +15% VAT on 66 -> grand 75.90
    }

    @Test
    void quoteOrderAppliesServiceChargeAndTax() {
        UUID orderId = openDineIn();
        QuoteView q = dining.quoteOrder(orderId);
        assertThat(q.subtotal()).isEqualByComparingTo("60.00");
        assertThat(q.serviceChargeAmount()).isEqualByComparingTo("6.00");
        assertThat(q.grandTotal()).isEqualByComparingTo("75.90");
        // read-only on the order: still closeable afterwards
        assertThat(dining.getOrder(orderId).status()).isEqualTo("OPEN");
    }

    @Test
    void quoteOrderGrandTotalEqualsWhatCloseCharges() {
        UUID orderId = openDineIn();
        QuoteView q = dining.quoteOrder(orderId);
        SaleView sale = dining.closeOrder(orderId,
                new CloseOrderCommand(
                        List.of(new TenderInput(PaymentMethod.CASH, new BigDecimal("75.90"),
                                new BigDecimal("75.90"))),
                        Map.of(), null, false),
                "alice", false);
        assertThat(q.grandTotal()).isEqualByComparingTo(sale.grandTotal());
        assertThat(q.serviceChargeAmount()).isEqualByComparingTo(sale.serviceChargeAmount());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw test -Dtest=DiningQuoteServiceTest
```
Expected: FAIL — compile error, `DiningService` has no `quoteOrder`.

- [ ] **Step 3: Add `quoteOrder` to the `DiningService` interface**

In `src/main/java/com/company/pos/dining/api/DiningService.java`, add the import `import com.company.pos.sales.api.QuoteView;` and, under the `// --- close / void ---` section, declare:

```java
    /** Read-only pricing pass for an open order: prices it exactly as {@link #closeOrder} would
     *  (same lines, same service-charge decision), returns the authoritative totals. Creates no
     *  sale, closes no order. */
    QuoteView quoteOrder(UUID orderId);
```

- [ ] **Step 4: Extract the ephemeral-cart helper and implement `quoteOrder` in `DefaultDiningService`**

Open `src/main/java/com/company/pos/dining/application/DefaultDiningService.java`. In `closeOrder`, the block that creates a cart and adds one pre-resolved line per order line (currently inline: `UUID cartId = carts.createCart();` … the `for (OrderLine line : order.getLines()) { … carts.addLinePreResolved(...); }` loop) is replaced by a call to a new shared helper. Add this private helper (place it next to `resolveApplyServiceCharge`):

```java
    /** Builds an ephemeral priced cart from an order's lines, snapshotting modifier deltas at
     *  add-time (the guest pays the quoted price). Shared by closeOrder and quoteOrder so the
     *  quote can never drift from what close charges. */
    private UUID priceCartFor(DiningOrder order) {
        UUID cartId = carts.createCart();
        for (OrderLine line : order.getLines()) {
            java.util.List<com.company.pos.cart.api.CartLineModifierInput> mods =
                    line.getModifiers().stream()
                            .map(m -> new com.company.pos.cart.api.CartLineModifierInput(
                                    m.getOptionId(), m.getName(), m.getPriceDelta()))
                            .toList();
            carts.addLinePreResolved(cartId, line.getSku(), line.getQty(), mods);
        }
        return cartId;
    }
```

Then in `closeOrder`, replace the inline cart-build block with:

```java
        UUID cartId = priceCartFor(order);
```
(Leave the rest of `closeOrder` unchanged — it still resolves the service charge, checks out, `carts.close(cartId)`, and `order.close(...)`.)

Add the `quoteOrder` method (annotate read-only transactional, matching the `quote` methods' style):

```java
    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public com.company.pos.sales.api.QuoteView quoteOrder(UUID orderId) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        if (order.getLines().isEmpty()) {
            throw com.company.pos.common.exception.DomainException.validation(
                    "Cannot quote an empty order");
        }
        UUID cartId = priceCartFor(order);
        boolean applyServiceCharge = resolveApplyServiceCharge(order, false, false);
        com.company.pos.sales.api.QuoteView quote = sales.quote(cartId, applyServiceCharge);
        carts.close(cartId);
        return quote;
    }
```
(If `load`, `requireOpen`, or `DomainException` are already imported/available in the file, use the short names consistent with the surrounding code instead of the fully-qualified forms.)

- [ ] **Step 5: Run the service test to verify it passes**

```bash
./mvnw test -Dtest=DiningQuoteServiceTest
```
Expected: PASS (both tests, incl the quote==close invariant).

- [ ] **Step 6: Write the failing controller test**

Create `src/test/java/com/company/pos/dining/DiningQuoteControllerTest.java`:

```java
package com.company.pos.dining;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.ServiceType;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
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
class DiningQuoteControllerTest {

    @Autowired MockMvc mvc;
    @Autowired DiningService dining;
    @Autowired ConfigurationService config;
    @Autowired FakeErpClient fake;
    @Autowired ProductSync productSync;

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        productSync.sync();
        config.put(SettingKey.SERVICE_CHARGE_ENABLED, "true");
        config.put(SettingKey.SERVICE_CHARGE_PERCENT, "10");
    }

    @Test
    void quoteOrderViaRestReturnsAuthoritativeTotal() throws Exception {
        UUID tableId = dining.registerTable(new RegisterTableCommand("L" + UUID.randomUUID(), 4)).id();
        UUID orderId = dining.openOrder(new OpenOrderCommand(tableId, ServiceType.DINE_IN), "alice").id();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("2"), null, null), "alice");

        mvc.perform(get("/dining/orders/" + orderId + "/quote")
                        .with(jwt().jwt(j -> j.subject("cashier1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceChargeAmount").value(6.00))
                .andExpect(jsonPath("$.grandTotal").value(75.90));
    }

    @Test
    void anonymousQuoteRejected() throws Exception {
        mvc.perform(get("/dining/orders/" + UUID.randomUUID() + "/quote"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 7: Run it to verify it fails**

```bash
./mvnw test -Dtest=DiningQuoteControllerTest
```
Expected: FAIL — `GET /dining/orders/{id}/quote` is 404 (no mapping), so the assertions fail.

- [ ] **Step 8: Add the endpoint to `DiningController`**

In `src/main/java/com/company/pos/dining/web/DiningController.java`, add the import `import com.company.pos.sales.api.QuoteView;` and, near the other `/dining/orders/{orderId}` GET mappings, add:

```java
    @GetMapping("/dining/orders/{orderId}/quote")
    QuoteView quoteOrder(@PathVariable UUID orderId) {
        return dining.quoteOrder(orderId);
    }
```

- [ ] **Step 9: Run the controller test to verify it passes**

```bash
./mvnw test -Dtest=DiningQuoteControllerTest
```
Expected: PASS (both tests).

- [ ] **Step 10: Verify module boundaries + regression on the two changed modules**

```bash
./mvnw test -Dtest=ModularityTests
./mvnw test -Dtest='com.company.pos.dining.*,com.company.pos.sales.*'
```
Expected: PASS — boundaries intact (no new cross-module dependency), and dining/sales suites green (incl the pre-existing `DiningServiceChargeTest`, `SalesQuoteTest`, confirming `closeOrder` still behaves after the `priceCartFor` extraction).

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/company/pos/dining/api/DiningService.java \
        src/main/java/com/company/pos/dining/application/DefaultDiningService.java \
        src/main/java/com/company/pos/dining/web/DiningController.java \
        src/test/java/com/company/pos/dining/DiningQuoteServiceTest.java \
        src/test/java/com/company/pos/dining/DiningQuoteControllerTest.java
git commit -m "feat(dining): GET /dining/orders/{id}/quote via shared priced-cart helper

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Terminal `QuoteView` DTO + `SalesApi.quote` + `DiningApi.quoteOrder`

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/QuoteView.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/SalesApi.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/QuoteApiTest.java` (create)

**Interfaces:**
- Produces:
  - `QuoteView(String currencyCode, BigDecimal subtotal, BigDecimal discountTotal, BigDecimal serviceChargeAmount, BigDecimal taxTotal, BigDecimal grandTotal)`
  - `SalesApi.quote(UUID cartId)` → `QuoteView` (POST `/sales/quote`, body `{"cartId":...}`)
  - `DiningApi.quoteOrder(UUID orderId)` → `QuoteView` (GET `/dining/orders/{orderId}/quote`)

- [ ] **Step 1: Write the failing test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/api/QuoteApiTest.java`:

```java
package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.QuoteView;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QuoteApiTest {

    private static final String QUOTE_JSON =
            "{\"currencyCode\":\"SAR\",\"subtotal\":60.00,\"discountTotal\":0.00,"
            + "\"serviceChargeAmount\":0.00,\"taxTotal\":9.00,\"grandTotal\":69.00}";

    @Test
    void salesQuotePostsCartIdAndParsesGrandTotal() throws Exception {
        UUID cart = UUID.fromString("11111111-1111-1111-1111-111111111111");
        try (StubServer stub = new StubServer(200, QUOTE_JSON, "application/json")) {
            SalesApi api = new SalesApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            QuoteView q = api.quote(cart);
            assertEquals(0, new BigDecimal("69.00").compareTo(q.grandTotal()));
            assertEquals(0, new BigDecimal("9.00").compareTo(q.taxTotal()));
            assertEquals("POST", stub.lastMethod);
            assertEquals("/sales/quote", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"cartId\":\"11111111-1111-1111-1111-111111111111\""));
        }
    }

    @Test
    void diningQuoteOrderGetsOrderQuote() throws Exception {
        UUID order = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String json = "{\"currencyCode\":\"SAR\",\"subtotal\":60.00,\"discountTotal\":0.00,"
                + "\"serviceChargeAmount\":6.00,\"taxTotal\":9.90,\"grandTotal\":75.90}";
        try (StubServer stub = new StubServer(200, json, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            QuoteView q = api.quoteOrder(order);
            assertEquals(0, new BigDecimal("75.90").compareTo(q.grandTotal()));
            assertEquals(0, new BigDecimal("6.00").compareTo(q.serviceChargeAmount()));
            assertEquals("GET", stub.lastMethod);
            assertEquals("/dining/orders/22222222-2222-2222-2222-222222222222/quote", stub.lastPath);
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -f pos-terminal/pom.xml test -Dtest=QuoteApiTest
```
Expected: FAIL — `QuoteView` and the `quote`/`quoteOrder` methods don't exist (compile error).

- [ ] **Step 3: Create the terminal `QuoteView` DTO**

`pos-terminal/src/main/java/com/company/pos/terminal/api/dto/QuoteView.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/** Authoritative priced totals for a cart/order (server-computed). Money is BigDecimal. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QuoteView(String currencyCode, BigDecimal subtotal, BigDecimal discountTotal,
        BigDecimal serviceChargeAmount, BigDecimal taxTotal, BigDecimal grandTotal) {
}
```

- [ ] **Step 4: Add `quote` to `SalesApi`**

In `pos-terminal/src/main/java/com/company/pos/terminal/api/SalesApi.java`, add imports `com.company.pos.terminal.api.dto.QuoteView` and `java.util.Map` and `java.util.UUID` if absent, then:

```java
    /** POST /sales/quote — authoritative totals for a retail cart (service charge off). */
    public QuoteView quote(UUID cartId) {
        return client.post("/sales/quote", Map.of("cartId", cartId),
                new com.fasterxml.jackson.core.type.TypeReference<QuoteView>() {});
    }
```

- [ ] **Step 5: Add `quoteOrder` to `DiningApi`**

In `pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java`, add the import `com.company.pos.terminal.api.dto.QuoteView`, then:

```java
    /** GET /dining/orders/{id}/quote — authoritative totals for a dine-in order (incl service charge). */
    public QuoteView quoteOrder(UUID orderId) {
        return client.get("/dining/orders/" + orderId + "/quote", new TypeReference<QuoteView>() {});
    }
```

- [ ] **Step 6: Run the test to verify it passes**

```bash
./mvnw -f pos-terminal/pom.xml test -Dtest=QuoteApiTest
```
Expected: PASS.

- [ ] **Step 7: Full terminal build**

```bash
./mvnw -f pos-terminal/pom.xml clean test
```
Expected: BUILD SUCCESS (89 + 2 = 91).

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/dto/QuoteView.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/SalesApi.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/QuoteApiTest.java
git commit -m "feat(terminal): QuoteView DTO + SalesApi.quote / DiningApi.quoteOrder

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `PaymentViewModel` tenders against an authoritative total

Make the tendered "due" an authoritative total set after the quote loads; refuse to tender until it is set (defense in depth with the controller's disabled buttons). Add a `setError` seam for the controller to surface a quote-fetch failure through the bound error label.

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/PaymentViewModel.java`
- Modify: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/PaymentViewModelTest.java`

**Interfaces:**
- Consumes: existing `CheckoutGateway`, `committed` accumulator, `ui` dispatcher.
- Produces: `void setAuthoritativeTotal(BigDecimal grandTotal)`; `void setError(String message)`; `remaining()` now computes against the authoritative total once set; tender actions (`addTender`, `payFull`, `finalizeSale`) no-op with a clear message while it is null.

- [ ] **Step 1: Update the existing tests + add guard/authoritative tests**

In `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/PaymentViewModelTest.java`, the existing tests tender without a loaded total; they must now set it first. Make these edits:

Add, at the top of each existing test that calls `payFull`/`addTender`/`finalizeSale` (`shortCashRejectedBeforeCheckout`, `fullCashCheckoutComputesChange`, `walletFullCheckout`, `splitCardThenCashFinalizesWhenCovered`, `finalizeRejectedWhenUnderTendered`, `checkoutFailureSurfacesErrorAndDoesNotMarkPaid`, `reprintCallsSalesApiWithSaleId`, `payFullFinalizesUnderDeferredDispatcher`), immediately after constructing `vm`, the line:

```java
        vm.setAuthoritativeTotal(new BigDecimal("28.75"));
```
For `payFullFinalizesUnderDeferredDispatcher`, this call happens through the deferring dispatcher too — so drain isn't needed for it because `setAuthoritativeTotal` sets the plain field synchronously (see Step 3); add the same line right after `vm` is constructed and before `vm.payFull(...)`.

Then add two new tests:

```java
    @Test
    void tenderRejectedBeforeAuthoritativeTotalLoaded() {
        RecordingGateway gw = new RecordingGateway();
        PaymentViewModel vm = new PaymentViewModel(gw, null, new BigDecimal("28.75"));
        // no setAuthoritativeTotal — the quote hasn't loaded
        vm.payFull("CARD", null);
        assertEquals(0, gw.calls, "must not checkout before the authoritative total is known");
        assertTrue(vm.errorMessage().get().toLowerCase().contains("total"));
        assertFalse(vm.paid().get());
    }

    @Test
    void tendersAgainstAuthoritativeTotalNotEstimate() {
        RecordingGateway gw = new RecordingGateway();
        // estimate 39.00 (pre-tax) but authoritative 44.85 (tax-in)
        PaymentViewModel vm = new PaymentViewModel(gw, null, new BigDecimal("39.00"));
        vm.setAuthoritativeTotal(new BigDecimal("44.85"));
        assertTrue(vm.remainingText().get().contains("44.85"));
        vm.payFull("CARD", null);
        assertEquals(1, gw.calls);
        assertEquals(new BigDecimal("44.85"), gw.received.get(0).amount());
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -f pos-terminal/pom.xml test -Dtest=PaymentViewModelTest
```
Expected: FAIL — `setAuthoritativeTotal` / `setError` don't exist (compile error), and the new guard test would fail against current logic.

- [ ] **Step 3: Implement the authoritative total in `PaymentViewModel`**

In `PaymentViewModel.java`:

Add a field beside `estimatedTotal`:

```java
    private BigDecimal authoritativeTotal; // null until the server quote loads
```

Add the two public methods (place near `remaining()`):

```java
    /** Sets the server-computed total the tenders must cover. Enables tendering. */
    public void setAuthoritativeTotal(BigDecimal grandTotal) {
        this.authoritativeTotal = grandTotal.setScale(2, RoundingMode.HALF_UP); // plain, synchronous
        String rem = remaining().toPlainString();
        ui.accept(() -> remainingText.set(rem));
    }

    /** Surfaces a message through the bound error property (e.g. a quote-fetch failure). */
    public void setError(String message) {
        ui.accept(() -> errorMessage.set(message));
    }
```

Change `remaining()` to compute against the authoritative total when present, else the estimate (display only):

```java
    public BigDecimal remaining() {
        BigDecimal due = authoritativeTotal != null ? authoritativeTotal : estimatedTotal;
        BigDecimal covered = BigDecimal.ZERO;
        for (TenderInput t : committed) {
            covered = covered.add(t.amount());
        }
        BigDecimal rem = due.subtract(covered);
        return rem.signum() < 0 ? BigDecimal.ZERO : rem;
    }
```

Add a guard at the top of `addTender` (before validation) so no tender is accepted until the total loads:

```java
        if (authoritativeTotal == null) {
            ui.accept(() -> errorMessage.set("Total not loaded yet"));
            return false;
        }
```

And the same guard at the top of `finalizeSale` (before reading `remaining()`):

```java
        if (authoritativeTotal == null) {
            ui.accept(() -> errorMessage.set("Total not loaded yet"));
            return;
        }
```
(`payFull` calls `addTender`/`finalizeSale`, so it is covered transitively — no separate guard needed there.)

- [ ] **Step 4: Run the test to verify it passes**

```bash
./mvnw -f pos-terminal/pom.xml test -Dtest=PaymentViewModelTest
```
Expected: PASS (existing tests updated + 2 new).

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/PaymentViewModel.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/PaymentViewModelTest.java
git commit -m "feat(terminal): PaymentViewModel tenders authoritative total, guarded until loaded

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: `PaymentController` fetches the quote before tendering

On entry, disable tenders, fetch the authoritative quote off-thread (retail vs dine-in by `Mode`), populate authoritative labels, set the VM's authoritative total, and enable tenders. On failure, surface the error and leave tenders disabled.

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/PaymentController.java`

**Interfaces:**
- Consumes: `SalesApi.quote(cartId)` / `DiningApi.quoteOrder(orderId)` (Task 3); `PaymentViewModel.setAuthoritativeTotal` / `setError` (Task 4); existing `Mode`, `id`, `services`, `vm`, `FxTasks`.

- [ ] **Step 1: Disable tenders initially and fetch the quote in `initialize()`**

In `PaymentController.java`, at the END of `initialize()` (after the existing button wiring and listeners), add:

```java
        // Tenders are disabled until the authoritative total loads (never tender a stale estimate).
        setTendersEnabled(false);
        totalLabel.setText("Total due: loading…");
        loadQuote();
```

Add these private methods to the controller:

```java
    private void setTendersEnabled(boolean enabled) {
        payCashButton.setDisable(!enabled);
        payCardButton.setDisable(!enabled);
        payWalletButton.setDisable(!enabled);
        addTenderButton.setDisable(!enabled);
    }

    /** Fetch the authoritative quote off the FX thread (retail cart or dine-in order). */
    private void loadQuote() {
        final com.company.pos.terminal.api.dto.QuoteView[] holder = new com.company.pos.terminal.api.dto.QuoteView[1];
        FxTasks.run(
                () -> holder[0] = (mode == Mode.RETAIL)
                        ? services.salesApi.quote(id)
                        : services.diningApi.quoteOrder(id),
                () -> onQuoteLoaded(holder[0]),
                err -> {
                    vm.setError("Couldn't load the total — go back and try again");
                    LOG.log(System.Logger.Level.ERROR, "Quote fetch failed", err);
                });
    }

    private void onQuoteLoaded(com.company.pos.terminal.api.dto.QuoteView q) {
        String cur = q.currencyCode();
        totalLabel.setText("Total due: " + money(q.grandTotal(), cur));
        vm.setAuthoritativeTotal(q.grandTotal());
        setTendersEnabled(true);
    }
```

Note: `money(BigDecimal, String)` already exists in the controller (used by `showResult`). `LOG`, `FxTasks`, `mode`, `id`, `services`, `vm`, and the four tender buttons are existing fields. If `setBusy(...)` also toggles these buttons, that's fine — `setTendersEnabled` is the initial/quote-driven gate; `setBusy` remains the per-tender in-flight gate.

- [ ] **Step 2: Full terminal build**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -f pos-terminal/pom.xml clean test
```
Expected: BUILD SUCCESS, 91 tests. (Controller/FXML aren't unit-tested per the module convention; this verifies compile + the VM/API suites.)

- [ ] **Step 3: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/PaymentController.java
git commit -m "feat(terminal): payment screen fetches authoritative quote before tendering

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: End-to-end verification against the dev backend

Prove the original 400 is gone by driving the real retail flow (and the dine-in quote) over HTTP against the running dev backend — the same check that found the bug.

**Files:** none (verification only).

- [ ] **Step 1: Rebuild the backend jar with the new endpoints**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw package -DskipTests
```
Expected: `target/pos.jar` rebuilt (BUILD SUCCESS).

- [ ] **Step 2: Start the dev backend**

```bash
java -jar target/pos.jar --spring.profiles.active=embedded,dev \
  --POS_DB_URL="jdbc:sqlite:file:$CLAUDE_JOB_DIR/tmp/pos-quote-verify.db" \
  > "$CLAUDE_JOB_DIR/tmp/backend-verify.log" 2>&1 &
```
Wait until the log shows `Started PosApplication`. Confirm the dev seeders ran (`[dev-seed] created user 'manager'`, `[dev-seed] seeded 5 products`).

- [ ] **Step 3: Drive the retail flow with the authoritative quote**

Log in (`manager`/`manager`), `POST /sync/erp`, create a cart, add COLA×2 + BURGER×1 + FRIES×1, then:
1. `POST /sales/quote {cartId}` → read `grandTotal` (expect 44.85).
2. `POST /sales {cartId, tenders:[{method:CASH, amount:<grandTotal>, tendered:50.00}]}`.

Expected: step 1 returns `grandTotal: 44.85`; step 2 returns **201** with a `SaleView` (no more `400 "Tenders … do not match the total"`). This is the exact sequence the fixed terminal now performs (quote → tender grandTotal).

- [ ] **Step 4: Spot-check the dine-in quote endpoint**

`POST /dining/tables` (manager), open an order on it, add a line, then `GET /dining/orders/{orderId}/quote` → expect 200 with a `grandTotal` (service charge applied if enabled). Confirms the dine-in path is wired.

- [ ] **Step 5: Stop the backend**

Kill the backend process. Report the observed quote total and the 201 checkout as evidence.

---

## Self-Review

**Spec coverage:**

| Spec item | Task |
| --- | --- |
| `POST /sales/quote` (retail, service charge off) | 1 |
| Retail invariant quote==checkout | pre-existing `SalesQuoteTest` (noted) + Task 6 live |
| `DiningService.quoteOrder` + shared `priceCartFor` helper | 2 |
| `GET /dining/orders/{id}/quote` | 2 |
| Dine-in invariant quoteOrder==closeOrder | 2 |
| `ModularityTests` stays green | 2 (Step 10) |
| Terminal `QuoteView` + `SalesApi.quote` + `DiningApi.quoteOrder` | 3 |
| `PaymentViewModel` authoritative total + tender guard | 4 |
| `PaymentController` fetch-quote-before-tender + disabled-until-loaded + failure handling | 5 |
| Live end-to-end (400 gone) | 6 |

**Placeholder scan:** none — every code/test/command step is concrete.

**Type consistency:** terminal `QuoteView` field order matches the backend record (`currencyCode, subtotal, discountTotal, serviceChargeAmount, taxTotal, grandTotal`). `SalesApi.quote(UUID)`/`DiningApi.quoteOrder(UUID)` return `QuoteView` and are consumed with those names in Task 5. `setAuthoritativeTotal(BigDecimal)`/`setError(String)` defined in Task 4 are called with those exact signatures in Task 5. `Mode`, `id`, `services`, `vm`, `money(...)`, the four tender buttons, and `LOG`/`FxTasks` are all pre-existing `PaymentController` members (from the retail slice). Test counts: terminal 89 → 91 (Task 3 +2; Task 4 net +2 new but edits existing).

**Ordering note:** Tasks 1–2 are backend (each ends on green module tests + `ModularityTests`); Tasks 3–5 are terminal (each ends on green `pos-terminal` build). Task 5 depends on Tasks 3 & 4. Task 6 requires Tasks 1–3 (endpoints + terminal build) and is pure verification.
