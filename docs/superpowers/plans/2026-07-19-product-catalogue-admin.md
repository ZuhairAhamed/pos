# Product Catalogue Admin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an ADMIN-only backend write surface and a JavaFX terminal screen to create/edit products and prices without an ERP, over their full lifecycle (create · edit · deactivate · reactivate).

**Architecture:** All backend work is inside the existing `product` module (light hexagonal: `api`/`web`/`application`/`domain`/`infrastructure`). Product admin auditing uses the system's **events-for-facts** pattern — `ProductAdminService` publishes a `ProductChanged` event and a new `@ApplicationModuleListener` in the `audit` module records it (a direct `product → audit` call would be a module cycle, since `audit` already depends on `product :: api`). The terminal adds a Products screen to the shared Admin area from sub-project #1, mirroring the Staff screen 1:1.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Modulith, Spring Data JPA, Spring Security (method security + JWT), JavaFX (terminal, separate Maven build), JUnit 5, AssertJ, MockMvc.

## Global Constraints

_Every task's requirements implicitly include this section._

- **Money is `BigDecimal`** — never `double`. (Terminal DTOs and server entity already use `BigDecimal`.)
- **`ProductAdminService` IS `@Transactional`** (class-level). It saves + publishes `ProductChanged` in-tx; the `audit` listener records async after commit. This is safe (no synchronous `REQUIRES_NEW` audit call → no single-writer-SQLite deadlock). Do **not** inject `AuditService` into `product`.
- **No module cycle:** `product` must NOT depend on `audit`. `product` publishes events via `DomainEvents` (`common`); `audit` listens. `product`'s only new `allowedDependencies` entry is `configuration :: api`.
- **SKU is immutable** — it is the key on sales/dining lines. Lifecycle endpoints key on `sku`. No SKU setter; no SKU edit in the UI.
- **Writes are ADMIN-only** — `@PreAuthorize("hasRole('ADMIN')")`. The existing read-only `ProductController` (`GET /products`, `GET /products/{sku}`) is **left untouched** (any authenticated user); no route clash because Spring maps by method+path.
- **No Flyway migration** — every table/column already exists. Enum-only additions to `AuditAction`.
- **Terminal FX-threading:** ViewModels are **synchronous**, return plain values; the controller runs them off the FX thread via `FxTasks.run(work, onDone, onError)` and reads results in the FX-thread `onDone` via a `holder[]` array. The only observable a VM writes off-thread is `errorMessage`, inside `ui.accept(...)`. Dialogs are **I/O-free** (collect input only; controller does all HTTP). Never call a blocking VM/HTTP method inside `onDone`. Every VM needs an async-dispatcher regression test (a deferred, undrained `ui` dispatcher).
- **Currency default:** `ConfigurationService.getString(SettingKey.CURRENCY_CODE)` (default `"SAR"`). UoM default `"EA"`.
- After any change, re-run the affected module's tests and `ModularityTests`.
- Set `JAVA_HOME` before Maven: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`.

---

## File Structure

**Backend (`src/main/java/com/company/pos/`)**
- Modify `product/domain/Product.java` — add mutation methods.
- Modify `product/domain/Category.java` — ensure `getId()/getCode()/getName()` getters (add if missing).
- Modify `audit/api/AuditAction.java` — add 5 enum values.
- Create `product/api/CreateProductCommand.java`, `product/api/UpdateProductCommand.java`, `product/api/CategoryView.java`, `product/api/ProductChangeType.java`, `product/api/ProductChanged.java`.
- Modify `product/package-info.java` — add `configuration :: api`.
- Create `product/application/ProductAdminService.java`.
- Create `product/web/ProductAdminController.java`.
- Create `audit/application/ProductChangedAuditListener.java`.

**Backend tests (`src/test/java/com/company/pos/`)**
- Create `product/domain/ProductBehaviorTest.java`.
- Create `product/ProductAdminServiceTest.java`.
- Create `product/ProductAdminControllerTest.java`.

**Terminal (`pos-terminal/src/main/java/com/company/pos/terminal/`)**
- Create `api/ProductAdminView.java`, `api/CreateProductRequest.java`, `api/UpdateProductRequest.java`, `api/CategoryView.java`, `api/ProductAdminApi.java`.
- Modify `app/Services.java` — add `productAdminApi`.
- Create `viewmodel/ProductAdminViewModel.java`.
- Create `view/ProductFormDialog.java`, `view/ProductsController.java`.
- Modify `view/AdminController.java` — add Products tile wiring.
- Modify `app/Navigator.java` — add `toProducts()`.
- Create `resources/fxml/products.fxml`; modify `resources/fxml/admin.fxml`.

**Terminal tests (`pos-terminal/src/test/java/com/company/pos/terminal/`)**
- Create `viewmodel/ProductAdminViewModelTest.java`, `view/ProductFormDialogTest.java`.

---

## Task 1: Product domain mutations + AuditAction enum values

**Files:**
- Modify: `src/main/java/com/company/pos/product/domain/Product.java`
- Modify: `src/main/java/com/company/pos/audit/api/AuditAction.java`
- Test: `src/test/java/com/company/pos/product/domain/ProductBehaviorTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `Product.rename(String)`, `Product.changeCategory(UUID, String)`, `Product.changePrice(BigDecimal, String)`, `Product.changeBarcode(String)`, `Product.changeUnitOfMeasure(String)`, `Product.activate()`, `Product.deactivate()`. New `AuditAction` constants: `PRODUCT_CREATED`, `PRODUCT_UPDATED`, `PRODUCT_DEACTIVATED`, `PRODUCT_REACTIVATED`, `CATEGORY_CREATED`.

Context: `Product` (package-private `@Entity`, table `product`) has a public constructor `Product(UUID id, String sku, String name)` and setters for every field except `id`/`sku` (`setName`, `setCategoryId`, `setCategoryName`, `setBarcode`, `setUnitOfMeasure`, `setUnitPrice`, `setCurrencyCode`, `setErpVersion`, `setActive`) and getters `getSku`, `getName`, `getCategoryName`, `getBarcode`, `getUnitOfMeasure`, `getUnitPrice`, `getCurrencyCode`, `isActive`. New products default `active = true`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/product/domain/ProductBehaviorTest.java`:

```java
package com.company.pos.product.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.common.util.Identifiers;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductBehaviorTest {

    private Product sample() {
        return new Product(Identifiers.newId(), "COLA", "Cola");
    }

    @Test
    void renameChangesName() {
        Product p = sample();
        p.rename("Diet Cola");
        assertThat(p.getName()).isEqualTo("Diet Cola");
    }

    @Test
    void changePriceSetsPriceAndCurrency() {
        Product p = sample();
        p.changePrice(new BigDecimal("6.50"), "SAR");
        assertThat(p.getUnitPrice()).isEqualByComparingTo("6.50");
        assertThat(p.getCurrencyCode()).isEqualTo("SAR");
    }

    @Test
    void changeCategorySetsIdAndName() {
        Product p = sample();
        UUID c = Identifiers.newId();
        p.changeCategory(c, "Beverages");
        assertThat(p.getCategoryName()).isEqualTo("Beverages");
    }

    @Test
    void deactivateAndActivateToggleActive() {
        Product p = sample();
        p.deactivate();
        assertThat(p.isActive()).isFalse();
        p.activate();
        assertThat(p.isActive()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw test -Dtest=ProductBehaviorTest`
Expected: FAIL — `rename`/`changePrice`/`changeCategory`/`activate`/`deactivate` not defined (compile error).

- [ ] **Step 3: Add the mutation methods to `Product.java`**

Add these methods to `Product` (after the existing setters), and add `import java.util.UUID;` and `import java.math.BigDecimal;` if not already present:

```java
    public void rename(String name) {
        setName(name);
    }

    public void changeCategory(UUID categoryId, String categoryName) {
        setCategoryId(categoryId);
        setCategoryName(categoryName);
    }

    public void changePrice(BigDecimal unitPrice, String currencyCode) {
        setUnitPrice(unitPrice);
        setCurrencyCode(currencyCode);
    }

    public void changeBarcode(String barcode) {
        setBarcode(barcode);
    }

    public void changeUnitOfMeasure(String unitOfMeasure) {
        setUnitOfMeasure(unitOfMeasure);
    }

    public void activate() {
        setActive(true);
    }

    public void deactivate() {
        setActive(false);
    }
```

- [ ] **Step 4: Add the enum values to `AuditAction.java`**

Add `PRODUCT_CREATED`, `PRODUCT_UPDATED`, `PRODUCT_DEACTIVATED`, `PRODUCT_REACTIVATED`, `CATEGORY_CREATED` to the end of the `AuditAction` enum (append after the existing final `USER_CREDENTIAL_RESET` value; keep it a valid enum — comma before, no trailing syntax error):

```java
    USER_CREDENTIAL_RESET,
    PRODUCT_CREATED,
    PRODUCT_UPDATED,
    PRODUCT_DEACTIVATED,
    PRODUCT_REACTIVATED,
    CATEGORY_CREATED
```

- [ ] **Step 5: Run test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw test -Dtest=ProductBehaviorTest`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/product/domain/Product.java \
        src/main/java/com/company/pos/audit/api/AuditAction.java \
        src/test/java/com/company/pos/product/domain/ProductBehaviorTest.java
git commit -m "feat(product): domain mutations + product AuditAction values"
```

---

## Task 2: `product.api` DTOs/events + `ProductAdminService`

**Files:**
- Create: `src/main/java/com/company/pos/product/api/CreateProductCommand.java`
- Create: `src/main/java/com/company/pos/product/api/UpdateProductCommand.java`
- Create: `src/main/java/com/company/pos/product/api/CategoryView.java`
- Create: `src/main/java/com/company/pos/product/api/ProductChangeType.java`
- Create: `src/main/java/com/company/pos/product/api/ProductChanged.java`
- Modify: `src/main/java/com/company/pos/product/package-info.java`
- Modify (verify): `src/main/java/com/company/pos/product/domain/Category.java`
- Create: `src/main/java/com/company/pos/product/application/ProductAdminService.java`
- Test: `src/test/java/com/company/pos/product/ProductAdminServiceTest.java`

**Interfaces:**
- Consumes (from Task 1): `Product` mutations. Existing: `ProductRepository.findBySku(String)`, `ProductRepository.save(...)`, `CategoryRepository.findByCode(String)`, `CategoryRepository.findAll()`, `CategoryRepository.save(...)`, `Category(UUID,String,String)`, `Identifiers.newId()`, `DomainEvents.publish(DomainEvent)`, `ConfigurationService.getString(SettingKey)`, `SettingKey.CURRENCY_CODE`, `DomainException.{validation,conflict,notFound}`, `com.company.pos.product.api.ProductView` (record: `sku,name,categoryName,barcode,unitOfMeasure,unitPrice,currencyCode,active`).
- Produces: `ProductAdminService.createProduct(CreateProductCommand): ProductView`, `updateProduct(String, UpdateProductCommand): ProductView`, `deactivate(String): void`, `reactivate(String): void`, `listCategories(): List<CategoryView>`. The `CreateProductCommand`/`UpdateProductCommand`/`CategoryView`/`ProductChangeType`/`ProductChanged` types.

- [ ] **Step 1: Create the api records/enum/event**

`src/main/java/com/company/pos/product/api/CreateProductCommand.java`:

```java
package com.company.pos.product.api;

import java.math.BigDecimal;

public record CreateProductCommand(String sku, String name, String categoryCode, String categoryName,
        BigDecimal unitPrice, String currencyCode, String unitOfMeasure, String barcode) {
}
```

`src/main/java/com/company/pos/product/api/UpdateProductCommand.java`:

```java
package com.company.pos.product.api;

import java.math.BigDecimal;

public record UpdateProductCommand(String name, String categoryCode, String categoryName,
        BigDecimal unitPrice, String currencyCode, String unitOfMeasure, String barcode) {
}
```

`src/main/java/com/company/pos/product/api/CategoryView.java`:

```java
package com.company.pos.product.api;

public record CategoryView(String code, String name) {
}
```

`src/main/java/com/company/pos/product/api/ProductChangeType.java`:

```java
package com.company.pos.product.api;

public enum ProductChangeType {
    CREATED, UPDATED, PRICE_CHANGED, DEACTIVATED, REACTIVATED, CATEGORY_CREATED
}
```

`src/main/java/com/company/pos/product/api/ProductChanged.java`:

```java
package com.company.pos.product.api;

import com.company.pos.common.events.DomainEvent;
import java.math.BigDecimal;

/**
 * Published by {@code ProductAdminService} for every catalogue admin action. Consumed by the
 * {@code audit} module's {@code ProductChangedAuditListener}. {@code entityRef} is the product SKU
 * (or the category code for {@code CATEGORY_CREATED}); {@code actor} is the admin who made the
 * change (captured on the request thread so the async listener records the real user, not
 * "system"); {@code oldPrice}/{@code newPrice} are non-null only for {@code PRICE_CHANGED}.
 */
public record ProductChanged(String entityRef, ProductChangeType type, String actor,
        BigDecimal oldPrice, BigDecimal newPrice) implements DomainEvent {
}
```

- [ ] **Step 2: Add `configuration :: api` to `product/package-info.java`**

Replace the annotation body so `allowedDependencies` reads exactly:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "integration :: api", "configuration :: api" })
package com.company.pos.product;
```

(Do **not** add `audit :: api` — that would create a cycle.)

- [ ] **Step 3: Verify `Category` getters**

Open `src/main/java/com/company/pos/product/domain/Category.java`. Confirm it has public getters `getId()` (`UUID`), `getCode()` (`String`), `getName()` (`String`). If any is missing, add it (do not add setters beyond the existing `setName`).

- [ ] **Step 4: Write the failing test**

Create `src/test/java/com/company/pos/product/ProductAdminServiceTest.java`:

```java
package com.company.pos.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.product.api.CategoryView;
import com.company.pos.product.api.CreateProductCommand;
import com.company.pos.product.api.ProductCatalog;
import com.company.pos.product.api.ProductChangeType;
import com.company.pos.product.api.ProductChanged;
import com.company.pos.product.api.ProductView;
import com.company.pos.product.api.UpdateProductCommand;
import com.company.pos.product.application.ProductAdminService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class ProductAdminServiceTest {

    @Autowired ProductAdminService svc;
    @Autowired ProductCatalog catalog;
    @Autowired Events events;

    @BeforeEach
    void reset() {
        events.clear();
    }

    private CreateProductCommand cola() {
        return new CreateProductCommand("COLA", "Cola", null, "Beverages",
                new BigDecimal("5.00"), "SAR", "EA", "bcCOLA");
    }

    @Test
    void createsProductAndPublishesCreated() {
        ProductView v = svc.createProduct(cola());
        assertThat(v.sku()).isEqualTo("COLA");
        assertThat(v.unitPrice()).isEqualByComparingTo("5.00");
        assertThat(v.active()).isTrue();
        assertThat(v.categoryName()).isEqualTo("Beverages");
        assertThat(events.typesFor("COLA")).contains(ProductChangeType.CREATED);
    }

    @Test
    void rejectsDuplicateSku() {
        svc.createProduct(cola());
        assertThatThrownBy(() -> svc.createProduct(cola())).isInstanceOf(DomainException.class);
    }

    @Test
    void createDefaultsCurrencyAndUom() {
        ProductView v = svc.createProduct(new CreateProductCommand("WATER", "Water", null, null,
                new BigDecimal("2.00"), null, null, null));
        assertThat(v.currencyCode()).isEqualTo("SAR");   // SettingKey.CURRENCY_CODE default
        assertThat(v.unitOfMeasure()).isEqualTo("EA");
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> svc.createProduct(new CreateProductCommand("X", "  ", null, null,
                new BigDecimal("1.00"), "SAR", "EA", null))).isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNegativePrice() {
        assertThatThrownBy(() -> svc.createProduct(new CreateProductCommand("X", "X", null, null,
                new BigDecimal("-1.00"), "SAR", "EA", null))).isInstanceOf(DomainException.class);
    }

    @Test
    void updatesFieldsAndPublishesPriceChanged() {
        svc.createProduct(cola());
        events.clear();
        ProductView v = svc.updateProduct("COLA", new UpdateProductCommand("Diet Cola", null,
                "Soft Drinks", new BigDecimal("6.50"), "SAR", "EA", "bcCOLA"));
        assertThat(v.name()).isEqualTo("Diet Cola");
        assertThat(v.unitPrice()).isEqualByComparingTo("6.50");
        assertThat(v.categoryName()).isEqualTo("Soft Drinks");
        assertThat(events.typesFor("COLA"))
                .contains(ProductChangeType.UPDATED, ProductChangeType.PRICE_CHANGED);
    }

    @Test
    void updateWithSamePriceDoesNotPublishPriceChanged() {
        svc.createProduct(cola());
        events.clear();
        svc.updateProduct("COLA", new UpdateProductCommand("Cola", null, "Beverages",
                new BigDecimal("5.00"), "SAR", "EA", "bcCOLA"));
        assertThat(events.typesFor("COLA")).contains(ProductChangeType.UPDATED)
                .doesNotContain(ProductChangeType.PRICE_CHANGED);
    }

    @Test
    void inlineCategoryReuseDoesNotDuplicate() {
        svc.createProduct(new CreateProductCommand("A", "A", null, "Beverages",
                new BigDecimal("1.00"), "SAR", "EA", null));
        svc.createProduct(new CreateProductCommand("B", "B", null, "Beverages",
                new BigDecimal("2.00"), "SAR", "EA", null));
        assertThat(svc.listCategories()).extracting(CategoryView::name)
                .filteredOn("Beverages"::equals).hasSize(1);
    }

    @Test
    void picksExistingCategoryByCode() {
        svc.createProduct(new CreateProductCommand("A", "A", null, "Beverages",
                new BigDecimal("1.00"), "SAR", "EA", null));
        // derived code for "Beverages" is BEVERAGES
        ProductView v = svc.createProduct(new CreateProductCommand("C", "C", "BEVERAGES", null,
                new BigDecimal("3.00"), "SAR", "EA", null));
        assertThat(v.categoryName()).isEqualTo("Beverages");
        assertThat(svc.listCategories()).hasSize(1);
    }

    @Test
    void deactivateThenReactivate() {
        svc.createProduct(cola());
        svc.deactivate("COLA");
        assertThat(catalog.findBySku("COLA").orElseThrow().active()).isFalse();
        svc.reactivate("COLA");
        assertThat(catalog.findBySku("COLA").orElseThrow().active()).isTrue();
    }

    interface Events {
        List<ProductChangeType> typesFor(String entityRef);
        void clear();
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        Events events() {
            return new EventsBean();
        }

        static class EventsBean implements Events {
            private final List<ProductChanged> captured = new ArrayList<>();

            // @EventListener fires synchronously on publish (within the still-open tx) — correct for
            // asserting DomainEvents.publish(); a @TransactionalEventListener(AFTER_COMMIT) would never
            // fire under this rolled-back test.
            @EventListener
            void on(ProductChanged e) {
                captured.add(e);
            }

            @Override
            public List<ProductChangeType> typesFor(String entityRef) {
                return captured.stream().filter(e -> e.entityRef().equals(entityRef))
                        .map(ProductChanged::type).toList();
            }

            @Override
            public void clear() {
                captured.clear();
            }
        }
    }
}
```

- [ ] **Step 5: Run test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw test -Dtest=ProductAdminServiceTest`
Expected: FAIL — `ProductAdminService` does not exist (compile error).

- [ ] **Step 6: Create `ProductAdminService`**

`src/main/java/com/company/pos/product/application/ProductAdminService.java`:

```java
package com.company.pos.product.application;

import com.company.pos.common.events.DomainEvents;
import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.product.api.CategoryView;
import com.company.pos.product.api.CreateProductCommand;
import com.company.pos.product.api.ProductChangeType;
import com.company.pos.product.api.ProductChanged;
import com.company.pos.product.api.ProductView;
import com.company.pos.product.api.UpdateProductCommand;
import com.company.pos.product.domain.Category;
import com.company.pos.product.domain.Product;
import com.company.pos.product.infrastructure.CategoryRepository;
import com.company.pos.product.infrastructure.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product-catalogue admin use cases. Class-level {@code @Transactional}: each method does its
 * repository write and publishes {@link ProductChanged} in the same transaction; the {@code audit}
 * module records those events async AFTER commit. There is no synchronous {@code REQUIRES_NEW}
 * audit call, so single-writer SQLite never deadlocks (the {@code ProductErpSyncService} pattern).
 * SKU uniqueness is pre-checked for a friendly error and backed by the DB unique constraint.
 */
@Service
@Transactional
public class ProductAdminService {

    private static final String DEFAULT_UOM = "EA";

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final ConfigurationService config;
    private final DomainEvents events;

    public ProductAdminService(ProductRepository products, CategoryRepository categories,
            ConfigurationService config, DomainEvents events) {
        this.products = products;
        this.categories = categories;
        this.config = config;
        this.events = events;
    }

    public ProductView createProduct(CreateProductCommand cmd) {
        String sku = requireText(cmd.sku(), "SKU is required");
        String name = requireText(cmd.name(), "Name is required");
        BigDecimal price = requireNonNegativePrice(cmd.unitPrice());
        if (products.findBySku(sku).isPresent()) {
            throw DomainException.conflict("SKU already in use");
        }
        String actor = actor();
        Category category = resolveCategory(cmd.categoryCode(), cmd.categoryName(), actor);
        String currency = trimToNull(cmd.currencyCode());
        if (currency == null) {
            currency = config.getString(SettingKey.CURRENCY_CODE);
        }
        String uom = trimToNull(cmd.unitOfMeasure());
        if (uom == null) {
            uom = DEFAULT_UOM;
        }

        Product p = new Product(Identifiers.newId(), sku, name);
        if (category != null) {
            p.changeCategory(category.getId(), category.getName());
        }
        p.changePrice(price, currency);
        p.changeUnitOfMeasure(uom);
        p.changeBarcode(trimToNull(cmd.barcode()));
        products.save(p);

        events.publish(new ProductChanged(sku, ProductChangeType.CREATED, actor, null, null));
        return toView(p);
    }

    public ProductView updateProduct(String sku, UpdateProductCommand cmd) {
        String name = requireText(cmd.name(), "Name is required");
        BigDecimal price = requireNonNegativePrice(cmd.unitPrice());
        Product p = products.findBySku(sku)
                .orElseThrow(() -> DomainException.notFound("No product with sku " + sku));
        String actor = actor();
        BigDecimal oldPrice = p.getUnitPrice();

        Category category = resolveCategory(cmd.categoryCode(), cmd.categoryName(), actor);
        String currency = trimToNull(cmd.currencyCode());
        if (currency == null) {
            currency = p.getCurrencyCode() != null ? p.getCurrencyCode()
                    : config.getString(SettingKey.CURRENCY_CODE);
        }
        String uom = trimToNull(cmd.unitOfMeasure());
        if (uom == null) {
            uom = p.getUnitOfMeasure() != null ? p.getUnitOfMeasure() : DEFAULT_UOM;
        }

        p.rename(name);
        p.changeCategory(category == null ? null : category.getId(),
                category == null ? null : category.getName());
        p.changePrice(price, currency);
        p.changeUnitOfMeasure(uom);
        p.changeBarcode(trimToNull(cmd.barcode()));
        products.save(p);

        events.publish(new ProductChanged(sku, ProductChangeType.UPDATED, actor, null, null));
        if (oldPrice == null || oldPrice.compareTo(price) != 0) {
            events.publish(new ProductChanged(sku, ProductChangeType.PRICE_CHANGED, actor,
                    oldPrice, price));
        }
        return toView(p);
    }

    public void deactivate(String sku) {
        Product p = products.findBySku(sku)
                .orElseThrow(() -> DomainException.notFound("No product with sku " + sku));
        p.deactivate();
        products.save(p);
        events.publish(new ProductChanged(sku, ProductChangeType.DEACTIVATED, actor(), null, null));
    }

    public void reactivate(String sku) {
        Product p = products.findBySku(sku)
                .orElseThrow(() -> DomainException.notFound("No product with sku " + sku));
        p.activate();
        products.save(p);
        events.publish(new ProductChanged(sku, ProductChangeType.REACTIVATED, actor(), null, null));
    }

    @Transactional(readOnly = true)
    public List<CategoryView> listCategories() {
        return categories.findAll().stream()
                .map(c -> new CategoryView(c.getCode(), c.getName()))
                .toList();
    }

    /**
     * Resolve the category for a create/update. A non-blank {@code code} must reference an existing
     * category. Otherwise a non-blank {@code name} derives a code (uppercased alphanumeric, ≤50),
     * reusing an existing category with that code or creating a new one (and publishing
     * CATEGORY_CREATED). Both blank ⇒ no category.
     */
    private Category resolveCategory(String code, String name, String actor) {
        String cd = trimToNull(code);
        if (cd != null) {
            return categories.findByCode(cd)
                    .orElseThrow(() -> DomainException.validation("No category with code " + cd));
        }
        String nm = trimToNull(name);
        if (nm == null) {
            return null;
        }
        String derived = deriveCode(nm);
        if (derived.isEmpty()) {
            throw DomainException.validation("Category name must contain a letter or digit");
        }
        return categories.findByCode(derived).orElseGet(() -> {
            Category created = new Category(Identifiers.newId(), derived, nm);
            categories.save(created);
            events.publish(new ProductChanged(derived, ProductChangeType.CATEGORY_CREATED, actor,
                    null, null));
            return created;
        });
    }

    private static String deriveCode(String name) {
        String code = name.toUpperCase().replaceAll("[^A-Z0-9]", "");
        return code.length() > 50 ? code.substring(0, 50) : code;
    }

    private ProductView toView(Product p) {
        return new ProductView(p.getSku(), p.getName(), p.getCategoryName(), p.getBarcode(),
                p.getUnitOfMeasure(), p.getUnitPrice(), p.getCurrencyCode(), p.isActive());
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw DomainException.validation(message);
        }
        return value.trim();
    }

    private static BigDecimal requireNonNegativePrice(BigDecimal price) {
        if (price == null) {
            throw DomainException.validation("Price is required");
        }
        if (price.signum() < 0) {
            throw DomainException.validation("Price must not be negative");
        }
        return price;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String actor() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null ? a.getName() : "system";
    }
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw test -Dtest=ProductAdminServiceTest`
Expected: PASS (10 tests).

- [ ] **Step 8: Run ModularityTests (the new dependency must not break boundaries)**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw test -Dtest=ModularityTests`
Expected: PASS (no cycle; `configuration :: api` declared).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/company/pos/product/api/ \
        src/main/java/com/company/pos/product/package-info.java \
        src/main/java/com/company/pos/product/domain/Category.java \
        src/main/java/com/company/pos/product/application/ProductAdminService.java \
        src/test/java/com/company/pos/product/ProductAdminServiceTest.java
git commit -m "feat(product): ProductAdminService + api DTOs/ProductChanged event"
```

---

## Task 3: `ProductChangedAuditListener` (audit module)

**Files:**
- Create: `src/main/java/com/company/pos/audit/application/ProductChangedAuditListener.java`
- Test: covered by `ProductAdminControllerTest` in Task 4 end-to-end; no separate unit test (mirrors the untested existing `ProductPriceChangedAuditListener`). Verify via `ModularityTests` + a focused build.

**Interfaces:**
- Consumes: `ProductChanged`, `ProductChangeType` (Task 2); `AuditAction` values (Task 1); existing `DefaultAuditService.append(AuditAction, String actor, String entityRef, Map<String,String> details)` (used by the existing `ProductPriceChangedAuditListener`).
- Produces: an `@ApplicationModuleListener` that records each `ProductChanged` as an audit row.

Context: the `audit` module already `allowedDependencies` includes `product :: api`, so importing `ProductChanged` needs no boundary change. `DefaultAuditService` is package-private in `audit/application` — this new class is in the same package, matching `ProductPriceChangedAuditListener`.

- [ ] **Step 1: Create the listener**

`src/main/java/com/company/pos/audit/application/ProductChangedAuditListener.java`:

```java
package com.company.pos.audit.application;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.product.api.ProductChangeType;
import com.company.pos.product.api.ProductChanged;
import java.util.HashMap;
import java.util.Map;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Records catalogue admin actions into the audit trail. Runs async AFTER the publishing
 * transaction commits (the outbox redelivers on failure), mirroring
 * {@link ProductPriceChangedAuditListener}. The actor rides on the event (captured on the request
 * thread), so the real admin is recorded rather than "system".
 */
@Component
class ProductChangedAuditListener {

    private final DefaultAuditService audit;

    ProductChangedAuditListener(DefaultAuditService audit) {
        this.audit = audit;
    }

    @ApplicationModuleListener
    void on(ProductChanged event) {
        audit.append(actionFor(event.type()), event.actor(), event.entityRef(), detailsFor(event));
    }

    private static AuditAction actionFor(ProductChangeType type) {
        return switch (type) {
            case CREATED -> AuditAction.PRODUCT_CREATED;
            case UPDATED -> AuditAction.PRODUCT_UPDATED;
            case PRICE_CHANGED -> AuditAction.PRICE_CHANGED;
            case DEACTIVATED -> AuditAction.PRODUCT_DEACTIVATED;
            case REACTIVATED -> AuditAction.PRODUCT_REACTIVATED;
            case CATEGORY_CREATED -> AuditAction.CATEGORY_CREATED;
        };
    }

    private static Map<String, String> detailsFor(ProductChanged event) {
        if (event.type() != ProductChangeType.PRICE_CHANGED) {
            return Map.of();
        }
        Map<String, String> d = new HashMap<>();
        d.put("oldPrice", event.oldPrice() == null ? "" : event.oldPrice().toPlainString());
        d.put("newPrice", event.newPrice() == null ? "" : event.newPrice().toPlainString());
        return d;
    }
}
```

- [ ] **Step 2: Confirm `DefaultAuditService.append` signature**

Open `src/main/java/com/company/pos/audit/application/ProductPriceChangedAuditListener.java` and confirm the `audit.append(AuditAction, String, String, Map<String,String>)` call shape matches the one used above. If `DefaultAuditService.append` differs, align this listener to the real signature.

- [ ] **Step 3: Compile + ModularityTests**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw test -Dtest=ModularityTests`
Expected: PASS (audit already allows `product :: api`; no cycle introduced).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/company/pos/audit/application/ProductChangedAuditListener.java
git commit -m "feat(audit): record ProductChanged events into the audit trail"
```

---

## Task 4: `ProductAdminController` + ADMIN-gate test

**Files:**
- Create: `src/main/java/com/company/pos/product/web/ProductAdminController.java`
- Test: `src/test/java/com/company/pos/product/ProductAdminControllerTest.java`

**Interfaces:**
- Consumes: `ProductAdminService` (Task 2); `CreateProductCommand`, `UpdateProductCommand`, `CategoryView`, `ProductView`.
- Produces: HTTP endpoints `POST /products` (201), `PUT /products/{sku}` (200), `POST /products/{sku}/deactivate` (204), `POST /products/{sku}/reactivate` (204), `GET /categories` (200) — all ADMIN-gated.

Context: the existing read-only `ProductController` stays as-is. This new controller is a separate `@RestController` bean; class-level `@PreAuthorize("hasRole('ADMIN')")` gates all its methods. `DatabaseCleaner` (in `com.company.pos.support`) resets tables between tests.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/product/ProductAdminControllerTest.java`:

```java
package com.company.pos.product;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class ProductAdminControllerTest {

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

    private static RequestPostProcessor manager() {
        return jwt().jwt(j -> j.subject("mgr")).authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    private static final String COLA = "{\"sku\":\"COLA\",\"name\":\"Cola\","
            + "\"categoryName\":\"Beverages\",\"unitPrice\":5.00,"
            + "\"currencyCode\":\"SAR\",\"unitOfMeasure\":\"EA\"}";

    @Test
    void adminCreatesProduct() throws Exception {
        mvc.perform(post("/products").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(COLA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("COLA"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void cashierCannotCreateProduct() throws Exception {
        mvc.perform(post("/products").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON).content(COLA))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotCreateProduct() throws Exception {
        mvc.perform(post("/products").with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content(COLA))
                .andExpect(status().isForbidden());
    }

    @Test
    void cashierCannotListCategories() throws Exception {
        mvc.perform(get("/categories").with(cashier())).andExpect(status().isForbidden());
    }

    @Test
    void adminListsCategoriesAfterCreate() throws Exception {
        mvc.perform(post("/products").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(COLA))
                .andExpect(status().isCreated());
        mvc.perform(get("/categories").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Beverages"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw test -Dtest=ProductAdminControllerTest`
Expected: FAIL — no `POST /products` mapping (create returns 405/404, not 201).

- [ ] **Step 3: Create the controller**

`src/main/java/com/company/pos/product/web/ProductAdminController.java`:

```java
package com.company.pos.product.web;

import com.company.pos.product.api.CategoryView;
import com.company.pos.product.api.CreateProductCommand;
import com.company.pos.product.api.ProductView;
import com.company.pos.product.api.UpdateProductCommand;
import com.company.pos.product.application.ProductAdminService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasRole('ADMIN')")
class ProductAdminController {

    private final ProductAdminService admin;

    ProductAdminController(ProductAdminService admin) {
        this.admin = admin;
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    ProductView create(@RequestBody CreateProductCommand cmd) {
        return admin.createProduct(cmd);
    }

    @PutMapping("/products/{sku}")
    ProductView update(@PathVariable String sku, @RequestBody UpdateProductCommand cmd) {
        return admin.updateProduct(sku, cmd);
    }

    @PostMapping("/products/{sku}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deactivate(@PathVariable String sku) {
        admin.deactivate(sku);
    }

    @PostMapping("/products/{sku}/reactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reactivate(@PathVariable String sku) {
        admin.reactivate(sku);
    }

    @GetMapping("/categories")
    List<CategoryView> categories() {
        return admin.listCategories();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw test -Dtest=ProductAdminControllerTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Run the full product module + ModularityTests**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw test -Dtest='com.company.pos.product.*,ModularityTests'`
Expected: PASS (existing product tests + new tests + boundaries).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/product/web/ProductAdminController.java \
        src/test/java/com/company/pos/product/ProductAdminControllerTest.java
git commit -m "feat(product): ADMIN-gated product admin REST endpoints"
```

---

## Task 5: Terminal API client + DTOs + `ProductAdminViewModel`

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/ProductAdminView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/CreateProductRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/UpdateProductRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/CategoryView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/ProductAdminApi.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/ProductAdminViewModel.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/ProductAdminViewModelTest.java`

**Interfaces:**
- Consumes: existing `ApiClient` (`<T> T get(String, TypeReference<T>)`, `<T> T post(String, Object, TypeReference<T>)`, `<T> T put(String, Object, TypeReference<T>)`; a null `TypeReference`/body is allowed and returns null), `ApiException` (`problem()`, `getMessage()`), `ProblemDetail` (`title`, `status`, `detail`).
- Produces: `ProductAdminApi` (`list`, `listCategories`, `create`, `update`, `deactivate`, `reactivate` — non-final for test subclassing), and `ProductAdminViewModel` (`load`, `loadCategories`, `create`, `update`, `deactivate`, `reactivate`, `errorMessage()`), plus `Services.productAdminApi`.

Context: the terminal already has a read-only `ProductApi` + `dto.ProductView` (which drops `active`/UoM/currency). This task adds a **separate** admin client and a richer `ProductAdminView`. All work is in the `pos-terminal/` build (`./mvnw -f pos-terminal/pom.xml`).

- [ ] **Step 1: Create the terminal DTOs**

`pos-terminal/src/main/java/com/company/pos/terminal/api/ProductAdminView.java`:

```java
package com.company.pos.terminal.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/** Admin-side product read model — includes active/UoM/currency that {@code dto.ProductView} drops. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductAdminView(String sku, String name, String categoryName, String barcode,
        String unitOfMeasure, BigDecimal unitPrice, String currencyCode, boolean active) {
}
```

`pos-terminal/src/main/java/com/company/pos/terminal/api/CreateProductRequest.java`:

```java
package com.company.pos.terminal.api;

import java.math.BigDecimal;

public record CreateProductRequest(String sku, String name, String categoryCode, String categoryName,
        BigDecimal unitPrice, String currencyCode, String unitOfMeasure, String barcode) {
}
```

`pos-terminal/src/main/java/com/company/pos/terminal/api/UpdateProductRequest.java`:

```java
package com.company.pos.terminal.api;

import java.math.BigDecimal;

public record UpdateProductRequest(String name, String categoryCode, String categoryName,
        BigDecimal unitPrice, String currencyCode, String unitOfMeasure, String barcode) {
}
```

`pos-terminal/src/main/java/com/company/pos/terminal/api/CategoryView.java`:

```java
package com.company.pos.terminal.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CategoryView(String code, String name) {
}
```

- [ ] **Step 2: Create `ProductAdminApi`**

`pos-terminal/src/main/java/com/company/pos/terminal/api/ProductAdminApi.java`:

```java
package com.company.pos.terminal.api;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;

/** Typed client for the store server's product admin endpoints (writes ADMIN-gated) plus the
 *  read-only {@code /products} list and {@code /categories}. Methods are non-final so view-model
 *  tests can subclass with fakes. */
public class ProductAdminApi {

    private final ApiClient client;

    public ProductAdminApi(ApiClient client) {
        this.client = client;
    }

    /** GET /products — all products (active and inactive); the screen filters inactive client-side. */
    public List<ProductAdminView> list() {
        return client.get("/products", new TypeReference<List<ProductAdminView>>() {});
    }

    /** GET /categories — existing categories for the form dropdown. */
    public List<CategoryView> listCategories() {
        return client.get("/categories", new TypeReference<List<CategoryView>>() {});
    }

    /** POST /products — create a product. */
    public ProductAdminView create(CreateProductRequest req) {
        return client.post("/products", req, new TypeReference<ProductAdminView>() {});
    }

    /** PUT /products/{sku} — edit a product. */
    public ProductAdminView update(String sku, UpdateProductRequest req) {
        return client.put("/products/" + sku, req, new TypeReference<ProductAdminView>() {});
    }

    /** POST /products/{sku}/deactivate. */
    public void deactivate(String sku) {
        client.post("/products/" + sku + "/deactivate", null, null);
    }

    /** POST /products/{sku}/reactivate. */
    public void reactivate(String sku) {
        client.post("/products/" + sku + "/reactivate", null, null);
    }
}
```

- [ ] **Step 3: Wire `productAdminApi` into `Services`**

In `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`: add the import `import com.company.pos.terminal.api.ProductAdminApi;`, add the field `public final ProductAdminApi productAdminApi;` (next to `usersApi`), and in the constructor add `this.productAdminApi = new ProductAdminApi(apiClient);` (after `this.usersApi = new UsersApi(apiClient);`).

- [ ] **Step 4: Write the failing view-model test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/ProductAdminViewModelTest.java`:

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.CreateProductRequest;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.ProductAdminApi;
import com.company.pos.terminal.api.ProductAdminView;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductAdminViewModelTest {

    private ProductAdminView sample() {
        return new ProductAdminView("COLA", "Cola", "Beverages", "bcCOLA", "EA",
                new BigDecimal("5.00"), "SAR", true);
    }

    @Test
    void loadReturnsProductsAndClearsError() {
        ProductAdminApi api = new ProductAdminApi(null) {
            @Override public List<ProductAdminView> list() { return List.of(sample()); }
        };
        ProductAdminViewModel vm = new ProductAdminViewModel(api, Runnable::run);
        List<ProductAdminView> r = vm.load();
        assertEquals(1, r.size());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void createSurfacesConflictAndReturnsNull() {
        ProductAdminApi api = new ProductAdminApi(null) {
            @Override public ProductAdminView create(CreateProductRequest req) {
                throw new ApiException(409, new ProblemDetail("Conflict", 409, "SKU already in use"),
                        "HTTP 409");
            }
        };
        ProductAdminViewModel vm = new ProductAdminViewModel(api, Runnable::run);
        assertNull(vm.create(new CreateProductRequest("COLA", "Cola", null, null,
                new BigDecimal("5.00"), "SAR", "EA", null)));
        assertEquals("SKU already in use", vm.errorMessage().get());
    }

    @Test
    void deactivateReturnsTrueOnSuccess() {
        ProductAdminApi api = new ProductAdminApi(null) {
            @Override public void deactivate(String sku) { /* ok */ }
        };
        ProductAdminViewModel vm = new ProductAdminViewModel(api, Runnable::run);
        assertTrue(vm.deactivate("COLA"));
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void deferredDispatcherHoldsErrorUntilDrained() {
        ProductAdminApi api = new ProductAdminApi(null) {
            @Override public List<ProductAdminView> list() {
                throw new ApiException(500, new ProblemDetail("Error", 500, "boom"), "HTTP 500");
            }
        };
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        ProductAdminViewModel vm = new ProductAdminViewModel(api, queue::add);
        assertNull(vm.load());
        assertEquals("", vm.errorMessage().get());   // deferred: not applied yet
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
        assertEquals("boom", vm.errorMessage().get());
    }
}
```

- [ ] **Step 5: Run test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw -f pos-terminal/pom.xml test -Dtest=ProductAdminViewModelTest`
Expected: FAIL — `ProductAdminViewModel` does not exist (compile error).

- [ ] **Step 6: Create `ProductAdminViewModel`**

`pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/ProductAdminViewModel.java`:

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.CategoryView;
import com.company.pos.terminal.api.CreateProductRequest;
import com.company.pos.terminal.api.ProductAdminApi;
import com.company.pos.terminal.api.ProductAdminView;
import com.company.pos.terminal.api.UpdateProductRequest;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the product catalogue admin screen. Synchronous like the other VMs — the controller
 * runs it off the FX thread via FxTasks and reads the return value; the only observable written
 * off-thread is {@code errorMessage}, inside the {@code ui} dispatcher.
 */
public class ProductAdminViewModel {

    private final ProductAdminApi api;
    private final Consumer<Runnable> ui;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public ProductAdminViewModel(ProductAdminApi api, Consumer<Runnable> ui) {
        this.api = api;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    public List<ProductAdminView> load() {
        try {
            List<ProductAdminView> list = api.list();
            ui.accept(() -> errorMessage.set(""));
            return list;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public List<CategoryView> loadCategories() {
        try {
            List<CategoryView> list = api.listCategories();
            ui.accept(() -> errorMessage.set(""));
            return list;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public ProductAdminView create(CreateProductRequest req) {
        try {
            ProductAdminView v = api.create(req);
            ui.accept(() -> errorMessage.set(""));
            return v;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public ProductAdminView update(String sku, UpdateProductRequest req) {
        try {
            ProductAdminView v = api.update(sku, req);
            ui.accept(() -> errorMessage.set(""));
            return v;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public boolean deactivate(String sku) {
        return voidCall(() -> api.deactivate(sku));
    }

    public boolean reactivate(String sku) {
        return voidCall(() -> api.reactivate(sku));
    }

    private boolean voidCall(Runnable call) {
        try {
            call.run();
            ui.accept(() -> errorMessage.set(""));
            return true;
        } catch (ApiException e) {
            fail(e);
            return false;
        }
    }

    private void fail(ApiException e) {
        String msg = messageOf(e);
        ui.accept(() -> errorMessage.set(msg));
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

- [ ] **Step 7: Run test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw -f pos-terminal/pom.xml test -Dtest=ProductAdminViewModelTest`
Expected: PASS (4 tests).

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/ProductAdminView.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/CreateProductRequest.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/UpdateProductRequest.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/CategoryView.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/ProductAdminApi.java \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java \
        pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/ProductAdminViewModel.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/ProductAdminViewModelTest.java
git commit -m "feat(terminal): ProductAdminApi + ProductAdminViewModel"
```

---

## Task 6: `ProductFormDialog` (I/O-free) + validation test

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/ProductFormDialog.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/view/ProductFormDialogTest.java`

**Interfaces:**
- Consumes: `CreateProductRequest`, `UpdateProductRequest`, `ProductAdminView`, `CategoryView` (Task 5).
- Produces: `ProductFormDialog.promptCreate(List<CategoryView>): Optional<CreateProductRequest>`, `promptEdit(ProductAdminView, List<CategoryView>): Optional<UpdateProductRequest>`, and package-visible static helpers `isValidCreate(String sku, String name, String price): boolean`, `isValidPrice(String): boolean`, `parsePrice(String): BigDecimal`.

Context: mirrors `UserFormDialog` — pure view, collects input only. The category combo lists existing category **names** plus an "➕ New category…" entry; selecting an existing name sends its `code` (server reuses it, no derivation); "New…" reveals a name field and sends a blank code + new name. Create sends `currencyCode = null` (server defaults); edit resends the existing currency. Only the static helpers are unit-tested (headless).

- [ ] **Step 1: Write the failing test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/view/ProductFormDialogTest.java`:

```java
package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ProductFormDialogTest {

    @Test
    void validWhenSkuNamePricePresent() {
        assertTrue(ProductFormDialog.isValidCreate("COLA", "Cola", "5.00"));
    }

    @Test
    void invalidWhenSkuBlank() {
        assertFalse(ProductFormDialog.isValidCreate("  ", "Cola", "5.00"));
    }

    @Test
    void invalidWhenNameBlank() {
        assertFalse(ProductFormDialog.isValidCreate("COLA", " ", "5.00"));
    }

    @Test
    void invalidWhenPriceNegative() {
        assertFalse(ProductFormDialog.isValidCreate("COLA", "Cola", "-1"));
    }

    @Test
    void invalidWhenPriceNotNumeric() {
        assertFalse(ProductFormDialog.isValidCreate("COLA", "Cola", "abc"));
    }

    @Test
    void parsePriceReturnsNullForBlank() {
        assertNull(ProductFormDialog.parsePrice("  "));
    }

    @Test
    void parsePriceParsesDecimal() {
        assertEquals(new BigDecimal("6.50"), ProductFormDialog.parsePrice(" 6.50 "));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw -f pos-terminal/pom.xml test -Dtest=ProductFormDialogTest`
Expected: FAIL — `ProductFormDialog` does not exist (compile error).

- [ ] **Step 3: Create `ProductFormDialog`**

`pos-terminal/src/main/java/com/company/pos/terminal/view/ProductFormDialog.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.CategoryView;
import com.company.pos.terminal.api.CreateProductRequest;
import com.company.pos.terminal.api.ProductAdminView;
import com.company.pos.terminal.api.UpdateProductRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Modal to create or edit a product. Pure view — collects input only; the controller performs all
 * HTTP and passes in the category list. Create returns a {@link CreateProductRequest}; edit returns
 * an {@link UpdateProductRequest} (SKU is immutable and not shown). Only {@link #isValidCreate},
 * {@link #isValidPrice} and {@link #parsePrice} are unit-tested headlessly.
 */
public final class ProductFormDialog {

    private static final String NEW_CATEGORY = "➕ New category…";

    private ProductFormDialog() {}

    public static Optional<CreateProductRequest> promptCreate(List<CategoryView> categories) {
        Dialog<CreateProductRequest> dialog = new Dialog<>();
        dialog.setTitle("New product");
        dialog.setHeaderText("Create a product");
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = configureButtons(dialog, "Create");

        TextField sku = textField("SKU");
        TextField name = textField("name");
        TextField price = textField("price");
        TextField uom = textField("unit (e.g. EA)");
        uom.setText("EA");
        TextField barcode = textField("barcode (optional)");
        ComboBox<String> category = categoryCombo(categories, null);
        TextField newCategory = textField("new category name");
        VBox newCategoryField = field("New category", newCategory);
        bindNewCategoryVisibility(category, newCategoryField);

        VBox box = new VBox(12,
                field("SKU", sku),
                field("Name", name),
                field("Price", price),
                field("Category", category),
                newCategoryField,
                field("Unit of measure", uom),
                field("Barcode", barcode));
        box.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(
                !isValidCreate(sku.getText(), name.getText(), price.getText()));
        sku.textProperty().addListener((o, a, b) -> revalidate.run());
        name.textProperty().addListener((o, a, b) -> revalidate.run());
        price.textProperty().addListener((o, a, b) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> {
            if (bt != submit || !isValidCreate(sku.getText(), name.getText(), price.getText())) {
                return null;
            }
            CategoryChoice cc = categoryChoice(category, newCategory, categories);
            return new CreateProductRequest(sku.getText().trim(), name.getText().trim(),
                    cc.code(), cc.name(), parsePrice(price.getText()),
                    null, trimToNull(uom.getText()), trimToNull(barcode.getText()));
        });
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    public static Optional<UpdateProductRequest> promptEdit(ProductAdminView existing,
            List<CategoryView> categories) {
        Dialog<UpdateProductRequest> dialog = new Dialog<>();
        dialog.setTitle("Edit product");
        dialog.setHeaderText("Edit " + existing.sku());
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = configureButtons(dialog, "Save");

        TextField name = textField("name");
        name.setText(existing.name());
        TextField price = textField("price");
        price.setText(existing.unitPrice() == null ? "" : existing.unitPrice().toPlainString());
        TextField uom = textField("unit (e.g. EA)");
        uom.setText(existing.unitOfMeasure() == null ? "" : existing.unitOfMeasure());
        TextField barcode = textField("barcode (optional)");
        barcode.setText(existing.barcode() == null ? "" : existing.barcode());
        ComboBox<String> category = categoryCombo(categories, existing.categoryName());
        TextField newCategory = textField("new category name");
        VBox newCategoryField = field("New category", newCategory);
        bindNewCategoryVisibility(category, newCategoryField);

        VBox box = new VBox(12,
                field("Name", name),
                field("Price", price),
                field("Category", category),
                newCategoryField,
                field("Unit of measure", uom),
                field("Barcode", barcode));
        box.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(
                name.getText().isBlank() || !isValidPrice(price.getText()));
        name.textProperty().addListener((o, a, b) -> revalidate.run());
        price.textProperty().addListener((o, a, b) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> {
            if (bt != submit || name.getText().isBlank() || !isValidPrice(price.getText())) {
                return null;
            }
            CategoryChoice cc = categoryChoice(category, newCategory, categories);
            return new UpdateProductRequest(name.getText().trim(), cc.code(), cc.name(),
                    parsePrice(price.getText()), existing.currencyCode(),
                    trimToNull(uom.getText()), trimToNull(barcode.getText()));
        });
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    /** Create validity: SKU + name non-blank and a valid non-negative price. */
    static boolean isValidCreate(String sku, String name, String price) {
        return sku != null && !sku.isBlank()
                && name != null && !name.isBlank()
                && isValidPrice(price);
    }

    static boolean isValidPrice(String price) {
        BigDecimal p = parsePrice(price);
        return p != null && p.signum() >= 0;
    }

    /** Parse a price string to BigDecimal, or null if blank/unparseable. */
    static BigDecimal parsePrice(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record CategoryChoice(String code, String name) {}

    private static CategoryChoice categoryChoice(ComboBox<String> combo, TextField newCategory,
            List<CategoryView> categories) {
        String sel = combo.getValue();
        if (sel == null || sel.isBlank()) {
            return new CategoryChoice(null, null);
        }
        if (NEW_CATEGORY.equals(sel)) {
            return new CategoryChoice(null, trimToNull(newCategory.getText()));
        }
        // Existing category selected by name → send its code so the server reuses it (no derivation).
        if (categories != null) {
            for (CategoryView c : categories) {
                if (c.name().equals(sel)) {
                    return new CategoryChoice(c.code(), null);
                }
            }
        }
        return new CategoryChoice(null, trimToNull(sel));
    }

    private static ComboBox<String> categoryCombo(List<CategoryView> categories, String selectedName) {
        List<String> names = new ArrayList<>();
        if (categories != null) {
            for (CategoryView c : categories) {
                names.add(c.name());
            }
        }
        names.add(NEW_CATEGORY);
        ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList(names));
        combo.setPromptText("(none)");
        if (selectedName != null && names.contains(selectedName)) {
            combo.setValue(selectedName);
        }
        return combo;
    }

    private static void bindNewCategoryVisibility(ComboBox<String> combo, VBox newCategoryField) {
        Runnable apply = () -> {
            boolean show = NEW_CATEGORY.equals(combo.getValue());
            newCategoryField.setVisible(show);
            newCategoryField.setManaged(show);
        };
        combo.valueProperty().addListener((o, a, b) -> apply.run());
        apply.run();
    }

    private static <T> ButtonType configureButtons(Dialog<T> dialog, String submitLabel) {
        ButtonType submit = new ButtonType(submitLabel, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);
        return submit;
    }

    private static TextField textField(String prompt) {
        TextField f = new TextField();
        f.setPromptText(prompt);
        return f;
    }

    private static VBox field(String label, Node control) {
        Label l = new Label(label);
        l.getStyleClass().add("field-label");
        VBox b = new VBox(6, l, control);
        b.getStyleClass().add("field");
        return b;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw -f pos-terminal/pom.xml test -Dtest=ProductFormDialogTest`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/ProductFormDialog.java \
        pos-terminal/src/test/java/com/company/pos/terminal/view/ProductFormDialogTest.java
git commit -m "feat(terminal): I/O-free ProductFormDialog (create/edit)"
```

---

## Task 7: Products screen wiring (controller + FXML + navigation + Admin tile)

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/ProductsController.java`
- Create: `pos-terminal/src/main/resources/fxml/products.fxml`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/AdminController.java`
- Modify: `pos-terminal/src/main/resources/fxml/admin.fxml`

**Interfaces:**
- Consumes: `ProductAdminViewModel`, `ProductAdminApi` via `Services.productAdminApi` (Task 5); `ProductFormDialog` (Task 6); `FxTasks.run`, `Navigator` (`toAdmin`, plus new `toProducts`), `Services`.
- Produces: `Navigator.toProducts()`; a functional Products screen.

Context: mirrors `StaffController`/`staff.fxml` 1:1. No unit test for the controller/FXML wiring (as with `StaffController`); verified by the full terminal build + suite. Products are loaded off-thread; categories are cached at load time and passed into the I/O-free dialog; each mutation re-kicks a fresh `FxTasks` task and reloads (never HTTP in `onDone`).

- [ ] **Step 1: Create `products.fxml`**

`pos-terminal/src/main/resources/fxml/products.fxml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.scene.control.Button?>
<?import javafx.scene.control.CheckBox?>
<?import javafx.scene.control.Label?>
<?import javafx.scene.control.TableColumn?>
<?import javafx.scene.control.TableView?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.layout.Pane?>
<?import javafx.scene.layout.VBox?>

<VBox styleClass="screen" spacing="16" xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml">
  <HBox spacing="16" alignment="CENTER_LEFT">
    <Label text="Products" styleClass="title"/>
    <Pane HBox.hgrow="ALWAYS"/>
    <Label fx:id="errorLabel" styleClass="error-text"/>
    <CheckBox fx:id="includeInactive" text="Show inactive"/>
    <Button fx:id="backButton" text="Back" styleClass="btn-secondary"/>
  </HBox>

  <TableView fx:id="table" VBox.vgrow="ALWAYS">
    <columns>
      <TableColumn fx:id="skuCol" text="SKU" prefWidth="140"/>
      <TableColumn fx:id="nameCol" text="Name" prefWidth="240"/>
      <TableColumn fx:id="categoryCol" text="Category" prefWidth="180"/>
      <TableColumn fx:id="priceCol" text="Price" prefWidth="120"/>
      <TableColumn fx:id="statusCol" text="Status" prefWidth="120"/>
    </columns>
  </TableView>

  <HBox spacing="12" alignment="CENTER_LEFT">
    <Button fx:id="newButton" text="New product" styleClass="btn-primary"/>
    <Button fx:id="editButton" text="Edit"/>
    <Button fx:id="toggleActiveButton" text="Deactivate"/>
  </HBox>
</VBox>
```

- [ ] **Step 2: Create `ProductsController`**

`pos-terminal/src/main/java/com/company/pos/terminal/view/ProductsController.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.CategoryView;
import com.company.pos.terminal.api.CreateProductRequest;
import com.company.pos.terminal.api.ProductAdminView;
import com.company.pos.terminal.api.UpdateProductRequest;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.ProductAdminViewModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

/**
 * ADMIN-only product catalogue screen. Loads products + categories off the FX thread via FxTasks,
 * edits through the I/O-free {@link ProductFormDialog} (the controller performs the HTTP), and
 * reloads after every mutation. The include-inactive toggle filters the cached list client-side.
 */
public class ProductsController {

    private static final System.Logger LOG = System.getLogger(ProductsController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final ProductAdminViewModel vm;

    private List<ProductAdminView> allProducts = new ArrayList<>();
    private List<CategoryView> categories = new ArrayList<>();

    @FXML private Label errorLabel;
    @FXML private CheckBox includeInactive;
    @FXML private Button backButton;
    @FXML private Button newButton;
    @FXML private Button editButton;
    @FXML private Button toggleActiveButton;
    @FXML private TableView<ProductAdminView> table;
    @FXML private TableColumn<ProductAdminView, String> skuCol;
    @FXML private TableColumn<ProductAdminView, String> nameCol;
    @FXML private TableColumn<ProductAdminView, String> categoryCol;
    @FXML private TableColumn<ProductAdminView, String> priceCol;
    @FXML private TableColumn<ProductAdminView, String> statusCol;

    public ProductsController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new ProductAdminViewModel(services.productAdminApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        skuCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().sku()));
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        categoryCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().categoryName() == null ? "" : c.getValue().categoryName()));
        priceCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().unitPrice() == null ? "" : c.getValue().unitPrice().toPlainString()));
        statusCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().active() ? "Active" : "Inactive"));

        errorLabel.textProperty().bind(vm.errorMessage());
        includeInactive.selectedProperty().addListener((o, a, b) -> applyFilter());
        table.getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> refreshButtons(sel));

        backButton.setOnAction(e -> navigator.toAdmin());
        newButton.setOnAction(e -> createProduct());
        editButton.setOnAction(e -> editSelected());
        toggleActiveButton.setOnAction(e -> toggleActiveSelected());

        refreshButtons(null);
        reload();
    }

    @SuppressWarnings("unchecked")
    private void reload() {
        final List<ProductAdminView>[] ph = new List[1];
        final List<CategoryView>[] ch = new List[1];
        FxTasks.run(
                () -> {
                    ph[0] = vm.load();
                    ch[0] = vm.loadCategories();
                },
                () -> {
                    if (ph[0] != null) {
                        allProducts = ph[0];
                        applyFilter();
                    }
                    if (ch[0] != null) {
                        categories = ch[0];
                    }
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Load products failed", err));
    }

    private void applyFilter() {
        boolean incl = includeInactive.isSelected();
        List<ProductAdminView> shown = allProducts.stream()
                .filter(p -> incl || p.active())
                .toList();
        table.setItems(FXCollections.observableArrayList(shown));
    }

    private void refreshButtons(ProductAdminView sel) {
        boolean has = sel != null;
        editButton.setDisable(!has);
        toggleActiveButton.setDisable(!has);
        toggleActiveButton.setText(has && !sel.active() ? "Reactivate" : "Deactivate");
    }

    private void createProduct() {
        Optional<CreateProductRequest> req = ProductFormDialog.promptCreate(categories);
        req.ifPresent(r -> {
            final ProductAdminView[] holder = new ProductAdminView[1];
            FxTasks.run(() -> holder[0] = vm.create(r),
                    () -> { if (holder[0] != null) reload(); },
                    err -> LOG.log(System.Logger.Level.ERROR, "Create product failed", err));
        });
    }

    private void editSelected() {
        ProductAdminView sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        Optional<UpdateProductRequest> req = ProductFormDialog.promptEdit(sel, categories);
        req.ifPresent(r -> {
            final ProductAdminView[] holder = new ProductAdminView[1];
            FxTasks.run(() -> holder[0] = vm.update(sel.sku(), r),
                    () -> { if (holder[0] != null) reload(); },
                    err -> LOG.log(System.Logger.Level.ERROR, "Update product failed", err));
        });
    }

    private void toggleActiveSelected() {
        ProductAdminView sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        boolean reactivating = !sel.active();
        final boolean[] holder = {false};
        FxTasks.run(
                () -> holder[0] = reactivating ? vm.reactivate(sel.sku()) : vm.deactivate(sel.sku()),
                () -> { if (holder[0]) reload(); },
                err -> LOG.log(System.Logger.Level.ERROR, "Toggle active failed", err));
    }
}
```

- [ ] **Step 3: Add `toProducts()` to `Navigator`**

In `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java`, add this method after `toStaff()`:

```java
    public void toProducts() {
        com.company.pos.terminal.view.ProductsController controller =
                new com.company.pos.terminal.view.ProductsController(services, this);
        setScene("/fxml/products.fxml", controller);
    }
```

- [ ] **Step 4: Add the Products tile to `AdminController`**

In `pos-terminal/src/main/java/com/company/pos/terminal/view/AdminController.java`, add the field and wiring:

```java
    @FXML private Button productsButton;
```

and inside `initialize()`, after the existing `staffButton` wiring:

```java
        productsButton.setVisible(admin);
        productsButton.setManaged(admin);
        productsButton.setOnAction(e -> navigator.toProducts());
```

(`admin` is the existing `boolean admin = services.session.roles().contains("ADMIN");` local.)

- [ ] **Step 5: Add the Products tile to `admin.fxml`**

In `pos-terminal/src/main/resources/fxml/admin.fxml`, add a `productsButton` inside the tiles `HBox` (next to `staffButton`):

```xml
    <HBox spacing="32" alignment="CENTER">
      <Button fx:id="staffButton" text="Staff" styleClass="home-tile"/>
      <Button fx:id="productsButton" text="Products" styleClass="home-tile"/>
    </HBox>
```

- [ ] **Step 6: Run the full terminal test suite**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw -f pos-terminal/pom.xml clean test`
Expected: PASS — compiles (new controller, `Navigator.toProducts`, `AdminController` field bound to `admin.fxml`) and the full suite is green.

- [ ] **Step 7: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/ProductsController.java \
        pos-terminal/src/main/resources/fxml/products.fxml \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/AdminController.java \
        pos-terminal/src/main/resources/fxml/admin.fxml
git commit -m "feat(terminal): Products admin screen + Admin-area tile"
```

---

## Final verification (after all tasks)

- [ ] **Backend full build + boundaries**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw verify`
Expected: full build green, including `ModularityTests` (no `product → audit` cycle; `configuration :: api` declared).

- [ ] **Terminal full build**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; ./mvnw -f pos-terminal/pom.xml clean test`
Expected: green.

- [ ] **Manual smoke (optional, needs a running backend)**

Start backend with `--spring.profiles.active=embedded,dev`, launch the terminal (`./mvnw -f pos-terminal/pom.xml javafx:run`), log in as `manager`/`manager` — note the seeded `manager` must hold ADMIN to see the Products tile; if it does not, use the first-admin runbook (`docs/operations/first-admin.md`) or seed an ADMIN. Verify: Admin → Products lists the seeded catalogue; create a product; edit its price; deactivate/reactivate; toggle "Show inactive".
