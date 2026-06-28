# Phase 2a — Checkout Core (Cash Sell Path) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ring up a basket and complete an immutable cash sale offline — price it, apply VAT, take cash with change, print a receipt through a device port, decrement stock, and read the sale back.

**Architecture:** Introduces the Tier-2 checkout capability modules (`cart`, `pricing`, `tax`, `payment`, `receipt`) and the Tier-1 `sales` orchestrator that composes them. `sales` persists an immutable `Sale` aggregate, assigns a collision-free `{storeId}-{terminalId}-{seq}` receipt number, and publishes a `SaleCompleted` domain event; `inventory` subscribes to that event (in-process, same transaction) to decrement on-hand and append a movement-ledger row. Receipt output goes through the existing `device` `Printer` port via a new in-memory fake adapter — the real JavaPOS/ESC-POS adapter lands later. Everything runs against the embedded SQLite profile in tests and PostgreSQL+Flyway in store-server.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith 1.2.5, Spring Data JPA, Flyway (store-server), JavaMoney/Moneta, JUnit 5 + spring-security-test, Maven (`./mvnw`).

## Global Constraints

- **Root package** `com.company.pos`; one package-per-module under it, boundaries enforced by `ApplicationModules.of(PosApplication.class).verify()`. The application **root package is itself boundary-constrained** — any type the root references must be exposed via a module's `@NamedInterface`.
- **JDK 21 required.** The machine default `java` is 17. Run every Maven command as: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw …`.
- **Build tool is Maven** via the committed wrapper `./mvnw`. No system `mvn`.
- **Modulith named interfaces:** a module is consumed cross-module only through a sub-package marked `@org.springframework.modulith.NamedInterface("api")`, and the consumer must list it in `allowedDependencies` as `module :: api`. Plain module name (`common`, `database`) references the module root.
- **UUID-as-VARCHAR convention:** entity ids are `UUID` fields annotated `@JdbcTypeCode(SqlTypes.VARCHAR)` + `@Column(length = 36)`; migrations declare `VARCHAR(36)`.
- **Money is `BigDecimal` + currency code — never `double`/`float`.** Monetary amounts (net, tax, totals, tender, change) are rounded to **scale 2, `RoundingMode.HALF_UP`**. Unit prices keep scale 4 (matches `product.unit_price NUMERIC(19,4)`); quantities scale 3 (matches `stock_level.quantity_on_hand NUMERIC(19,3)`). Use `java.math.RoundingMode.HALF_UP` everywhere — no exceptions.
- **Single physical `pos` schema.** Entities are schema-agnostic (no `@Table(schema=…)`); the store-server profile sets `hibernate.default_schema: pos` and `flyway.default-schema: pos`.
- **Flyway versions are globally unique and ordered across all per-module locations** (V1 config, V2 auth, V3 product, V4 inventory, V5 integration already exist). New migrations continue at **V6** and every new migration directory must be added to the `flyway.locations` list in `application-store-server.yml`. The `embedded` profile uses Hibernate `ddl-auto: update` and **no Flyway** — tests run on `embedded`.
- **Stateless JWT bearer auth** (HS256) is already in place. All new endpoints require an authenticated user; no new role gate is added in this phase (cashiers sell). Roles: `CASHIER`, `MANAGER`, `ADMIN`.
- **Offline-first:** nothing in the sell path may block on connectivity, and a sale must never be rolled back by a downstream side effect. Stock may go negative (the store is authoritative; ERP reconciles later).
- **Errors** surface as `DomainException.notFound/validation/conflict(...)` → RFC-7807 `ProblemDetail` via the existing `ApiExceptionHandler`.

---

## Existing interfaces this phase consumes (already implemented — do not redefine)

```java
// com.company.pos.common.events
public interface DomainEvent {}
@Component public class DomainEvents { public void publish(DomainEvent event) {…} }

// com.company.pos.common.exception
public class DomainException extends RuntimeException {
    public static DomainException notFound(String message);
    public static DomainException validation(String message);
    public static DomainException conflict(String message);
}

// com.company.pos.common.util
public final class Identifiers { public static UUID newId(); }
public final class Monies {
    public static javax.money.MonetaryAmount of(BigDecimal amount, String currencyCode);
    public static String format(javax.money.MonetaryAmount amount, java.util.Locale locale);
}

// com.company.pos.product.api  (module exposes @NamedInterface("api"))
public interface ProductCatalog { Optional<ProductView> findBySku(String sku); … }
public record ProductView(String sku, String name, String categoryName, String barcode,
        String unitOfMeasure, BigDecimal unitPrice, String currencyCode, boolean active) {}

// com.company.pos.configuration.api  (interface exists; package is NOT YET a named interface — Task 1 fixes that)
public interface ConfigurationService {
    String getString(SettingKey key); int getInt(SettingKey key);
    boolean getBoolean(SettingKey key); void put(SettingKey key, String value);
}
public enum SettingKey { STORE_NAME, CURRENCY_CODE, LOCALE, TAX_INCLUSIVE, RECEIPT_PRINTER_PORT; /* Task 1 adds more */ }

// com.company.pos.device.api  (ports exist; package is NOT YET a named interface — Task 2 fixes that)
public interface Printer { void print(List<PrintLine> lines); void cut(); }
public record PrintLine(String text, boolean bold) {}
public interface CashDrawer { void open(); boolean isOpen(); }
```

## New cross-module API surface produced by this phase

| Module | `@NamedInterface("api")` types |
|---|---|
| `cart` | `CartService`, `CartView`, `CartLineView` |
| `pricing` | `PricingService`, `PricingInput`, `PricedLine` |
| `tax` | `TaxService`, `TaxLineInput`, `TaxedLine`, `TaxedCart` |
| `payment` | `PaymentService`, `PaymentMethod`, `CashPaymentView` |
| `receipt` | `ReceiptService`, `ReceiptData`, `ReceiptLineData` |
| `sales` | `SalesService`, `CheckoutCommand`, `SaleView`, `SaleLineView`, `SalePaymentView`, `SaleCompleted` (event) |

## Module dependency declarations introduced/changed

```
cart        allowedDependencies = { "common", "database", "product :: api" }
pricing     allowedDependencies = { "common" }
tax         allowedDependencies = { "common" }
payment     allowedDependencies = { "common", "database" }
receipt     allowedDependencies = { "common", "device :: api", "configuration :: api" }
sales       allowedDependencies = { "common", "database", "cart :: api", "pricing :: api",
                                     "tax :: api", "payment :: api", "receipt :: api",
                                     "configuration :: api" }
inventory   allowedDependencies = { "common", "database", "integration :: api", "sales :: api" }   // CHANGED: add "sales :: api"
```
No cycle: `inventory → sales :: api` (event subscriber), and `sales` does **not** depend on `inventory`. `sales` reads the catalogue snapshot the `cart` already captured, so it does **not** depend on `product :: api` (only `cart` does).

## Migrations & config added

| Version | Location dir | Tables |
|---|---|---|
| V6 | `db/migration/cart` | `cart`, `cart_line` |
| V7 | `db/migration/payment` | `payment` |
| V8 | `db/migration/sales` | `sale`, `sale_line`, `sale_number_sequence` |
| V9 | `db/migration/inventory` | `stock_movement` |

New `SettingKey`s: `VAT_RATE` (`tax.rate`=`0.15`), `STORE_ID` (`store.id`=`S01`), `TERMINAL_ID` (`terminal.id`=`T01`), `INVENTORY_LOCATION` (`inventory.location`=`MAIN`).

---

### Task 1: Configuration — checkout setting keys + expose `configuration.api`

Adds the settings the checkout path reads (VAT rate, store/terminal ids, inventory location) and exposes the configuration module's API package as a Modulith named interface so `tax`, `receipt`, and `sales` may depend on it.

**Files:**
- Modify: `src/main/java/com/company/pos/configuration/api/SettingKey.java`
- Create: `src/main/java/com/company/pos/configuration/api/package-info.java`
- Test: `src/test/java/com/company/pos/configuration/SettingKeyTest.java`

**Interfaces:**
- Consumes: existing `ConfigurationService` (unchanged).
- Produces: `SettingKey.VAT_RATE`, `SettingKey.STORE_ID`, `SettingKey.TERMINAL_ID`, `SettingKey.INVENTORY_LOCATION` (each with `.key()` / `.defaultValue()`); `configuration.api` becomes a `@NamedInterface("api")`.

- [ ] **Step 1: Write the failing test**

```java
package com.company.pos.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.configuration.api.SettingKey;
import org.junit.jupiter.api.Test;

class SettingKeyTest {

    @Test
    void exposesCheckoutDefaults() {
        assertThat(SettingKey.VAT_RATE.key()).isEqualTo("tax.rate");
        assertThat(SettingKey.VAT_RATE.defaultValue()).isEqualTo("0.15");
        assertThat(SettingKey.STORE_ID.defaultValue()).isEqualTo("S01");
        assertThat(SettingKey.TERMINAL_ID.defaultValue()).isEqualTo("T01");
        assertThat(SettingKey.INVENTORY_LOCATION.defaultValue()).isEqualTo("MAIN");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=SettingKeyTest`
Expected: FAIL — `VAT_RATE` cannot be resolved (compile error).

- [ ] **Step 3: Add the enum constants**

Edit `SettingKey.java` — add four constants to the existing enum list (keep the existing five):

```java
public enum SettingKey {
    STORE_NAME("store.name", "My Store"),
    CURRENCY_CODE("currency.code", "SAR"),
    LOCALE("locale", "en"),
    TAX_INCLUSIVE("tax.inclusive", "false"),
    RECEIPT_PRINTER_PORT("printer.port", "COM1"),
    VAT_RATE("tax.rate", "0.15"),
    STORE_ID("store.id", "S01"),
    TERMINAL_ID("terminal.id", "T01"),
    INVENTORY_LOCATION("inventory.location", "MAIN");

    private final String key;
    private final String defaultValue;

    SettingKey(String key, String defaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
    }

    public String key() {
        return key;
    }

    public String defaultValue() {
        return defaultValue;
    }
}
```

- [ ] **Step 4: Expose `configuration.api` as a named interface**

Create `src/main/java/com/company/pos/configuration/api/package-info.java`:

```java
@org.springframework.modulith.NamedInterface("api")
package com.company.pos.configuration.api;
```

- [ ] **Step 5: Run test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=SettingKeyTest`
Expected: PASS.

- [ ] **Step 6: Verify module boundaries still pass**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ModularityTests`
Expected: PASS — no module yet depends on `configuration :: api`, so adding the named interface is non-breaking.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/pos/configuration/api/SettingKey.java \
        src/main/java/com/company/pos/configuration/api/package-info.java \
        src/test/java/com/company/pos/configuration/SettingKeyTest.java
git commit -m "feat(configuration): add checkout setting keys and expose api named interface"
```

---

### Task 2: Device — in-memory fake `Printer` and `CashDrawer` adapters + expose `device.api`

The `device` module defines ports but ships no Spring beans, so nothing can `@Autowired Printer`. Add in-memory fake adapters (the default beans until a real JavaPOS/ESC-POS adapter exists) and expose `device.api` as a named interface so `receipt` may depend on it.

**Files:**
- Create: `src/main/java/com/company/pos/device/infrastructure/InMemoryPrinter.java`
- Create: `src/main/java/com/company/pos/device/infrastructure/InMemoryCashDrawer.java`
- Create: `src/main/java/com/company/pos/device/api/package-info.java`
- Test: `src/test/java/com/company/pos/device/InMemoryDeviceAdapterTest.java`

**Interfaces:**
- Consumes: `Printer`, `PrintLine`, `CashDrawer` (existing `device.api`).
- Produces: `InMemoryPrinter` bean implementing `Printer` with `List<PrintLine> lastReceipt()` and `int cutCount()` for assertions; `InMemoryCashDrawer` bean implementing `CashDrawer`; `device.api` becomes `@NamedInterface("api")`.

- [ ] **Step 1: Write the failing test**

```java
package com.company.pos.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.device.api.CashDrawer;
import com.company.pos.device.api.PrintLine;
import com.company.pos.device.api.Printer;
import com.company.pos.device.infrastructure.InMemoryPrinter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class InMemoryDeviceAdapterTest {

    @Autowired
    Printer printer;
    @Autowired
    CashDrawer drawer;
    @Autowired
    InMemoryPrinter inMemoryPrinter;

    @Test
    void printerCapturesLinesAndCuts() {
        // cutCount accumulates across the shared application context, so assert the delta.
        int cutsBefore = inMemoryPrinter.cutCount();
        printer.print(List.of(new PrintLine("HELLO", true), new PrintLine("world", false)));
        printer.cut();

        assertThat(inMemoryPrinter.lastReceipt()).extracting(PrintLine::text)
                .containsExactly("HELLO", "world");
        assertThat(inMemoryPrinter.cutCount()).isEqualTo(cutsBefore + 1);
    }

    @Test
    void drawerOpensAndReportsState() {
        drawer.open();
        assertThat(drawer.isOpen()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=InMemoryDeviceAdapterTest`
Expected: FAIL — `InMemoryPrinter` does not exist / no `Printer` bean to autowire.

- [ ] **Step 3: Implement `InMemoryPrinter`**

```java
package com.company.pos.device.infrastructure;

import com.company.pos.device.api.PrintLine;
import com.company.pos.device.api.Printer;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Default {@link Printer} adapter: records output in memory. Replaced by a JavaPOS/ESC-POS adapter later. */
@Component
public class InMemoryPrinter implements Printer {

    private final List<PrintLine> lastReceipt = new ArrayList<>();
    private int cutCount;

    @Override
    public synchronized void print(List<PrintLine> lines) {
        lastReceipt.clear();
        lastReceipt.addAll(lines);
    }

    @Override
    public synchronized void cut() {
        cutCount++;
    }

    public synchronized List<PrintLine> lastReceipt() {
        return List.copyOf(lastReceipt);
    }

    public synchronized int cutCount() {
        return cutCount;
    }
}
```

- [ ] **Step 4: Implement `InMemoryCashDrawer`**

```java
package com.company.pos.device.infrastructure;

import com.company.pos.device.api.CashDrawer;
import org.springframework.stereotype.Component;

/** Default {@link CashDrawer} adapter: tracks open state in memory. */
@Component
public class InMemoryCashDrawer implements CashDrawer {

    private volatile boolean open;

    @Override
    public void open() {
        this.open = true;
    }

    @Override
    public boolean isOpen() {
        return open;
    }
}
```

- [ ] **Step 5: Expose `device.api` as a named interface**

Create `src/main/java/com/company/pos/device/api/package-info.java`:

```java
@org.springframework.modulith.NamedInterface("api")
package com.company.pos.device.api;
```

> Note: `device/package-info.java` keeps `allowedDependencies = { "common" }`. The new `infrastructure` sub-package is internal to the `device` module — only `device.api` is exposed.

- [ ] **Step 6: Run test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=InMemoryDeviceAdapterTest`
Expected: PASS.

- [ ] **Step 7: Verify module boundaries**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ModularityTests`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/company/pos/device/infrastructure/ \
        src/main/java/com/company/pos/device/api/package-info.java \
        src/test/java/com/company/pos/device/InMemoryDeviceAdapterTest.java
git commit -m "feat(device): add in-memory Printer/CashDrawer adapters and expose api named interface"
```

---

### Task 3: `cart` module — in-progress basket with persisted lines

A server-side `Cart` aggregate keyed by UUID, so stateless terminals can ring items up and `sales` can read the basket at checkout. Lines snapshot the product name, unit price, and currency from the catalog at add-time (price stability). Adding an existing SKU merges quantities.

**Files:**
- Create: `src/main/java/com/company/pos/cart/package-info.java`
- Create: `src/main/java/com/company/pos/cart/api/package-info.java`
- Create: `src/main/java/com/company/pos/cart/api/CartService.java`
- Create: `src/main/java/com/company/pos/cart/api/CartView.java`
- Create: `src/main/java/com/company/pos/cart/api/CartLineView.java`
- Create: `src/main/java/com/company/pos/cart/domain/Cart.java`
- Create: `src/main/java/com/company/pos/cart/domain/CartLine.java`
- Create: `src/main/java/com/company/pos/cart/infrastructure/CartRepository.java`
- Create: `src/main/java/com/company/pos/cart/application/DefaultCartService.java`
- Create: `src/main/java/com/company/pos/cart/web/CartController.java`
- Create: `src/main/resources/db/migration/cart/V6__cart.sql`
- Test: `src/test/java/com/company/pos/cart/CartServiceTest.java`
- Test: `src/test/java/com/company/pos/cart/CartControllerTest.java`

**Interfaces:**
- Consumes: `ProductCatalog.findBySku(String) -> Optional<ProductView>`; `Identifiers.newId()`; `DomainException`.
- Produces:
  - `record CartLineView(String sku, String name, BigDecimal quantity, BigDecimal unitPrice, String currencyCode)`
  - `record CartView(UUID cartId, String status, String currencyCode, List<CartLineView> lines)`
  - `interface CartService { UUID createCart(); CartView addLine(UUID cartId, String sku, BigDecimal quantity); CartView updateLine(UUID cartId, String sku, BigDecimal quantity); CartView removeLine(UUID cartId, String sku); CartView getCart(UUID cartId); void close(UUID cartId); }`
  - Cart statuses are the strings `"OPEN"` and `"CHECKED_OUT"`.

- [ ] **Step 1: Declare the module**

Create `src/main/java/com/company/pos/cart/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "product :: api" })
package com.company.pos.cart;
```

Create `src/main/java/com/company/pos/cart/api/package-info.java`:

```java
@org.springframework.modulith.NamedInterface("api")
package com.company.pos.cart.api;
```

- [ ] **Step 2: Write the failing service test**

```java
package com.company.pos.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.cart.api.CartService;
import com.company.pos.cart.api.CartView;
import com.company.pos.common.exception.DomainException;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.product.api.ProductSync;
import java.math.BigDecimal;
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
class CartServiceTest {

    @Autowired
    CartService carts;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;

    @BeforeEach
    void seedCatalogue() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        productSync.sync();
    }

    @Test
    void addLineSnapshotsCataloguePrice() {
        UUID cart = carts.createCart();
        CartView view = carts.addLine(cart, "COLA", new BigDecimal("2"));

        assertThat(view.status()).isEqualTo("OPEN");
        assertThat(view.lines()).hasSize(1);
        assertThat(view.lines().get(0).name()).isEqualTo("Cola Can");
        assertThat(view.lines().get(0).unitPrice()).isEqualByComparingTo("4.50");
        assertThat(view.lines().get(0).quantity()).isEqualByComparingTo("2");
        assertThat(view.currencyCode()).isEqualTo("SAR");
    }

    @Test
    void addingSameSkuMergesQuantity() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        CartView view = carts.addLine(cart, "COLA", new BigDecimal("3"));

        assertThat(view.lines()).hasSize(1);
        assertThat(view.lines().get(0).quantity()).isEqualByComparingTo("5");
    }

    @Test
    void unknownSkuIsRejected() {
        UUID cart = carts.createCart();
        assertThatThrownBy(() -> carts.addLine(cart, "NOPE", BigDecimal.ONE))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void nonPositiveQuantityIsRejected() {
        UUID cart = carts.createCart();
        assertThatThrownBy(() -> carts.addLine(cart, "COLA", BigDecimal.ZERO))
                .isInstanceOf(DomainException.class);
    }
}
```

> `ProductSync.sync()` is the Phase 1 facade (returns an `int` count of synced products) that loads the fake ERP catalogue into the local `product` tables. The seed's only job is to make `COLA` resolvable via `ProductCatalog`.

- [ ] **Step 3: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CartServiceTest`
Expected: FAIL — `CartService` and friends do not exist (compile error).

- [ ] **Step 4: Write the API records and interface**

`src/main/java/com/company/pos/cart/api/CartLineView.java`:

```java
package com.company.pos.cart.api;

import java.math.BigDecimal;

public record CartLineView(String sku, String name, BigDecimal quantity,
        BigDecimal unitPrice, String currencyCode) {
}
```

`src/main/java/com/company/pos/cart/api/CartView.java`:

```java
package com.company.pos.cart.api;

import java.util.List;
import java.util.UUID;

public record CartView(UUID cartId, String status, String currencyCode, List<CartLineView> lines) {
}
```

`src/main/java/com/company/pos/cart/api/CartService.java`:

```java
package com.company.pos.cart.api;

import java.math.BigDecimal;
import java.util.UUID;

public interface CartService {

    UUID createCart();

    CartView addLine(UUID cartId, String sku, BigDecimal quantity);

    CartView updateLine(UUID cartId, String sku, BigDecimal quantity);

    CartView removeLine(UUID cartId, String sku);

    CartView getCart(UUID cartId);

    void close(UUID cartId);
}
```

- [ ] **Step 5: Write the domain entities**

`src/main/java/com/company/pos/cart/domain/Cart.java`:

```java
package com.company.pos.cart.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "cart")
public class Cart {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<CartLine> lines = new ArrayList<>();

    protected Cart() {
        // JPA
    }

    public Cart(UUID id, Instant createdAt) {
        this.id = id;
        this.status = "OPEN";
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public List<CartLine> getLines() {
        return lines;
    }

    public Optional<CartLine> findLine(String sku) {
        return lines.stream().filter(l -> l.getSku().equals(sku)).findFirst();
    }

    public void addLine(String sku, String name, BigDecimal quantity, BigDecimal unitPrice, String currency) {
        if (this.currencyCode == null) {
            this.currencyCode = currency;
        }
        findLine(sku).ifPresentOrElse(
                line -> line.addQuantity(quantity),
                () -> lines.add(new CartLine(this, lines.size() + 1, sku, name, quantity, unitPrice, currency)));
    }

    public void setLineQuantity(String sku, BigDecimal quantity) {
        lines.stream().filter(l -> l.getSku().equals(sku)).findFirst()
                .ifPresent(line -> line.setQuantity(quantity));
    }

    public void removeLine(String sku) {
        lines.removeIf(l -> l.getSku().equals(sku));
    }

    public void close() {
        this.status = "CHECKED_OUT";
    }

    public boolean isOpen() {
        return "OPEN".equals(status);
    }
}
```

`src/main/java/com/company/pos/cart/domain/CartLine.java`:

```java
package com.company.pos.cart.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "cart_line",
        uniqueConstraints = @UniqueConstraint(name = "uq_cart_line_sku",
                columnNames = { "cart_id", "sku" }))
class CartLine {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    protected CartLine() {
        // JPA
    }

    CartLine(Cart cart, int lineNo, String sku, String name, BigDecimal quantity,
            BigDecimal unitPrice, String currencyCode) {
        this.id = UUID.randomUUID();
        this.cart = cart;
        this.lineNo = lineNo;
        this.sku = sku;
        this.name = name;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.currencyCode = currencyCode;
    }

    String getSku() {
        return sku;
    }

    String getName() {
        return name;
    }

    BigDecimal getQuantity() {
        return quantity;
    }

    BigDecimal getUnitPrice() {
        return unitPrice;
    }

    String getCurrencyCode() {
        return currencyCode;
    }

    void addQuantity(BigDecimal delta) {
        this.quantity = this.quantity.add(delta);
    }

    void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }
}
```

- [ ] **Step 6: Write the repository**

`src/main/java/com/company/pos/cart/infrastructure/CartRepository.java`:

```java
package com.company.pos.cart.infrastructure;

import com.company.pos.cart.domain.Cart;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartRepository extends JpaRepository<Cart, UUID> {
}
```

- [ ] **Step 7: Write the service**

`src/main/java/com/company/pos/cart/application/DefaultCartService.java`:

```java
package com.company.pos.cart.application;

import com.company.pos.cart.api.CartLineView;
import com.company.pos.cart.api.CartService;
import com.company.pos.cart.api.CartView;
import com.company.pos.cart.domain.Cart;
import com.company.pos.cart.infrastructure.CartRepository;
import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.product.api.ProductCatalog;
import com.company.pos.product.api.ProductView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultCartService implements CartService {

    private final CartRepository carts;
    private final ProductCatalog catalogue;

    DefaultCartService(CartRepository carts, ProductCatalog catalogue) {
        this.carts = carts;
        this.catalogue = catalogue;
    }

    @Override
    public UUID createCart() {
        Cart cart = new Cart(Identifiers.newId(), Instant.now());
        carts.save(cart);
        return cart.getId();
    }

    @Override
    public CartView addLine(UUID cartId, String sku, BigDecimal quantity) {
        requirePositive(quantity);
        Cart cart = openCart(cartId);
        ProductView product = catalogue.findBySku(sku)
                .orElseThrow(() -> DomainException.notFound("Unknown sku " + sku));
        cart.addLine(sku, product.name(), quantity, product.unitPrice(), product.currencyCode());
        return toView(cart);
    }

    @Override
    public CartView updateLine(UUID cartId, String sku, BigDecimal quantity) {
        requirePositive(quantity);
        Cart cart = openCart(cartId);
        if (cart.findLine(sku).isEmpty()) {
            throw DomainException.notFound("No line for sku " + sku);
        }
        cart.setLineQuantity(sku, quantity);
        return toView(cart);
    }

    @Override
    public CartView removeLine(UUID cartId, String sku) {
        Cart cart = openCart(cartId);
        cart.removeLine(sku);
        return toView(cart);
    }

    @Override
    @Transactional(readOnly = true)
    public CartView getCart(UUID cartId) {
        return toView(load(cartId));
    }

    @Override
    public void close(UUID cartId) {
        load(cartId).close();
    }

    private Cart load(UUID cartId) {
        return carts.findById(cartId)
                .orElseThrow(() -> DomainException.notFound("No cart " + cartId));
    }

    private Cart openCart(UUID cartId) {
        Cart cart = load(cartId);
        if (!cart.isOpen()) {
            throw DomainException.conflict("Cart " + cartId + " is not open");
        }
        return cart;
    }

    private void requirePositive(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw DomainException.validation("Quantity must be positive");
        }
    }

    private CartView toView(Cart cart) {
        List<CartLineView> lines = cart.getLines().stream()
                .map(l -> new CartLineView(l.getSku(), l.getName(), l.getQuantity(),
                        l.getUnitPrice(), l.getCurrencyCode()))
                .toList();
        return new CartView(cart.getId(), cart.getStatus(), cart.getCurrencyCode(), lines);
    }
}
```

> `DefaultCartService` (in the `cart.application` package) maps each `CartLine` via its read getters, but `CartLine` lives in `cart.domain` — a different Java package. The read getters must therefore be `public` (Step 8 widens them). The class `CartLine` itself stays package-private and its mutators/constructor stay package-private — only `Cart`, in the same package, constructs and mutates lines. `cart.getLines()` returns `List<CartLine>`; the service uses type inference (`l -> …`) so it never needs to name the package-private type. Modulith boundaries govern cross-*module* access; this is all within the `cart` module, so it passes `verify()`.

- [ ] **Step 8: Make `CartLine` getters public**

Edit `CartLine.java` — change `getSku`, `getName`, `getQuantity`, `getUnitPrice`, `getCurrencyCode` from package-private to `public`. (The mutators `addQuantity`/`setQuantity` and the constructor stay package-private — only `Cart`, in the same package, calls them.)

- [ ] **Step 9: Write the migration (store-server)**

`src/main/resources/db/migration/cart/V6__cart.sql`:

```sql
CREATE TABLE cart (
    id            VARCHAR(36) PRIMARY KEY,
    status        VARCHAR(16) NOT NULL,
    currency_code VARCHAR(3),
    created_at    TIMESTAMP NOT NULL
);

CREATE TABLE cart_line (
    id            VARCHAR(36) PRIMARY KEY,
    cart_id       VARCHAR(36) NOT NULL REFERENCES cart (id),
    line_no       INTEGER NOT NULL,
    sku           VARCHAR(64) NOT NULL,
    name          VARCHAR(300) NOT NULL,
    quantity      NUMERIC(19, 3) NOT NULL,
    unit_price    NUMERIC(19, 4) NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    CONSTRAINT uq_cart_line_sku UNIQUE (cart_id, sku)
);

CREATE INDEX idx_cart_line_cart ON cart_line (cart_id);
```

- [ ] **Step 10: Add the migration location to store-server config**

Edit `src/main/resources/application-store-server.yml` — append `,classpath:db/migration/cart` to the `flyway.locations` value (keep all existing entries on the single comma-separated line).

- [ ] **Step 11: Run the service test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CartServiceTest`
Expected: PASS (all four tests).

- [ ] **Step 12: Write the controller and its test**

`src/main/java/com/company/pos/cart/web/CartController.java`:

```java
package com.company.pos.cart.web;

import com.company.pos.cart.api.CartService;
import com.company.pos.cart.api.CartView;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CartController {

    private final CartService carts;

    CartController(CartService carts) {
        this.carts = carts;
    }

    record CartLineRequest(String sku, BigDecimal quantity) {
    }

    record QuantityRequest(BigDecimal quantity) {
    }

    @PostMapping("/carts")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> create() {
        return Map.of("cartId", carts.createCart());
    }

    @GetMapping("/carts/{cartId}")
    CartView get(@PathVariable UUID cartId) {
        return carts.getCart(cartId);
    }

    @PostMapping("/carts/{cartId}/lines")
    CartView addLine(@PathVariable UUID cartId, @RequestBody CartLineRequest body) {
        return carts.addLine(cartId, body.sku(), body.quantity());
    }

    @PutMapping("/carts/{cartId}/lines/{sku}")
    CartView updateLine(@PathVariable UUID cartId, @PathVariable String sku,
            @RequestBody QuantityRequest body) {
        return carts.updateLine(cartId, sku, body.quantity());
    }

    @DeleteMapping("/carts/{cartId}/lines/{sku}")
    CartView removeLine(@PathVariable UUID cartId, @PathVariable String sku) {
        return carts.removeLine(cartId, sku);
    }
}
```

`src/test/java/com/company/pos/cart/CartControllerTest.java`:

```java
package com.company.pos.cart;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
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
class CartControllerTest {

    @Autowired
    MockMvc mvc;
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
    void createAddAndReadCart() throws Exception {
        String created = mvc.perform(post("/carts").with(jwt().jwt(j -> j.subject("cashier"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String cartId = JsonPath.read(created, "$.cartId");

        mvc.perform(post("/carts/" + cartId + "/lines").with(jwt().jwt(j -> j.subject("cashier")))
                        .contentType("application/json")
                        .content("{\"sku\":\"COLA\",\"quantity\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].sku").value("COLA"))
                .andExpect(jsonPath("$.lines[0].quantity").value(3));

        mvc.perform(get("/carts/" + cartId).with(jwt().jwt(j -> j.subject("cashier"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(post("/carts")).andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 13: Run the controller test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CartControllerTest`
Expected: PASS.

- [ ] **Step 14: Commit**

```bash
git add src/main/java/com/company/pos/cart/ \
        src/main/resources/db/migration/cart/ \
        src/main/resources/application-store-server.yml \
        src/test/java/com/company/pos/cart/
git commit -m "feat(cart): persisted basket aggregate with add/update/remove and REST"
```

---

### Task 4: `pricing` module — line extension calculator (pure)

A stateless calculator that turns catalogue prices and quantities into rounded extended line amounts. No persistence. Discounts/coupons/BOGO arrive in Phase 2b+; this is the seam they will extend.

**Files:**
- Create: `src/main/java/com/company/pos/pricing/package-info.java`
- Create: `src/main/java/com/company/pos/pricing/api/package-info.java`
- Create: `src/main/java/com/company/pos/pricing/api/PricingInput.java`
- Create: `src/main/java/com/company/pos/pricing/api/PricedLine.java`
- Create: `src/main/java/com/company/pos/pricing/api/PricingService.java`
- Create: `src/main/java/com/company/pos/pricing/application/DefaultPricingService.java`
- Test: `src/test/java/com/company/pos/pricing/PricingServiceTest.java`

**Interfaces:**
- Consumes: nothing beyond `common` (none used here).
- Produces:
  - `record PricingInput(String sku, String name, BigDecimal quantity, BigDecimal unitPrice, String currencyCode)`
  - `record PricedLine(String sku, String name, BigDecimal quantity, BigDecimal unitPrice, String currencyCode, BigDecimal extendedPrice)`
  - `interface PricingService { List<PricedLine> price(List<PricingInput> inputs); }`
  - Rounding rule: `extendedPrice = (unitPrice × quantity)` rounded to **scale 2, HALF_UP**.

- [ ] **Step 1: Declare the module**

`src/main/java/com/company/pos/pricing/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common" })
package com.company.pos.pricing;
```

`src/main/java/com/company/pos/pricing/api/package-info.java`:

```java
@org.springframework.modulith.NamedInterface("api")
package com.company.pos.pricing.api;
```

- [ ] **Step 2: Write the failing test**

```java
package com.company.pos.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.pricing.api.PricedLine;
import com.company.pos.pricing.api.PricingInput;
import com.company.pos.pricing.application.DefaultPricingService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PricingServiceTest {

    private final DefaultPricingService pricing = new DefaultPricingService();

    @Test
    void extendsAndRoundsHalfUp() {
        // 4.505 * 3 = 13.515 -> 13.52 (HALF_UP at scale 2)
        List<PricedLine> result = pricing.price(List.of(
                new PricingInput("COLA", "Cola Can", new BigDecimal("3"),
                        new BigDecimal("4.5050"), "SAR")));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).extendedPrice()).isEqualByComparingTo("13.52");
        assertThat(result.get(0).sku()).isEqualTo("COLA");
    }

    @Test
    void fractionalQuantitySupported() {
        // 2.50 * 1.5 = 3.75
        List<PricedLine> result = pricing.price(List.of(
                new PricingInput("RICE", "Rice", new BigDecimal("1.5"),
                        new BigDecimal("2.5000"), "SAR")));

        assertThat(result.get(0).extendedPrice()).isEqualByComparingTo("3.75");
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=PricingServiceTest`
Expected: FAIL — types do not exist.

- [ ] **Step 4: Write the API types**

`PricingInput.java`:

```java
package com.company.pos.pricing.api;

import java.math.BigDecimal;

public record PricingInput(String sku, String name, BigDecimal quantity,
        BigDecimal unitPrice, String currencyCode) {
}
```

`PricedLine.java`:

```java
package com.company.pos.pricing.api;

import java.math.BigDecimal;

public record PricedLine(String sku, String name, BigDecimal quantity, BigDecimal unitPrice,
        String currencyCode, BigDecimal extendedPrice) {
}
```

`PricingService.java`:

```java
package com.company.pos.pricing.api;

import java.util.List;

public interface PricingService {

    List<PricedLine> price(List<PricingInput> inputs);
}
```

- [ ] **Step 5: Write the implementation**

`DefaultPricingService.java`:

```java
package com.company.pos.pricing.application;

import com.company.pos.pricing.api.PricedLine;
import com.company.pos.pricing.api.PricingInput;
import com.company.pos.pricing.api.PricingService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
class DefaultPricingService implements PricingService {

    @Override
    public List<PricedLine> price(List<PricingInput> inputs) {
        return inputs.stream().map(this::priceLine).toList();
    }

    private PricedLine priceLine(PricingInput input) {
        BigDecimal extended = input.unitPrice()
                .multiply(input.quantity())
                .setScale(2, RoundingMode.HALF_UP);
        return new PricedLine(input.sku(), input.name(), input.quantity(),
                input.unitPrice(), input.currencyCode(), extended);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=PricingServiceTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/pos/pricing/ src/test/java/com/company/pos/pricing/
git commit -m "feat(pricing): pure line-extension calculator with HALF_UP rounding"
```

---

### Task 5: `tax` module — VAT calculator (pure, inclusive/exclusive)

A stateless calculator that applies a single VAT rate to priced lines, supporting exclusive (tax added on top) and inclusive (tax extracted from price) modes, and rolls up cart totals. Multiple rates / exemptions are later phases.

**Files:**
- Create: `src/main/java/com/company/pos/tax/package-info.java`
- Create: `src/main/java/com/company/pos/tax/api/package-info.java`
- Create: `src/main/java/com/company/pos/tax/api/TaxLineInput.java`
- Create: `src/main/java/com/company/pos/tax/api/TaxedLine.java`
- Create: `src/main/java/com/company/pos/tax/api/TaxedCart.java`
- Create: `src/main/java/com/company/pos/tax/api/TaxService.java`
- Create: `src/main/java/com/company/pos/tax/application/DefaultTaxService.java`
- Test: `src/test/java/com/company/pos/tax/TaxServiceTest.java`

**Interfaces:**
- Consumes: nothing beyond `common`. (Deliberately independent of `pricing` — `sales` maps `PricedLine` → `TaxLineInput`, so `tax` owns its own input contract.)
- Produces:
  - `record TaxLineInput(String sku, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal extendedPrice, String currencyCode)`
  - `record TaxedLine(String sku, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxAmount, BigDecimal lineTotal, String currencyCode)`
  - `record TaxedCart(List<TaxedLine> lines, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal, String currencyCode, BigDecimal taxRate, boolean taxInclusive)`
  - `interface TaxService { TaxedCart applyTax(List<TaxLineInput> lines, BigDecimal rate, boolean inclusive, String currencyCode); }`
  - **Exclusive:** `net = extendedPrice`; `tax = round(extendedPrice × rate, 2)`; `lineTotal = net + tax`; `grandTotal = subtotal + taxTotal`.
  - **Inclusive:** `net = round(extendedPrice ÷ (1 + rate), 2)`; `tax = extendedPrice − net`; `lineTotal = extendedPrice`; `grandTotal = Σ extendedPrice`.
  - All money rounded scale 2, HALF_UP.

- [ ] **Step 1: Declare the module**

`src/main/java/com/company/pos/tax/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common" })
package com.company.pos.tax;
```

`src/main/java/com/company/pos/tax/api/package-info.java`:

```java
@org.springframework.modulith.NamedInterface("api")
package com.company.pos.tax.api;
```

- [ ] **Step 2: Write the failing test**

```java
package com.company.pos.tax;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.tax.api.TaxLineInput;
import com.company.pos.tax.api.TaxedCart;
import com.company.pos.tax.application.DefaultTaxService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaxServiceTest {

    private final DefaultTaxService tax = new DefaultTaxService();

    private TaxLineInput line(String extended) {
        return new TaxLineInput("COLA", "Cola Can", new BigDecimal("2"),
                new BigDecimal("4.5000"), new BigDecimal(extended), "SAR");
    }

    @Test
    void exclusiveAddsTaxOnTop() {
        TaxedCart cart = tax.applyTax(List.of(line("9.00")), new BigDecimal("0.15"), false, "SAR");

        assertThat(cart.subtotal()).isEqualByComparingTo("9.00");
        assertThat(cart.taxTotal()).isEqualByComparingTo("1.35");   // 9.00 * 0.15
        assertThat(cart.grandTotal()).isEqualByComparingTo("10.35");
        assertThat(cart.lines().get(0).netAmount()).isEqualByComparingTo("9.00");
        assertThat(cart.lines().get(0).taxAmount()).isEqualByComparingTo("1.35");
        assertThat(cart.lines().get(0).lineTotal()).isEqualByComparingTo("10.35");
    }

    @Test
    void inclusiveExtractsTaxFromPrice() {
        // 11.50 inclusive @ 15%: net = 11.50 / 1.15 = 10.00, tax = 1.50
        TaxedCart cart = tax.applyTax(List.of(line("11.50")), new BigDecimal("0.15"), true, "SAR");

        assertThat(cart.subtotal()).isEqualByComparingTo("10.00");
        assertThat(cart.taxTotal()).isEqualByComparingTo("1.50");
        assertThat(cart.grandTotal()).isEqualByComparingTo("11.50");
    }

    @Test
    void zeroRateProducesNoTax() {
        TaxedCart cart = tax.applyTax(List.of(line("9.00")), BigDecimal.ZERO, false, "SAR");

        assertThat(cart.taxTotal()).isEqualByComparingTo("0.00");
        assertThat(cart.grandTotal()).isEqualByComparingTo("9.00");
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=TaxServiceTest`
Expected: FAIL — types do not exist.

- [ ] **Step 4: Write the API types**

`TaxLineInput.java`:

```java
package com.company.pos.tax.api;

import java.math.BigDecimal;

public record TaxLineInput(String sku, String name, BigDecimal quantity, BigDecimal unitPrice,
        BigDecimal extendedPrice, String currencyCode) {
}
```

`TaxedLine.java`:

```java
package com.company.pos.tax.api;

import java.math.BigDecimal;

public record TaxedLine(String sku, String name, BigDecimal quantity, BigDecimal unitPrice,
        BigDecimal netAmount, BigDecimal taxAmount, BigDecimal lineTotal, String currencyCode) {
}
```

`TaxedCart.java`:

```java
package com.company.pos.tax.api;

import java.math.BigDecimal;
import java.util.List;

public record TaxedCart(List<TaxedLine> lines, BigDecimal subtotal, BigDecimal taxTotal,
        BigDecimal grandTotal, String currencyCode, BigDecimal taxRate, boolean taxInclusive) {
}
```

`TaxService.java`:

```java
package com.company.pos.tax.api;

import java.math.BigDecimal;
import java.util.List;

public interface TaxService {

    TaxedCart applyTax(List<TaxLineInput> lines, BigDecimal rate, boolean inclusive, String currencyCode);
}
```

- [ ] **Step 5: Write the implementation**

`DefaultTaxService.java`:

```java
package com.company.pos.tax.application;

import com.company.pos.tax.api.TaxLineInput;
import com.company.pos.tax.api.TaxService;
import com.company.pos.tax.api.TaxedCart;
import com.company.pos.tax.api.TaxedLine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
class DefaultTaxService implements TaxService {

    @Override
    public TaxedCart applyTax(List<TaxLineInput> lines, BigDecimal rate, boolean inclusive,
            String currencyCode) {
        List<TaxedLine> taxed = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        BigDecimal grandTotal = BigDecimal.ZERO;

        for (TaxLineInput line : lines) {
            BigDecimal extended = line.extendedPrice();
            BigDecimal net;
            BigDecimal tax;
            BigDecimal lineTotal;
            if (inclusive) {
                net = extended.divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);
                tax = extended.subtract(net);
                lineTotal = extended;
            } else {
                net = extended.setScale(2, RoundingMode.HALF_UP);
                tax = extended.multiply(rate).setScale(2, RoundingMode.HALF_UP);
                lineTotal = net.add(tax);
            }
            taxed.add(new TaxedLine(line.sku(), line.name(), line.quantity(), line.unitPrice(),
                    net, tax, lineTotal, line.currencyCode()));
            subtotal = subtotal.add(net);
            taxTotal = taxTotal.add(tax);
            grandTotal = grandTotal.add(lineTotal);
        }

        return new TaxedCart(taxed,
                subtotal.setScale(2, RoundingMode.HALF_UP),
                taxTotal.setScale(2, RoundingMode.HALF_UP),
                grandTotal.setScale(2, RoundingMode.HALF_UP),
                currencyCode, rate, inclusive);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=TaxServiceTest`
Expected: PASS (all three).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/pos/tax/ src/test/java/com/company/pos/tax/
git commit -m "feat(tax): VAT calculator with inclusive/exclusive modes and total roll-up"
```

---

### Task 6: `payment` module — cash tender

Records a cash tender against a sale id, validates the tendered amount covers what's due, computes change, and persists an immutable `payment` row. Card/QR/wallet/split arrive in Phase 2b — `PaymentMethod` is an enum so they slot in without an API change.

**Files:**
- Create: `src/main/java/com/company/pos/payment/package-info.java`
- Create: `src/main/java/com/company/pos/payment/api/package-info.java`
- Create: `src/main/java/com/company/pos/payment/api/PaymentMethod.java`
- Create: `src/main/java/com/company/pos/payment/api/CashPaymentView.java`
- Create: `src/main/java/com/company/pos/payment/api/PaymentService.java`
- Create: `src/main/java/com/company/pos/payment/domain/Payment.java`
- Create: `src/main/java/com/company/pos/payment/infrastructure/PaymentRepository.java`
- Create: `src/main/java/com/company/pos/payment/application/DefaultPaymentService.java`
- Create: `src/main/resources/db/migration/payment/V7__payment.sql`
- Test: `src/test/java/com/company/pos/payment/PaymentServiceTest.java`

**Interfaces:**
- Consumes: `Identifiers.newId()`, `DomainException`.
- Produces:
  - `enum PaymentMethod { CASH }`
  - `record CashPaymentView(UUID saleId, BigDecimal amount, BigDecimal amountTendered, BigDecimal changeDue, String currencyCode)`
  - `interface PaymentService { CashPaymentView recordCash(UUID saleId, String currencyCode, BigDecimal amountDue, BigDecimal amountTendered); }`
  - Validation: `amountTendered` < `amountDue` → `DomainException.validation`. `changeDue = amountTendered − amountDue` (scale 2). Amounts stored scale 2.

- [ ] **Step 1: Declare the module**

`src/main/java/com/company/pos/payment/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database" })
package com.company.pos.payment;
```

`src/main/java/com/company/pos/payment/api/package-info.java`:

```java
@org.springframework.modulith.NamedInterface("api")
package com.company.pos.payment.api;
```

- [ ] **Step 2: Write the failing test**

```java
package com.company.pos.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.payment.api.CashPaymentView;
import com.company.pos.payment.api.PaymentService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class PaymentServiceTest {

    @Autowired
    PaymentService payments;

    @Test
    void recordsCashAndComputesChange() {
        UUID saleId = Identifiers.newId();
        CashPaymentView view = payments.recordCash(saleId, "SAR",
                new BigDecimal("10.35"), new BigDecimal("20.00"));

        assertThat(view.saleId()).isEqualTo(saleId);
        assertThat(view.amount()).isEqualByComparingTo("10.35");
        assertThat(view.changeDue()).isEqualByComparingTo("9.65");
    }

    @Test
    void exactTenderGivesZeroChange() {
        CashPaymentView view = payments.recordCash(Identifiers.newId(), "SAR",
                new BigDecimal("10.35"), new BigDecimal("10.35"));
        assertThat(view.changeDue()).isEqualByComparingTo("0.00");
    }

    @Test
    void insufficientTenderIsRejected() {
        assertThatThrownBy(() -> payments.recordCash(Identifiers.newId(), "SAR",
                new BigDecimal("10.35"), new BigDecimal("5.00")))
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=PaymentServiceTest`
Expected: FAIL — types do not exist.

- [ ] **Step 4: Write the API types**

`PaymentMethod.java`:

```java
package com.company.pos.payment.api;

public enum PaymentMethod {
    CASH
}
```

`CashPaymentView.java`:

```java
package com.company.pos.payment.api;

import java.math.BigDecimal;
import java.util.UUID;

public record CashPaymentView(UUID saleId, BigDecimal amount, BigDecimal amountTendered,
        BigDecimal changeDue, String currencyCode) {
}
```

`PaymentService.java`:

```java
package com.company.pos.payment.api;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentService {

    CashPaymentView recordCash(UUID saleId, String currencyCode, BigDecimal amountDue,
            BigDecimal amountTendered);
}
```

- [ ] **Step 5: Write the entity**

`Payment.java`:

```java
package com.company.pos.payment.domain;

import com.company.pos.payment.api.PaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "sale_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID saleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentMethod method;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "amount_tendered", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountTendered;

    @Column(name = "change_due", nullable = false, precision = 19, scale = 2)
    private BigDecimal changeDue;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Payment() {
        // JPA
    }

    public Payment(UUID id, UUID saleId, PaymentMethod method, BigDecimal amount,
            BigDecimal amountTendered, BigDecimal changeDue, String currencyCode, Instant createdAt) {
        this.id = id;
        this.saleId = saleId;
        this.method = method;
        this.amount = amount;
        this.amountTendered = amountTendered;
        this.changeDue = changeDue;
        this.currencyCode = currencyCode;
        this.createdAt = createdAt;
    }

    public UUID getSaleId() {
        return saleId;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
```

- [ ] **Step 6: Write the repository**

`PaymentRepository.java`:

```java
package com.company.pos.payment.infrastructure;

import com.company.pos.payment.domain.Payment;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
}
```

- [ ] **Step 7: Write the service**

`DefaultPaymentService.java`:

```java
package com.company.pos.payment.application;

import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.payment.api.CashPaymentView;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.payment.api.PaymentService;
import com.company.pos.payment.domain.Payment;
import com.company.pos.payment.infrastructure.PaymentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultPaymentService implements PaymentService {

    private final PaymentRepository payments;

    DefaultPaymentService(PaymentRepository payments) {
        this.payments = payments;
    }

    @Override
    public CashPaymentView recordCash(UUID saleId, String currencyCode, BigDecimal amountDue,
            BigDecimal amountTendered) {
        BigDecimal due = amountDue.setScale(2, RoundingMode.HALF_UP);
        BigDecimal tendered = amountTendered.setScale(2, RoundingMode.HALF_UP);
        if (tendered.compareTo(due) < 0) {
            throw DomainException.validation(
                    "Tendered " + tendered + " is less than amount due " + due);
        }
        BigDecimal change = tendered.subtract(due);
        Payment payment = new Payment(Identifiers.newId(), saleId, PaymentMethod.CASH,
                due, tendered, change, currencyCode, Instant.now());
        payments.save(payment);
        return new CashPaymentView(saleId, due, tendered, change, currencyCode);
    }
}
```

- [ ] **Step 8: Write the migration**

`src/main/resources/db/migration/payment/V7__payment.sql`:

```sql
CREATE TABLE payment (
    id              VARCHAR(36) PRIMARY KEY,
    sale_id         VARCHAR(36) NOT NULL,
    method          VARCHAR(16) NOT NULL,
    amount          NUMERIC(19, 2) NOT NULL,
    amount_tendered NUMERIC(19, 2) NOT NULL,
    change_due      NUMERIC(19, 2) NOT NULL,
    currency_code   VARCHAR(3) NOT NULL,
    created_at      TIMESTAMP NOT NULL
);

CREATE INDEX idx_payment_sale ON payment (sale_id);
```

- [ ] **Step 9: Add the migration location to store-server config**

Edit `src/main/resources/application-store-server.yml` — append `,classpath:db/migration/payment` to `flyway.locations`.

- [ ] **Step 10: Run test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=PaymentServiceTest`
Expected: PASS (all three).

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/company/pos/payment/ \
        src/main/resources/db/migration/payment/ \
        src/main/resources/application-store-server.yml \
        src/test/java/com/company/pos/payment/
git commit -m "feat(payment): cash tender with change calculation and immutable payment record"
```

---

### Task 7: `receipt` module — text receipt renderer + printer output

Renders a `ReceiptData` into `PrintLine`s and sends them through the `device` `Printer` port, then cuts. Stateless — `sales` builds the data and triggers print (and reprint). Uses configuration for store name and locale; formats money with `Monies`.

**Files:**
- Create: `src/main/java/com/company/pos/receipt/package-info.java`
- Create: `src/main/java/com/company/pos/receipt/api/package-info.java`
- Create: `src/main/java/com/company/pos/receipt/api/ReceiptLineData.java`
- Create: `src/main/java/com/company/pos/receipt/api/ReceiptData.java`
- Create: `src/main/java/com/company/pos/receipt/api/ReceiptService.java`
- Create: `src/main/java/com/company/pos/receipt/application/DefaultReceiptService.java`
- Test: `src/test/java/com/company/pos/receipt/ReceiptServiceTest.java`

**Interfaces:**
- Consumes: `Printer.print(List<PrintLine>)`, `Printer.cut()`, `PrintLine` (`device.api`); `ConfigurationService.getString(SettingKey)` for `STORE_NAME`, `CURRENCY_CODE`, `LOCALE`; `Monies.format(MonetaryAmount, Locale)`.
- Produces:
  - `record ReceiptLineData(String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineTotal)`
  - `record ReceiptData(String receiptNumber, String cashierName, Instant timestamp, List<ReceiptLineData> lines, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal, BigDecimal amountTendered, BigDecimal changeDue, String currencyCode)`
  - `interface ReceiptService { void print(ReceiptData data); }`

- [ ] **Step 1: Declare the module**

`src/main/java/com/company/pos/receipt/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "device :: api", "configuration :: api" })
package com.company.pos.receipt;
```

`src/main/java/com/company/pos/receipt/api/package-info.java`:

```java
@org.springframework.modulith.NamedInterface("api")
package com.company.pos.receipt.api;
```

- [ ] **Step 2: Write the failing test**

```java
package com.company.pos.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.device.api.PrintLine;
import com.company.pos.device.infrastructure.InMemoryPrinter;
import com.company.pos.receipt.api.ReceiptData;
import com.company.pos.receipt.api.ReceiptLineData;
import com.company.pos.receipt.api.ReceiptService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class ReceiptServiceTest {

    @Autowired
    ReceiptService receipts;
    @Autowired
    InMemoryPrinter printer;

    @Test
    void printsReceiptHeaderLinesAndTotals() {
        ReceiptData data = new ReceiptData("S01-T01-000001", "cashier", Instant.now(),
                List.of(new ReceiptLineData("Cola Can", new BigDecimal("2"),
                        new BigDecimal("4.50"), new BigDecimal("10.35"))),
                new BigDecimal("9.00"), new BigDecimal("1.35"), new BigDecimal("10.35"),
                new BigDecimal("20.00"), new BigDecimal("9.65"), "SAR");

        receipts.print(data);

        List<String> text = printer.lastReceipt().stream().map(PrintLine::text).toList();
        assertThat(text).anyMatch(t -> t.contains("S01-T01-000001"));
        assertThat(text).anyMatch(t -> t.contains("Cola Can"));
        assertThat(text).anyMatch(t -> t.contains("TOTAL"));
        assertThat(printer.cutCount()).isGreaterThanOrEqualTo(1);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ReceiptServiceTest`
Expected: FAIL — types do not exist.

- [ ] **Step 4: Write the API types**

`ReceiptLineData.java`:

```java
package com.company.pos.receipt.api;

import java.math.BigDecimal;

public record ReceiptLineData(String name, BigDecimal quantity, BigDecimal unitPrice,
        BigDecimal lineTotal) {
}
```

`ReceiptData.java`:

```java
package com.company.pos.receipt.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ReceiptData(String receiptNumber, String cashierName, Instant timestamp,
        List<ReceiptLineData> lines, BigDecimal subtotal, BigDecimal taxTotal,
        BigDecimal grandTotal, BigDecimal amountTendered, BigDecimal changeDue,
        String currencyCode) {
}
```

`ReceiptService.java`:

```java
package com.company.pos.receipt.api;

public interface ReceiptService {

    void print(ReceiptData data);
}
```

- [ ] **Step 5: Write the implementation**

`DefaultReceiptService.java`:

```java
package com.company.pos.receipt.application;

import com.company.pos.common.util.Monies;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.device.api.PrintLine;
import com.company.pos.device.api.Printer;
import com.company.pos.receipt.api.ReceiptData;
import com.company.pos.receipt.api.ReceiptLineData;
import com.company.pos.receipt.api.ReceiptService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
class DefaultReceiptService implements ReceiptService {

    private final Printer printer;
    private final ConfigurationService config;

    DefaultReceiptService(Printer printer, ConfigurationService config) {
        this.printer = printer;
        this.config = config;
    }

    @Override
    public void print(ReceiptData data) {
        String storeName = config.getString(SettingKey.STORE_NAME);
        Locale locale = Locale.forLanguageTag(config.getString(SettingKey.LOCALE));
        String currency = data.currencyCode();

        List<PrintLine> lines = new ArrayList<>();
        lines.add(new PrintLine(storeName, true));
        lines.add(new PrintLine("Receipt: " + data.receiptNumber(), false));
        lines.add(new PrintLine("Cashier: " + data.cashierName(), false));
        lines.add(new PrintLine("Date: " + data.timestamp(), false));
        lines.add(new PrintLine("--------------------------------", false));
        for (ReceiptLineData line : data.lines()) {
            lines.add(new PrintLine(line.name(), false));
            lines.add(new PrintLine("  " + line.quantity().stripTrailingZeros().toPlainString()
                    + " x " + money(line.unitPrice(), currency, locale)
                    + " = " + money(line.lineTotal(), currency, locale), false));
        }
        lines.add(new PrintLine("--------------------------------", false));
        lines.add(new PrintLine("Subtotal: " + money(data.subtotal(), currency, locale), false));
        lines.add(new PrintLine("Tax:      " + money(data.taxTotal(), currency, locale), false));
        lines.add(new PrintLine("TOTAL:    " + money(data.grandTotal(), currency, locale), true));
        lines.add(new PrintLine("Cash:     " + money(data.amountTendered(), currency, locale), false));
        lines.add(new PrintLine("Change:   " + money(data.changeDue(), currency, locale), false));

        printer.print(lines);
        printer.cut();
    }

    private String money(BigDecimal amount, String currency, Locale locale) {
        return Monies.format(Monies.of(amount, currency), locale);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ReceiptServiceTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/pos/receipt/ src/test/java/com/company/pos/receipt/
git commit -m "feat(receipt): text receipt renderer printing through device Printer port"
```

---

### Task 8: `sales` module — immutable `Sale` aggregate, persistence, and receipt-number sequence

The persistence foundation for the orchestrator: the `Sale`/`SaleLine` entities, their repository, and a per-`{storeId}-{terminalId}` monotonic sequence that issues collision-free receipt numbers offline. No orchestration yet (Task 9) and no REST (Task 10).

**Files:**
- Create: `src/main/java/com/company/pos/sales/package-info.java`
- Create: `src/main/java/com/company/pos/sales/api/package-info.java`
- Create: `src/main/java/com/company/pos/sales/domain/Sale.java`
- Create: `src/main/java/com/company/pos/sales/domain/SaleLine.java`
- Create: `src/main/java/com/company/pos/sales/domain/SaleNumberSequence.java`
- Create: `src/main/java/com/company/pos/sales/infrastructure/SaleRepository.java`
- Create: `src/main/java/com/company/pos/sales/infrastructure/SaleNumberSequenceRepository.java`
- Create: `src/main/java/com/company/pos/sales/application/ReceiptNumbering.java`
- Create: `src/main/resources/db/migration/sales/V8__sales.sql`
- Test: `src/test/java/com/company/pos/sales/ReceiptNumberingTest.java`

**Interfaces:**
- Consumes: `Identifiers`, `DomainException`.
- Produces (within-module, used by Task 9):
  - `Sale` aggregate (constructor + `addLine` + getters), `SaleLine`.
  - `interface`-free `ReceiptNumbering` bean: `String nextReceiptNumber(String storeId, String terminalId)` → `"S01-T01-000001"`, incrementing under a pessimistic lock.
  - `sales.api` is declared as a `@NamedInterface("api")` here (populated with public types in Tasks 9–10).

> The `sales.api` package needs at least one type for the named-interface package-info to be meaningful at compile time. Create the `package-info.java` in this task but add the first public API type (`SaleView`) in Task 9. An empty named-interface package is valid for Modulith; it simply exposes nothing yet.

- [ ] **Step 1: Declare the module**

`src/main/java/com/company/pos/sales/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "cart :: api", "pricing :: api",
                "tax :: api", "payment :: api", "receipt :: api", "configuration :: api" })
package com.company.pos.sales;
```

`src/main/java/com/company/pos/sales/api/package-info.java`:

```java
@org.springframework.modulith.NamedInterface("api")
package com.company.pos.sales.api;
```

- [ ] **Step 2: Write the failing test**

```java
package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.sales.application.ReceiptNumbering;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class ReceiptNumberingTest {

    @Autowired
    ReceiptNumbering numbering;

    private long seq(String receiptNumber) {
        return Long.parseLong(receiptNumber.substring(receiptNumber.lastIndexOf('-') + 1));
    }

    @Test
    void issuesPaddedMonotonicNumbersPerTerminal() {
        // Distinct store/terminal ids (not the S01/T01 used by checkout tests) avoid
        // cross-test contamination: nextReceiptNumber commits in REQUIRES_NEW, so its
        // increment survives even a @Transactional caller's rollback in the shared context.
        String first = numbering.nextReceiptNumber("RNT", "TA");
        String second = numbering.nextReceiptNumber("RNT", "TA");

        assertThat(first).matches("RNT-TA-\\d{6}");
        assertThat(second).matches("RNT-TA-\\d{6}");
        assertThat(seq(second)).isEqualTo(seq(first) + 1);
    }

    @Test
    void differentTerminalsHaveIndependentSequences() {
        String terminalA = numbering.nextReceiptNumber("RNT", "TB");
        String terminalC = numbering.nextReceiptNumber("RNT", "TC");

        // Independent sequences: TB's value does not advance TC's. Both are first-touch
        // for their key within this run (unique ids), so each is 1.
        assertThat(seq(terminalA)).isEqualTo(1L);
        assertThat(seq(terminalC)).isEqualTo(1L);
    }
}
```

> This test is **not** `@Transactional`: `ReceiptNumbering` commits in `REQUIRES_NEW`, so its sequence rows persist regardless. It uses store/terminal ids (`RNT-TA/TB/TC`) that no other test touches, so the assertions hold no matter the test execution order or shared application context.

- [ ] **Step 3: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ReceiptNumberingTest`
Expected: FAIL — `ReceiptNumbering` does not exist.

- [ ] **Step 4: Write the `SaleNumberSequence` entity**

`SaleNumberSequence.java`:

```java
package com.company.pos.sales.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sale_number_sequence")
public class SaleNumberSequence {

    @Id
    @Column(length = 64)
    private String id;   // "{storeId}-{terminalId}"

    @Column(name = "next_value", nullable = false)
    private long nextValue;

    protected SaleNumberSequence() {
        // JPA
    }

    public SaleNumberSequence(String id) {
        this.id = id;
        this.nextValue = 1L;
    }

    public long takeNext() {
        long current = nextValue;
        nextValue = current + 1;
        return current;
    }
}
```

- [ ] **Step 5: Write the `Sale` and `SaleLine` entities**

`Sale.java`:

```java
package com.company.pos.sales.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sale")
public class Sale {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "receipt_number", nullable = false, unique = true, length = 40)
    private String receiptNumber;

    @Column(name = "store_id", nullable = false, length = 16)
    private String storeId;

    @Column(name = "terminal_id", nullable = false, length = 16)
    private String terminalId;

    @Column(name = "cashier_username", nullable = false, length = 100)
    private String cashierUsername;

    @Column(name = "location_code", nullable = false, length = 32)
    private String locationCode;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "tax_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal taxTotal;

    @Column(name = "grand_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal grandTotal;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "sale", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<SaleLine> lines = new ArrayList<>();

    protected Sale() {
        // JPA
    }

    public Sale(UUID id, String receiptNumber, String storeId, String terminalId,
            String cashierUsername, String locationCode, String currencyCode,
            BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal, Instant createdAt) {
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
        this.status = "COMPLETED";
    }

    public void addLine(SaleLine line) {
        lines.add(line);
    }

    public UUID getId() {
        return id;
    }

    public String getReceiptNumber() {
        return receiptNumber;
    }

    public String getCashierUsername() {
        return cashierUsername;
    }

    public String getLocationCode() {
        return locationCode;
    }

    public String getStatus() {
        return status;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getTaxTotal() {
        return taxTotal;
    }

    public BigDecimal getGrandTotal() {
        return grandTotal;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<SaleLine> getLines() {
        return lines;
    }
}
```

`SaleLine.java`:

```java
package com.company.pos.sales.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sale_line")
public class SaleLine {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "sale_id", nullable = false)
    private Sale sale;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal lineTotal;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    protected SaleLine() {
        // JPA
    }

    public SaleLine(UUID id, Sale sale, int lineNo, String sku, String name, BigDecimal quantity,
            BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxAmount, BigDecimal lineTotal,
            String currencyCode) {
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
    }

    public int getLineNo() {
        return lineNo;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }
}
```

- [ ] **Step 6: Write the repositories**

`SaleRepository.java`:

```java
package com.company.pos.sales.infrastructure;

import com.company.pos.sales.domain.Sale;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaleRepository extends JpaRepository<Sale, UUID> {
}
```

`SaleNumberSequenceRepository.java`:

```java
package com.company.pos.sales.infrastructure;

import com.company.pos.sales.domain.SaleNumberSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;

public interface SaleNumberSequenceRepository extends JpaRepository<SaleNumberSequence, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SaleNumberSequence> findById(String id);
}
```

> Overriding `findById` with `@Lock(PESSIMISTIC_WRITE)` makes the read-modify-write atomic under PostgreSQL. On the embedded SQLite profile (`maximum-pool-size: 1`) writes are already serialized, so the lock is a harmless no-op there.

- [ ] **Step 7: Write the `ReceiptNumbering` bean**

`ReceiptNumbering.java`:

> **AS-BUILT NOTE:** the plan originally specified `@Transactional(propagation = REQUIRES_NEW)` here. During execution this was changed to plain `@Transactional` (default `REQUIRED` — joins the checkout transaction). Reason: under the embedded test profile's single-connection pool, a `REQUIRES_NEW` call nested inside the `@Transactional` checkout needs a *second* connection and deadlocks. `REQUIRED` is also better domain behaviour — the receipt number commits atomically with the sale (no gaps on rollback). The code below reflects the as-built `REQUIRED` version.

```java
package com.company.pos.sales.application;

import com.company.pos.sales.domain.SaleNumberSequence;
import com.company.pos.sales.infrastructure.SaleNumberSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReceiptNumbering {

    private final SaleNumberSequenceRepository sequences;

    ReceiptNumbering(SaleNumberSequenceRepository sequences) {
        this.sequences = sequences;
    }

    @Transactional
    public String nextReceiptNumber(String storeId, String terminalId) {
        String key = storeId + "-" + terminalId;
        SaleNumberSequence sequence = sequences.findById(key)
                .orElseGet(() -> sequences.save(new SaleNumberSequence(key)));
        long value = sequence.takeNext();
        sequences.save(sequence);
        return String.format("%s-%s-%06d", storeId, terminalId, value);
    }
}
```

- [ ] **Step 8: Write the migration**

`src/main/resources/db/migration/sales/V8__sales.sql`:

```sql
CREATE TABLE sale (
    id               VARCHAR(36) PRIMARY KEY,
    receipt_number   VARCHAR(40) NOT NULL UNIQUE,
    store_id         VARCHAR(16) NOT NULL,
    terminal_id      VARCHAR(16) NOT NULL,
    cashier_username VARCHAR(100) NOT NULL,
    location_code    VARCHAR(32) NOT NULL,
    status           VARCHAR(16) NOT NULL,
    currency_code    VARCHAR(3) NOT NULL,
    subtotal         NUMERIC(19, 2) NOT NULL,
    tax_total        NUMERIC(19, 2) NOT NULL,
    grand_total      NUMERIC(19, 2) NOT NULL,
    created_at       TIMESTAMP NOT NULL
);

CREATE TABLE sale_line (
    id            VARCHAR(36) PRIMARY KEY,
    sale_id       VARCHAR(36) NOT NULL REFERENCES sale (id),
    line_no       INTEGER NOT NULL,
    sku           VARCHAR(64) NOT NULL,
    name          VARCHAR(300) NOT NULL,
    quantity      NUMERIC(19, 3) NOT NULL,
    unit_price    NUMERIC(19, 4) NOT NULL,
    net_amount    NUMERIC(19, 2) NOT NULL,
    tax_amount    NUMERIC(19, 2) NOT NULL,
    line_total    NUMERIC(19, 2) NOT NULL,
    currency_code VARCHAR(3) NOT NULL
);

CREATE INDEX idx_sale_line_sale ON sale_line (sale_id);

CREATE TABLE sale_number_sequence (
    id         VARCHAR(64) PRIMARY KEY,
    next_value BIGINT NOT NULL
);
```

- [ ] **Step 9: Add the migration location to store-server config**

Edit `src/main/resources/application-store-server.yml` — append `,classpath:db/migration/sales` to `flyway.locations`.

- [ ] **Step 10: Run test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ReceiptNumberingTest`
Expected: PASS (both).

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/company/pos/sales/ \
        src/main/resources/db/migration/sales/ \
        src/main/resources/application-store-server.yml \
        src/test/java/com/company/pos/sales/ReceiptNumberingTest.java
git commit -m "feat(sales): immutable Sale aggregate, persistence, and receipt-number sequence"
```

---

### Task 9: `sales` orchestration — checkout use case + `SaleCompleted` event

The heart of the phase: `SalesService.checkout` reads the cart, prices and taxes it, takes the cash tender, persists the immutable sale with a fresh receipt number, closes the cart, publishes `SaleCompleted`, and prints the receipt (best-effort). Plus read-side `getSale`/`reprint`.

**Files:**
- Create: `src/main/java/com/company/pos/sales/api/CheckoutCommand.java`
- Create: `src/main/java/com/company/pos/sales/api/SaleLineView.java`
- Create: `src/main/java/com/company/pos/sales/api/SalePaymentView.java`
- Create: `src/main/java/com/company/pos/sales/api/SaleView.java`
- Create: `src/main/java/com/company/pos/sales/api/SaleCompleted.java`
- Create: `src/main/java/com/company/pos/sales/api/SalesService.java`
- Create: `src/main/java/com/company/pos/sales/application/DefaultSalesService.java`
- Test: `src/test/java/com/company/pos/sales/CheckoutServiceTest.java`

**Interfaces:**
- Consumes: `CartService` (`getCart`, `close`), `PricingService.price`, `TaxService.applyTax`, `PaymentService.recordCash`, `ReceiptService.print`, `ConfigurationService.getString`, `SaleRepository`, `ReceiptNumbering`, `DomainEvents.publish`, `Identifiers`.
- Produces:
  - `record CheckoutCommand(UUID cartId, BigDecimal amountTendered)`
  - `record SaleLineView(int lineNo, String sku, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxAmount, BigDecimal lineTotal, String currencyCode)`
  - `record SalePaymentView(String method, BigDecimal amount, BigDecimal amountTendered, BigDecimal changeDue)`
  - `record SaleView(UUID id, String receiptNumber, String status, String currencyCode, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal, Instant createdAt, List<SaleLineView> lines, SalePaymentView payment)`
  - `record SaleCompleted(UUID saleId, String receiptNumber, String locationCode, String currencyCode, BigDecimal grandTotal, List<SoldLine> lines) implements DomainEvent` with nested `record SoldLine(String sku, BigDecimal quantity)`
  - `interface SalesService { SaleView checkout(CheckoutCommand command, String cashierUsername); SaleView getSale(UUID saleId); void reprint(UUID saleId); }`

- [ ] **Step 1: Write the failing test**

```java
package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.cart.api.CartService;
import com.company.pos.common.exception.DomainException;
import com.company.pos.device.infrastructure.InMemoryPrinter;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import java.math.BigDecimal;
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
class CheckoutServiceTest {

    @Autowired
    SalesService sales;
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
    void checkoutPricesTaxesPaysAndPersists() {
        // VAT default 0.15, exclusive. 2 x 4.50 = 9.00 net, tax 1.35, total 10.35.
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));

        SaleView sale = sales.checkout(new CheckoutCommand(cart, new BigDecimal("20.00")), "cashier");

        assertThat(sale.receiptNumber()).matches("S01-T01-\\d{6}");
        assertThat(sale.subtotal()).isEqualByComparingTo("9.00");
        assertThat(sale.taxTotal()).isEqualByComparingTo("1.35");
        assertThat(sale.grandTotal()).isEqualByComparingTo("10.35");
        assertThat(sale.payment().changeDue()).isEqualByComparingTo("9.65");
        assertThat(sale.lines()).hasSize(1);
        assertThat(sale.status()).isEqualTo("COMPLETED");

        // sale is retrievable
        SaleView fetched = sales.getSale(sale.id());
        assertThat(fetched.receiptNumber()).isEqualTo(sale.receiptNumber());

        // receipt was printed
        assertThat(printer.lastReceipt()).isNotEmpty();

        // cart is closed
        assertThat(carts.getCart(cart).status()).isEqualTo("CHECKED_OUT");
    }

    @Test
    void emptyCartCannotCheckout() {
        UUID cart = carts.createCart();
        assertThatThrownBy(() -> sales.checkout(new CheckoutCommand(cart, new BigDecimal("5")), "cashier"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void insufficientCashIsRejected() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));
        assertThatThrownBy(() -> sales.checkout(new CheckoutCommand(cart, new BigDecimal("1.00")), "cashier"))
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CheckoutServiceTest`
Expected: FAIL — `SalesService` and the API records do not exist.

- [ ] **Step 3: Write the API records**

`CheckoutCommand.java`:

```java
package com.company.pos.sales.api;

import java.math.BigDecimal;
import java.util.UUID;

public record CheckoutCommand(UUID cartId, BigDecimal amountTendered) {
}
```

`SaleLineView.java`:

```java
package com.company.pos.sales.api;

import java.math.BigDecimal;

public record SaleLineView(int lineNo, String sku, String name, BigDecimal quantity,
        BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxAmount, BigDecimal lineTotal,
        String currencyCode) {
}
```

`SalePaymentView.java`:

```java
package com.company.pos.sales.api;

import java.math.BigDecimal;

public record SalePaymentView(String method, BigDecimal amount, BigDecimal amountTendered,
        BigDecimal changeDue) {
}
```

`SaleView.java`:

```java
package com.company.pos.sales.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SaleView(UUID id, String receiptNumber, String status, String currencyCode,
        BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal, Instant createdAt,
        List<SaleLineView> lines, SalePaymentView payment) {
}
```

`SaleCompleted.java`:

```java
package com.company.pos.sales.api;

import com.company.pos.common.events.DomainEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record SaleCompleted(UUID saleId, String receiptNumber, String locationCode,
        String currencyCode, BigDecimal grandTotal, List<SoldLine> lines) implements DomainEvent {

    public record SoldLine(String sku, BigDecimal quantity) {
    }
}
```

`SalesService.java`:

```java
package com.company.pos.sales.api;

import java.util.UUID;

public interface SalesService {

    SaleView checkout(CheckoutCommand command, String cashierUsername);

    SaleView getSale(UUID saleId);

    void reprint(UUID saleId);
}
```

- [ ] **Step 4: Write the orchestration service**

`DefaultSalesService.java`:

```java
package com.company.pos.sales.application;

import com.company.pos.cart.api.CartLineView;
import com.company.pos.cart.api.CartService;
import com.company.pos.cart.api.CartView;
import com.company.pos.common.events.DomainEvents;
import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.payment.api.CashPaymentView;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.payment.api.PaymentService;
import com.company.pos.pricing.api.PricedLine;
import com.company.pos.pricing.api.PricingInput;
import com.company.pos.pricing.api.PricingService;
import com.company.pos.receipt.api.ReceiptData;
import com.company.pos.receipt.api.ReceiptLineData;
import com.company.pos.receipt.api.ReceiptService;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SaleCompleted;
import com.company.pos.sales.api.SaleLineView;
import com.company.pos.sales.api.SalePaymentView;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import com.company.pos.sales.domain.Sale;
import com.company.pos.sales.domain.SaleLine;
import com.company.pos.sales.infrastructure.SaleRepository;
import com.company.pos.tax.api.TaxLineInput;
import com.company.pos.tax.api.TaxService;
import com.company.pos.tax.api.TaxedCart;
import com.company.pos.tax.api.TaxedLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultSalesService implements SalesService {

    private static final Logger log = LoggerFactory.getLogger(DefaultSalesService.class);

    private final CartService carts;
    private final PricingService pricing;
    private final TaxService tax;
    private final PaymentService payments;
    private final ReceiptService receipts;
    private final ConfigurationService config;
    private final SaleRepository sales;
    private final ReceiptNumbering numbering;
    private final DomainEvents events;

    DefaultSalesService(CartService carts, PricingService pricing, TaxService tax,
            PaymentService payments, ReceiptService receipts, ConfigurationService config,
            SaleRepository sales, ReceiptNumbering numbering, DomainEvents events) {
        this.carts = carts;
        this.pricing = pricing;
        this.tax = tax;
        this.payments = payments;
        this.receipts = receipts;
        this.config = config;
        this.sales = sales;
        this.numbering = numbering;
        this.events = events;
    }

    @Override
    public SaleView checkout(CheckoutCommand command, String cashierUsername) {
        CartView cart = carts.getCart(command.cartId());
        if (!"OPEN".equals(cart.status())) {
            throw DomainException.conflict("Cart " + command.cartId() + " is not open");
        }
        if (cart.lines().isEmpty()) {
            throw DomainException.validation("Cannot checkout an empty cart");
        }

        // 1. Price the lines
        List<PricingInput> pricingInputs = cart.lines().stream()
                .map(l -> new PricingInput(l.sku(), l.name(), l.quantity(), l.unitPrice(), l.currencyCode()))
                .toList();
        List<PricedLine> priced = pricing.price(pricingInputs);

        // 2. Apply tax
        String currency = cart.currencyCode() != null
                ? cart.currencyCode()
                : config.getString(SettingKey.CURRENCY_CODE);
        BigDecimal rate = new BigDecimal(config.getString(SettingKey.VAT_RATE));
        boolean inclusive = Boolean.parseBoolean(config.getString(SettingKey.TAX_INCLUSIVE));
        List<TaxLineInput> taxInputs = priced.stream()
                .map(p -> new TaxLineInput(p.sku(), p.name(), p.quantity(), p.unitPrice(),
                        p.extendedPrice(), p.currencyCode()))
                .toList();
        TaxedCart taxed = tax.applyTax(taxInputs, rate, inclusive, currency);

        // 3. Take cash payment (validates tender covers the total)
        UUID saleId = Identifiers.newId();
        CashPaymentView payment = payments.recordCash(saleId, currency,
                taxed.grandTotal(), command.amountTendered());

        // 4. Persist the immutable sale
        String storeId = config.getString(SettingKey.STORE_ID);
        String terminalId = config.getString(SettingKey.TERMINAL_ID);
        String location = config.getString(SettingKey.INVENTORY_LOCATION);
        String receiptNumber = numbering.nextReceiptNumber(storeId, terminalId);
        Instant now = Instant.now();
        Sale sale = new Sale(saleId, receiptNumber, storeId, terminalId, cashierUsername,
                location, currency, taxed.subtotal(), taxed.taxTotal(), taxed.grandTotal(), now);
        int lineNo = 1;
        for (TaxedLine t : taxed.lines()) {
            sale.addLine(new SaleLine(Identifiers.newId(), sale, lineNo++, t.sku(), t.name(),
                    t.quantity(), t.unitPrice(), t.netAmount(), t.taxAmount(), t.lineTotal(),
                    t.currencyCode()));
        }
        sales.save(sale);

        // 5. Close the cart
        carts.close(command.cartId());

        // 6. Publish SaleCompleted (in-process; inventory decrements synchronously in this tx)
        List<SaleCompleted.SoldLine> soldLines = taxed.lines().stream()
                .map(t -> new SaleCompleted.SoldLine(t.sku(), t.quantity()))
                .toList();
        events.publish(new SaleCompleted(saleId, receiptNumber, location, currency,
                taxed.grandTotal(), soldLines));

        // 7. Print the receipt (best-effort — never fails the sale)
        printReceipt(sale, payment);

        return toView(sale, payment);
    }

    @Override
    @Transactional(readOnly = true)
    public SaleView getSale(UUID saleId) {
        Sale sale = sales.findById(saleId)
                .orElseThrow(() -> DomainException.notFound("No sale " + saleId));
        return toView(sale, null);
    }

    @Override
    @Transactional(readOnly = true)
    public void reprint(UUID saleId) {
        Sale sale = sales.findById(saleId)
                .orElseThrow(() -> DomainException.notFound("No sale " + saleId));
        printReceipt(sale, null);
    }

    private void printReceipt(Sale sale, CashPaymentView payment) {
        try {
            List<ReceiptLineData> lines = sale.getLines().stream()
                    .map(l -> new ReceiptLineData(l.getName(), l.getQuantity(), l.getUnitPrice(),
                            l.getLineTotal()))
                    .toList();
            BigDecimal tendered = payment != null ? payment.amountTendered() : sale.getGrandTotal();
            BigDecimal change = payment != null ? payment.changeDue() : BigDecimal.ZERO;
            receipts.print(new ReceiptData(sale.getReceiptNumber(), sale.getCashierUsername(),
                    sale.getCreatedAt(), lines, sale.getSubtotal(), sale.getTaxTotal(),
                    sale.getGrandTotal(), tendered, change, sale.getCurrencyCode()));
        } catch (RuntimeException ex) {
            log.warn("Receipt print failed for sale {} ({}) — sale is recorded; reprint available",
                    sale.getId(), sale.getReceiptNumber(), ex);
        }
    }

    private SaleView toView(Sale sale, CashPaymentView payment) {
        List<SaleLineView> lines = new ArrayList<>();
        for (SaleLine l : sale.getLines()) {
            lines.add(new SaleLineView(l.getLineNo(), l.getSku(), l.getName(), l.getQuantity(),
                    l.getUnitPrice(), l.getNetAmount(), l.getTaxAmount(), l.getLineTotal(),
                    l.getCurrencyCode()));
        }
        SalePaymentView paymentView = payment != null
                ? new SalePaymentView(PaymentMethod.CASH.name(), payment.amount(),
                        payment.amountTendered(), payment.changeDue())
                : null;
        return new SaleView(sale.getId(), sale.getReceiptNumber(), sale.getStatus(),
                sale.getCurrencyCode(), sale.getSubtotal(), sale.getTaxTotal(), sale.getGrandTotal(),
                sale.getCreatedAt(), lines, paymentView);
    }
}
```

> `getSale`/`reprint` return a `SaleView` whose `payment` is `null` — the read side does not re-load the `payment` row (that lives in the `payment` module and is not exposed by a query API in this phase). `checkout`'s returned view carries the live `CashPaymentView`. If the read-side payment is needed later, add a `PaymentService` lookup method in Phase 2b.

- [ ] **Step 5: Run test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CheckoutServiceTest`
Expected: PASS — but note the inventory listener (Task 11) does not exist yet, so `SaleCompleted` currently has no subscriber. Publishing an event with no listener is a no-op in Spring, so these tests pass without it.

- [ ] **Step 6: Verify module boundaries**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ModularityTests`
Expected: PASS — `sales` only touches the named interfaces it declared.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/pos/sales/api/ \
        src/main/java/com/company/pos/sales/application/DefaultSalesService.java \
        src/test/java/com/company/pos/sales/CheckoutServiceTest.java
git commit -m "feat(sales): checkout orchestration composing pricing/tax/payment/receipt + SaleCompleted"
```

---

### Task 10: `sales` REST — checkout, read, reprint endpoints

Exposes the orchestrator over HTTP. The authenticated principal's name becomes the cashier on the sale.

**Files:**
- Create: `src/main/java/com/company/pos/sales/web/SalesController.java`
- Test: `src/test/java/com/company/pos/sales/SalesControllerTest.java`

**Interfaces:**
- Consumes: `SalesService`, `CartService`, `SaleView`, `CheckoutCommand`. Uses `java.security.Principal` (injected by Spring Security) for the cashier name.
- Produces: `POST /sales` → `SaleView` (201), `GET /sales/{id}` → `SaleView`, `POST /sales/{id}/reprint` → 204.

- [ ] **Step 1: Write the failing test**

```java
package com.company.pos.sales;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class SalesControllerTest {

    @Autowired
    MockMvc mvc;
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
    void checkoutViaRestReturnsSale() throws Exception {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("2"));

        mvc.perform(post("/sales").with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"cartId\":\"" + cart + "\",\"amountTendered\":20.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.grandTotal").value(10.35))
                .andExpect(jsonPath("$.payment.changeDue").value(9.65))
                .andExpect(jsonPath("$.receiptNumber", matchesPattern("S01-T01-\\d{6}")));
    }

    @Test
    void reprintReturnsNoContent() throws Exception {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("1"));
        String body = mvc.perform(post("/sales").with(jwt().jwt(j -> j.subject("cashier1")))
                        .contentType("application/json")
                        .content("{\"cartId\":\"" + cart + "\",\"amountTendered\":10.00}"))
                .andReturn().getResponse().getContentAsString();
        String saleId = com.jayway.jsonpath.JsonPath.read(body, "$.id");

        mvc.perform(post("/sales/" + saleId + "/reprint").with(jwt().jwt(j -> j.subject("cashier1"))))
                .andExpect(status().isNoContent());
    }

    @Test
    void anonymousCheckoutRejected() throws Exception {
        mvc.perform(post("/sales").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=SalesControllerTest`
Expected: FAIL — `SalesController` does not exist (404/no mapping).

- [ ] **Step 3: Write the controller**

`SalesController.java`:

```java
package com.company.pos.sales.web;

import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
    SaleView checkout(@RequestBody CheckoutCommand command, Principal principal) {
        return sales.checkout(command, principal.getName());
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

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=SalesControllerTest`
Expected: PASS (all three).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/pos/sales/web/ \
        src/test/java/com/company/pos/sales/SalesControllerTest.java
git commit -m "feat(sales): REST checkout, read, and reprint endpoints"
```

---

### Task 11: `inventory` — decrement on `SaleCompleted` + append movement ledger

`inventory` subscribes to `SaleCompleted` (synchronous, same transaction as checkout) and, for each sold line, decrements on-hand at the sale's location and appends an immutable `stock_movement` row (negative delta, reason `SALE`). Stock is allowed to go negative (offline-authoritative store); the listener never throws, so it can never roll back a sale.

**Files:**
- Modify: `src/main/java/com/company/pos/inventory/package-info.java` (add `"sales :: api"`)
- Create: `src/main/java/com/company/pos/inventory/domain/StockMovement.java`
- Create: `src/main/java/com/company/pos/inventory/infrastructure/StockMovementRepository.java`
- Create: `src/main/java/com/company/pos/inventory/application/SaleCompletedListener.java`
- Create: `src/main/resources/db/migration/inventory/V9__inventory_stock_movement.sql`
- Test: `src/test/java/com/company/pos/inventory/SaleDecrementsStockTest.java`

**Interfaces:**
- Consumes: `SaleCompleted` (+ nested `SoldLine`) from `sales.api`; existing `StockLevel`, `StockLevelRepository` (`findBySkuAndLocationCode`); `Identifiers`; `ConfigurationService` is **not** needed (location comes from the event).
- Produces: `StockMovement` entity + `stock_movement` table; `SaleCompletedListener` `@EventListener`.

- [ ] **Step 1: Add the module dependency**

Edit `src/main/java/com/company/pos/inventory/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "integration :: api", "sales :: api" })
package com.company.pos.inventory;
```

- [ ] **Step 2: Write the failing test**

```java
package com.company.pos.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.inventory.api.InventoryService;
import com.company.pos.inventory.infrastructure.StockMovementRepository;
import com.company.pos.product.api.ProductSync;
import com.company.pos.inventory.api.InventorySync;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SalesService;
import java.math.BigDecimal;
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
class SaleDecrementsStockTest {

    @Autowired
    SalesService sales;
    @Autowired
    CartService carts;
    @Autowired
    InventoryService inventory;
    @Autowired
    StockMovementRepository movements;
    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    InventorySync inventorySync;

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("20"), 1));
        productSync.sync();
        inventorySync.sync();
    }

    @Test
    void completingSaleDecrementsOnHandAndWritesMovement() {
        UUID cart = carts.createCart();
        carts.addLine(cart, "COLA", new BigDecimal("3"));
        sales.checkout(new CheckoutCommand(cart, new BigDecimal("100")), "cashier");

        assertThat(inventory.onHand("COLA").orElseThrow().quantityOnHand())
                .isEqualByComparingTo("17");   // 20 - 3
        assertThat(movements.findBySku("COLA")).hasSize(1);
        assertThat(movements.findBySku("COLA").get(0).getQuantityDelta())
                .isEqualByComparingTo("-3");
    }
}
```

> `InventorySync.sync()` is the Phase 1 inventory down-sync facade (returns an `int` count). The seed's job is to make `COLA@MAIN = 20` before checkout.

- [ ] **Step 3: Run test to verify it fails**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=SaleDecrementsStockTest`
Expected: FAIL — `StockMovementRepository` / `StockMovement` do not exist; on-hand stays 20 (no listener).

- [ ] **Step 4: Write the `StockMovement` entity**

`src/main/java/com/company/pos/inventory/domain/StockMovement.java`:

```java
package com.company.pos.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "stock_movement")
public class StockMovement {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(name = "location_code", nullable = false, length = 32)
    private String locationCode;

    @Column(name = "quantity_delta", nullable = false, precision = 19, scale = 3)
    private BigDecimal quantityDelta;

    @Column(nullable = false, length = 24)
    private String reason;

    @Column(name = "reference_id", length = 36)
    private String referenceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StockMovement() {
        // JPA
    }

    public StockMovement(UUID id, String sku, String locationCode, BigDecimal quantityDelta,
            String reason, String referenceId, Instant createdAt) {
        this.id = id;
        this.sku = sku;
        this.locationCode = locationCode;
        this.quantityDelta = quantityDelta;
        this.reason = reason;
        this.referenceId = referenceId;
        this.createdAt = createdAt;
    }

    public String getSku() {
        return sku;
    }

    public BigDecimal getQuantityDelta() {
        return quantityDelta;
    }

    public String getReason() {
        return reason;
    }
}
```

- [ ] **Step 5: Write the repository**

`src/main/java/com/company/pos/inventory/infrastructure/StockMovementRepository.java`:

```java
package com.company.pos.inventory.infrastructure;

import com.company.pos.inventory.domain.StockMovement;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    List<StockMovement> findBySku(String sku);
}
```

- [ ] **Step 6: Write the listener**

`src/main/java/com/company/pos/inventory/application/SaleCompletedListener.java`:

```java
package com.company.pos.inventory.application;

import com.company.pos.common.util.Identifiers;
import com.company.pos.inventory.domain.StockLevel;
import com.company.pos.inventory.domain.StockMovement;
import com.company.pos.inventory.infrastructure.StockLevelRepository;
import com.company.pos.inventory.infrastructure.StockMovementRepository;
import com.company.pos.sales.api.SaleCompleted;
import java.math.BigDecimal;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decrements on-hand and appends a movement-ledger row when a sale completes.
 * Runs synchronously in the checkout transaction. Never throws: a sale must not be
 * rolled back by a stock side effect, and the offline store is authoritative (oversell
 * is allowed and reconciled with the ERP later via movement sync in Phase 3).
 */
@Component
class SaleCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(SaleCompletedListener.class);

    private final StockLevelRepository stock;
    private final StockMovementRepository movements;

    SaleCompletedListener(StockLevelRepository stock, StockMovementRepository movements) {
        this.stock = stock;
        this.movements = movements;
    }

    @EventListener
    @Transactional
    void on(SaleCompleted event) {
        for (SaleCompleted.SoldLine line : event.lines()) {
            try {
                applyMovement(event, line);
            } catch (RuntimeException ex) {
                log.warn("Failed to apply stock movement for sku {} on sale {}",
                        line.sku(), event.receiptNumber(), ex);
            }
        }
    }

    private void applyMovement(SaleCompleted event, SaleCompleted.SoldLine line) {
        StockLevel level = stock.findBySkuAndLocationCode(line.sku(), event.locationCode())
                .orElseGet(() -> stock.save(
                        new StockLevel(Identifiers.newId(), line.sku(), event.locationCode())));
        BigDecimal updated = level.getQuantityOnHand().subtract(line.quantity());
        if (updated.signum() < 0) {
            log.warn("Stock for sku {} at {} went negative ({}) after sale {}",
                    line.sku(), event.locationCode(), updated, event.receiptNumber());
        }
        level.setQuantityOnHand(updated);
        movements.save(new StockMovement(Identifiers.newId(), line.sku(), event.locationCode(),
                line.quantity().negate(), "SALE", event.saleId().toString(), Instant.now()));
    }
}
```

- [ ] **Step 7: Write the migration**

`src/main/resources/db/migration/inventory/V9__inventory_stock_movement.sql`:

```sql
CREATE TABLE stock_movement (
    id             VARCHAR(36) PRIMARY KEY,
    sku            VARCHAR(64) NOT NULL,
    location_code  VARCHAR(32) NOT NULL,
    quantity_delta NUMERIC(19, 3) NOT NULL,
    reason         VARCHAR(24) NOT NULL,
    reference_id   VARCHAR(36),
    created_at     TIMESTAMP NOT NULL
);

CREATE INDEX idx_stock_movement_sku ON stock_movement (sku);
```

> No `application-store-server.yml` change needed: `classpath:db/migration/inventory` is already in `flyway.locations` (V4). V9 simply joins that location.

- [ ] **Step 8: Run test to verify it passes**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=SaleDecrementsStockTest`
Expected: PASS — on-hand is 17 and one `-3` movement exists.

- [ ] **Step 9: Verify module boundaries**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ModularityTests`
Expected: PASS — `inventory → sales :: api` is now declared; no cycle (`sales` does not depend on `inventory`).

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/company/pos/inventory/ \
        src/main/resources/db/migration/inventory/V9__inventory_stock_movement.sql \
        src/test/java/com/company/pos/inventory/SaleDecrementsStockTest.java
git commit -m "feat(inventory): decrement on-hand and append movement on SaleCompleted"
```

---

### Task 12: Module assertions, capstone end-to-end, and docs

Locks in the new module set in `ModularityTests`, proves the whole phase goal through one HTTP flow (login → cart → checkout → receipt → stock decrement), and documents the new API surface in `docs/run-modes.md`.

**Files:**
- Modify: `src/test/java/com/company/pos/ModularityTests.java`
- Create: `src/test/java/com/company/pos/CashSaleEndToEndTest.java`
- Modify: `docs/run-modes.md`

**Interfaces:**
- Consumes: every public surface built above, through HTTP + autowired `InMemoryPrinter`.

- [ ] **Step 1: Extend `ModularityTests` for the new modules**

Add this test method to `ModularityTests` (keep `verifiesModuleBoundaries` and the existing Phase 0 test unchanged):

```java
    @Test
    void detectsTheCheckoutCoreModules() {
        Set<String> names = modules.stream()
                .map(ApplicationModule::getName)
                .collect(Collectors.toSet());
        assertThat(names).contains("cart", "pricing", "tax", "payment", "receipt", "sales");
    }
```

- [ ] **Step 2: Run it to verify it passes (modules exist and verify)**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=ModularityTests`
Expected: PASS — both the boundary verification and the new module-presence assertion.

- [ ] **Step 3: Write the capstone end-to-end test**

`src/test/java/com/company/pos/CashSaleEndToEndTest.java`:

```java
package com.company.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.util.Identifiers;
import com.company.pos.device.infrastructure.InMemoryPrinter;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Transactional
class CashSaleEndToEndTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    FakeErpClient fake;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    InMemoryPrinter printer;

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("20"), 1));
        users.save(new User(Identifiers.newId(), "cashier", "Cashier One",
                encoder.encode("pw"), Set.of(Role.CASHIER)));
        users.save(new User(Identifiers.newId(), "manager", "Store Manager",
                encoder.encode("pw"), Set.of(Role.MANAGER)));
    }

    @Test
    void loginSyncRingUpPayPrintAndDecrementStock() throws Exception {
        // 1. Manager logs in and runs ERP down-sync to load catalogue + stock
        String managerToken = login("manager");
        mvc.perform(post("/sync/erp").header("Authorization", managerToken))
                .andExpect(status().isOk());

        // 2. Cashier logs in
        String token = login("cashier");

        // 3. Open a cart and ring up 2 colas
        String createdCart = mvc.perform(post("/carts").header("Authorization", token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String cartId = JsonPath.read(createdCart, "$.cartId");

        mvc.perform(post("/carts/" + cartId + "/lines").header("Authorization", token)
                        .contentType("application/json")
                        .content("{\"sku\":\"COLA\",\"quantity\":2}"))
                .andExpect(status().isOk());

        // 4. Checkout with cash
        String sale = mvc.perform(post("/sales").header("Authorization", token)
                        .contentType("application/json")
                        .content("{\"cartId\":\"" + cartId + "\",\"amountTendered\":20.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotal").value(9.00))
                .andExpect(jsonPath("$.taxTotal").value(1.35))
                .andExpect(jsonPath("$.grandTotal").value(10.35))
                .andExpect(jsonPath("$.payment.changeDue").value(9.65))
                .andExpect(jsonPath("$.receiptNumber", matchesPattern("S01-T01-\\d{6}")))
                .andReturn().getResponse().getContentAsString();
        String saleId = JsonPath.read(sale, "$.id");
        String receiptNumber = JsonPath.read(sale, "$.receiptNumber");

        // 5. The receipt was printed (this sale's receipt is the last one printed)
        assertThat(printer.lastReceipt()).isNotEmpty();
        assertThat(printer.lastReceipt()).anyMatch(l -> l.text().contains(receiptNumber));

        // 6. The sale is retrievable
        mvc.perform(get("/sales/" + saleId).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // 7. Stock decremented 20 -> 18
        mvc.perform(get("/inventory/COLA").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(18));
    }

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }
}
```

> This test stays `@Transactional` (rollback) and still observes the stock decrement because the `inventory` listener is a synchronous in-transaction `@EventListener` — the decrement happens within the same transaction before the assertions run. This is the deliberate Phase 2a design choice: no async/outbox machinery yet (that arrives in Phase 3, which will also make this an `@TransactionalEventListener` draining a persistent outbox).

- [ ] **Step 4: Run the capstone test**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test -Dtest=CashSaleEndToEndTest`
Expected: PASS.

- [ ] **Step 5: Update `docs/run-modes.md`**

Append this section after the existing "Phase 1" section:

```markdown
## Phase 2a — Checkout core (cash sell path)

All endpoints require a bearer token. Any authenticated user (cashier) may sell.

```
POST   /carts                          -> 201 {"cartId"}
GET    /carts/{cartId}                 -> CartView
POST   /carts/{cartId}/lines           {"sku","quantity"}   -> CartView
PUT    /carts/{cartId}/lines/{sku}     {"quantity"}         -> CartView
DELETE /carts/{cartId}/lines/{sku}                          -> CartView
POST   /sales                          {"cartId","amountTendered"} -> 201 SaleView
GET    /sales/{saleId}                 -> SaleView
POST   /sales/{saleId}/reprint         -> 204
```

Checkout prices the cart, applies VAT, takes a cash tender (rejecting short payment),
persists an immutable sale with a `{storeId}-{terminalId}-{seq}` receipt number, prints
through the device `Printer` port, and decrements stock (writing a movement-ledger row).

Config (env overridable via the `configuration` settings store):
- `tax.rate` (default `0.15`), `tax.inclusive` (default `false`)
- `store.id` (default `S01`), `terminal.id` (default `T01`), `inventory.location` (default `MAIN`)
- `store.name`, `currency.code` (default `SAR`), `locale` (default `en`)

> Receipts print to an in-memory fake `Printer`/`CashDrawer` in this phase. A real
> JavaPOS/ESC-POS adapter implements `com.company.pos.device.api.Printer` later with no
> change to the `receipt`/`sales` modules. Card/QR tenders, split payment, void, hold/resume,
> and cashdrawer/shift reconciliation arrive in Phase 2b; returns/exchanges are a later plan.
```

- [ ] **Step 6: Full verification build**

Run: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -B verify`
Expected: BUILD SUCCESS — all Phase 0/1/2a tests green (including the Testcontainers store-server test, which needs Docker running; it now also validates V6–V9 migrations apply cleanly against PostgreSQL).

- [ ] **Step 7: Commit**

```bash
git add src/test/java/com/company/pos/ModularityTests.java \
        src/test/java/com/company/pos/CashSaleEndToEndTest.java \
        docs/run-modes.md
git commit -m "test(sales): capstone cash-sale e2e; assert checkout-core modules; document API"
```

---

## Phase 2a → 2b boundary (explicitly deferred — do not build here)

Phase 2b (separate plan): card-via-terminal tender (semi-integrated `PaymentTerminal` port + fake), QR/wallet, **split/mixed payment**, **void item**, **hold/resume sale**, and `cashdrawer`/`shift` (open/close drawer, cash in/out, count, reconciliation, shift summary). Returns/exchanges/refunds are their own later plan (they lean on Phase 3's outbox for refund upload). Phase 3 moves the `SaleCompleted` handling onto the persistent transactional outbox and adds ERP sales upload.

## Notes for the executing engineer

- **Always** prefix Maven with the JDK-21 `JAVA_HOME` (machine default is 17): `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw …`. The full `verify` and `DatabaseStoreServerTest` need Docker running.
- Tests run on the `embedded` (SQLite, Hibernate `ddl-auto: update`) profile — no Flyway. The store-server profile uses Flyway with the per-module locations; every new migration dir (`cart`, `payment`, `sales`) must be in `application-store-server.yml`'s `flyway.locations` (the `inventory` dir is already there).
- Money: `BigDecimal`, scale 2 `HALF_UP` for monetary amounts, scale 4 for unit price, scale 3 for quantity. Never `double`.
- All new entity ids follow the UUID-as-VARCHAR(36) convention with `@JdbcTypeCode(SqlTypes.VARCHAR)`.
- Cross-module access is only through `@NamedInterface("api")` packages declared in each consumer's `allowedDependencies`. If `ModularityTests.verifiesModuleBoundaries` fails, the fix is almost always a missing named interface or a missing `allowedDependencies` entry — not loosening a boundary.
- The Phase 1 down-sync facades are `ProductSync.sync()` and `InventorySync.sync()` (both return an `int` count), verified against `product/api/ProductSync.java` and `inventory/api/InventorySync.java`.
- `ErpProduct(sku, name, categoryCode, categoryName, barcode, unitOfMeasure, unitPrice, currencyCode, version, active)` and `ErpStockLevel(sku, locationCode, quantityOnHand, version)` — signatures verified against `integration/api`. `User(UUID id, String username, String displayName, String passwordHash, Set<Role> roles)` verified against `auth/domain/User.java`.
```
