# Terminal Slice 13 — Service-charge waiver — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a manager waive the dine-in service charge at checkout from the terminal — on both the whole-order payment screen and the split-bill screen — with the quote (and thus the tendered total) reflecting the waiver.

**Architecture:** The close/split-close backend already accepts and enforces `waiveServiceCharge` (manager-only, via `resolveApplyServiceCharge`). This slice adds (1) the missing **quote** counterpart on the backend as an ungated preview, and (2) the terminal UI: a manager-gated "Waive service charge" toggle that re-quotes and carries a one-shot manager token into close. Backend changes stay inside `dining`'s quote path.

**Tech Stack:** Java 21, Spring Boot 3.3 / Spring Modulith (backend), JavaFX (terminal), JUnit 5 + AssertJ + MockMvc, Maven (two builds — root reactor for backend, `pos-terminal/pom.xml` for the terminal).

## Global Constraints

- JDK 21: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` before any mvn.
- **Two separate builds.** Backend tests: `./mvnw test -Dtest=...` (root reactor, `embedded` SQLite profile — no Docker). Terminal tests: `./mvnw -f pos-terminal/pom.xml ... test`.
- **After the backend change, run `ModularityTests`** — this slice stays inside `dining`, so it must pass with no `allowedDependencies` edit.
- Money is `BigDecimal`. No migration, no config/enum change, no new Maven dependencies.
- **The quote is an ungated preview**: waive-aware quote methods call `resolveApplyServiceCharge(order, waive, /*callerIsManager*/ true)` so a non-manager (cashier session) never trips the "Only a manager can waive" throw. **Enforcement stays at close/split-close** (already manager-gated). Do NOT weaken or duplicate the close-side check.
- Waiver is **manager-only** and **all-or-nothing**. The terminal collects the manager PIN **upfront** at waive-time (reusing the one-shot-token machinery), stores the token, and rides it on the close/split-close call.
- **FX-threading (the recurring bug class):** VM methods synchronous, return plain values; controllers run them off the FX thread via `FxTasks.run`, results read in `onDone` via holders; observables written off-thread only inside `ui.accept`; `onError` never `setText` a bound label; **never call a blocking VM method (quote/close) inside an `onDone`** — put it in the `work` lambda. New VM behaviour gets an async-dispatcher regression test.
- **Backward-compatible DTO changes:** when adding `waiveServiceCharge` to a request record, keep a secondary constructor delegating with `false`, so existing call sites keep compiling until the task that updates them (Maven compiles all sources at once).

---

## File Structure

| File | Track | Responsibility | Task |
|------|-------|----------------|------|
| `dining/api/DiningService.java` | BE | `quoteOrder` waive overload | 1 |
| `dining/application/DefaultDiningService.java` | BE | implement `quoteOrder` waive (ungated) | 1 |
| `dining/web/DiningController.java` | BE | `QuoteOrderRequest.waiveServiceCharge` | 1 |
| `src/test/.../dining/DiningServiceChargeWaiverQuoteTest.java` | BE | quote-waiver tests | 1 |
| `dining/api/DiningService.java` | BE | `quoteSplitByItem`/`quoteSplitEven` waive overloads | 2 |
| `dining/application/DefaultDiningService.java` | BE | implement split-quote waive (ungated) | 2 |
| `dining/web/DiningController.java` | BE | `QuoteSplitRequest.waiveServiceCharge` | 2 |
| `src/test/.../dining/DiningServiceChargeWaiverQuoteTest.java` | BE | split-quote-waiver tests (same file) | 2 |
| `pos-terminal/.../api/dto/QuoteOrderRequest.java`, `QuoteSplitRequest.java` | FE | `waiveServiceCharge` field (+ back-compat ctor) | 3 |
| `pos-terminal/.../api/DiningApi.java` | FE | `quoteOrder` waive overload, `quoteSplit` flag, `closeSplit` token overload | 3 |
| `pos-terminal/.../api/DiningApiTest.java` | FE | client tests | 3 |
| `pos-terminal/.../view/PaymentController.java` | FE | waive state + button + PIN + chip + thread | 4 |
| `pos-terminal/.../resources/fxml/payment.fxml`, `resources/css/app.css` | FE | waive button + chip | 4 |
| `pos-terminal/.../viewmodel/SplitViewModel.java` | FE | waive/token state + thread into quote-split/close-split | 5 |
| `pos-terminal/.../view/SplitController.java` | FE | waive button + PIN + chip + re-quote | 5 |
| `pos-terminal/.../resources/fxml/split.fxml` | FE | waive button + chip | 5 |
| `pos-terminal/.../viewmodel/SplitViewModelTest.java` | FE | VM tests (+ async) | 5 |
| `pos-terminal/README.md` | FE | slice-13 manual E2E | 5 |

Order: **1 → 2** (backend), then **3 → 4 → 5** (terminal). Task 4 depends on Task 3; Task 5 depends on Tasks 3.

---

## Task 1: Backend — `quoteOrder` waive overload

**Files:**
- Modify: `src/main/java/com/company/pos/dining/api/DiningService.java`
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java`
- Modify: `src/main/java/com/company/pos/dining/web/DiningController.java`
- Test: `src/test/java/com/company/pos/dining/DiningServiceChargeWaiverQuoteTest.java`

**Interfaces:**
- Produces: `QuoteView DiningService.quoteOrder(UUID orderId, Map<String,DiscountInput> lineDiscounts, DiscountInput transactionDiscount, boolean waiveServiceCharge)` — waive-aware, ungated preview.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/dining/DiningServiceChargeWaiverQuoteTest.java`. It seeds a product, enables the service charge via the `configuration` store, opens a dine-in order with a line, and checks the waived quote. (Confirm the config write API from an existing test that toggles settings — search `SettingKey.SERVICE_CHARGE_ENABLED` / `SettingsService` / `configuration` test helpers; the snippet below uses `SettingsService.put`, adapt to the real setter if it differs.)

```java
package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.company.pos.configuration.api.SettingKey;
import com.company.pos.configuration.api.SettingsService;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.CourseTag;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.QuoteView;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
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
class DiningServiceChargeWaiverQuoteTest {

    @Autowired DiningService dining;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired SettingsService settings;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        productSync.sync();
        settings.put(SettingKey.SERVICE_CHARGE_ENABLED, "true");
        settings.put(SettingKey.SERVICE_CHARGE_PERCENT, "10");
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private UUID openDineInWithLine() {
        UUID table = dining.registerTable(new RegisterTableCommand("SC-" + UUID.randomUUID(), 4)).id();
        UUID orderId = dining.openOrder(new OpenOrderCommand(table, null), "alice").id();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("1"), null, CourseTag.MAIN),
                "alice");
        return orderId;
    }

    @Test
    void waivedQuoteHasNoServiceChargeAndSmallerTotal() {
        UUID orderId = openDineInWithLine();

        QuoteView withSc = dining.quoteOrder(orderId, Map.of(), null, false);
        QuoteView waived = dining.quoteOrder(orderId, Map.of(), null, true);

        assertThat(withSc.serviceChargeAmount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(waived.serviceChargeAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(waived.grandTotal()).isLessThan(withSc.grandTotal());
    }

    @Test
    void waivedQuoteDoesNotRequireManager() {
        UUID orderId = openDineInWithLine();
        // The quote is an ungated preview — it must NOT throw the manager-only validation.
        assertThatCode(() -> dining.quoteOrder(orderId, Map.of(), null, true)).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=DiningServiceChargeWaiverQuoteTest`
Expected: compilation failure — the 4-arg `quoteOrder` does not exist.

- [ ] **Step 3: Declare the overload**

In `DiningService.java`, add after the existing discount-aware `quoteOrder` declaration:

```java
    /** As {@link #quoteOrder(UUID, java.util.Map, DiscountInput)} but computes the total with the
     *  service charge waived when {@code waiveServiceCharge} is true. Ungated PREVIEW — the manager
     *  check is enforced at close, not here. */
    QuoteView quoteOrder(UUID orderId, java.util.Map<String, DiscountInput> lineDiscounts,
            DiscountInput transactionDiscount, boolean waiveServiceCharge);
```

- [ ] **Step 4: Implement the overload (ungated preview)**

In `DefaultDiningService.java`, replace the existing 3-arg `quoteOrder` (lines ~360-374) so it delegates to a new 4-arg version. The 4-arg version passes `callerIsManager=true` to the resolver so the preview never throws:

```java
    @Override
    @Transactional
    public QuoteView quoteOrder(UUID orderId, Map<String, DiscountInput> lineDiscounts,
            DiscountInput transactionDiscount) {
        return quoteOrder(orderId, lineDiscounts, transactionDiscount, false);
    }

    @Override
    @Transactional
    public QuoteView quoteOrder(UUID orderId, Map<String, DiscountInput> lineDiscounts,
            DiscountInput transactionDiscount, boolean waiveServiceCharge) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        if (order.getLines().isEmpty()) {
            throw DomainException.validation("Cannot quote an empty order");
        }
        UUID cartId = priceCartFor(order);
        // Ungated preview: callerIsManager=true bypasses the manager throw; close is the real gate.
        boolean applyServiceCharge = resolveApplyServiceCharge(order, waiveServiceCharge, true);
        QuoteView quote = sales.quote(cartId, lineDiscounts, transactionDiscount, applyServiceCharge);
        carts.close(cartId);
        return quote;
    }
```

- [ ] **Step 5: Add `waiveServiceCharge` to the quote endpoint**

In `DiningController.java`, update `QuoteOrderRequest` and the handler:

```java
    /** Body for the discount-aware quote. Nulls (or omitted fields) mean "no discounts". */
    record QuoteOrderRequest(Map<String, DiscountInput> lineDiscounts,
            DiscountInput transactionDiscount, boolean waiveServiceCharge) {
        QuoteOrderRequest {
            lineDiscounts = lineDiscounts == null ? Map.of() : lineDiscounts;
        }
    }

    @PostMapping("/dining/orders/{orderId}/quote")
    QuoteView quoteOrderWithDiscounts(@PathVariable UUID orderId,
            @RequestBody QuoteOrderRequest body) {
        return dining.quoteOrder(orderId, body.lineDiscounts(), body.transactionDiscount(),
                body.waiveServiceCharge());
    }
```

(`boolean waiveServiceCharge` defaults to `false` when the JSON omits it — Jackson leaves a primitive boolean `false`.)

- [ ] **Step 6: Run the test + ModularityTests**

Run: `./mvnw test -Dtest=DiningServiceChargeWaiverQuoteTest`
Expected: PASS (2 tests).
Run: `./mvnw test -Dtest=ModularityTests`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/pos/dining/api/DiningService.java \
        src/main/java/com/company/pos/dining/application/DefaultDiningService.java \
        src/main/java/com/company/pos/dining/web/DiningController.java \
        src/test/java/com/company/pos/dining/DiningServiceChargeWaiverQuoteTest.java
git commit -m "feat(dining): waive-aware quoteOrder (ungated service-charge preview)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Backend — split-quote waive overloads

**Files:**
- Modify: `src/main/java/com/company/pos/dining/api/DiningService.java`
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java`
- Modify: `src/main/java/com/company/pos/dining/web/DiningController.java`
- Test: `src/test/java/com/company/pos/dining/DiningServiceChargeWaiverQuoteTest.java` (add cases)

**Interfaces:**
- Consumes: `resolveApplyServiceCharge` (Task 1 unchanged).
- Produces: `SplitQuoteView DiningService.quoteSplitByItem(UUID, List<List<UUID>>, boolean waiveServiceCharge)` and `SplitQuoteView quoteSplitEven(UUID, int ways, boolean waiveServiceCharge)`.

- [ ] **Step 1: Add the failing tests**

Add to `DiningServiceChargeWaiverQuoteTest.java`:

```java
    @Test
    void waivedEvenSplitQuoteHasNoServiceCharge() {
        UUID orderId = openDineInWithLine();

        com.company.pos.dining.api.SplitQuoteView waived = dining.quoteSplitEven(orderId, 2, true);
        com.company.pos.dining.api.SplitQuoteView withSc = dining.quoteSplitEven(orderId, 2, false);

        assertThat(waived.order().serviceChargeAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(withSc.order().serviceChargeAmount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(waived.order().grandTotal()).isLessThan(withSc.order().grandTotal());
    }

    @Test
    void waivedByItemSplitQuoteHasNoServiceCharge() {
        UUID table = dining.registerTable(new RegisterTableCommand("SC-" + UUID.randomUUID(), 4)).id();
        UUID orderId = dining.openOrder(new OpenOrderCommand(table, null), "alice").id();
        var line = dining.addLine(orderId,
                new AddLineCommand("BURGER", new BigDecimal("1"), null, CourseTag.MAIN), "alice")
                .lines().get(0).id();

        com.company.pos.dining.api.SplitQuoteView waived =
                dining.quoteSplitByItem(orderId, java.util.List.of(java.util.List.of(line)), true);

        assertThat(waived.bills().get(0).serviceChargeAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw test -Dtest=DiningServiceChargeWaiverQuoteTest`
Expected: compilation failure — the 3-arg split-quote overloads do not exist.

- [ ] **Step 3: Declare the overloads**

In `DiningService.java`, add after the existing `quoteSplitByItem`/`quoteSplitEven` declarations:

```java
    /** As {@link #quoteSplitByItem(UUID, java.util.List)} but with the service charge waived when
     *  {@code waiveServiceCharge} is true. Ungated preview. */
    SplitQuoteView quoteSplitByItem(UUID orderId, java.util.List<java.util.List<UUID>> billLineIds,
            boolean waiveServiceCharge);

    /** As {@link #quoteSplitEven(UUID, int)} but with the service charge waived when
     *  {@code waiveServiceCharge} is true. Ungated preview. */
    SplitQuoteView quoteSplitEven(UUID orderId, int ways, boolean waiveServiceCharge);
```

- [ ] **Step 4: Implement the overloads**

In `DefaultDiningService.java`, change the existing 2-arg methods to delegate to new 3-arg versions that thread the flag through `resolveApplyServiceCharge(order, waiveServiceCharge, true)`:

```java
    @Override
    public SplitQuoteView quoteSplitByItem(UUID orderId, List<List<UUID>> billLineIds) {
        return quoteSplitByItem(orderId, billLineIds, false);
    }

    @Override
    public SplitQuoteView quoteSplitByItem(UUID orderId, List<List<UUID>> billLineIds,
            boolean waiveServiceCharge) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        if (order.getLines().isEmpty()) {
            throw DomainException.validation("Cannot quote an empty order");
        }
        validatePartition(order, billLineIds);
        boolean applyServiceCharge = resolveApplyServiceCharge(order, waiveServiceCharge, true);
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
        return quoteSplitEven(orderId, ways, false);
    }

    @Override
    public SplitQuoteView quoteSplitEven(UUID orderId, int ways, boolean waiveServiceCharge) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        if (order.getLines().isEmpty()) {
            throw DomainException.validation("Cannot quote an empty order");
        }
        if (ways < 2) {
            throw DomainException.validation("Even split requires at least 2 ways");
        }
        UUID cartId = priceCartFor(order);
        boolean applyServiceCharge = resolveApplyServiceCharge(order, waiveServiceCharge, true);
        QuoteView quote = sales.quote(cartId, applyServiceCharge);
        carts.close(cartId);
        if (quote.grandTotal().signum() <= 0) {
            throw DomainException.validation("Cannot evenly split a non-positive total");
        }
        return new SplitQuoteView(null, quote, evenShares(quote.grandTotal(), ways));
    }
```

- [ ] **Step 5: Add `waiveServiceCharge` to the quote-split endpoint**

In `DiningController.java`, update `QuoteSplitRequest` and the handler:

```java
    /** Body for the split quote. BY_ITEM populates {@code bills}; EVEN populates {@code even}. */
    record QuoteSplitRequest(SplitMode mode, List<QuoteBillInput> bills, QuoteEvenInput even,
            boolean waiveServiceCharge) {
    }
```

```java
    @PostMapping("/dining/orders/{orderId}/quote-split")
    SplitQuoteView quoteSplit(@PathVariable UUID orderId, @RequestBody QuoteSplitRequest body) {
        if (body.mode() == null) {
            throw DomainException.validation("Split mode is required");
        }
        return switch (body.mode()) {
            case BY_ITEM -> dining.quoteSplitByItem(orderId,
                    body.bills() == null ? List.of()
                            : body.bills().stream().map(QuoteBillInput::lineIds).toList(),
                    body.waiveServiceCharge());
            case EVEN -> {
                if (body.even() == null) {
                    throw DomainException.validation("Even split details are required");
                }
                yield dining.quoteSplitEven(orderId, body.even().ways(), body.waiveServiceCharge());
            }
        };
    }
```

- [ ] **Step 6: Run the tests + ModularityTests**

Run: `./mvnw test -Dtest=DiningServiceChargeWaiverQuoteTest`
Expected: PASS (4 tests).
Run: `./mvnw test -Dtest=ModularityTests`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/pos/dining/api/DiningService.java \
        src/main/java/com/company/pos/dining/application/DefaultDiningService.java \
        src/main/java/com/company/pos/dining/web/DiningController.java \
        src/test/java/com/company/pos/dining/DiningServiceChargeWaiverQuoteTest.java
git commit -m "feat(dining): waive-aware split quotes (ungated service-charge preview)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Terminal — DTOs + `DiningApi` (quote waive, closeSplit token)

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/QuoteOrderRequest.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/QuoteSplitRequest.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/DiningApiTest.java`

**Interfaces:**
- Produces: `DiningApi.quoteOrder(UUID, DiscountInput, boolean waiveServiceCharge)`, `DiningApi.closeSplit(UUID, SplitCloseRequest, String bearerToken)`; client `QuoteOrderRequest`/`QuoteSplitRequest` gain a `waiveServiceCharge` component with a back-compat constructor.

- [ ] **Step 1: Write the failing tests**

Add to `DiningApiTest.java` (fixtures `ORDER_ID`, `ORDER_JSON`, `StubServer`, `ApiClient`, `SessionManager` already exist; `SplitQuoteView`/`SaleView`/`SplitCloseRequest` DTOs exist). For the split-close list response, reuse whatever JSON constant the existing `closeSplit` test uses (search the file for a `[...]` sale-list JSON; the snippet references `SALES_JSON` — adapt to the real constant name):

```java
    @Test
    void quoteOrderSendsWaiveServiceChargeFlag() throws Exception {
        try (StubServer stub = new StubServer(200, QUOTE_JSON, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            api.quoteOrder(ORDER_ID, null, true);
            assertEquals("POST", stub.lastMethod);
            assertEquals("/dining/orders/33333333-3333-3333-3333-333333333333/quote", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"waiveServiceCharge\":true"));
        }
    }

    @Test
    void closeSplitWithTokenSendsBearerAndWaiveFlag() throws Exception {
        try (StubServer stub = new StubServer(201, SALES_JSON, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            SplitCloseRequest req = new SplitCloseRequest("EVEN", null,
                    new EvenSplitRequest(2, java.util.List.of("CASH", "CASH")), true);
            api.closeSplit(ORDER_ID, req, "mgr-token-xyz");
            assertEquals("POST", stub.lastMethod);
            assertEquals("/dining/orders/33333333-3333-3333-3333-333333333333/close-split", stub.lastPath);
            assertEquals("Bearer mgr-token-xyz", stub.lastAuthHeader);
            assertTrue(stub.lastBody.contains("\"waiveServiceCharge\":true"));
        }
    }
```

Notes for the implementer: use the same `QUOTE_JSON` constant the existing `quoteOrder` test uses (a `QuoteView` JSON); if none exists, add a minimal one. `stub.lastBody` and `stub.lastAuthHeader` are the fields the existing DiningApiTest uses to assert request body/headers — confirm their exact names in the file and adapt (`transferOrder`/`voidOrder` tests already assert `lastAuthHeader`/`lastBody` style).

- [ ] **Step 2: Run to verify failure**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml -Dtest=DiningApiTest test`
Expected: compilation failure — 3-arg `quoteOrder` and 3-arg `closeSplit` do not exist.

- [ ] **Step 3: Add `waiveServiceCharge` to the client request DTOs (back-compat)**

`QuoteOrderRequest.java`:

```java
package com.company.pos.terminal.api.dto;

import java.util.Map;

/** POST /dining/orders/{id}/quote body — the discount-aware dine-in quote. */
public record QuoteOrderRequest(Map<String, DiscountInput> lineDiscounts,
        DiscountInput transactionDiscount, boolean waiveServiceCharge) {

    /** No-waiver convenience (keeps existing callers compiling). */
    public QuoteOrderRequest(Map<String, DiscountInput> lineDiscounts,
            DiscountInput transactionDiscount) {
        this(lineDiscounts, transactionDiscount, false);
    }
}
```

`QuoteSplitRequest.java`:

```java
package com.company.pos.terminal.api.dto;

import java.util.List;

/** POST /dining/orders/{id}/quote-split body. {@code mode} is "BY_ITEM" (populate {@code bills})
 *  or "EVEN" (populate {@code even}), mirroring the server's SplitMode enum names. */
public record QuoteSplitRequest(String mode, List<QuoteBillInput> bills, QuoteEvenInput even,
        boolean waiveServiceCharge) {

    /** No-waiver convenience (keeps existing callers compiling). */
    public QuoteSplitRequest(String mode, List<QuoteBillInput> bills, QuoteEvenInput even) {
        this(mode, bills, even, false);
    }
}
```

- [ ] **Step 4: Add the `DiningApi` methods**

In `DiningApi.java`, add the 3-arg `quoteOrder` after the existing 2-arg one, and change the 2-arg to delegate:

```java
    /** POST /dining/orders/{id}/quote — quote priced with a whole-sale discount ({@code null} = none). */
    public QuoteView quoteOrder(UUID orderId, DiscountInput transactionDiscount) {
        return quoteOrder(orderId, transactionDiscount, false);
    }

    /** As above, with the service charge waived when {@code waiveServiceCharge} is true (manager-approved). */
    public QuoteView quoteOrder(UUID orderId, DiscountInput transactionDiscount,
            boolean waiveServiceCharge) {
        return client.post("/dining/orders/" + orderId + "/quote",
                new QuoteOrderRequest(Map.of(), transactionDiscount, waiveServiceCharge),
                new TypeReference<QuoteView>() {});
    }
```

And add the token overload for `closeSplit` after the existing `closeSplit`:

```java
    /** As {@link #closeSplit(UUID, SplitCloseRequest)} but authenticated with a one-shot manager
     *  token ({@code null} = the signed-in cashier's session token). */
    public List<SaleView> closeSplit(UUID orderId, SplitCloseRequest req, String bearerToken) {
        return client.post("/dining/orders/" + orderId + "/close-split", req,
                new TypeReference<List<SaleView>>() {}, bearerToken);
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=DiningApiTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/dto/QuoteOrderRequest.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/QuoteSplitRequest.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/DiningApiTest.java
git commit -m "feat(terminal): DiningApi waive-aware quote + closeSplit token overload

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Terminal — waive on the payment screen

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/PaymentController.java`
- Modify: `pos-terminal/src/main/resources/fxml/payment.fxml`
- Modify: `pos-terminal/src/main/resources/css/app.css`

**Interfaces:**
- Consumes: `DiningApi.quoteOrder(id, discount, waive)` (Task 3), the existing `CloseOrderRequest(tenders, lineDiscounts, transactionDiscount, waiveServiceCharge)`, `ManagerPinDialog`/`AuthApi.pinLoginForToken`/`ManagerAuth`.
- Produces: the working payment-screen waive flow. Display-dependent → verified by the full suite compiling+passing plus README manual E2E (no headless test; the wire is proven by Task 3's `DiningApiTest`).

- [ ] **Step 1: Add `mergeButton`-style FXML for the waive button + chip**

In `payment.fxml`, next to the existing `discountButton` and `discountChipRow`, add a waive button and a removable chip (mirror the discount markup exactly — same container, same styleClasses):

```xml
        <Button fx:id="waiveButton" text="Waive service charge" styleClass="btn-secondary"
                visible="false" managed="false"/>
```

and, beside `discountChipRow`:

```xml
        <HBox fx:id="waiveChipRow" styleClass="chip-row" visible="false" managed="false">
          <Label fx:id="waiveChipLabel" styleClass="discount-chip"/>
          <Button fx:id="removeWaiveButton" text="✕" styleClass="chip-remove"/>
        </HBox>
```

(Match the exact styleClasses used by `discountChipRow`/`discountChipLabel`/`removeDiscountButton` in the real file — reuse them so no new CSS is strictly required.)

- [ ] **Step 2: Add CSS (only if the discount-chip classes are not reused verbatim)**

If Step 1 reused the discount chip classes, skip. Otherwise append to `app.css`:

```css
/* Slice 13 — service-charge waiver chip (reuses discount-chip tokens) */
.waive-chip { -fx-padding: 6 10; }
```

- [ ] **Step 3: Wire the controller**

In `PaymentController.java`:

(a) Add the `@FXML` fields (with the other buttons/chips):

```java
    @FXML private Button waiveButton;
    @FXML private HBox waiveChipRow;
    @FXML private Label waiveChipLabel;
    @FXML private Button removeWaiveButton;
```

(b) Add the state field (near `discount`/`pendingApprovalToken`):

```java
    private volatile boolean waiveServiceCharge;
```

(c) In `initialize()` (where `discountButton`/`removeDiscountButton` handlers are set), add:

```java
        waiveButton.setOnAction(e -> waiveServiceChargeTapped());
        removeWaiveButton.setOnAction(e -> removeWaiver());
```

(d) In `loadQuote()`, pass the flag to the dine-in quote. Change the dine-in branch:

```java
                    holder[0] = (mode == Mode.RETAIL)
                            ? services.salesApi.quote(id, discount)
                            : services.diningApi.quoteOrder(id, discount, waiveServiceCharge);
```

(e) In `onQuoteLoaded(QuoteView q)`, after the discount-chip block, drive the waive button + chip visibility from the quote's service charge:

```java
        boolean scPresent = mode != Mode.RETAIL && q.serviceChargeAmount() != null
                && q.serviceChargeAmount().signum() > 0;
        // Show the Waive button only when there is a service charge to waive and it isn't already waived.
        waiveButton.setVisible(scPresent && !waiveServiceCharge);
        waiveButton.setManaged(scPresent && !waiveServiceCharge);
        waiveChipRow.setVisible(waiveServiceCharge);
        waiveChipRow.setManaged(waiveServiceCharge);
        if (waiveServiceCharge) {
            waiveChipLabel.setText("Service charge waived");
        }
```

(f) In `requote()`, hide the waive chip alongside the discount chip while re-fetching (so it repaints cleanly from `onQuoteLoaded`):

```java
        waiveChipRow.setVisible(false);
        waiveChipRow.setManaged(false);
```

(g) In `gatewayFor(...)`, thread the flag into the dine-in `CloseOrderRequest` (keep the existing `pendingApprovalToken`). Change the dine-in return to:

```java
        return tenders -> services.diningApi.close(id,
                new CloseOrderRequest(tenders, Map.of(), discount, waiveServiceCharge),
                pendingApprovalToken);
```

(h) Add the waive handlers. `waiveServiceChargeTapped()` collects the manager PIN upfront (mirrors `requestApprovalThen` but, on success, sets the flag and re-quotes instead of paying):

```java
    /** Waiving the service charge is manager-only: collect the PIN upfront, capture the one-shot
     *  token, then set the flag and re-quote so the previewed/tendered total drops the charge. */
    private void waiveServiceChargeTapped() {
        if (!quoteLoaded || waiveServiceCharge) {
            return;
        }
        var creds = ManagerPinDialog.promptForApproval(
                "Waiving the service charge requires manager approval");
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
            waiveServiceCharge = true;
            requote();
        }, err -> {
            setBusy(false);
            String msg = err.getMessage();
            vm.setError(msg == null || msg.isBlank() ? "Manager approval failed" : msg);
        });
    }

    private void removeWaiver() {
        waiveServiceCharge = false;
        requote();
    }
```

(Confirm the exact `ManagerPinDialog.promptForApproval` signature + the `creds.get().cashierCode()/pin()` accessors from the existing `requestApprovalThen` — they are shown there. `setBusy`, `quoteLoaded`, `FxTasks`, `ManagerAuth`, `ApiException` are already used in this controller.)

- [ ] **Step 4: Run the full terminal suite**

Run: `./mvnw -f pos-terminal/pom.xml clean test`
Expected: BUILD SUCCESS, all tests green (no new headless test in this task — the flag-on-the-wire is covered by Task 3's `DiningApiTest`; the button/PIN flow is manual-E2E). If compilation fails on an `fx:id` or import, fix against this task's edits.

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/PaymentController.java \
        pos-terminal/src/main/resources/fxml/payment.fxml \
        pos-terminal/src/main/resources/css/app.css
git commit -m "feat(terminal): waive service charge on the payment screen (manager-gated)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Terminal — waive on the split screen

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/SplitViewModel.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/SplitController.java`
- Modify: `pos-terminal/src/main/resources/fxml/split.fxml`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/SplitViewModelTest.java`
- Modify: `pos-terminal/README.md`

**Interfaces:**
- Consumes: `DiningApi.quoteSplit` (waive flag via `QuoteSplitRequest`), `DiningApi.closeSplit(orderId, req, token)` (Task 3), `ManagerPinDialog`/`AuthApi`/`ManagerAuth`.
- Produces: `SplitViewModel.setWaiveServiceCharge(boolean)`, `setApprovalToken(String)`, `boolean quoteHasServiceCharge()`; the working split-screen waive flow.

- [ ] **Step 1: Write the failing VM tests**

Add to `SplitViewModelTest.java` (mirror the existing `quoteSplit`/`closeAll` tests' `DiningApi` stub + `orderWith`/fixtures; confirm helper names and adapt). These capture the request the stub receives:

```java
    @Test
    void waivedSplitQuoteSendsFlag() {
        boolean[] sawWaive = {false};
        DiningApi dining = new DiningApi(null) {
            @Override
            public SplitQuoteView quoteSplit(UUID orderId, QuoteSplitRequest req) {
                sawWaive[0] = req.waiveServiceCharge();
                return evenQuote(); // a SplitQuoteView test fixture with order()+shares()
            }
        };
        SplitViewModel vm = new SplitViewModel(dining);
        vm.load(orderId);
        vm.setMode(SplitViewModel.EVEN);
        vm.setWays(2);
        vm.setWaiveServiceCharge(true);
        assertTrue(vm.quoteSplit(orderId));
        assertTrue(sawWaive[0]);
    }

    @Test
    void closeAllWithTokenUsesTokenOverload() {
        String[] sawToken = {null};
        DiningApi dining = new DiningApi(null) {
            @Override
            public SplitQuoteView quoteSplit(UUID orderId, QuoteSplitRequest req) { return evenQuote(); }
            @Override
            public List<SaleView> closeSplit(UUID orderId, SplitCloseRequest req, String bearerToken) {
                sawToken[0] = bearerToken;
                return List.of();
            }
        };
        SplitViewModel vm = new SplitViewModel(dining);
        vm.load(orderId);
        vm.setMode(SplitViewModel.EVEN);
        vm.setWays(2);
        vm.setApprovalToken("mgr-token");
        vm.setWaiveServiceCharge(true);
        vm.quoteSplit(orderId);
        // set a payment method for each of the 2 even guests so canCloseAll passes
        vm.setMethod(0, "CARD");
        vm.setMethod(1, "CARD");
        vm.closeAll(orderId);
        assertEquals("mgr-token", sawToken[0]);
    }
```

(`evenQuote()` = a small helper building a `SplitQuoteView(null, order, shares)` with 2 shares; add it if the test file lacks one. Confirm `setMethod`/`setWays`/`setMode` names against the real VM — they are shown in the exploration.)

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=SplitViewModelTest test`
Expected: compilation failure — `setWaiveServiceCharge`/`setApprovalToken` do not exist.

- [ ] **Step 3: Add waive/token state to `SplitViewModel`**

In `SplitViewModel.java`:

(a) Add the fields (near the other plain state fields):

```java
    private boolean waiveServiceCharge;
    private String approvalToken;
```

(b) Add the setters + a service-charge query (public, plain — read on the calling thread):

```java
    public void setWaiveServiceCharge(boolean waive) { this.waiveServiceCharge = waive; }

    public void setApprovalToken(String token) { this.approvalToken = token; }

    /** True when the current split quote includes a non-zero service charge (any bill / the order). */
    public boolean quoteHasServiceCharge() {
        if (quote == null) {
            return false;
        }
        if (quote.order() != null && quote.order().serviceChargeAmount() != null) {
            return quote.order().serviceChargeAmount().signum() > 0;
        }
        return quote.bills() != null && quote.bills().stream()
                .anyMatch(b -> b.serviceChargeAmount() != null && b.serviceChargeAmount().signum() > 0);
    }
```

(c) In `quoteSplit(...)`, pass the flag on BOTH request constructions (add the 4th arg):

```java
                q = dining.quoteSplit(orderId, new QuoteSplitRequest(BY_ITEM,
                        bills.stream().map(QuoteBillInput::new).toList(), null, waiveServiceCharge));
```
```java
                q = dining.quoteSplit(orderId, new QuoteSplitRequest(EVEN, null,
                        new QuoteEvenInput(ways), waiveServiceCharge));
```

(d) In `closeAll(...)`, set the flag on the `SplitCloseRequest` and use the token overload when a token is present:

```java
            req = new SplitCloseRequest(BY_ITEM, bills, null, waiveServiceCharge);
```
```java
            req = new SplitCloseRequest(EVEN, null,
                    new EvenSplitRequest(quotedWays, new ArrayList<>(methods)), waiveServiceCharge);
```

and change the close call:

```java
        try {
            results = approvalToken != null
                    ? dining.closeSplit(orderId, req, approvalToken)
                    : dining.closeSplit(orderId, req);   // SYNCHRONOUS — control-flow state
            ui.accept(() -> errorMessage.set(""));
            return true;
        } catch (ApiException e) {
```

- [ ] **Step 4: Add the async-dispatcher regression test**

Add to `SplitViewModelTest.java` — proves the waived-quote error surfaces via the deferred `ui` dispatcher (mirror the existing SplitViewModel async test if one exists):

```java
    @Test
    void deferredDispatcherHoldsWaivedQuoteErrorUntilDrained() {
        DiningApi dining = new DiningApi(null) {
            @Override
            public SplitQuoteView quoteSplit(UUID orderId, QuoteSplitRequest req) {
                throw new ApiException(400, new ProblemDetail("Bad Request", 400, "boom"), "HTTP 400");
            }
        };
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        SplitViewModel vm = new SplitViewModel(dining, queue::add);
        vm.load(orderId);
        while (!queue.isEmpty()) queue.poll().run();
        vm.setMode(SplitViewModel.EVEN);
        vm.setWays(2);
        vm.setWaiveServiceCharge(true);
        boolean ok = vm.quoteSplit(orderId);
        assertFalse(ok);                                  // synchronous return
        assertEquals("", vm.errorMessage().get());        // deferred: not applied yet
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals("boom", vm.errorMessage().get());
    }
```

- [ ] **Step 5: Wire the split controller**

In `SplitController.java`:

(a) Add `@FXML` fields for a waive button + chip in the tender phase:

```java
    @FXML private Button waiveButton;
    @FXML private HBox waiveChipRow;
    @FXML private Label waiveChipLabel;
    @FXML private Button removeWaiveButton;
```

(b) In `initialize()`, wire the handlers:

```java
        waiveButton.setOnAction(e -> waiveTapped());
        removeWaiveButton.setOnAction(e -> removeWaiver());
```

(c) Where the tender phase is shown after a successful quote (the `onDone` that reveals `tenderBox` / renders guest rows), drive the waive button/chip from `vm.quoteHasServiceCharge()`:

```java
        boolean scPresent = vm.quoteHasServiceCharge();
        waiveButton.setVisible(scPresent);
        waiveButton.setManaged(scPresent);
        waiveChipRow.setVisible(false);
        waiveChipRow.setManaged(false);
```

(after a waiver is applied and re-quoted, `quoteHasServiceCharge()` returns false, so the button hides and the chip shows — handled in `waiveTapped`'s success path below.)

(d) Add the handlers. Waiver is manager-gated (PIN upfront), then re-quote-split off the FX thread:

```java
    private void waiveTapped() {
        var creds = ManagerPinDialog.promptForApproval(
                "Waiving the service charge requires manager approval");
        if (creds.isEmpty()) {
            return;
        }
        FxTasks.run(() -> {
            ManagerAuth auth = services.authApi.pinLoginForToken(
                    creds.get().cashierCode(), creds.get().pin());
            if (!auth.isManager()) {
                throw new ApiException(403, null, "This account is not a manager");
            }
            vm.setApprovalToken(auth.token());
            vm.setWaiveServiceCharge(true);
            vm.quoteSplit(orderId);                 // re-quote WITH the waiver, off-thread
        }, () -> {
            renderTenders();                        // repaint phase-2 amounts from the new quote
            waiveButton.setVisible(false);
            waiveButton.setManaged(false);
            waiveChipLabel.setText("Service charge waived");
            waiveChipRow.setVisible(true);
            waiveChipRow.setManaged(true);
        }, err -> {
            String msg = err.getMessage();
            vm.setError(msg == null || msg.isBlank() ? "Manager approval failed" : msg);
        });
    }

    private void removeWaiver() {
        FxTasks.run(() -> {
            vm.setWaiveServiceCharge(false);
            vm.quoteSplit(orderId);
        }, () -> {
            renderTenders();
            waiveChipRow.setVisible(false);
            waiveChipRow.setManaged(false);
            waiveButton.setVisible(vm.quoteHasServiceCharge());
            waiveButton.setManaged(vm.quoteHasServiceCharge());
        }, err -> LOG.log(System.Logger.Level.ERROR, "Failed to re-quote after removing waiver", err));
    }
```

Implementer notes: confirm the real name of the method that renders the tender-phase amounts (called `renderTenders()` above — use the actual method the controller already uses after `vm.quoteSplit` succeeds, e.g. the body of the existing continue→quote `onDone`). Confirm `vm.setError(...)` exists on `SplitViewModel` (add a `setError` mirroring `PaymentViewModel.setError` if it does not — it writes `errorMessage` via `ui.accept`). Add imports for `ManagerPinDialog`, `ManagerAuth`, `ApiException`, `HBox`, `Label`, `Button` as needed. `services.authApi`/`services.session` are available on the controller's `services`.

- [ ] **Step 6: Add the waive button + chip to `split.fxml`**

In `split.fxml`, inside the tender-phase container (`tenderBox`), add near the `closeAllButton`:

```xml
        <Button fx:id="waiveButton" text="Waive service charge" styleClass="btn-secondary"
                visible="false" managed="false"/>
        <HBox fx:id="waiveChipRow" styleClass="chip-row" visible="false" managed="false">
          <Label fx:id="waiveChipLabel" styleClass="discount-chip"/>
          <Button fx:id="removeWaiveButton" text="✕" styleClass="chip-remove"/>
        </HBox>
```

(Reuse the same chip styleClasses as the payment screen so no new CSS is needed. Confirm `Button`/`HBox`/`Label` are imported in the FXML.)

- [ ] **Step 7: Run the split VM tests, then the full suite**

Run: `./mvnw -f pos-terminal/pom.xml -Dtest=SplitViewModelTest test`
Expected: PASS.
Run: `./mvnw -f pos-terminal/pom.xml clean test`
Expected: BUILD SUCCESS, all green.

- [ ] **Step 8: Add the Slice 13 manual-E2E section to the README**

Append to `pos-terminal/README.md` (after the Slice 12 section):

```markdown
## Slice 13 — Service-charge waiver (manual E2E)

**Prereq:** backend on `embedded,dev` with the service charge enabled
(`service.charge.enabled=true`, `service.charge.percent` > 0 in the configuration store); log in
(`manager`/`manager`).

1. **Payment screen:** open a dine-in table, add lines, go to Pay. The total shows a service charge.
   Tap **Waive service charge** → enter the manager PIN → the total drops the service charge, a
   "Service charge waived" chip appears, and paying tenders the reduced total. Remove the chip to
   restore the charge.
2. **Non-manager path:** cancel the PIN or use a cashier account → no waiver is applied; the charge
   stays.
3. **Split screen:** open a dine-in table with lines, go to Split, partition/choose even, Continue.
   In the tender phase tap **Waive service charge** → manager PIN → every guest's amount drops the
   service charge. Close all bills.
```

- [ ] **Step 9: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/SplitViewModel.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/SplitController.java \
        pos-terminal/src/main/resources/fxml/split.fxml \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/SplitViewModelTest.java \
        pos-terminal/README.md
git commit -m "feat(terminal): waive service charge on the split screen (manager-gated)

Closes terminal slice 13 (service-charge waiver).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Backend waive-aware `quoteOrder` (ungated preview) + tests → Task 1. ✓
- Backend waive-aware `quoteSplitByItem`/`quoteSplitEven` + tests → Task 2. ✓
- Terminal DTOs + `DiningApi.quoteOrder(waive)` + `closeSplit(token)` + tests → Task 3. ✓
- Payment-screen waive button + manager PIN + chip + thread into quote/close → Task 4. ✓
- Split-screen waive (VM state + token overload + controller PIN + chip) + tests + README → Task 5. ✓
- Manager-only enforced at close (unchanged); quote ungated (`callerIsManager=true`); no migration/config/enum change; `ModularityTests` after each backend task. ✓

**Placeholder scan:** every code step shows the code; the "confirm the real name/adapt" notes (config setter in T1, `stub.lastBody`/`lastAuthHeader` + JSON constants in T3, `renderTenders`/`setError` in T5) are deliberate guardrails against name drift in files not fully quoted here, each with the concrete intended shape — not TBDs.

**Type consistency:** `quoteOrder(UUID, Map, DiscountInput, boolean)` (T1 backend) ↔ endpoint `QuoteOrderRequest.waiveServiceCharge` (T1) ↔ terminal `DiningApi.quoteOrder(UUID, DiscountInput, boolean)` + client `QuoteOrderRequest` (T3) ↔ `PaymentController` call (T4). `quoteSplitByItem/Even(..., boolean)` (T2) ↔ `QuoteSplitRequest.waiveServiceCharge` (T2 + T3 client) ↔ `SplitViewModel.quoteSplit` (T5). `closeSplit(UUID, SplitCloseRequest, String)` defined T3, called T5. `SplitViewModel.setWaiveServiceCharge`/`setApprovalToken`/`quoteHasServiceCharge` defined T5, used T5. `CloseOrderRequest`/`SplitCloseRequest` already carry `waiveServiceCharge` (no change).

**Ungated-preview invariant:** every waive-aware quote method passes `callerIsManager=true` to `resolveApplyServiceCharge` (T1, T2) — the quote never throws for a cashier; the close/split-close paths (unchanged, manager-gated) remain the sole enforcement point. A cashier can preview a waived total but cannot complete it without a manager token.

**Back-compat compile safety:** `QuoteOrderRequest`/`QuoteSplitRequest` (client, T3) and the server request records (T1, T2) add the boolean with the existing constructors preserved (server records: primitive defaults to false on absent JSON; client records: explicit no-waiver secondary constructors), so `SplitViewModel`'s existing 3-arg `QuoteSplitRequest` calls keep compiling between T3 and T5.

**FX-threading:** payment `waiveServiceChargeTapped` and split `waiveTapped`/`removeWaiver` run the PIN exchange + re-quote in `FxTasks` work lambdas; UI/observable writes happen in `onDone` or via `ui.accept`; no blocking VM call sits in an `onDone`. `SplitViewModel` stays synchronous with an async-dispatcher regression test (T5 Step 4).
