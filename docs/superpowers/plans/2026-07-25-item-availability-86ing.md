# Item Availability / 86ing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let any signed-in cashier/server temporarily mark a menu item "sold out tonight" (86 it) and restore it, with the ordering path rejecting 86'd items server-side and the terminal showing a dedicated 86 board plus greyed-out pickers.

**Architecture:** Add a manual `available` boolean to the product SKU (separate from the permanent `active` soft-delete). A cashier-level `PUT /products/{sku}/availability` flips it and publishes the existing `ProductChanged` event (audited async via the outbox). Both ordering services (`cart`, `dining`) reject a 86'd SKU at add-line with `DomainException.conflict` → HTTP 409. The JavaFX terminal gets a new 86-board screen and greys/disables 86'd items in the order and retail menus; correctness rides on the server rejection, so the terminal only re-fetches on open (no WebSocket).

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Modulith, JPA/Hibernate, Flyway (store-server only), JavaFX (pos-terminal, separate Maven build), JUnit 5, MockMvc, Awaitility, AssertJ.

## Global Constraints

- **JDK 21 required.** `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` before any Maven command.
- **Backend build/test:** `./mvnw` from repo root. **Terminal build/test:** `./mvnw -f pos-terminal/pom.xml ...` (NOT in the root reactor).
- **Money is `BigDecimal`** — never `double`. (Not central here, but holds.)
- **Module boundaries are enforced** by `ModularityTests` (`./mvnw test -Dtest=ModularityTests`). `cart` and `dining` already allow `product :: api`; `audit` already allows `product :: api`. No new dependencies are introduced.
- **Flyway version numbers are globally sequential** across all `db/migration/<module>/` folders. Current ceiling is **V36**; the new migration is **V37**.
- **Auditing is via event, never a synchronous `audit.record`.** The service only `events.publish(new ProductChanged(...))`; the `audit` listener records it async post-commit.
- **`ProductChangeType` audit switch is exhaustive (no `default`)** — adding enum values is a compile error until `ProductChangedAuditListener` handles them (and matching `AuditAction` constants exist). Expect that spillover in the same task.
- **Terminal DTOs are `@JsonIgnoreProperties(ignoreUnknown = true)` subsets** deserialized by field name — add a field to receive a new backend field.
- **Terminal FX-threading:** ViewModel methods are synchronous and return plain values; only `errorMessage` is written off-thread inside `ui.accept(...)`. The controller runs VM calls off-thread via `FxTasks.run(work, onDone, onError)` and reads results in `onDone` via a holder array. Every VM needs an async-dispatcher regression test (a deferred, undrained `ui` queue).
- After any change, re-run the affected module's tests **and** `ModularityTests`.

---

## File Structure

**Backend (`src/main/java/com/company/pos/`)**
- `product/domain/Product.java` — MODIFY: add `available` field + `isAvailable()`/`markAvailable()`/`markUnavailable()`.
- `product/api/ProductView.java` — MODIFY: add `boolean available`.
- `product/api/ProductChangeType.java` — MODIFY: add `MARKED_UNAVAILABLE`, `MARKED_AVAILABLE`.
- `product/api/SetAvailabilityCommand.java` — CREATE: request record.
- `product/application/ProductAdminService.java` — MODIFY: `setAvailability(...)` + update `toView`.
- `product/application/DefaultProductCatalog.java` — MODIFY: update `toView`.
- `product/web/ProductAvailabilityController.java` — CREATE: ungated `PUT /products/{sku}/availability`.
- `src/main/resources/db/migration/product/V37__product_available.sql` — CREATE.
- `audit/api/AuditAction.java` — MODIFY: add `PRODUCT_MARKED_UNAVAILABLE`, `PRODUCT_MARKED_AVAILABLE`.
- `audit/application/ProductChangedAuditListener.java` — MODIFY: two new switch cases.
- `cart/application/DefaultCartService.java` — MODIFY: 86 gate in `addLine`.
- `dining/application/DefaultDiningService.java` — MODIFY: 86 gate in `addLine`.

**Terminal (`pos-terminal/src/main/java/com/company/pos/terminal/`)**
- `api/dto/ProductView.java` — MODIFY: add `Boolean active, Boolean available`.
- `api/AvailabilityApi.java` — CREATE.
- `api/dto/AvailabilityRequest.java` — CREATE.
- `viewmodel/AvailabilityViewModel.java` — CREATE.
- `view/AvailabilityController.java` — CREATE.
- `pos-terminal/src/main/resources/fxml/availability.fxml` — CREATE.
- `app/Navigator.java` — MODIFY: `toAvailability()`.
- `app/Services.java` — MODIFY: `availabilityApi` field.
- `view/HomeController.java` + `resources/fxml/home.fxml` — MODIFY: "86 Board" tile.
- `view/RetailController.java` + `view/OrderController.java` — MODIFY: grey/disable 86'd items + 409 handling.
- `pos-terminal/src/main/resources/css/app.css` — MODIFY: `.product-unavailable`.

---

## Task 1: Product `available` field, `ProductView`, migration

**Files:**
- Modify: `src/main/java/com/company/pos/product/domain/Product.java`
- Modify: `src/main/java/com/company/pos/product/api/ProductView.java`
- Modify: `src/main/java/com/company/pos/product/application/ProductAdminService.java` (the `toView` method only)
- Modify: `src/main/java/com/company/pos/product/application/DefaultProductCatalog.java` (the `toView` method only)
- Create: `src/main/resources/db/migration/product/V37__product_available.sql`
- Test: `src/test/java/com/company/pos/product/domain/ProductBehaviorTest.java`

**Interfaces:**
- Produces: `Product.isAvailable()`, `Product.markAvailable()`, `Product.markUnavailable()`; `ProductView(String sku, String name, String categoryName, String barcode, String unitOfMeasure, BigDecimal unitPrice, String currencyCode, boolean active, boolean available)`.

- [ ] **Step 1: Write the failing domain test**

Add to `ProductBehaviorTest.java`:

```java
    @Test
    void availableDefaultsTrueAndTogglesBothWays() {
        Product p = sample();
        assertThat(p.isAvailable()).isTrue();
        p.markUnavailable();
        assertThat(p.isAvailable()).isFalse();
        p.markAvailable();
        assertThat(p.isAvailable()).isTrue();
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw test -Dtest=ProductBehaviorTest#availableDefaultsTrueAndTogglesBothWays`
Expected: FAIL — `cannot find symbol: method isAvailable()`.

- [ ] **Step 3: Add the field + methods to `Product.java`**

After the `active` field (`private boolean active = true;`) add:

```java
    @Column(nullable = false)
    private boolean available = true;
```

After the `deactivate()` method add:

```java
    public boolean isAvailable() {
        return available;
    }

    public void markAvailable() {
        this.available = true;
    }

    public void markUnavailable() {
        this.available = false;
    }
```

- [ ] **Step 4: Add `available` to `ProductView.java`**

```java
package com.company.pos.product.api;

import java.math.BigDecimal;

public record ProductView(
        String sku,
        String name,
        String categoryName,
        String barcode,
        String unitOfMeasure,
        BigDecimal unitPrice,
        String currencyCode,
        boolean active,
        boolean available) {
}
```

- [ ] **Step 5: Update both `toView` mappings to pass `p.isAvailable()`**

In `ProductAdminService.java`:

```java
    private ProductView toView(Product p) {
        return new ProductView(p.getSku(), p.getName(), p.getCategoryName(), p.getBarcode(),
                p.getUnitOfMeasure(), p.getUnitPrice(), p.getCurrencyCode(), p.isActive(),
                p.isAvailable());
    }
```

In `DefaultProductCatalog.java`:

```java
    private ProductView toView(Product p) {
        return new ProductView(p.getSku(), p.getName(), p.getCategoryName(), p.getBarcode(),
                p.getUnitOfMeasure(), p.getUnitPrice(), p.getCurrencyCode(), p.isActive(),
                p.isAvailable());
    }
```

- [ ] **Step 6: Create the Flyway migration**

`src/main/resources/db/migration/product/V37__product_available.sql`:

```sql
ALTER TABLE product ADD COLUMN available BOOLEAN NOT NULL DEFAULT TRUE;
```

- [ ] **Step 7: Fix any other `new ProductView(...)` call sites**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; grep -rn "new ProductView(" src/main src/test`
For each hit that passes 8 args, append `, true` (available defaults true) so it compiles. Example in a test that constructs a view directly:

```java
new ProductView("COLA", "Cola", "Beverages", null, "EA", new BigDecimal("5.00"), "SAR", true, true)
```

- [ ] **Step 8: Run the domain test + compile**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw test -Dtest=ProductBehaviorTest`
Expected: PASS (all methods green).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/company/pos/product/domain/Product.java \
        src/main/java/com/company/pos/product/api/ProductView.java \
        src/main/java/com/company/pos/product/application/ProductAdminService.java \
        src/main/java/com/company/pos/product/application/DefaultProductCatalog.java \
        src/main/resources/db/migration/product/V37__product_available.sql \
        src/test/java/com/company/pos/product/domain/ProductBehaviorTest.java
git commit -m "feat(product): add manual 'available' (86) flag to product SKU

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `setAvailability` service + `ProductChangeType` + audit spillover

**Files:**
- Modify: `src/main/java/com/company/pos/product/api/ProductChangeType.java`
- Modify: `src/main/java/com/company/pos/product/application/ProductAdminService.java`
- Modify: `src/main/java/com/company/pos/audit/api/AuditAction.java`
- Modify: `src/main/java/com/company/pos/audit/application/ProductChangedAuditListener.java`
- Test: `src/test/java/com/company/pos/product/ProductAdminServiceTest.java`

**Interfaces:**
- Consumes: `Product.markAvailable()/markUnavailable()`, `ProductView.available()` (Task 1).
- Produces: `ProductAdminService.setAvailability(String sku, boolean available) : ProductView`; `ProductChangeType.MARKED_UNAVAILABLE`, `MARKED_AVAILABLE`; `AuditAction.PRODUCT_MARKED_UNAVAILABLE`, `PRODUCT_MARKED_AVAILABLE`.

- [ ] **Step 1: Write the failing service test**

Look at the top of `ProductAdminServiceTest.java` to mirror its setup (it is `@SpringBootTest @ActiveProfiles("embedded")` with a seeded product or a `createProduct` helper). Add:

```java
    @Test
    void setAvailabilityTogglesFlagAndPersists() {
        service.createProduct(new CreateProductCommand("SALMON", "Grilled Salmon", null,
                "Mains", new BigDecimal("42.00"), "SAR", "EA", null));

        ProductView off = service.setAvailability("SALMON", false);
        assertThat(off.available()).isFalse();
        assertThat(catalog.findBySku("SALMON").orElseThrow().available()).isFalse();

        ProductView on = service.setAvailability("SALMON", true);
        assertThat(on.available()).isTrue();
    }
```

If `ProductAdminServiceTest` does not already autowire the read side, add `@Autowired ProductCatalog catalog;` and the import `com.company.pos.product.api.ProductCatalog`. Ensure `CreateProductCommand` and `BigDecimal` are imported.

- [ ] **Step 2: Run it to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw test -Dtest=ProductAdminServiceTest#setAvailabilityTogglesFlagAndPersists`
Expected: FAIL — `cannot find symbol: method setAvailability`.

- [ ] **Step 3: Add the enum values**

`ProductChangeType.java`:

```java
package com.company.pos.product.api;

public enum ProductChangeType {
    CREATED, UPDATED, PRICE_CHANGED, DEACTIVATED, REACTIVATED, CATEGORY_CREATED,
    MARKED_UNAVAILABLE, MARKED_AVAILABLE
}
```

- [ ] **Step 4: Add the service method**

In `ProductAdminService.java`, after `reactivate(...)`:

```java
    public ProductView setAvailability(String sku, boolean available) {
        Product p = products.findBySku(sku)
                .orElseThrow(() -> DomainException.notFound("No product with sku " + sku));
        if (available) {
            p.markAvailable();
        } else {
            p.markUnavailable();
        }
        products.save(p);
        ProductChangeType type = available ? ProductChangeType.MARKED_AVAILABLE
                : ProductChangeType.MARKED_UNAVAILABLE;
        events.publish(new ProductChanged(sku, type, actor(), null, null));
        return toView(p);
    }
```

- [ ] **Step 5: Add the `AuditAction` constants**

In `AuditAction.java`, add after `CATEGORY_CREATED,`:

```java
    PRODUCT_MARKED_UNAVAILABLE,
    PRODUCT_MARKED_AVAILABLE,
```

- [ ] **Step 6: Handle the new cases in the audit switch**

In `ProductChangedAuditListener.java`, extend the `actionFor` switch (it has NO `default`, so this is required to compile):

```java
    private static AuditAction actionFor(ProductChangeType type) {
        return switch (type) {
            case CREATED -> AuditAction.PRODUCT_CREATED;
            case UPDATED -> AuditAction.PRODUCT_UPDATED;
            case PRICE_CHANGED -> AuditAction.PRICE_CHANGED;
            case DEACTIVATED -> AuditAction.PRODUCT_DEACTIVATED;
            case REACTIVATED -> AuditAction.PRODUCT_REACTIVATED;
            case CATEGORY_CREATED -> AuditAction.CATEGORY_CREATED;
            case MARKED_UNAVAILABLE -> AuditAction.PRODUCT_MARKED_UNAVAILABLE;
            case MARKED_AVAILABLE -> AuditAction.PRODUCT_MARKED_AVAILABLE;
        };
    }
```

- [ ] **Step 7: Run the service test + ModularityTests**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw test -Dtest=ProductAdminServiceTest#setAvailabilityTogglesFlagAndPersists,ModularityTests`
Expected: PASS (both).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/company/pos/product/api/ProductChangeType.java \
        src/main/java/com/company/pos/product/application/ProductAdminService.java \
        src/main/java/com/company/pos/audit/api/AuditAction.java \
        src/main/java/com/company/pos/audit/application/ProductChangedAuditListener.java \
        src/test/java/com/company/pos/product/ProductAdminServiceTest.java
git commit -m "feat(product): setAvailability service + audit spillover for 86 toggle

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Cashier-level `PUT /products/{sku}/availability` endpoint + audit E2E

**Files:**
- Create: `src/main/java/com/company/pos/product/api/SetAvailabilityCommand.java`
- Create: `src/main/java/com/company/pos/product/web/ProductAvailabilityController.java`
- Test: `src/test/java/com/company/pos/product/ProductAvailabilityControllerTest.java` (CREATE)

**Interfaces:**
- Consumes: `ProductAdminService.setAvailability(sku, available)` (Task 2).
- Produces: HTTP `PUT /products/{sku}/availability` body `{"available": boolean}`, authenticated (no role gate), returns `ProductView`; 404 for unknown SKU.

- [ ] **Step 1: Write the failing web test**

`ProductAvailabilityControllerTest.java` (mirrors `ProductAdminControllerTest` harness — MockMvc + `jwt()` post-processors; seeds a product via the ADMIN create endpoint):

```java
package com.company.pos.product;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class ProductAvailabilityControllerTest {

    @Autowired MockMvc mvc;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("root")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor cashier() {
        return jwt().jwt(j -> j.subject("cashier")).authorities(new SimpleGrantedAuthority("ROLE_CASHIER"));
    }

    private static final String SALMON = "{\"sku\":\"SALMON\",\"name\":\"Grilled Salmon\","
            + "\"categoryName\":\"Mains\",\"unitPrice\":42.00,"
            + "\"currencyCode\":\"SAR\",\"unitOfMeasure\":\"EA\"}";

    private void seedSalmon() throws Exception {
        mvc.perform(post("/products").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(SALMON))
                .andExpect(status().isCreated());
    }

    @Test
    void cashierCan86AndRestore() throws Exception {
        seedSalmon();
        mvc.perform(put("/products/SALMON/availability").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"available\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));
        mvc.perform(put("/products/SALMON/availability").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"available\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void unknownSkuIs404() throws Exception {
        mvc.perform(put("/products/NOPE/availability").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"available\":false}"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw test -Dtest=ProductAvailabilityControllerTest`
Expected: FAIL — 404/405 for the unmapped `PUT /products/{sku}/availability`.

- [ ] **Step 3: Create the request command**

`SetAvailabilityCommand.java`:

```java
package com.company.pos.product.api;

public record SetAvailabilityCommand(boolean available) {
}
```

- [ ] **Step 4: Create the ungated controller**

`ProductAvailabilityController.java` (NOTE: no `@PreAuthorize` — SecurityConfig authenticates everything except `/auth/*`, so this is any signed-in user = cashier-level):

```java
package com.company.pos.product.web;

import com.company.pos.product.api.ProductView;
import com.company.pos.product.api.SetAvailabilityCommand;
import com.company.pos.product.application.ProductAdminService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cashier-level item 86 toggle. Deliberately NOT annotated with {@code @PreAuthorize}: the
 * SecurityConfig authenticates every request outside {@code /auth/*}, so any signed-in
 * cashier/server may 86 or restore an item. The change is audited via {@code ProductChanged}.
 */
@RestController
class ProductAvailabilityController {

    private final ProductAdminService admin;

    ProductAvailabilityController(ProductAdminService admin) {
        this.admin = admin;
    }

    @PutMapping("/products/{sku}/availability")
    ProductView setAvailability(@PathVariable String sku, @RequestBody SetAvailabilityCommand cmd) {
        return admin.setAvailability(sku, cmd.available());
    }
}
```

- [ ] **Step 5: Run the web test**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw test -Dtest=ProductAvailabilityControllerTest`
Expected: PASS.

- [ ] **Step 6: Add the audit E2E test**

Append to `ProductAvailabilityControllerTest.java` an Awaitility check mirroring `AuditTrailE2ETest`. Add fields/imports:

```java
    @Autowired com.company.pos.audit.application.DefaultAuditService audit;
```
```java
    @Test
    void toggleIsAudited() throws Exception {
        seedSalmon();
        mvc.perform(put("/products/SALMON/availability").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"available\":false}"))
                .andExpect(status().isOk());

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    java.util.List<String> actions = audit.recent(50).stream()
                            .map(com.company.pos.audit.api.AuditRecordView::action).toList();
                    org.assertj.core.api.Assertions.assertThat(actions)
                            .contains("PRODUCT_MARKED_UNAVAILABLE");
                });
    }
```

- [ ] **Step 7: Run the full test class + ModularityTests**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw test -Dtest=ProductAvailabilityControllerTest,ModularityTests`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/company/pos/product/api/SetAvailabilityCommand.java \
        src/main/java/com/company/pos/product/web/ProductAvailabilityController.java \
        src/test/java/com/company/pos/product/ProductAvailabilityControllerTest.java
git commit -m "feat(product): cashier-level PUT /products/{sku}/availability (86 toggle)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Reject 86'd SKU in the retail cart add-line (`cart`)

**Files:**
- Modify: `src/main/java/com/company/pos/cart/application/DefaultCartService.java`
- Test: `src/test/java/com/company/pos/cart/CartServiceTest.java`

**Interfaces:**
- Consumes: `ProductView.available()` (Task 1), `ProductAdminService.setAvailability` (Task 2).
- Produces: `cart.addLine` throws `DomainException.conflict(...)` (→ 409) for a 86'd SKU.

- [ ] **Step 1: Write the failing test**

Mirror `CartServiceTest`'s setup (it is `@SpringBootTest @ActiveProfiles("embedded")`; check how it seeds a product — likely `FakeErpClient` + `ProductSync`, or a `ProductAdminService`). Add a test that 86's a seeded product then asserts `addLine` is rejected. Using `ProductAdminService` to 86:

```java
    @Test
    void addLineRejectsEightySixedSku() {
        // seed a product however this test class already does (e.g. productAdmin.createProduct(...) or FakeErp+sync)
        productAdmin.setAvailability("COLA", false);
        UUID cart = service.createCart();
        assertThatThrownBy(() -> service.addLine(cart, "COLA", new BigDecimal("1")))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).errorCode())
                        .isEqualTo(ErrorCode.CONFLICT));
    }
```

Add imports as needed: `com.company.pos.common.exception.DomainException`, `com.company.pos.common.exception.ErrorCode`, `static org.assertj.core.api.Assertions.assertThatThrownBy`, `static org.assertj.core.api.Assertions.assertThat`, and autowire `ProductAdminService productAdmin` if not present. If the class seeds products through `FakeErpClient`, keep that seeding and only add the `setAvailability` + assertion.

- [ ] **Step 2: Run it to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw test -Dtest=CartServiceTest#addLineRejectsEightySixedSku`
Expected: FAIL — no exception thrown (the line is added).

- [ ] **Step 3: Add the 86 gate in `addLine`**

In `DefaultCartService.addLine`, after resolving the product:

```java
    @Override
    public CartView addLine(UUID cartId, String sku, BigDecimal quantity) {
        requirePositive(quantity);
        Cart cart = openCart(cartId);
        ProductView product = catalogue.findBySku(sku)
                .orElseThrow(() -> DomainException.notFound("Unknown sku " + sku));
        if (!product.available()) {
            throw DomainException.conflict("Item is 86'd: " + sku);
        }
        cart.addLine(sku, product.name(), quantity, product.unitPrice(), product.currencyCode());
        return toView(cart);
    }
```

- [ ] **Step 4: Run the test**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw test -Dtest=CartServiceTest#addLineRejectsEightySixedSku`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/pos/cart/application/DefaultCartService.java \
        src/test/java/com/company/pos/cart/CartServiceTest.java
git commit -m "feat(cart): reject 86'd SKU at add-line (409)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Reject 86'd SKU in the dine-in add-line (`dining`)

**Files:**
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java`
- Test: the dining service/web test class that already covers `addLine` (find with `grep -rln "addLine" src/test/java/com/company/pos/dining`).

**Interfaces:**
- Consumes: `ProductView.available()` (Task 1).
- Produces: `dining.addLine` throws `DomainException.conflict(...)` (→ 409) for a 86'd SKU.

- [ ] **Step 1: Find the dining add-line test class**

Run: `grep -rln "addLine" src/test/java/com/company/pos/dining`
Open the matching test (e.g. `DiningServiceTest` / `DiningOrderTest`) and mirror its seeding + open-order helper.

- [ ] **Step 2: Write the failing test**

Add (adapt names to the class's existing helpers for opening an order and seeding a product):

```java
    @Test
    void addLineRejectsEightySixedSku() {
        productAdmin.setAvailability("SALMON", false); // seed SALMON as this class already seeds products
        UUID orderId = openSampleOrder();              // reuse the class's existing open-order helper
        AddLineCommand cmd = new AddLineCommand("SALMON", new BigDecimal("1"), null, "MAIN", List.of());
        assertThatThrownBy(() -> service.addLine(orderId, cmd, "cashier"))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).errorCode())
                        .isEqualTo(ErrorCode.CONFLICT));
    }
```

Match `AddLineCommand`'s real constructor signature (verify against `dining/api/AddLineCommand.java`; adjust the modifier-ids/course args to the actual record). Autowire `ProductAdminService productAdmin` if absent.

- [ ] **Step 3: Run it to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw test -Dtest=<DiningTestClass>#addLineRejectsEightySixedSku`
Expected: FAIL — no exception (line added).

- [ ] **Step 4: Add the 86 gate in `addLine`**

In `DefaultDiningService.addLine`, replace the bare resolve with a captured-and-checked resolve. Ensure `import com.company.pos.product.api.ProductView;` is present:

```java
        ProductView product = products.findBySku(command.sku())
                .orElseThrow(() -> DomainException.validation("Unknown sku " + command.sku()));
        if (!product.available()) {
            throw DomainException.conflict("Item is 86'd: " + command.sku());
        }
```

(Leave the rest of the method — `new OrderLine(...)`, modifiers, `order.addLine(line)`, `publishFloorChanged(...)` — unchanged.)

- [ ] **Step 5: Run the test + ModularityTests**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw test -Dtest=<DiningTestClass>#addLineRejectsEightySixedSku,ModularityTests`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/dining/application/DefaultDiningService.java \
        src/test/java/com/company/pos/dining/<DiningTestClass>.java
git commit -m "feat(dining): reject 86'd SKU at add-line (409)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Terminal `ProductView` DTO field + `AvailabilityApi` client

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ProductView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/AvailabilityRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/AvailabilityApi.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/AvailabilityApiTest.java` (CREATE)

**Interfaces:**
- Produces: terminal `ProductView(String sku, String name, String categoryName, String barcode, BigDecimal unitPrice, Boolean active, Boolean available)`; `AvailabilityApi.list() : List<ProductView>`; `AvailabilityApi.setAvailability(String sku, boolean available) : ProductView`.

- [ ] **Step 1: Write the failing API-client test**

`AvailabilityApiTest.java` (mirrors `ShiftApiTest` + `StubServer`):

```java
package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.company.pos.terminal.api.dto.ProductView;
import org.junit.jupiter.api.Test;

class AvailabilityApiTest {

    @Test
    void setAvailabilityPutsToTheRightPathWithBody() throws Exception {
        String body = "{\"sku\":\"SALMON\",\"name\":\"Grilled Salmon\",\"categoryName\":\"Mains\","
                + "\"barcode\":null,\"unitPrice\":42.00,\"active\":true,\"available\":false}";
        try (StubServer stub = new StubServer(200, body, "application/json")) {
            AvailabilityApi api = new AvailabilityApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            ProductView v = api.setAvailability("SALMON", false);
            assertFalse(v.available());
            assertEquals("PUT", stub.lastMethod);
            assertEquals("/products/SALMON/availability", stub.lastPath);
            assertEquals("{\"available\":false}", stub.lastBody);
        }
    }

    @Test
    void listGetsProducts() throws Exception {
        String body = "[{\"sku\":\"SALMON\",\"name\":\"Grilled Salmon\",\"categoryName\":\"Mains\","
                + "\"barcode\":null,\"unitPrice\":42.00,\"active\":true,\"available\":true}]";
        try (StubServer stub = new StubServer(200, body, "application/json")) {
            AvailabilityApi api = new AvailabilityApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            assertEquals(1, api.list().size());
            assertEquals("GET", stub.lastMethod);
            assertEquals("/products", stub.lastPath);
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw -f pos-terminal/pom.xml test -Dtest=AvailabilityApiTest`
Expected: FAIL — `AvailabilityApi` / `ProductView.available()` do not exist.

- [ ] **Step 3: Add fields to the terminal `ProductView` DTO**

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * Terminal-side mirror of the store server's product read model. Deserialized by field name;
 * {@code active}/{@code available} are boxed so an older server that omits them yields null
 * (treated as active/available by callers). Money is {@link BigDecimal}, never double.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductView(String sku, String name, String categoryName, String barcode,
        BigDecimal unitPrice, Boolean active, Boolean available) {
}
```

- [ ] **Step 4: Create the request DTO**

`AvailabilityRequest.java`:

```java
package com.company.pos.terminal.api.dto;

/** Body for PUT /products/{sku}/availability. */
public record AvailabilityRequest(boolean available) {
}
```

- [ ] **Step 5: Create the API client**

`AvailabilityApi.java`:

```java
package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.AvailabilityRequest;
import com.company.pos.terminal.api.dto.ProductView;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;

/** Typed client for the 86-board: reads the product list and toggles per-SKU availability. */
public class AvailabilityApi {

    private final ApiClient client;

    public AvailabilityApi(ApiClient client) {
        this.client = client;
    }

    /** GET /products — the full catalogue (each carries active + available). */
    public List<ProductView> list() {
        return client.get("/products", new TypeReference<List<ProductView>>() {});
    }

    /** PUT /products/{sku}/availability — 86 (false) or restore (true) an item. */
    public ProductView setAvailability(String sku, boolean available) {
        return client.put("/products/" + sku + "/availability", new AvailabilityRequest(available),
                new TypeReference<ProductView>() {});
    }
}
```

- [ ] **Step 6: Fix any terminal `new ProductView(...)` call sites**

Run: `grep -rn "new ProductView(" pos-terminal/src`
For each hit (tests, fixtures) passing 5 args, append `, true, true`.

- [ ] **Step 7: Run the test**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw -f pos-terminal/pom.xml test -Dtest=AvailabilityApiTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ProductView.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/AvailabilityRequest.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/AvailabilityApi.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/AvailabilityApiTest.java
git commit -m "feat(terminal): AvailabilityApi + ProductView active/available fields

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: `AvailabilityViewModel` + tests

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/AvailabilityViewModel.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/AvailabilityViewModelTest.java` (CREATE)

**Interfaces:**
- Consumes: `AvailabilityApi.list()`, `AvailabilityApi.setAvailability(sku, boolean)` (Task 6).
- Produces: `AvailabilityViewModel(AvailabilityApi, Consumer<Runnable> ui)`; `List<ProductView> load()` (active only, null on failure); `ProductView setAvailability(String sku, boolean available)` (null on failure); `ReadOnlyStringProperty errorMessage()`.

- [ ] **Step 1: Write the failing VM test (incl. async-dispatcher regression)**

`AvailabilityViewModelTest.java`:

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.AvailabilityApi;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.dto.ProductView;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class AvailabilityViewModelTest {

    private ProductView p(String sku, Boolean active, Boolean available) {
        return new ProductView(sku, sku, "Mains", null, new BigDecimal("10.00"), active, available);
    }

    @Test
    void loadReturnsActiveProductsOnly() {
        AvailabilityApi api = new AvailabilityApi(null) {
            @Override public List<ProductView> list() {
                return List.of(p("SALMON", true, true), p("OLD", false, true), p("NULLACT", null, true));
            }
        };
        AvailabilityViewModel vm = new AvailabilityViewModel(api, Runnable::run);
        List<ProductView> rows = vm.load();
        assertEquals(2, rows.size()); // SALMON + NULLACT (null active treated as active); OLD filtered out
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void setAvailabilityReturnsUpdatedRow() {
        AvailabilityApi api = new AvailabilityApi(null) {
            @Override public ProductView setAvailability(String sku, boolean available) {
                return p(sku, true, available);
            }
        };
        AvailabilityViewModel vm = new AvailabilityViewModel(api, Runnable::run);
        ProductView v = vm.setAvailability("SALMON", false);
        assertNotNull(v);
        assertEquals(Boolean.FALSE, v.available());
    }

    @Test
    void loadSurfacesErrorAndReturnsNull() {
        AvailabilityApi api = new AvailabilityApi(null) {
            @Override public List<ProductView> list() {
                throw new ApiException(500, new ProblemDetail("Error", 500, "boom"), "HTTP 500");
            }
        };
        AvailabilityViewModel vm = new AvailabilityViewModel(api, Runnable::run);
        assertNull(vm.load());
        assertEquals("boom", vm.errorMessage().get());
    }

    @Test
    void deferredDispatcherHoldsErrorUntilDrained() {
        AvailabilityApi api = new AvailabilityApi(null) {
            @Override public ProductView setAvailability(String sku, boolean available) {
                throw new ApiException(409, new ProblemDetail("Conflict", 409, "Item is 86'd"), "HTTP 409");
            }
        };
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        AvailabilityViewModel vm = new AvailabilityViewModel(api, queue::add);
        ProductView r = vm.setAvailability("SALMON", false);
        assertNull(r);                               // synchronous return
        assertEquals("", vm.errorMessage().get());   // deferred: not applied yet
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals("Item is 86'd", vm.errorMessage().get());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw -f pos-terminal/pom.xml test -Dtest=AvailabilityViewModelTest`
Expected: FAIL — `AvailabilityViewModel` does not exist.

- [ ] **Step 3: Create the ViewModel (mirrors `CashDrawerViewModel`)**

`AvailabilityViewModel.java`:

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.AvailabilityApi;
import com.company.pos.terminal.api.dto.ProductView;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the 86 board. Synchronous like the other VMs (the controller runs it off the FX
 * thread via FxTasks); the only observable written off-thread is {@code errorMessage}, inside the
 * {@code ui} dispatcher. Methods return plain values (or null on failure) so the controller reads
 * control-flow truth from the return value.
 */
public class AvailabilityViewModel {

    private final AvailabilityApi api;
    private final Consumer<Runnable> ui;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public AvailabilityViewModel(AvailabilityApi api, Consumer<Runnable> ui) {
        this.api = api;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    /** Fetches active products only; on failure surfaces the reason and returns null. */
    public List<ProductView> load() {
        try {
            List<ProductView> rows = api.list().stream()
                    .filter(p -> p.active() == null || p.active())
                    .toList();
            ui.accept(() -> errorMessage.set(""));
            return rows;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return null;
        }
    }

    /** Toggles a SKU's availability; returns the updated view or null (with errorMessage set). */
    public ProductView setAvailability(String sku, boolean available) {
        try {
            ProductView v = api.setAvailability(sku, available);
            ui.accept(() -> errorMessage.set(""));
            return v;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return null;
        }
    }

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

- [ ] **Step 4: Run the test**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw -f pos-terminal/pom.xml test -Dtest=AvailabilityViewModelTest`
Expected: PASS (all four).

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/AvailabilityViewModel.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/AvailabilityViewModelTest.java
git commit -m "feat(terminal): AvailabilityViewModel for the 86 board

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: 86-board screen, navigation, Services wiring, Home tile

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/AvailabilityController.java`
- Create: `pos-terminal/src/main/resources/fxml/availability.fxml`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/HomeController.java`
- Modify: `pos-terminal/src/main/resources/fxml/home.fxml`

**Interfaces:**
- Consumes: `AvailabilityViewModel` (Task 7), `AvailabilityApi` (Task 6).
- Produces: `Services.availabilityApi`; `Navigator.toAvailability()`.

- [ ] **Step 1: Wire `AvailabilityApi` into `Services`**

In `Services.java`: add the import `import com.company.pos.terminal.api.AvailabilityApi;`, the field `public final AvailabilityApi availabilityApi;`, and in the constructor `this.availabilityApi = new AvailabilityApi(apiClient);`.

- [ ] **Step 2: Create the controller**

`AvailabilityController.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.ProductView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.AvailabilityViewModel;
import java.util.List;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The "86 board": a searchable list of active products; each row shows an Available/86'D pill and a
 * toggle. Pure I/O-free view logic here — all HTTP goes through {@link AvailabilityViewModel} off
 * the FX thread via {@link FxTasks}, results applied in onDone via a holder array.
 */
public class AvailabilityController {

    private static final System.Logger LOG = System.getLogger(AvailabilityController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final AvailabilityViewModel vm;
    private List<ProductView> all = List.of();

    @FXML private TextField searchField;
    @FXML private VBox listBox;
    @FXML private Label statusLabel;
    @FXML private Button backButton;

    public AvailabilityController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new AvailabilityViewModel(services.availabilityApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        statusLabel.setText("");
        backButton.setOnAction(e -> navigator.toHome());
        searchField.textProperty().addListener((o, a, b) -> renderRows());
        reload();
    }

    private void reload() {
        final List<ProductView>[] holder = new List[1];
        FxTasks.run(() -> holder[0] = vm.load(),
                () -> {
                    if (holder[0] == null) {
                        statusLabel.setText(vm.errorMessage().get());
                        return;
                    }
                    all = holder[0];
                    renderRows();
                },
                err -> {
                    statusLabel.setText("Couldn't load products");
                    LOG.log(System.Logger.Level.ERROR, "Load availability failed", err);
                });
    }

    private void renderRows() {
        listBox.getChildren().clear();
        String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        for (ProductView p : all) {
            if (!q.isEmpty() && !p.name().toLowerCase().contains(q) && !p.sku().toLowerCase().contains(q)) {
                continue;
            }
            listBox.getChildren().add(row(p));
        }
    }

    private HBox row(ProductView p) {
        boolean available = p.available() == null || p.available();
        Label name = new Label(p.name());
        name.getStyleClass().add("subtitle");
        Label pill = new Label(available ? "Available" : "86'D");
        pill.getStyleClass().add(available ? "pill-available" : "pill-eightysix");
        Button toggle = new Button(available ? "86 it" : "Restore");
        toggle.getStyleClass().add("btn-secondary");
        toggle.setOnAction(e -> toggle(p, !available));
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(12, name, spacer, pill, toggle);
        row.getStyleClass().add("cart-line");
        return row;
    }

    private void toggle(ProductView p, boolean makeAvailable) {
        final ProductView[] holder = new ProductView[1];
        FxTasks.run(() -> holder[0] = vm.setAvailability(p.sku(), makeAvailable),
                () -> {
                    if (holder[0] == null) {
                        statusLabel.setText(vm.errorMessage().get());
                        return;
                    }
                    statusLabel.setText("");
                    reload();
                },
                err -> {
                    statusLabel.setText("Couldn't update " + p.sku());
                    LOG.log(System.Logger.Level.ERROR, "Toggle availability failed", err);
                });
    }
}
```

- [ ] **Step 3: Create the FXML**

`pos-terminal/src/main/resources/fxml/availability.fxml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.scene.control.Button?>
<?import javafx.scene.control.Label?>
<?import javafx.scene.control.ScrollPane?>
<?import javafx.scene.control.TextField?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.layout.Pane?>
<?import javafx.scene.layout.StackPane?>
<?import javafx.scene.layout.VBox?>

<StackPane styleClass="screen" xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml">
  <VBox spacing="16" maxWidth="720">
    <HBox spacing="12" alignment="CENTER_LEFT">
      <Label text="86 Board" styleClass="title"/>
      <Pane HBox.hgrow="ALWAYS"/>
      <Label fx:id="statusLabel" styleClass="subtitle"/>
      <Button fx:id="backButton" text="Back" styleClass="btn-secondary"/>
    </HBox>
    <TextField fx:id="searchField" promptText="Search item…"/>
    <ScrollPane fitToWidth="true" styleClass="menu-scroll">
      <VBox fx:id="listBox" spacing="8"/>
    </ScrollPane>
  </VBox>
</StackPane>
```

- [ ] **Step 4: Add `toAvailability()` to `Navigator`**

In `Navigator.java`, mirroring `toHome()`:

```java
    public void toAvailability() {
        com.company.pos.terminal.view.AvailabilityController controller =
                new com.company.pos.terminal.view.AvailabilityController(services, this);
        setScene("/fxml/availability.fxml", controller);
    }
```

- [ ] **Step 5: Add the Home tile**

In `home.fxml`, add to the tile `HBox` (after the returns button):

```xml
      <Button fx:id="availabilityButton" text="86 Board" styleClass="home-tile"/>
```

In `HomeController.java`, add the field `@FXML private Button availabilityButton;` and in `initialize()` wire it:

```java
        availabilityButton.setOnAction(e -> navigator.toAvailability());
```

- [ ] **Step 6: Add pill CSS**

Append to `pos-terminal/src/main/resources/css/app.css`:

```css
.pill-available { -fx-text-fill: #2e7d32; -fx-font-weight: bold; }
.pill-eightysix { -fx-text-fill: #c62828; -fx-font-weight: bold; }
.product-unavailable { -fx-opacity: 0.45; }
```

- [ ] **Step 7: Build the terminal (compile + existing tests)**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw -f pos-terminal/pom.xml test`
Expected: PASS (compiles; no regressions). FXML wiring is exercised at runtime; the compile + existing suite is the gate here.

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/AvailabilityController.java \
        pos-terminal/src/main/resources/fxml/availability.fxml \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/HomeController.java \
        pos-terminal/src/main/resources/fxml/home.fxml \
        pos-terminal/src/main/resources/css/app.css
git commit -m "feat(terminal): 86 board screen + Home tile + navigation

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: Grey out 86'd items in the retail + dine-in menus and handle add 409

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/RetailController.java` (`buildMenuFrom` + `addProduct`)
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/OrderController.java` (`buildMenu` + `addWithGroups`)

**Interfaces:**
- Consumes: terminal `ProductView.available()` (Task 6); `ApiException.status()`.

- [ ] **Step 1: Grey/disable unavailable buttons in the retail menu**

In `RetailController.buildMenuFrom`, replace the per-product loop body with:

```java
            for (ProductView p : source.productsInCategory(category)) {
                Button b = new Button(p.name() + "\n" + priceText(p));
                b.getStyleClass().addAll("menu-button", categoryClass(category));
                b.setWrapText(true);
                boolean available = p.available() == null || p.available();
                b.setDisable(!available);
                if (!available) {
                    b.getStyleClass().add("product-unavailable");
                }
                b.setOnAction(e -> addProduct(p));
                grid.getChildren().add(b);
            }
```

- [ ] **Step 2: Grey/disable unavailable buttons in the dine-in menu**

In `OrderController.buildMenu`, replace the per-product loop body with:

```java
            for (ProductView p : cache.productsInCategory(category)) {
                Button b = new Button(p.name() + "\n" + priceText(p));
                b.getStyleClass().add("menu-button");
                b.setWrapText(true);
                boolean available = p.available() == null || p.available();
                b.setDisable(!available);
                if (!available) {
                    b.getStyleClass().add("product-unavailable");
                }
                b.setOnAction(e -> addProduct(p));
                grid.getChildren().add(b);
            }
```

- [ ] **Step 3: Surface the server 409 on a stale add (dine-in)**

In `OrderController.addWithGroups`, replace the trailing `FxTasks.run(...)` `onError` so a 409 (item 86'd between fetch and tap) shows a message instead of only logging:

```java
        final List<UUID> ids = optionIds;
        FxTasks.run(
                () -> vm.addLine(p.sku(), BigDecimal.ONE, null, "MAIN", ids),
                () -> {},
                err -> {
                    if (err instanceof ApiException ae && ae.status() == 409) {
                        new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING,
                                ae.problem() != null && ae.problem().detail() != null
                                        ? ae.problem().detail() : "Item is 86'd").showAndWait();
                    } else {
                        LOG.log(System.Logger.Level.ERROR, "Failed to add line " + p.sku(), err);
                    }
                });
```

**NOTE:** this fires only if `OrderViewModel.addLine` lets `ApiException` propagate. Verify it does (open `OrderViewModel.addLine` — it should throw, not catch-and-return like the drawer VM). If it swallows the exception, either surface the failure through its existing mechanism or let the add throw; do NOT change unrelated behavior. The greyed button (Step 2, refreshed on every `buildMenu`) is the primary UX; this 409 branch is the stale-tap backstop.

- [ ] **Step 4: Surface the server 409 on a stale add (retail)**

In `RetailController.addWithGroups` (the method whose `FxTasks.run` calls `vm.addBySku(...)`), apply the same `onError` 409 branch as Step 3, using `p.sku()` in the log message. Ensure `import com.company.pos.terminal.api.ApiException;` is present in `RetailController`.

- [ ] **Step 5: Build the terminal**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw -f pos-terminal/pom.xml test`
Expected: PASS (compiles; no regressions).

- [ ] **Step 6: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/RetailController.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/OrderController.java
git commit -m "feat(terminal): grey out 86'd items in menus + 409 backstop on add

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: Full verification + docs

**Files:**
- Modify: `docs/run-modes.md` (add the new endpoint to the HTTP surface)
- Modify: `CLAUDE.md` (Flyway ceiling V36 → V37; note the 86 flag)

- [ ] **Step 1: Full backend build**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw verify`
Expected: BUILD SUCCESS (all module tests + `ModularityTests`).

- [ ] **Step 2: Full terminal build**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw -f pos-terminal/pom.xml clean test`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Update docs**

In `docs/run-modes.md`, add to the products endpoint list: `PUT /products/{sku}/availability` — cashier-level 86 toggle (audited). In `CLAUDE.md`, bump the Flyway ceiling note from V36 to **V37** (`product/V37__product_available.sql`) and add a one-line note that a product SKU carries a manual `available` (86) flag distinct from `active`, enforced at add-line in `cart`/`dining`.

- [ ] **Step 4: Commit**

```bash
git add docs/run-modes.md CLAUDE.md
git commit -m "docs: surface 86 toggle endpoint + V37 ceiling + available flag

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review notes (addressed)

- **Spec coverage:** data flag + migration (T1); service + audit spillover (T2); cashier endpoint + audit E2E (T3); cart gate (T4); dining gate (T5); terminal DTO+API (T6); VM + async regression (T7); 86-board screen + Home tile + nav (T8); greyed pickers + 409 backstop (T9); verification + docs (T10). All spec sections map to a task.
- **Deferred items** (modifier-option 86, auto-reset, stock coupling, live WebSocket push) are intentionally absent — matches the spec's out-of-scope list.
- **Type consistency:** `ProductChangeType.MARKED_UNAVAILABLE/MARKED_AVAILABLE` ↔ `AuditAction.PRODUCT_MARKED_UNAVAILABLE/PRODUCT_MARKED_AVAILABLE` used identically in T2. `ProductView` 9-arg backend record (T1) and 7-arg terminal record (T6) are distinct types in distinct modules — do not conflate. `setAvailability(String, boolean)` signature identical across service (T2), endpoint (T3), API client (T6), VM (T7).
- **Known adaptation points flagged inline:** exact dining test class name (T5 Step 1) and `AddLineCommand` constructor (T5 Step 2) must be matched to the real files; `OrderViewModel.addLine` propagation must be verified (T9 Step 3). These are labeled, not silent.
