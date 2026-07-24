# Returns & Refunds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a cashier look up a completed sale by receipt number, stage a per-line return, and — with manager PIN approval — process the refund on the JavaFX terminal, on top of the existing backend returns engine.

**Architecture:** One additive backend read endpoint (`GET /sales/by-receipt/{receiptNumber}` → `SaleView`, authenticated) whose repository query already exists; everything else is terminal-side — new `ReturnApi`, return DTOs, a `SalesApi.getSaleByReceipt`, a synchronous `ReturnsViewModel`, and a `ReturnsController`+`returns.fxml` reached from an always-visible Home tile. `POST /returns` is unchanged (already built, MANAGER-gated); the manager gate is satisfied by the terminal's one-shot PIN token via the `ApiClient` bearer-override overload.

**Tech Stack:** Java 21, Spring Boot 3.3 (Spring Modulith) backend; JavaFX standalone `pos-terminal` build; Jackson; JUnit 5 + MockMvc (backend) / StubServer (terminal).

## Global Constraints

- **JDK 21 required.** Before any Maven command: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`.
- **Backend build/test:** `./mvnw test -Dtest=<Class>` and `./mvnw test -Dtest=ModularityTests` (embedded profile, no Docker). **Terminal build/test:** `./mvnw -f pos-terminal/pom.xml test [-Dtest=<Class>]` (headless). The terminal is NOT in the root reactor.
- **Backend change is a single additive read endpoint.** No schema change, no Flyway migration (version ceiling untouched), no new module dependency — all within the `sales` module. `ModularityTests` must stay green.
- **Money is `BigDecimal`** + a sibling `currencyCode` String from the DTO; never `double`. Render `amount + " " + currencyCode`.
- **FX-threading convention:** ViewModels are synchronous and return plain values; the controller runs them off-thread via `FxTasks.run(work, onDone, onError)` and reads results only in the FX-thread `onDone` via a holder array. The only observable a VM writes off-thread is `errorMessage`, inside `ui.accept(...)`. `onDone` never calls a blocking (HTTP) VM method — chain I/O in `work`. `showAndWait()` (the PIN dialog) is UI, allowed on the FX thread, and is collected in the button handler BEFORE the `FxTasks` task.
- **Manager approval rides EXACTLY ONE call** via the `ApiClient` bearer-override overload (`returnApi.process(cmd, token)`); a 401 on that call never clears the cashier session.
- **Api classes** are `public` (non-final, so VM tests subclass with fakes), hold one `ApiClient`, delegate via `client.get(...)` / `client.post(...)`. Register as `public final` fields in `Services`.
- **New terminal DTOs** live in `com.company.pos.terminal.api.dto` and carry `@JsonIgnoreProperties(ignoreUnknown = true)`.
- **Entry tile is NOT role-gated** (visible to all signed-in users) — the manager gate is enforced only at submit via the PIN token.
- **Status/emphasis is colour + text**, never colour alone. Append new CSS under a `/* ---- Slice 19 — Returns & Refunds ---- */` block reusing existing tokens.

---

## File structure

**Backend (module `sales`)**
- Modify `src/main/java/com/company/pos/sales/api/SalesService.java` — add `getSaleByReceipt(String)`.
- Modify `src/main/java/com/company/pos/sales/application/DefaultSalesService.java` — implement it (mirrors `getSale`).
- Modify `src/main/java/com/company/pos/sales/web/SalesController.java` — add `GET /sales/by-receipt/{receiptNumber}`.
- Modify `src/test/java/com/company/pos/sales/SalesControllerTest.java` — add 200 + 404 tests.
- (No change to `SaleRepository` — `findByReceiptNumber(String)` already exists. No change to `ReturnController`.)

**Terminal**
- Modify `pos-terminal/.../api/dto/SaleLineView.java` — add `lineNo`, `unitPrice`.
- Modify `pos-terminal/.../api/SalesApi.java` — add `getSaleByReceipt`.
- Create `pos-terminal/.../api/dto/`: `ReturnCommand.java`, `ReturnLineRequest.java`, `ReturnView.java`, `SaleReturnLineView.java`, `ReturnPaymentView.java`.
- Create `pos-terminal/.../api/ReturnApi.java`; modify `pos-terminal/.../app/Services.java` (+ `returnApi`).
- Create `pos-terminal/.../viewmodel/ReturnsViewModel.java`.
- Create `pos-terminal/.../view/ReturnsController.java` + `pos-terminal/src/main/resources/fxml/returns.fxml`.
- Modify `pos-terminal/.../app/Navigator.java` (+ `toReturns`), `pos-terminal/.../view/HomeController.java` + `home.fxml` (Returns tile), `pos-terminal/src/main/resources/css/app.css`.
- Tests: `SalesControllerTest` (backend), `SalesApiTest` (add a method), `ReturnApiTest`, `ReturnsViewModelTest`.

---

## Task 1: Backend — `GET /sales/by-receipt/{receiptNumber}`

**Files:**
- Modify: `src/main/java/com/company/pos/sales/api/SalesService.java`
- Modify: `src/main/java/com/company/pos/sales/application/DefaultSalesService.java`
- Modify: `src/main/java/com/company/pos/sales/web/SalesController.java`
- Test: `src/test/java/com/company/pos/sales/SalesControllerTest.java`

**Interfaces:**
- Consumes: existing `SaleRepository.findByReceiptNumber(String) : Optional<Sale>`, existing private `DefaultSalesService.toView(Sale, List<PaymentView>)`, existing `DomainException.notFound(String)`.
- Produces: `SalesService.getSaleByReceipt(String receiptNumber) : SaleView`; HTTP `GET /sales/by-receipt/{receiptNumber}` → `SaleView` (200) / 404 when absent (authenticated, no role gate).

- [ ] **Step 1: Write the failing tests**

In `src/test/java/com/company/pos/sales/SalesControllerTest.java`, ensure these static imports exist (add any missing — the class already uses `post`/`status`):
```java
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
```
Add two test methods:
```java
    @Test
    void getByReceiptReturnsSale() throws Exception {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("1"));
        String body = mvc.perform(post("/sales").with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"cartId\":\"" + cart + "\",\"tenders\":[{\"method\":\"CASH\",\"tendered\":10.00}]}"))
                .andReturn().getResponse().getContentAsString();
        String receipt = com.jayway.jsonpath.JsonPath.read(body, "$.receiptNumber");
        String saleId = com.jayway.jsonpath.JsonPath.read(body, "$.id");

        mvc.perform(get("/sales/by-receipt/" + receipt).with(jwt().jwt(j -> j.subject("cashier1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(saleId))
                .andExpect(jsonPath("$.receiptNumber").value(receipt))
                .andExpect(jsonPath("$.lines[0].lineNo").exists());
    }

    @Test
    void getByReceiptUnknownReturnsNotFound() throws Exception {
        mvc.perform(get("/sales/by-receipt/NOPE-404-0").with(jwt().jwt(j -> j.subject("cashier1"))))
                .andExpect(status().isNotFound());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=SalesControllerTest`
Expected: `getByReceiptReturnsSale` FAILS (404 — no such endpoint yet); `getByReceiptUnknownReturnsNotFound` may pass vacuously (unmapped path → 404). After the endpoint exists both must pass for the right reasons.

- [ ] **Step 3: Add the interface method**

In `src/main/java/com/company/pos/sales/api/SalesService.java`, next to `SaleView getSale(UUID saleId);` add:
```java
    /** Fetch a committed sale by its unique receipt number; 404 (NOT_FOUND) when absent. */
    SaleView getSaleByReceipt(String receiptNumber);
```

- [ ] **Step 4: Implement it in `DefaultSalesService`**

In `src/main/java/com/company/pos/sales/application/DefaultSalesService.java`, directly below the existing `getSale(...)` method (around line 301), add — mirroring `getSale` exactly, reusing the existing `sales` repository field, `payments` port, `toView(...)` mapper, and `DomainException.notFound`:
```java
    @Override
    @Transactional(readOnly = true)
    public SaleView getSaleByReceipt(String receiptNumber) {
        Sale sale = sales.findByReceiptNumber(receiptNumber)
                .orElseThrow(() -> DomainException.notFound("No sale with receipt " + receiptNumber));
        return toView(sale, payments.findBySale(sale.getId()));
    }
```

- [ ] **Step 5: Add the controller endpoint**

In `src/main/java/com/company/pos/sales/web/SalesController.java`, directly below the existing `get(@PathVariable UUID saleId)` method (around line 75), add:
```java
    @GetMapping("/sales/by-receipt/{receiptNumber}")
    SaleView getByReceipt(@PathVariable String receiptNumber) {
        return sales.getSaleByReceipt(receiptNumber);
    }
```
(No `@PreAuthorize` — authenticated only, matching `get(saleId)`. `@GetMapping`/`@PathVariable` are already imported.)

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=SalesControllerTest`
Expected: PASS (all methods, including the two new ones).

- [ ] **Step 7: Re-run the boundary enforcer**

Run: `./mvnw test -Dtest=ModularityTests`
Expected: PASS (no new cross-module dependency was introduced).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/company/pos/sales/api/SalesService.java \
        src/main/java/com/company/pos/sales/application/DefaultSalesService.java \
        src/main/java/com/company/pos/sales/web/SalesController.java \
        src/test/java/com/company/pos/sales/SalesControllerTest.java
git commit -m "feat(sales): GET /sales/by-receipt/{receiptNumber} lookup"
```

---

## Task 2: Terminal — `SaleLineView.lineNo`/`unitPrice` + `SalesApi.getSaleByReceipt`

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/SaleLineView.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/SalesApi.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/SalesApiTest.java` (add one method)

**Interfaces:**
- Produces: `SaleLineView(int lineNo, String sku, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineTotal, List<SaleLineModifierView> modifiers)`.
- Produces: `SalesApi.getSaleByReceipt(String receiptNumber) : SaleView`.

- [ ] **Step 1: Add the failing test**

In `pos-terminal/src/test/java/com/company/pos/terminal/api/SalesApiTest.java`, add a test method (define the JSON inline so it carries `lineNo`/`unitPrice`):
```java
    @Test
    void getByReceiptHitsPathAndParsesLineNo() throws Exception {
        String saleJson = "{\"id\":\"11111111-1111-1111-1111-111111111111\","
                + "\"receiptNumber\":\"S01-T01-9\",\"currencyCode\":\"SAR\",\"subtotal\":4.50,"
                + "\"taxTotal\":0.68,\"serviceChargeAmount\":0.00,\"grandTotal\":5.18,"
                + "\"discountTotal\":0.00,\"payments\":[],"
                + "\"lines\":[{\"lineNo\":1,\"sku\":\"COLA\",\"name\":\"Cola Can\",\"quantity\":1,"
                + "\"unitPrice\":4.50,\"lineTotal\":5.18,\"modifiers\":[]}]}";
        try (StubServer stub = new StubServer(200, saleJson, "application/json")) {
            SalesApi api = new SalesApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            SaleView s = api.getSaleByReceipt("S01-T01-9");
            assertEquals("S01-T01-9", s.receiptNumber());
            assertEquals(1, s.lines().get(0).lineNo());
            assertEquals(0, new BigDecimal("4.50").compareTo(s.lines().get(0).unitPrice()));
            assertEquals("GET", stub.lastMethod);
            assertEquals("/sales/by-receipt/S01-T01-9", stub.lastPath);
        }
    }
```
(Ensure imports for `SaleView`, `BigDecimal`, `assertEquals` are present — the class already tests `SalesApi`; add any missing.)

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml test -Dtest=SalesApiTest`
Expected: FAIL — `getSaleByReceipt` does not exist and `SaleLineView.lineNo()`/`unitPrice()` do not exist (compile error).

- [ ] **Step 3: Expand `SaleLineView`**

Replace the record header in `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/SaleLineView.java`:
```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SaleLineView(int lineNo, String sku, String name, BigDecimal quantity,
        BigDecimal unitPrice, BigDecimal lineTotal, List<SaleLineModifierView> modifiers) {
}
```

- [ ] **Step 4: Fix any positional constructor call sites**

Run: `grep -rn "new SaleLineView(" pos-terminal/src`
For every hit (production or test), update the positional arguments to the new order `(lineNo, sku, name, quantity, unitPrice, lineTotal, modifiers)`. JSON deserialization sites are unaffected (Jackson binds by name). If there are no `new SaleLineView(` hits, this step is a no-op.

- [ ] **Step 5: Add `getSaleByReceipt` to `SalesApi`**

In `pos-terminal/src/main/java/com/company/pos/terminal/api/SalesApi.java`, add (below `discountPolicy()`):
```java
    /** GET /sales/by-receipt/{receiptNumber} — fetch a committed sale by its printed receipt number
     *  (for staging a return). A 404/miss throws ApiException. */
    public SaleView getSaleByReceipt(String receiptNumber) {
        return client.get("/sales/by-receipt/" + receiptNumber, new TypeReference<SaleView>() {});
    }
```

- [ ] **Step 6: Run the full terminal suite**

Run: `./mvnw -f pos-terminal/pom.xml test`
Expected: PASS (the new `SalesApiTest` method + all existing tests; the `SaleLineView` field addition compiles everywhere).

- [ ] **Step 7: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/dto/SaleLineView.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/SalesApi.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/SalesApiTest.java
git commit -m "feat(terminal): SaleLineView lineNo/unitPrice + SalesApi.getSaleByReceipt"
```

---

## Task 3: Terminal — Return DTOs + `ReturnApi` (+ Services)

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ReturnLineRequest.java`, `ReturnCommand.java`, `ReturnPaymentView.java`, `SaleReturnLineView.java`, `ReturnView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/ReturnApi.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/ReturnApiTest.java`

**Interfaces:**
- Consumes: `ApiClient.post(String, Object, TypeReference<T>, String bearerToken)` (Task none — exists).
- Produces DTOs: `ReturnLineRequest(int lineNo, BigDecimal quantity)`; `ReturnCommand(UUID originalSaleId, String receiptNumber, List<ReturnLineRequest> lines)`; `ReturnPaymentView(String method, BigDecimal amount, String maskedPan)`; `SaleReturnLineView(int lineNo, String sku, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxAmount, BigDecimal lineTotal, String currencyCode)`; `ReturnView(UUID id, String creditNoteNumber, UUID originalSaleId, String status, String currencyCode, BigDecimal refundSubtotal, BigDecimal refundTaxTotal, BigDecimal refundGrandTotal, Instant createdAt, List<SaleReturnLineView> lines, List<ReturnPaymentView> refunds)`.
- Produces: `ReturnApi(ApiClient client)` with `ReturnView process(ReturnCommand command, String managerToken)`.
- Produces: `Services.returnApi`.

- [ ] **Step 1: Write the failing test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/api/ReturnApiTest.java`:
```java
package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.dto.ReturnCommand;
import com.company.pos.terminal.api.dto.ReturnLineRequest;
import com.company.pos.terminal.api.dto.ReturnView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReturnApiTest {

    private static final UUID SALE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String RETURN_JSON =
            "{\"id\":\"22222222-2222-2222-2222-222222222222\",\"creditNoteNumber\":\"CN-1\","
            + "\"originalSaleId\":\"11111111-1111-1111-1111-111111111111\",\"status\":\"COMPLETED\","
            + "\"currencyCode\":\"SAR\",\"refundSubtotal\":9.00,\"refundTaxTotal\":1.35,"
            + "\"refundGrandTotal\":10.35,\"lines\":[],"
            + "\"refunds\":[{\"method\":\"CASH\",\"amount\":10.35,\"maskedPan\":null}]}";

    @Test
    void processPostsReturnWithManagerToken() throws Exception {
        try (StubServer stub = new StubServer(201, RETURN_JSON, "application/json")) {
            ReturnApi api = new ReturnApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            ReturnView v = api.process(new ReturnCommand(SALE_ID, null,
                    List.of(new ReturnLineRequest(1, new BigDecimal("2")))), "mgr-token");
            assertEquals("CN-1", v.creditNoteNumber());
            assertEquals(0, new BigDecimal("10.35").compareTo(v.refundGrandTotal()));
            assertEquals("POST", stub.lastMethod);
            assertEquals("/returns", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"originalSaleId\":\"" + SALE_ID + "\""));
            assertTrue(stub.lastBody.contains("\"lineNo\":1"));
            assertTrue(stub.lastBody.contains("\"quantity\":2"));
            assertEquals("Bearer mgr-token", stub.lastAuth);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=ReturnApiTest`
Expected: FAIL — DTOs and `ReturnApi` do not exist.

- [ ] **Step 3: Create the request DTOs**

`.../api/dto/ReturnLineRequest.java`:
```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReturnLineRequest(int lineNo, BigDecimal quantity) {
}
```

`.../api/dto/ReturnCommand.java`:
```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReturnCommand(UUID originalSaleId, String receiptNumber, List<ReturnLineRequest> lines) {
}
```

- [ ] **Step 4: Create the response DTOs**

`.../api/dto/ReturnPaymentView.java`:
```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReturnPaymentView(String method, BigDecimal amount, String maskedPan) {
}
```

`.../api/dto/SaleReturnLineView.java`:
```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SaleReturnLineView(int lineNo, String sku, String name, BigDecimal quantity,
        BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxAmount, BigDecimal lineTotal,
        String currencyCode) {
}
```

`.../api/dto/ReturnView.java`:
```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReturnView(UUID id, String creditNoteNumber, UUID originalSaleId, String status,
        String currencyCode, BigDecimal refundSubtotal, BigDecimal refundTaxTotal,
        BigDecimal refundGrandTotal, Instant createdAt, List<SaleReturnLineView> lines,
        List<ReturnPaymentView> refunds) {
}
```

- [ ] **Step 5: Create `ReturnApi`**

`.../api/ReturnApi.java`:
```java
package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.ReturnCommand;
import com.company.pos.terminal.api.dto.ReturnView;
import com.fasterxml.jackson.core.type.TypeReference;

/** Typed client for {@code POST /returns}. Non-final so view-model tests subclass it. Returns are
 *  MANAGER-gated, so {@code process} always rides a one-shot manager bearer token. */
public class ReturnApi {

    private final ApiClient client;

    public ReturnApi(ApiClient client) {
        this.client = client;
    }

    /** POST /returns with the manager's one-shot token; returns the authoritative ReturnView. */
    public ReturnView process(ReturnCommand command, String managerToken) {
        return client.post("/returns", command, new TypeReference<ReturnView>() {}, managerToken);
    }
}
```

- [ ] **Step 6: Register `returnApi` in `Services`**

In `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`: add import `import com.company.pos.terminal.api.ReturnApi;`, field `public final ReturnApi returnApi;` (near `salesApi`), and constructor line `this.returnApi = new ReturnApi(apiClient);`.

- [ ] **Step 7: Run test to verify it passes**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=ReturnApiTest`
Expected: PASS (1 test).

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ReturnLineRequest.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ReturnCommand.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ReturnPaymentView.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/SaleReturnLineView.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ReturnView.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/ReturnApi.java \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/ReturnApiTest.java
git commit -m "feat(terminal): ReturnApi + return DTOs (+ Services wiring)"
```

---

## Task 4: Terminal — `ReturnsViewModel`

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/ReturnsViewModel.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/ReturnsViewModelTest.java`

**Interfaces:**
- Consumes: `SalesApi.getSaleByReceipt`, `ReturnApi.process`, `AuthApi.pinLoginForToken(String,String) : ManagerAuth`, `ManagerAuth.isManager()/token()`, `ApiException.status()/problem()`.
- Produces: ctors `ReturnsViewModel(SalesApi, ReturnApi, AuthApi)` and `(SalesApi, ReturnApi, AuthApi, Consumer<Runnable> ui)`; `ReadOnlyStringProperty errorMessage()`; `SaleView lookup(String receiptNumber)`; `ReturnView process(UUID saleId, List<ReturnLineRequest> lines, String cashierCode, String pin)`. Both fetches return `null` on failure and set `errorMessage`.

- [ ] **Step 1: Write the failing test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/ReturnsViewModelTest.java`:
```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.AuthApi;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.ReturnApi;
import com.company.pos.terminal.api.SalesApi;
import com.company.pos.terminal.api.dto.ManagerAuth;
import com.company.pos.terminal.api.dto.ReturnCommand;
import com.company.pos.terminal.api.dto.ReturnLineRequest;
import com.company.pos.terminal.api.dto.ReturnView;
import com.company.pos.terminal.api.dto.SaleLineView;
import com.company.pos.terminal.api.dto.SaleView;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ReturnsViewModelTest {

    private static final UUID SALE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private SaleView sale() {
        return new SaleView(SALE_ID, "S01-T01-9", new BigDecimal("4.50"), new BigDecimal("0.68"),
                BigDecimal.ZERO, new BigDecimal("5.18"), "SAR", BigDecimal.ZERO,
                List.of(new SaleLineView(1, "COLA", "Cola Can", BigDecimal.ONE, new BigDecimal("4.50"),
                        new BigDecimal("5.18"), List.of())),
                List.of());
    }

    private ReturnView returnView() {
        return new ReturnView(UUID.randomUUID(), "CN-1", SALE_ID, "COMPLETED", "SAR",
                new BigDecimal("4.50"), new BigDecimal("0.68"), new BigDecimal("5.18"), null,
                List.of(), List.of());
    }

    @Test
    void lookupReturnsSale() {
        SalesApi sales = new SalesApi(null) {
            @Override public SaleView getSaleByReceipt(String r) { return sale(); }
        };
        ReturnsViewModel vm = new ReturnsViewModel(sales, new ReturnApi(null), new AuthApi(null, null));
        SaleView s = vm.lookup("S01-T01-9");
        assertEquals("S01-T01-9", s.receiptNumber());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void lookupMissSetsFriendlyMessage() {
        SalesApi sales = new SalesApi(null) {
            @Override public SaleView getSaleByReceipt(String r) {
                throw new ApiException(404, new ProblemDetail("Not Found", 404, "No sale with receipt X"),
                        "HTTP 404");
            }
        };
        ReturnsViewModel vm = new ReturnsViewModel(sales, new ReturnApi(null), new AuthApi(null, null));
        assertNull(vm.lookup("BOGUS"));
        assertEquals("No sale found for that receipt", vm.errorMessage().get());
    }

    @Test
    void processHappyPathReturnsView() {
        ReturnApi returns = new ReturnApi(null) {
            @Override public ReturnView process(ReturnCommand c, String token) { return returnView(); }
        };
        AuthApi auth = new AuthApi(null, null) {
            @Override public ManagerAuth pinLoginForToken(String code, String pin) {
                return new ManagerAuth("tok", "mgr", Set.of("MANAGER"));
            }
        };
        ReturnsViewModel vm = new ReturnsViewModel(new SalesApi(null), returns, auth);
        ReturnView v = vm.process(SALE_ID, List.of(new ReturnLineRequest(1, BigDecimal.ONE)), "m1", "1234");
        assertEquals("CN-1", v.creditNoteNumber());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void processRejectsNonManagerWithoutCallingReturns() {
        boolean[] called = {false};
        ReturnApi returns = new ReturnApi(null) {
            @Override public ReturnView process(ReturnCommand c, String token) {
                called[0] = true; return returnView();
            }
        };
        AuthApi auth = new AuthApi(null, null) {
            @Override public ManagerAuth pinLoginForToken(String code, String pin) {
                return new ManagerAuth("tok", "cashier", Set.of("CASHIER"));
            }
        };
        ReturnsViewModel vm = new ReturnsViewModel(new SalesApi(null), returns, auth);
        assertNull(vm.process(SALE_ID, List.of(new ReturnLineRequest(1, BigDecimal.ONE)), "c1", "0000"));
        assertEquals("This account is not a manager", vm.errorMessage().get());
        org.junit.jupiter.api.Assertions.assertEquals(false, called[0]);
    }

    @Test
    void processSurfacesErrorUnderDeferredDispatcher() {
        AuthApi auth = new AuthApi(null, null) {
            @Override public ManagerAuth pinLoginForToken(String code, String pin) {
                return new ManagerAuth("tok", "mgr", Set.of("MANAGER"));
            }
        };
        ReturnApi returns = new ReturnApi(null) {
            @Override public ReturnView process(ReturnCommand c, String token) {
                throw new ApiException(422, new ProblemDetail("Unprocessable", 422,
                        "return quantity exceeds sold"), "HTTP 422");
            }
        };
        Deque<Runnable> queue = new ArrayDeque<>();
        Consumer<Runnable> deferred = queue::add;
        ReturnsViewModel vm = new ReturnsViewModel(new SalesApi(null), returns, auth, deferred);

        ReturnView v = vm.process(SALE_ID, List.of(new ReturnLineRequest(1, BigDecimal.ONE)), "m1", "1234");

        assertNull(v);                              // synchronous truth: failed
        assertEquals("", vm.errorMessage().get());  // deferred: not applied yet
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals("return quantity exceeds sold", vm.errorMessage().get());
    }
}
```
NOTE: the `SaleView` constructor order used above is the terminal DTO's actual order `(id, receiptNumber, subtotal, taxTotal, serviceChargeAmount, grandTotal, currencyCode, discountTotal, lines, payments)`. Confirm against `pos-terminal/.../api/dto/SaleView.java` and adjust the test's `sale()` literal if the field order differs. Also confirm the `AuthApi` constructor signature (`new AuthApi(ApiClient, SessionManager)`) — the fakes pass `(null, null)`; match the real ctor arity.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=ReturnsViewModelTest`
Expected: FAIL — `ReturnsViewModel` does not exist.

- [ ] **Step 3: Create `ReturnsViewModel`**

Create `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/ReturnsViewModel.java`:
```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.AuthApi;
import com.company.pos.terminal.api.ReturnApi;
import com.company.pos.terminal.api.SalesApi;
import com.company.pos.terminal.api.dto.ManagerAuth;
import com.company.pos.terminal.api.dto.ReturnCommand;
import com.company.pos.terminal.api.dto.ReturnLineRequest;
import com.company.pos.terminal.api.dto.ReturnView;
import com.company.pos.terminal.api.dto.SaleView;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the returns screen. Synchronous (the controller runs it off the FX thread via
 * FxTasks); the only off-thread observable write is {@code errorMessage} inside {@code ui}. The
 * manager gate is enforced here (pinLoginForToken -> isManager) before the one-shot-token POST.
 */
public class ReturnsViewModel {

    private final SalesApi salesApi;
    private final ReturnApi returnApi;
    private final AuthApi authApi;
    private final Consumer<Runnable> ui;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public ReturnsViewModel(SalesApi salesApi, ReturnApi returnApi, AuthApi authApi) {
        this(salesApi, returnApi, authApi, Runnable::run);
    }

    public ReturnsViewModel(SalesApi salesApi, ReturnApi returnApi, AuthApi authApi,
            Consumer<Runnable> ui) {
        this.salesApi = salesApi;
        this.returnApi = returnApi;
        this.authApi = authApi;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    /** Fetch the sale to return by receipt number; null on failure (message via errorMessage). */
    public SaleView lookup(String receiptNumber) {
        try {
            SaleView sale = salesApi.getSaleByReceipt(receiptNumber.trim());
            ui.accept(() -> errorMessage.set(""));
            return sale;
        } catch (ApiException e) {
            String msg = e.status() == 404 ? "No sale found for that receipt" : messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return null;
        }
    }

    /** Elevate via manager PIN then POST the return; null on failure/non-manager (message set). */
    public ReturnView process(UUID saleId, List<ReturnLineRequest> lines, String cashierCode,
            String pin) {
        try {
            ManagerAuth auth = authApi.pinLoginForToken(cashierCode, pin);
            if (!auth.isManager()) {
                ui.accept(() -> errorMessage.set("This account is not a manager"));
                return null;
            }
            ReturnView view = returnApi.process(new ReturnCommand(saleId, null, lines), auth.token());
            ui.accept(() -> errorMessage.set(""));
            return view;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return null;
        }
    }

    /** Prefer the server's ProblemDetail (detail, then title), else the exception message. */
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

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=ReturnsViewModelTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/ReturnsViewModel.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/ReturnsViewModelTest.java
git commit -m "feat(terminal): ReturnsViewModel (receipt lookup + manager-gated process)"
```

---

## Task 5: Terminal — Returns screen + Home tile + CSS

No headless controller test (FXML/controller). Gate: clean compile + full terminal suite (incl. `FxmlContractTest`/`AppCssTest`).

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/ReturnsController.java`
- Create: `pos-terminal/src/main/resources/fxml/returns.fxml`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/HomeController.java`
- Modify: `pos-terminal/src/main/resources/fxml/home.fxml`
- Modify: `pos-terminal/src/main/resources/css/app.css`

**Interfaces:**
- Consumes: `Services.salesApi`, `Services.returnApi`, `services.authApi`, `ReturnsViewModel`, `FxTasks.run`, `ManagerPinDialog.promptForApproval(String) : Optional<Credentials(cashierCode, pin)>`, `Navigator`.
- Produces: `Navigator.toReturns()`.

- [ ] **Step 1: Add `toReturns()` to Navigator**

In `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java`, add (mirroring existing routes):
```java
    public void toReturns() {
        com.company.pos.terminal.view.ReturnsController controller =
                new com.company.pos.terminal.view.ReturnsController(services, this);
        setScene("/fxml/returns.fxml", controller);
    }
```

- [ ] **Step 2: Create `returns.fxml`**

Create `pos-terminal/src/main/resources/fxml/returns.fxml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<?import javafx.scene.control.Button?>
<?import javafx.scene.control.Label?>
<?import javafx.scene.control.TableColumn?>
<?import javafx.scene.control.TableView?>
<?import javafx.scene.control.TextField?>
<?import javafx.scene.layout.BorderPane?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.layout.Pane?>
<?import javafx.scene.layout.StackPane?>
<?import javafx.scene.layout.VBox?>

<BorderPane styleClass="screen" xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml">
    <top>
        <VBox spacing="8" styleClass="screen-header">
            <HBox spacing="16" alignment="CENTER_LEFT">
                <Label text="Returns" styleClass="title"/>
                <Label fx:id="errorLabel" styleClass="error-text"/>
                <Pane HBox.hgrow="ALWAYS"/>
                <Button fx:id="backButton" text="Back" styleClass="btn-secondary"/>
            </HBox>
            <HBox spacing="8" alignment="CENTER_LEFT">
                <Label text="Receipt #"/>
                <TextField fx:id="receiptField" promptText="e.g. S01-T01-42"/>
                <Button fx:id="findButton" text="Find sale" styleClass="btn-primary"/>
            </HBox>
        </VBox>
    </top>
    <center>
        <StackPane fx:id="bodyArea" styleClass="returns-body">
            <VBox fx:id="stagePane" spacing="12" visible="false" managed="false">
                <Label fx:id="saleHeaderLabel" styleClass="section-label"/>
                <TableView fx:id="linesTable" VBox.vgrow="ALWAYS">
                    <columns>
                        <TableColumn fx:id="colName" text="Item"/>
                        <TableColumn fx:id="colSku" text="SKU"/>
                        <TableColumn fx:id="colSold" text="Qty sold"/>
                        <TableColumn fx:id="colLineTotal" text="Line total"/>
                        <TableColumn fx:id="colReturnQty" text="Return qty"/>
                    </columns>
                </TableView>
                <HBox spacing="12" alignment="CENTER_LEFT">
                    <Button fx:id="returnAllButton" text="Return all" styleClass="btn-secondary"/>
                    <Pane HBox.hgrow="ALWAYS"/>
                    <Label fx:id="estimateLabel" styleClass="subtitle"/>
                    <Button fx:id="processButton" text="Process return" styleClass="btn-primary"/>
                </HBox>
            </VBox>
            <VBox fx:id="resultPane" spacing="12" visible="false" managed="false">
                <Label fx:id="creditNoteLabel" styleClass="title"/>
                <Label fx:id="refundTotalLabel" styleClass="section-label"/>
                <TableView fx:id="refundsTable" VBox.vgrow="ALWAYS">
                    <columns>
                        <TableColumn fx:id="colRefundMethod" text="Method"/>
                        <TableColumn fx:id="colRefundAmount" text="Refunded"/>
                    </columns>
                </TableView>
                <HBox spacing="12">
                    <Button fx:id="newReturnButton" text="New return" styleClass="btn-secondary"/>
                    <Button fx:id="doneButton" text="Done" styleClass="btn-primary"/>
                </HBox>
            </VBox>
        </StackPane>
    </center>
</BorderPane>
```

- [ ] **Step 3: Create `ReturnsController`**

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/ReturnsController.java`:
```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.ReturnLineRequest;
import com.company.pos.terminal.api.dto.ReturnPaymentView;
import com.company.pos.terminal.api.dto.ReturnView;
import com.company.pos.terminal.api.dto.SaleLineView;
import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.ReturnsViewModel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.VBox;
import javafx.util.converter.BigDecimalStringConverter;

/** Returns screen: receipt lookup -> per-line return-qty -> manager PIN -> refund result. */
public class ReturnsController implements Navigator.Screen {

    private static final System.Logger LOG = System.getLogger(ReturnsController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final ReturnsViewModel vm;

    private SaleView currentSale;
    private String currencyCode = "";

    @FXML private Label errorLabel;
    @FXML private Button backButton;
    @FXML private TextField receiptField;
    @FXML private Button findButton;
    @FXML private VBox stagePane;
    @FXML private Label saleHeaderLabel;
    @FXML private TableView<Row> linesTable;
    @FXML private TableColumn<Row, String> colName;
    @FXML private TableColumn<Row, String> colSku;
    @FXML private TableColumn<Row, String> colSold;
    @FXML private TableColumn<Row, String> colLineTotal;
    @FXML private TableColumn<Row, BigDecimal> colReturnQty;
    @FXML private Button returnAllButton;
    @FXML private Label estimateLabel;
    @FXML private Button processButton;
    @FXML private VBox resultPane;
    @FXML private Label creditNoteLabel;
    @FXML private Label refundTotalLabel;
    @FXML private TableView<ReturnPaymentView> refundsTable;
    @FXML private TableColumn<ReturnPaymentView, String> colRefundMethod;
    @FXML private TableColumn<ReturnPaymentView, String> colRefundAmount;
    @FXML private Button newReturnButton;
    @FXML private Button doneButton;

    public ReturnsController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new ReturnsViewModel(services.salesApi, services.returnApi, services.authApi,
                Platform::runLater);
    }

    @FXML
    public void initialize() {
        errorLabel.textProperty().bind(vm.errorMessage());
        errorLabel.visibleProperty().bind(vm.errorMessage().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        colName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().line.name()));
        colSku.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().line.sku()));
        colSold.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().line.quantity().stripTrailingZeros().toPlainString()));
        colLineTotal.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().line.lineTotal().toPlainString() + " " + currencyCode));
        colReturnQty.setCellValueFactory(c -> c.getValue().returnQty);
        colReturnQty.setCellFactory(TextFieldTableCell.forTableColumn(new BigDecimalStringConverter()));
        colReturnQty.setOnEditCommit(e -> {
            Row row = e.getRowValue();
            row.setClamped(e.getNewValue());
            recomputeEstimate();
            linesTable.refresh();
        });
        linesTable.setEditable(true);

        colRefundMethod.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().method()));
        colRefundAmount.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().amount().toPlainString() + " " + currencyCode));

        findButton.setOnAction(e -> find());
        receiptField.setOnAction(e -> find());
        returnAllButton.setOnAction(e -> {
            linesTable.getItems().forEach(r -> r.returnQty.set(r.line.quantity()));
            recomputeEstimate();
            linesTable.refresh();
        });
        processButton.setOnAction(e -> process());
        newReturnButton.setOnAction(e -> reset());
        doneButton.setOnAction(e -> navigator.toHome());
        backButton.setOnAction(e -> navigator.toHome());
    }

    private void find() {
        String receipt = receiptField.getText();
        if (receipt == null || receipt.isBlank()) {
            return;
        }
        SaleView[] holder = new SaleView[1];
        FxTasks.run(
                () -> holder[0] = vm.lookup(receipt),
                () -> { if (holder[0] != null) showSale(holder[0]); },
                err -> LOG.log(System.Logger.Level.ERROR, "Sale lookup failed", err));
    }

    private void showSale(SaleView sale) {
        currentSale = sale;
        currencyCode = sale.currencyCode();
        saleHeaderLabel.setText("Receipt " + sale.receiptNumber() + "  •  "
                + sale.grandTotal().toPlainString() + " " + currencyCode);
        List<Row> rows = new ArrayList<>();
        for (SaleLineView l : sale.lines()) {
            rows.add(new Row(l));
        }
        linesTable.setItems(FXCollections.observableArrayList(rows));
        recomputeEstimate();
        resultPane.setVisible(false);
        resultPane.setManaged(false);
        stagePane.setVisible(true);
        stagePane.setManaged(true);
    }

    private void recomputeEstimate() {
        BigDecimal est = BigDecimal.ZERO;
        for (Row r : linesTable.getItems()) {
            BigDecimal qty = r.returnQty.get();
            if (qty != null && qty.signum() > 0 && r.line.quantity().signum() > 0) {
                est = est.add(r.line.lineTotal().multiply(qty)
                        .divide(r.line.quantity(), 2, RoundingMode.HALF_UP));
            }
        }
        estimateLabel.setText("≈ " + est.setScale(2, RoundingMode.HALF_UP).toPlainString()
                + " " + currencyCode);
    }

    private void process() {
        if (currentSale == null) {
            return;
        }
        List<ReturnLineRequest> lines = new ArrayList<>();
        for (Row r : linesTable.getItems()) {
            BigDecimal qty = r.returnQty.get();
            if (qty != null && qty.signum() > 0) {
                lines.add(new ReturnLineRequest(r.line.lineNo(), qty));
            }
        }
        if (lines.isEmpty()) {
            estimateLabel.setText("Select at least one line to return");
            return;
        }
        Optional<ManagerPinDialog.Credentials> creds =
                ManagerPinDialog.promptForApproval("Manager approval required to process this return");
        if (creds.isEmpty()) {
            return;
        }
        UUID saleId = currentSale.id();
        String code = creds.get().cashierCode();
        String pin = creds.get().pin();
        ReturnView[] holder = new ReturnView[1];
        FxTasks.run(
                () -> holder[0] = vm.process(saleId, lines, code, pin),
                () -> { if (holder[0] != null) showResult(holder[0]); },
                err -> LOG.log(System.Logger.Level.ERROR, "Return processing failed", err));
    }

    private void showResult(ReturnView view) {
        creditNoteLabel.setText("Credit note " + view.creditNoteNumber());
        refundTotalLabel.setText("Refunded " + view.refundGrandTotal().toPlainString() + " "
                + view.currencyCode());
        currencyCode = view.currencyCode();
        refundsTable.setItems(FXCollections.observableArrayList(view.refunds()));
        stagePane.setVisible(false);
        stagePane.setManaged(false);
        resultPane.setVisible(true);
        resultPane.setManaged(true);
    }

    private void reset() {
        currentSale = null;
        receiptField.clear();
        linesTable.setItems(FXCollections.observableArrayList());
        resultPane.setVisible(false);
        resultPane.setManaged(false);
        stagePane.setVisible(false);
        stagePane.setManaged(false);
    }

    /** Table row model: the sold line + an editable, clamped return quantity. */
    private static final class Row {
        private final SaleLineView line;
        private final SimpleObjectProperty<BigDecimal> returnQty = new SimpleObjectProperty<>(BigDecimal.ZERO);

        Row(SaleLineView line) {
            this.line = line;
        }

        void setClamped(BigDecimal value) {
            BigDecimal v = value == null ? BigDecimal.ZERO : value;
            if (v.signum() < 0) {
                v = BigDecimal.ZERO;
            }
            if (v.compareTo(line.quantity()) > 0) {
                v = line.quantity();
            }
            returnQty.set(v);
        }
    }
}
```

- [ ] **Step 4: Add the Returns tile to Home (visible to all)**

In `pos-terminal/src/main/resources/fxml/home.fxml`, add to the second `HBox` (the `home-tile` row, currently dine-in/retail/kitchen):
```xml
      <Button fx:id="returnsButton" text="Returns" styleClass="home-tile"/>
```
In `pos-terminal/src/main/java/com/company/pos/terminal/view/HomeController.java`: add the field `@FXML private Button returnsButton;` alongside the other tile buttons, and in `initialize()` — NOT role-gated (visible to all, like the Kitchen tile) — wire only the action:
```java
        returnsButton.setOnAction(e -> navigator.toReturns());
```

- [ ] **Step 5: Add returns styles**

Append to `pos-terminal/src/main/resources/css/app.css`:
```css
/* ---- Slice 19 — Returns & Refunds ---- */
.returns-body { -fx-padding: 16; }
```
(If `.screen-header`, `.section-label`, `.subtitle`, `.error-text`, `.title` are not all already defined by earlier slices, reuse the closest existing token rather than inventing a colour. `AppCssTest` will flag an undefined style class.)

- [ ] **Step 6: Compile + full terminal suite**

Run: `./mvnw -f pos-terminal/pom.xml test`
Expected: PASS (all existing + new). Resolve any FXML `fx:id`↔`@FXML` mismatch (`FxmlContractTest`) or CSS issue (`AppCssTest`). Every `@FXML` field must have a matching `fx:id` in `returns.fxml` and vice versa.

- [ ] **Step 7: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/ReturnsController.java \
        pos-terminal/src/main/resources/fxml/returns.fxml \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/HomeController.java \
        pos-terminal/src/main/resources/fxml/home.fxml \
        pos-terminal/src/main/resources/css/app.css
git commit -m "feat(terminal): Returns screen + Home Returns tile + CSS"
```

---

## Task 6: Integration gate + docs

**Files:**
- Modify: `docs/run-modes.md`

- [ ] **Step 1: Full backend build (backend changed this slice)**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw verify`
Expected: PASS — full compile + all backend tests + `ModularityTests`. (If Docker is unavailable, the Testcontainers PostgreSQL tests may be skipped/fail on environment, not on this change — if so, fall back to `./mvnw test` and note it.)

- [ ] **Step 2: Full clean terminal build**

Run: `./mvnw -f pos-terminal/pom.xml clean test`
Expected: PASS (all terminal tests). If `QuoteApiTest` alone flakes with an HTTP 403, rerun it in isolation to confirm the known StubServer flake, not this change.

- [ ] **Step 3: Document the returns surfacing**

In `docs/run-modes.md`, under the `## Returns & Refunds (Phase 4)` section, add a short note matching the document's style: the JavaFX terminal now surfaces returns as a **Returns** screen (Home tile, visible to all; the refund is manager-PIN-gated at submit) that looks a sale up by receipt via the new **`GET /sales/by-receipt/{receiptNumber}`** (authenticated) and posts the existing MANAGER-gated `POST /returns` with a one-shot manager token — terminal slice 19. Note the one new backend endpoint.

- [ ] **Step 4: Commit**

```bash
git add docs/run-modes.md
git commit -m "docs: terminal surfaces returns + GET /sales/by-receipt (slice 19)"
```

---

## Self-review notes (author)

- **Spec coverage:** backend by-receipt endpoint (T1), terminal SaleLineView.lineNo + SalesApi.getSaleByReceipt (T2), return DTOs + ReturnApi (T3), ReturnsViewModel with manager-gated process (T4), Returns screen + Home tile + CSS (T5), integration gate + docs (T6). Every spec section maps to a task.
- **Backend minimalism:** the only backend change is the read endpoint + its service method; `SaleRepository.findByReceiptNumber` already exists, no schema/Flyway change, no new module dependency (ModularityTests re-run in T1 Step 7 and T6).
- **Type consistency:** terminal DTO fields copied from the backend records; `ReturnCommand`/`ReturnLineRequest` field names (`originalSaleId`, `lineNo`, `quantity`) match the backend and the `ReturnApiTest` body assertions; `getSaleByReceipt`/`process` names identical across Api (T2/T3), VM (T4), and controller (T5); `SaleView`/`SaleLineView` constructor order used in the T4 test is flagged to be confirmed against the actual DTO before relying on it.
- **FX-threading:** both controller actions (`find`, `process`) run the blocking VM call in the `FxTasks` work lambda and render in `onDone`; the PIN dialog is collected in the button handler on the FX thread BEFORE the task; no blocking VM method runs in `onDone`. The VM writes `errorMessage` only inside `ui.accept`, and has a deferred-dispatcher regression test.
- **Manager gate:** the return rides exactly one call via `returnApi.process(cmd, token)` (bearer-override); a non-manager PIN is rejected in the VM before any POST; a 401 on the override never clears the cashier session.
- **Known verify-before-code hooks (called out inline):** confirm the terminal `SaleView` constructor field order (T4 test literal) and the `AuthApi(ApiClient, SessionManager)` ctor arity (T4 fakes); grep and fix any positional `new SaleLineView(` call sites (T2 Step 4); confirm `.screen-header`/`.section-label`/`.subtitle`/`.error-text` style classes exist before reusing (T5 Step 5).
