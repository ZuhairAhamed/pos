# Slice 8 — Email Receipt Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a cashier-initiated **Email receipt** action to the terminal payment success screen, backed by a new fake `Emailer` port and a `POST /sales/{saleId}/send-receipt` endpoint that renders the receipt server-side.

**Architecture:** A new `Emailer` port in `device.api` (beside `Printer`) with an `InMemoryEmailer` fake. The `receipt` module renders the receipt body (reusing the exact lines the printer produces) and calls `Emailer`. The `sales` module exposes `emailReceipt(saleId, address)` on the existing `receipt→device` and `sales→receipt` edges — **no module dependency changes**. The terminal POSTs an address to the per-sale endpoint; the server renders authoritatively. Single-sale closes only (retail + whole-order dine-in).

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Modulith, Maven; JavaFX 21 terminal (separate build). No new dependency.

## Global Constraints

- **JDK 21:** `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` before any build. The repo path contains spaces and `&` — quote it.
- **Backend build/test:** `./mvnw test` (embedded profile, in-memory SQLite, no Docker). **Terminal build/test:** `./mvnw -f pos-terminal/pom.xml clean test` (headless; no TestFX/display).
- **No new module dependencies.** The `Emailer` port rides existing edges (`receipt→device`, `sales→receipt`). No `allowedDependencies` edits. Re-run `ModularityTests` after backend changes: `./mvnw test -Dtest=ModularityTests`.
- **No schema migration, no config keys, no new Maven dependency.** The fake `Emailer` needs no SMTP settings.
- **Fakes-only convention.** `Emailer` is a port; `InMemoryEmailer` is its only implementation this slice (logs + records for tests), mirroring `InMemoryPrinter` / `InMemoryNotifier`.
- **Terminal holds no business rules and renders no receipt content.** It passes an address; the server renders. DTOs carry `@JsonIgnoreProperties(ignoreUnknown = true)` where they parse responses (the request DTO here needs none).
- **MVVM sync-VM convention:** ViewModel methods are synchronous on the calling thread and return plain values; the controller runs them off the FX thread via `FxTasks` and reads results in the FX-thread `onDone` via a holder array. The only observable written off-thread is `errorMessage`, inside `ui.accept(...)`. An async-dispatcher regression test is mandatory.
- **CSS uses existing emerald tokens / `derive()` only**; new tap targets ≥ 56px.
- Commit trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. **Never stage or commit the pre-existing `M CLAUDE.md`.**

---

## File Structure

**Backend (root reactor):**
- Create `src/main/java/com/company/pos/device/api/EmailMessage.java` — port DTO.
- Create `src/main/java/com/company/pos/device/api/Emailer.java` — port interface.
- Create `src/main/java/com/company/pos/device/infrastructure/InMemoryEmailer.java` — fake adapter.
- Modify `src/main/java/com/company/pos/receipt/api/ReceiptService.java` — add `emailReceipt`.
- Modify `src/main/java/com/company/pos/receipt/application/DefaultReceiptService.java` — inject `Emailer`, extract `renderLines`, add `emailReceipt`.
- Modify `src/main/java/com/company/pos/sales/api/SalesService.java` — add `emailReceipt`.
- Modify `src/main/java/com/company/pos/sales/application/DefaultSalesService.java` — extract `buildReceiptData`, add `emailReceipt`.
- Modify `src/main/java/com/company/pos/sales/web/SalesController.java` — add endpoint + request record.
- Tests: `src/test/java/com/company/pos/device/DevicePortContractTest.java` (+ FakeEmailer), `InMemoryDeviceAdapterTest.java` (+ emailer test) or new `InMemoryEmailerTest`; `src/test/java/com/company/pos/receipt/ReceiptServiceTest.java` (+ email test); `src/test/java/com/company/pos/sales/SalesControllerTest.java` (+ 3 tests).

**Terminal (separate build):**
- Create `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/EmailReceiptRequest.java`.
- Modify `pos-terminal/src/main/java/com/company/pos/terminal/api/SalesApi.java` — add `emailReceipt`.
- Modify `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/PaymentViewModel.java` — add `emailReceipt`.
- Modify `pos-terminal/src/main/resources/fxml/payment.fxml` — email button, dialog, confirm label.
- Modify `pos-terminal/src/main/java/com/company/pos/terminal/view/PaymentController.java` — wire dialog.
- Modify `pos-terminal/src/main/resources/css/app.css` — `.email-dialog`, `.email-confirm`.
- Tests: `pos-terminal/src/test/java/com/company/pos/terminal/api/SalesApiTest.java`, `viewmodel/PaymentViewModelTest.java`, `FxmlContractTest.java`, `AppCssTest.java`.

**Docs:** `docs/run-modes.md`, `pos-terminal/README.md`.

---

## Task 1: Backend `Emailer` port + `InMemoryEmailer` fake

**Files:**
- Create: `src/main/java/com/company/pos/device/api/EmailMessage.java`
- Create: `src/main/java/com/company/pos/device/api/Emailer.java`
- Create: `src/main/java/com/company/pos/device/infrastructure/InMemoryEmailer.java`
- Test: `src/test/java/com/company/pos/device/DevicePortContractTest.java` (add), `src/test/java/com/company/pos/device/InMemoryDeviceAdapterTest.java` (add)

**Interfaces:**
- Produces: `com.company.pos.device.api.EmailMessage(String to, String subject, String body)`; `com.company.pos.device.api.Emailer.send(EmailMessage)`; `InMemoryEmailer.sent()` → `List<EmailMessage>`, `InMemoryEmailer.clear()`.

Note: `device.api` is already tagged `@NamedInterface("api")` in its `package-info.java`; the new types are exposed automatically. `device`'s `allowedDependencies` is `{ "common" }` — unchanged. `PrintLine`'s accessor is `text()` / `bold()` (record).

- [ ] **Step 1: Write the failing contract test** — append to `DevicePortContractTest` (a plain JUnit test, no Spring):

```java
    /** A fake fulfilling the Emailer port — proves the interface is implementable/usable. */
    static final class FakeEmailer implements com.company.pos.device.api.Emailer {
        final List<com.company.pos.device.api.EmailMessage> sent = new ArrayList<>();
        @Override
        public void send(com.company.pos.device.api.EmailMessage message) {
            sent.add(message);
        }
    }

    @Test
    void emailerAcceptsAMessage() {
        FakeEmailer emailer = new FakeEmailer();
        emailer.send(new com.company.pos.device.api.EmailMessage("a@b.com", "Receipt S1", "TOTAL 10.00"));
        assertThat(emailer.sent).hasSize(1);
        assertThat(emailer.sent.get(0).to()).isEqualTo("a@b.com");
        assertThat(emailer.sent.get(0).subject()).isEqualTo("Receipt S1");
        assertThat(emailer.sent.get(0).body()).contains("TOTAL");
    }
```

- [ ] **Step 2: Run it to verify it fails** — `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` then `./mvnw test -Dtest=DevicePortContractTest`. Expected: FAIL to compile (`EmailMessage` / `Emailer` do not exist).

- [ ] **Step 3: Create the port DTO** — `device/api/EmailMessage.java`:

```java
package com.company.pos.device.api;

/** A rendered outbound email. The {@code body} is plain text this phase. */
public record EmailMessage(String to, String subject, String body) {
}
```

- [ ] **Step 4: Create the port interface** — `device/api/Emailer.java`:

```java
package com.company.pos.device.api;

/**
 * Port for sending an already-rendered email. The only implementation this phase is the
 * in-memory fake; a real SMTP adapter implements this later with no change to callers.
 */
public interface Emailer {

    void send(EmailMessage message);
}
```

- [ ] **Step 5: Create the fake adapter** — `device/infrastructure/InMemoryEmailer.java` (mirrors `InMemoryPrinter` / `InMemoryNotifier`):

```java
package com.company.pos.device.infrastructure;

import com.company.pos.device.api.EmailMessage;
import com.company.pos.device.api.Emailer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Default {@link Emailer} adapter: logs and records mail in memory. Replaced by an SMTP adapter later. */
@Component
public class InMemoryEmailer implements Emailer {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEmailer.class);

    private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(EmailMessage message) {
        sent.add(message);
        log.info("EMAIL to={} subject={}", message.to(), message.subject());
    }

    public List<EmailMessage> sent() {
        return List.copyOf(sent);
    }

    public void clear() {
        sent.clear();
    }
}
```

- [ ] **Step 6: Add the Spring-wired fake test** — append to `InMemoryDeviceAdapterTest`. Add fields/imports:

```java
    @Autowired
    com.company.pos.device.api.Emailer emailer;
    @Autowired
    com.company.pos.device.infrastructure.InMemoryEmailer inMemoryEmailer;
```

```java
    @Test
    void emailerRecordsSentMessages() {
        inMemoryEmailer.clear();
        emailer.send(new com.company.pos.device.api.EmailMessage("guest@example.com", "Receipt", "TOTAL 10.00"));
        assertThat(inMemoryEmailer.sent()).extracting(com.company.pos.device.api.EmailMessage::to)
                .containsExactly("guest@example.com");
    }
```

- [ ] **Step 7: Run tests to verify they pass** — `./mvnw test -Dtest=DevicePortContractTest,InMemoryDeviceAdapterTest`. Expected: PASS.

- [ ] **Step 8: Run ModularityTests** — `./mvnw test -Dtest=ModularityTests`. Expected: PASS (no boundary change).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/company/pos/device/api/EmailMessage.java \
        src/main/java/com/company/pos/device/api/Emailer.java \
        src/main/java/com/company/pos/device/infrastructure/InMemoryEmailer.java \
        src/test/java/com/company/pos/device/DevicePortContractTest.java \
        src/test/java/com/company/pos/device/InMemoryDeviceAdapterTest.java
git commit -m "feat(device): add fake Emailer port for receipt delivery

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 2: `receipt` module renders + sends

**Files:**
- Modify: `src/main/java/com/company/pos/receipt/api/ReceiptService.java`
- Modify: `src/main/java/com/company/pos/receipt/application/DefaultReceiptService.java`
- Test: `src/test/java/com/company/pos/receipt/ReceiptServiceTest.java`

**Interfaces:**
- Consumes: `device.api.Emailer`, `device.api.EmailMessage`, `device.api.PrintLine` (Task 1).
- Produces: `ReceiptService.emailReceipt(String to, ReceiptData data)`.

The refactor keeps `print()` output **byte-for-byte identical** (same `PrintLine` list including bold flags), so existing `ReceiptServiceTest` / `ServiceChargeReceiptTest` / `ReceiptShowsDiscountTest` pass unchanged. `receipt` already depends on `device` (uses `Printer`), so no boundary change.

- [ ] **Step 1: Write the failing test** — append to `ReceiptServiceTest`. Add imports + an autowired emailer, and a `@BeforeEach` clear:

```java
import com.company.pos.device.api.EmailMessage;
import com.company.pos.device.infrastructure.InMemoryEmailer;
import org.junit.jupiter.api.BeforeEach;
```

```java
    @Autowired
    InMemoryEmailer emailer;

    @BeforeEach
    void clearEmail() {
        emailer.clear();
    }

    @Test
    void emailReceiptSendsRenderedBodyToEmailer() {
        ReceiptData data = new ReceiptData("S01-T01-000042", "cashier", Instant.now(),
                List.of(new ReceiptLineData("Cola Can", new BigDecimal("2"),
                        new BigDecimal("4.50"), new BigDecimal("10.35"))),
                new BigDecimal("9.00"), new BigDecimal("1.35"), new BigDecimal("10.35"),
                List.of(new ReceiptPaymentData("CASH", new BigDecimal("10.35"),
                        new BigDecimal("11.00"), new BigDecimal("0.65"), null)),
                "SAR");

        receipts.emailReceipt("guest@example.com", data);

        assertThat(emailer.sent()).hasSize(1);
        EmailMessage msg = emailer.sent().get(0);
        assertThat(msg.to()).isEqualTo("guest@example.com");
        assertThat(msg.subject()).contains("S01-T01-000042");
        assertThat(msg.body()).contains("Cola Can");
        assertThat(msg.body()).contains("TOTAL");
        assertThat(msg.body()).contains("\n");
    }
```

- [ ] **Step 2: Run it to verify it fails** — `./mvnw test -Dtest=ReceiptServiceTest`. Expected: FAIL to compile (`emailReceipt` not on `ReceiptService`).

- [ ] **Step 3: Add the method to the port** — `receipt/api/ReceiptService.java`:

```java
package com.company.pos.receipt.api;

public interface ReceiptService {

    void print(ReceiptData data);

    /** Renders {@code data} as a plain-text body and emails it to {@code to}. */
    void emailReceipt(String to, ReceiptData data);
}
```

- [ ] **Step 4: Refactor `DefaultReceiptService`** — inject `Emailer`, extract the line-building into `renderLines`, and add `emailReceipt`. Add imports:

```java
import com.company.pos.device.api.EmailMessage;
import com.company.pos.device.api.Emailer;
import java.util.stream.Collectors;
```

Change the field block + constructor:

```java
    private final Printer printer;
    private final Emailer emailer;
    private final ConfigurationService config;

    DefaultReceiptService(Printer printer, Emailer emailer, ConfigurationService config) {
        this.printer = printer;
        this.emailer = emailer;
        this.config = config;
    }
```

Replace the current `print(ReceiptData data)` method (the whole method body that builds `lines` and calls `printer.print/cut`) with a `renderLines` helper plus thin `print`/`emailReceipt`. Rename `public void print(ReceiptData data) {` to `private List<PrintLine> renderLines(ReceiptData data) {`, and at its end replace:

```java
        printer.print(lines);
        printer.cut();
    }
```

with:

```java
        return lines;
    }

    @Override
    public void print(ReceiptData data) {
        printer.print(renderLines(data));
        printer.cut();
    }

    @Override
    public void emailReceipt(String to, ReceiptData data) {
        String body = renderLines(data).stream().map(PrintLine::text)
                .collect(Collectors.joining("\n"));
        String subject = "Your receipt " + data.receiptNumber();
        emailer.send(new EmailMessage(to, subject, body));
    }
```

(Everything between the signature and the old `printer.print(lines)` — the `storeName`/lines/subtotal/tax/TOTAL/payments building into the local `List<PrintLine> lines` — is unchanged; it now lives in `renderLines`. The `money(...)` private helper is unchanged.)

- [ ] **Step 5: Run the receipt tests to verify they pass** — `./mvnw test -Dtest=ReceiptServiceTest,ServiceChargeReceiptTest,ReceiptShowsDiscountTest`. Expected: PASS (email test green; print tests unchanged).

- [ ] **Step 6: Run ModularityTests** — `./mvnw test -Dtest=ModularityTests`. Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/pos/receipt/api/ReceiptService.java \
        src/main/java/com/company/pos/receipt/application/DefaultReceiptService.java \
        src/test/java/com/company/pos/receipt/ReceiptServiceTest.java
git commit -m "feat(receipt): render receipt to email via Emailer port

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 3: `sales` module — service method + endpoint

**Files:**
- Modify: `src/main/java/com/company/pos/sales/api/SalesService.java`
- Modify: `src/main/java/com/company/pos/sales/application/DefaultSalesService.java`
- Modify: `src/main/java/com/company/pos/sales/web/SalesController.java`
- Test: `src/test/java/com/company/pos/sales/SalesControllerTest.java`

**Interfaces:**
- Consumes: `ReceiptService.emailReceipt` (Task 2); existing `sales.findById`, `payments.findBySale`, `DomainException.validation/notFound`.
- Produces: `SalesService.emailReceipt(UUID saleId, String toAddress)`; `POST /sales/{saleId}/send-receipt` body `{ "email": "..." }` → 204.

`DomainException.validation(...)` → HTTP 400; `.notFound(...)` → 404 (via `ErrorCode`). The endpoint has no `@PreAuthorize`, matching `reprint` (any authenticated user; anonymous → 401 by `SecurityConfig`). Address is validated **before** the DB lookup.

- [ ] **Step 1: Write the failing web tests** — append to `SalesControllerTest` (it already seeds `COLA` and shows the checkout+reprint pattern). Add import:

```java
import java.util.UUID;
```

(already imported) and these tests:

```java
    @Test
    void emailReceiptReturnsNoContent() throws Exception {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("1"));
        String body = mvc.perform(post("/sales").with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"cartId\":\"" + cart + "\",\"tenders\":[{\"method\":\"CASH\",\"tendered\":10.00}]}"))
                .andReturn().getResponse().getContentAsString();
        String saleId = com.jayway.jsonpath.JsonPath.read(body, "$.id");

        mvc.perform(post("/sales/" + saleId + "/send-receipt").with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"email\":\"guest@example.com\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void emailReceiptRejectsMalformedAddress() throws Exception {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("1"));
        String body = mvc.perform(post("/sales").with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"cartId\":\"" + cart + "\",\"tenders\":[{\"method\":\"CASH\",\"tendered\":10.00}]}"))
                .andReturn().getResponse().getContentAsString();
        String saleId = com.jayway.jsonpath.JsonPath.read(body, "$.id");

        mvc.perform(post("/sales/" + saleId + "/send-receipt").with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emailReceiptUnknownSaleReturnsNotFound() throws Exception {
        mvc.perform(post("/sales/" + UUID.randomUUID() + "/send-receipt").with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"email\":\"guest@example.com\"}"))
                .andExpect(status().isNotFound());
    }
```

- [ ] **Step 2: Run them to verify they fail** — `./mvnw test -Dtest=SalesControllerTest`. Expected: FAIL (404 for the endpoint path / no mapping).

- [ ] **Step 3: Add the method to the service interface** — `sales/api/SalesService.java`, after `void reprint(UUID saleId);`:

```java
    /**
     * Renders the stored sale's receipt and emails it to {@code toAddress}. Throws a
     * validation error (400) for a blank/malformed address and not-found (404) for an
     * unknown sale. Unlike print, delivery failure is NOT swallowed.
     */
    void emailReceipt(UUID saleId, String toAddress);
```

- [ ] **Step 4: Implement in `DefaultSalesService`** — extract `buildReceiptData` from the existing `printReceipt`, then add `emailReceipt`. Replace the current `printReceipt(Sale sale, List<PaymentView> salePayments)` method (lines ~311–335) with:

```java
    private ReceiptData buildReceiptData(Sale sale, List<PaymentView> salePayments) {
        List<ReceiptLineData> lines = sale.getLines().stream()
                .map(l -> {
                    List<ReceiptLineModifierData> mods = l.getModifiers().stream()
                            .map(m -> new ReceiptLineModifierData(m.getName(), m.getPriceDelta()))
                            .toList();
                    return new ReceiptLineData(l.getName(), l.getQuantity(), l.getUnitPrice(),
                            l.getLineTotal(), l.getGrossAmount(), l.getLineDiscountAmount(), mods);
                })
                .toList();
        List<ReceiptPaymentData> pays = salePayments.stream()
                .map(p -> new ReceiptPaymentData(p.method(), p.amount(), p.amountTendered(),
                        p.changeDue(), p.maskedPan()))
                .toList();
        return new ReceiptData(sale.getReceiptNumber(), sale.getCashierUsername(),
                sale.getCreatedAt(), lines, sale.getSubtotal(), sale.getTaxTotal(),
                sale.getGrandTotal(), pays, sale.getCurrencyCode(), sale.getDiscountTotal(),
                sale.getTxnDiscountAmount(), sale.getTxnDiscountReason(),
                sale.getServiceChargeAmount());
    }

    private void printReceipt(Sale sale, List<PaymentView> salePayments) {
        try {
            receipts.print(buildReceiptData(sale, salePayments));
        } catch (RuntimeException ex) {
            log.warn("Receipt print failed for sale {} ({}) — sale is recorded; reprint available",
                    sale.getId(), sale.getReceiptNumber(), ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void emailReceipt(UUID saleId, String toAddress) {
        String addr = toAddress == null ? "" : toAddress.trim();
        if (addr.isBlank() || !addr.contains("@")) {
            throw DomainException.validation("A valid email address is required");
        }
        Sale sale = sales.findById(saleId)
                .orElseThrow(() -> DomainException.notFound("No sale " + saleId));
        receipts.emailReceipt(addr, buildReceiptData(sale, payments.findBySale(saleId)));
    }
```

Confirm `DomainException` is imported (it is — used by `reprint`). `@Transactional` and `@Override` are already used in the file.

- [ ] **Step 5: Add the endpoint** — `sales/web/SalesController.java`. Add the request record near the other nested record and the mapping after `reprint`:

```java
    /** Body for POST /sales/{saleId}/send-receipt. */
    record EmailReceiptRequest(String email) {
    }

    @PostMapping("/sales/{saleId}/send-receipt")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void sendReceipt(@PathVariable UUID saleId, @RequestBody EmailReceiptRequest body) {
        sales.emailReceipt(saleId, body.email());
    }
```

(`@RequestBody`, `@PathVariable`, `@PostMapping`, `@ResponseStatus`, `HttpStatus`, `UUID` are already imported.)

- [ ] **Step 6: Run the sales tests to verify they pass** — `./mvnw test -Dtest=SalesControllerTest`. Expected: PASS (204/400/404). Also run the checkout/reprint regressions: `./mvnw test -Dtest=CheckoutServiceTest,ReceiptShowsDiscountTest`.

- [ ] **Step 7: Run ModularityTests** — `./mvnw test -Dtest=ModularityTests`. Expected: PASS.

- [ ] **Step 8: Run the full backend suite** — `./mvnw -q test`. Expected: BUILD SUCCESS, 0 failures/errors. (A post-`System.exit(0)` "Surefire is going to kill self fork JVM" line from the scheduled poller is benign, not a failure.)

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/company/pos/sales/api/SalesService.java \
        src/main/java/com/company/pos/sales/application/DefaultSalesService.java \
        src/main/java/com/company/pos/sales/web/SalesController.java \
        src/test/java/com/company/pos/sales/SalesControllerTest.java
git commit -m "feat(sales): POST /sales/{id}/send-receipt emails the stored sale

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 4: Terminal `SalesApi.emailReceipt` + request DTO

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/EmailReceiptRequest.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/SalesApi.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/SalesApiTest.java`

**Interfaces:**
- Produces: `SalesApi.emailReceipt(UUID saleId, String email)` → `POST /sales/{saleId}/send-receipt` body `{ "email": ... }`, 204 No Content (void).

Mirrors the existing `reprint(UUID)` shape. The `StubServer` test helper is in the same test package (`com.company.pos.terminal.api`); its constructor is `(int status, String body, String contentType)` and it records `lastMethod` / `lastPath` / `lastBody`.

- [ ] **Step 1: Write the failing test** — append to `SalesApiTest`:

```java
    @Test
    void emailReceiptPostsAddressToSendReceiptEndpoint() throws Exception {
        UUID saleId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        try (StubServer stub = new StubServer(204, "", "application/json")) {
            SalesApi api = new SalesApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            api.emailReceipt(saleId, "guest@example.com");
            assertEquals("POST", stub.lastMethod);
            assertEquals("/sales/" + saleId + "/send-receipt", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"email\":\"guest@example.com\""));
        }
    }
```

- [ ] **Step 2: Run it to verify it fails** — `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` then `./mvnw -f pos-terminal/pom.xml test -Dtest=SalesApiTest`. Expected: FAIL to compile (`emailReceipt` missing).

- [ ] **Step 3: Create the request DTO** — `pos-terminal/.../api/dto/EmailReceiptRequest.java`:

```java
package com.company.pos.terminal.api.dto;

/** Request body for POST /sales/{saleId}/send-receipt. */
public record EmailReceiptRequest(String email) {
}
```

- [ ] **Step 4: Add the client method** — `SalesApi.java`. Add import `import com.company.pos.terminal.api.dto.EmailReceiptRequest;` and, after `reprint`:

```java
    /** {@code POST /sales/{id}/send-receipt} — emails the stored sale's receipt. 204 No Content. */
    public void emailReceipt(UUID saleId, String email) {
        client.post("/sales/" + saleId + "/send-receipt", new EmailReceiptRequest(email),
                new TypeReference<Void>() {});
    }
```

- [ ] **Step 5: Run it to verify it passes** — `./mvnw -f pos-terminal/pom.xml test -Dtest=SalesApiTest`. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/dto/EmailReceiptRequest.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/SalesApi.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/SalesApiTest.java
git commit -m "feat(terminal): SalesApi.emailReceipt posts to send-receipt endpoint

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 5: Terminal `PaymentViewModel.emailReceipt`

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/PaymentViewModel.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/PaymentViewModelTest.java`

**Interfaces:**
- Consumes: `SalesApi.emailReceipt` (Task 4); the VM already holds `SalesApi sales` and uses it in `reprint()`.
- Produces: `boolean PaymentViewModel.emailReceipt(String toAddress)` — true when accepted and sent; false (with `errorMessage` set) on no-sale-yet, blank/malformed address, or `ApiException`.

Mirrors the existing `reprint()` method (sibling; no new gateway interface). The return value is synchronous even under a deferred `ui` dispatcher (only observable writes are deferred via `ui.accept`), so the controller can read it in the FX-thread `onDone`.

- [ ] **Step 1: Write the failing tests** — append to `PaymentViewModelTest` (uses the existing `RecordingGateway`, `sale28_75()` helpers and the `SalesApi(null){…}` override idiom from the reprint tests):

```java
    @Test
    void emailReceiptCallsSalesApiWithSaleIdAndAddress() {
        RecordingGateway gw = new RecordingGateway();
        List<UUID> ids = new ArrayList<>();
        List<String> addrs = new ArrayList<>();
        SalesApi sales = new SalesApi(null) {
            @Override public void emailReceipt(UUID saleId, String email) { ids.add(saleId); addrs.add(email); }
        };
        PaymentViewModel vm = new PaymentViewModel(gw, sales, new BigDecimal("28.75"));
        vm.setAuthoritativeTotal(new BigDecimal("28.75"));
        vm.payFull("CARD", null);
        assertTrue(vm.emailReceipt("guest@example.com"));
        assertEquals(1, ids.size());
        assertEquals(vm.sale().get().id(), ids.get(0));
        assertEquals("guest@example.com", addrs.get(0));
    }

    @Test
    void emailReceiptRejectsBlankAndMalformedWithoutCallingApi() {
        RecordingGateway gw = new RecordingGateway();
        int[] calls = {0};
        SalesApi sales = new SalesApi(null) {
            @Override public void emailReceipt(UUID saleId, String email) { calls[0]++; }
        };
        PaymentViewModel vm = new PaymentViewModel(gw, sales, new BigDecimal("28.75"));
        vm.setAuthoritativeTotal(new BigDecimal("28.75"));
        vm.payFull("CARD", null);
        assertFalse(vm.emailReceipt("   "));
        assertFalse(vm.emailReceipt("not-an-email"));
        assertEquals(0, calls[0]);
        assertTrue(vm.errorMessage().get().toLowerCase().contains("email"));
    }

    @Test
    void emailReceiptReturnsFalseBeforeAnySale() {
        RecordingGateway gw = new RecordingGateway();
        int[] calls = {0};
        SalesApi sales = new SalesApi(null) {
            @Override public void emailReceipt(UUID saleId, String email) { calls[0]++; }
        };
        PaymentViewModel vm = new PaymentViewModel(gw, sales, new BigDecimal("28.75"));
        assertFalse(vm.emailReceipt("guest@example.com"));
        assertEquals(0, calls[0]);
    }

    @Test
    void emailReceiptFailureSurfacesError() {
        RecordingGateway gw = new RecordingGateway();
        SalesApi sales = new SalesApi(null) {
            @Override public void emailReceipt(UUID saleId, String email) {
                throw new ApiException(400, null, "invalid email address");
            }
        };
        PaymentViewModel vm = new PaymentViewModel(gw, sales, new BigDecimal("28.75"));
        vm.setAuthoritativeTotal(new BigDecimal("28.75"));
        vm.payFull("CARD", null);
        assertFalse(vm.emailReceipt("x@y.com"));
        assertEquals("invalid email address", vm.errorMessage().get());
    }

    @Test
    void emailReceiptSucceedsUnderDeferredDispatcher() {
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        RecordingGateway gw = new RecordingGateway();
        List<String> addrs = new ArrayList<>();
        SalesApi sales = new SalesApi(null) {
            @Override public void emailReceipt(UUID saleId, String email) { addrs.add(email); }
        };
        PaymentViewModel vm = new PaymentViewModel(gw, sales, new BigDecimal("28.75"), queue::add);
        vm.setAuthoritativeTotal(new BigDecimal("28.75"));
        vm.payFull("CARD", null);
        while (!queue.isEmpty()) queue.poll().run();          // drain payFull's deferred writes → sale set
        assertTrue(vm.emailReceipt("guest@example.com"),
                "emailReceipt returns synchronously regardless of the UI dispatcher");
        assertEquals(1, addrs.size());
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals("", vm.errorMessage().get());
    }
```

- [ ] **Step 2: Run them to verify they fail** — `./mvnw -f pos-terminal/pom.xml test -Dtest=PaymentViewModelTest`. Expected: FAIL to compile (`emailReceipt` missing).

- [ ] **Step 3: Add the method** — `PaymentViewModel.java`, after `reprint()`:

```java
    /**
     * Emails the closed sale's receipt to {@code toAddress}. Returns true when the address was
     * accepted and the send succeeded. No sale yet, a blank/malformed address, or an API error
     * sets {@link #errorMessage()} (where applicable) and returns false. Synchronous like
     * {@link #reprint()} — the controller runs it off the FX thread and reads the result.
     */
    public boolean emailReceipt(String toAddress) {
        SaleView current = sale.get();
        if (current == null) {
            return false;
        }
        String addr = toAddress == null ? "" : toAddress.trim();
        if (addr.isBlank() || !addr.contains("@")) {
            ui.accept(() -> errorMessage.set("Enter a valid email address"));
            return false;
        }
        try {
            sales.emailReceipt(current.id(), addr);
            ui.accept(() -> errorMessage.set(""));
            return true;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return false;
        }
    }
```

- [ ] **Step 4: Run them to verify they pass** — `./mvnw -f pos-terminal/pom.xml test -Dtest=PaymentViewModelTest`. Expected: PASS (all, including the deferred-dispatcher test).

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/PaymentViewModel.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/PaymentViewModelTest.java
git commit -m "feat(terminal): PaymentViewModel.emailReceipt validates and sends

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 6: Terminal success screen — email button, dialog, CSS

**Files:**
- Modify: `pos-terminal/src/main/resources/fxml/payment.fxml`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/PaymentController.java`
- Modify: `pos-terminal/src/main/resources/css/app.css`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/FxmlContractTest.java`, `pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java`

**Interfaces:**
- Consumes: `PaymentViewModel.emailReceipt` (Task 5); existing `FxTasks.run`, the `vm.errorMessage()`-bound `errorLabel`.
- Produces: fx:ids `emailReceiptButton`, `emailDialog`, `emailField`, `emailSendButton`, `emailCancelButton`, `emailConfirmLabel`; CSS classes `.email-dialog`, `.email-confirm`.

The email dialog is an inline region (shown via `visible`/`managed`), consistent with the discount chip / result-box pattern already in this FXML. `showSegment`-style visibility flips are set in `initialize()` before the scene shows, so no flash. On send success the dialog hides and `emailConfirmLabel` shows a persistent "Sent to {addr}" note (relaxed from a timed toast — no `PauseTransition` needed); it is cleared/hidden whenever the dialog is reopened.

- [ ] **Step 1: Write the failing FXML + CSS contract tests.** In `FxmlContractTest`, add:

```java
    @Test
    void paymentDeclaresEmailReceiptNodes() throws Exception {
        String fxml = resource("/fxml/payment.fxml");
        for (String id : new String[] {
            "emailReceiptButton", "emailDialog", "emailField",
            "emailSendButton", "emailCancelButton", "emailConfirmLabel"
        }) {
            assertTrue(fxml.contains("fx:id=\"" + id + "\""), "missing email node: " + id);
        }
    }
```

In `AppCssTest`, add:

```java
    @Test
    void definesSliceEightClasses() throws Exception {
        String css = css();
        for (String cls : new String[] { ".email-dialog", ".email-confirm" }) {
            assertTrue(css.contains(cls), "missing style class: " + cls);
        }
    }
```

- [ ] **Step 2: Run them to verify they fail** — `./mvnw -f pos-terminal/pom.xml test -Dtest=FxmlContractTest,AppCssTest`. Expected: FAIL (nodes/classes absent).

- [ ] **Step 3: Edit `payment.fxml`.** Replace the final action-row block inside `resultBox` (the `<HBox>` currently holding `reprintButton` + `doneButton`, lines ~113–116) with:

```xml
      <HBox spacing="12" alignment="CENTER" maxWidth="Infinity">
        <Button fx:id="reprintButton" text="Reprint receipt" styleClass="btn-secondary" maxWidth="Infinity" HBox.hgrow="ALWAYS"/>
        <Button fx:id="emailReceiptButton" text="Email receipt" styleClass="btn-secondary" maxWidth="Infinity" HBox.hgrow="ALWAYS"/>
      </HBox>

      <!-- Email receipt: hidden until "Email receipt" is tapped. Free-type address; server renders. -->
      <VBox fx:id="emailDialog" spacing="8" styleClass="email-dialog" visible="false" managed="false" maxWidth="Infinity">
        <Label text="Email this receipt" styleClass="field-label"/>
        <TextField fx:id="emailField" promptText="name@example.com" maxWidth="Infinity"/>
        <HBox spacing="12" alignment="CENTER" maxWidth="Infinity">
          <Button fx:id="emailCancelButton" text="Cancel" styleClass="btn-secondary" maxWidth="Infinity" HBox.hgrow="ALWAYS"/>
          <Button fx:id="emailSendButton" text="Send" styleClass="btn-primary" maxWidth="Infinity" HBox.hgrow="ALWAYS"/>
        </HBox>
      </VBox>
      <Label fx:id="emailConfirmLabel" styleClass="email-confirm" visible="false" managed="false" wrapText="true" maxWidth="Infinity"/>

      <Button fx:id="doneButton" text="Done" styleClass="btn-primary" maxWidth="Infinity"/>
```

(`TextField`, `Label`, `Button`, `HBox`, `VBox` are already imported in this FXML.)

- [ ] **Step 4: Edit `PaymentController`.** Add the `@FXML` fields (near `reprintButton`/`doneButton`):

```java
    @FXML private Button emailReceiptButton;
    @FXML private VBox emailDialog;
    @FXML private TextField emailField;
    @FXML private Button emailSendButton;
    @FXML private Button emailCancelButton;
    @FXML private Label emailConfirmLabel;
```

In `initialize()`, after `reprintButton.setOnAction(e -> reprint());`, add:

```java
        emailReceiptButton.setOnAction(e -> showEmailDialog());
        emailCancelButton.setOnAction(e -> hideEmailDialog());
        emailSendButton.setOnAction(e -> sendEmail());
```

Add the handlers (near `reprint()`):

```java
    private void showEmailDialog() {
        emailConfirmLabel.setVisible(false);
        emailConfirmLabel.setManaged(false);
        emailField.clear();
        emailDialog.setVisible(true);
        emailDialog.setManaged(true);
        emailField.requestFocus();
    }

    private void hideEmailDialog() {
        emailDialog.setVisible(false);
        emailDialog.setManaged(false);
        emailField.clear();
    }

    private void sendEmail() {
        final String addr = emailField.getText();
        final boolean[] holder = new boolean[1];
        FxTasks.run(
                () -> holder[0] = vm.emailReceipt(addr),
                () -> {
                    if (holder[0]) {
                        hideEmailDialog();
                        emailConfirmLabel.setText("Sent to " + (addr == null ? "" : addr.trim()));
                        emailConfirmLabel.setVisible(true);
                        emailConfirmLabel.setManaged(true);
                    }
                    // On failure the dialog stays open; vm.errorMessage() (bound to errorLabel) shows why.
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Unexpected error emailing receipt", err));
    }
```

(`Button`, `Label`, `TextField`, `VBox`, `FxTasks` are already imported.)

- [ ] **Step 5: Edit `app.css`.** Append near the slice-7 classes, using existing tokens only (tap targets already met by `btn-*` and the shared `.text-field` sizing):

```css
/* Slice 8 — email receipt */
.email-dialog {
    -fx-background-color: derive(-fx-canvas, -3%);
    -fx-background-radius: 12;
    -fx-border-color: derive(-fx-primary, 55%);
    -fx-border-radius: 12;
    -fx-border-width: 1;
    -fx-padding: 14;
}
.email-confirm {
    -fx-text-fill: -fx-primary;
    -fx-font-weight: bold;
    -fx-padding: 4 0 0 0;
}
```

- [ ] **Step 6: Run the contract tests to verify they pass** — `./mvnw -f pos-terminal/pom.xml test -Dtest=FxmlContractTest,AppCssTest`. Expected: PASS.

- [ ] **Step 7: Run the full terminal suite** — `./mvnw -f pos-terminal/pom.xml clean test`. Expected: BUILD SUCCESS (FXML loads with the new controller fields wired; all tests green).

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/resources/fxml/payment.fxml \
        pos-terminal/src/main/java/com/company/pos/terminal/view/PaymentController.java \
        pos-terminal/src/main/resources/css/app.css \
        pos-terminal/src/test/java/com/company/pos/terminal/FxmlContractTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java
git commit -m "feat(terminal): Email receipt action on the payment success screen

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 7: Documentation

**Files:**
- Modify: `docs/run-modes.md`
- Modify: `pos-terminal/README.md`

**Interfaces:** none (docs only).

- [ ] **Step 1: Document the endpoint in `docs/run-modes.md`.** In the HTTP-surface / Sales section (near where `POST /sales/{saleId}/reprint` is described), add an additive line:

```markdown
- `POST /sales/{saleId}/send-receipt` — emails the stored sale's receipt to the address in the
  `{ "email": "..." }` body. Renders the same content as the printed slip via the `Emailer` port
  (an `InMemoryEmailer` fake this phase — logs and records mail; a real SMTP adapter drops in
  later). 204 on success; 400 for a blank/malformed address; 404 for an unknown sale. Any
  authenticated user (same as reprint).
```

- [ ] **Step 2: Add the manual E2E to `pos-terminal/README.md`.** Under the manual-E2E scenarios, add a slice-8 subsection:

```markdown
### Slice 8 — Email receipt (manual)

Run backend with `--spring.profiles.active=embedded,dev`; login `manager`/`manager`; `./mvnw -f pos-terminal/pom.xml javafx:run`.

1. **Retail:** ring a retail sale, take payment. On the success screen tap **Email receipt**,
   type `guest@example.com`, tap **Send** → the dialog closes and a green "Sent to guest@example.com"
   note appears. Check the backend log for `EMAIL to=guest@example.com subject=Your receipt …`.
2. **Dine-in:** seat a table, add items, close the whole order, pay. Same **Email receipt** flow on
   the success screen.
3. **Validation:** tap **Email receipt**, leave the field blank (or type `nope`), tap **Send** →
   the dialog stays open and the error line reads "Enter a valid email address"; no email is logged.
```

- [ ] **Step 3: Commit**

```bash
git add docs/run-modes.md pos-terminal/README.md
git commit -m "docs(terminal): document send-receipt endpoint and slice 8 E2E

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Self-Review (completed while writing)

- **Spec coverage:** Emailer port + fake (T1) ✓; receipt render/send (T2) ✓; sales service + endpoint 204/400/404 (T3) ✓; terminal client (T4) ✓; VM validate/send + async regression (T5) ✓; success-screen button/dialog + CSS + contract tests (T6) ✓; docs + manual E2E (T7) ✓. Single-sale-only scope honoured (no split UI). No new module dependency / migration / config / Maven dependency.
- **Placeholder scan:** none — every code step carries complete code.
- **Type consistency:** `EmailMessage(to, subject, body)` and `Emailer.send` consistent across T1/T2; `ReceiptService.emailReceipt(String,ReceiptData)` matches T2↔T3; `SalesService.emailReceipt(UUID,String)` matches T3↔(endpoint); `SalesApi.emailReceipt(UUID,String)` matches T4↔T5↔T6; `PaymentViewModel.emailReceipt(String)→boolean` matches T5↔T6. `PrintLine::text` accessor confirmed against existing tests.
- **Deviation from spec (noted):** the spec proposed an `EmailGateway` interface; the plan instead calls `sales.emailReceipt` directly, mirroring the sibling `reprint()` which already uses the injected `SalesApi` — simpler, no extra interface (YAGNI). Same observable behaviour.
