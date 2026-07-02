# Customer Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `customer` module (register/edit/search + soft-delete, attach a customer to a cart, and per-customer purchase history) to complete the `customer` slice of the plan's §14 MVP.

**Architecture:** A Tier-2 Spring Modulith module in the house hexagonal layout (`api`/`web`/`application`/`domain`/`infrastructure`), mirroring `product`. Purchase history is a read projection the module builds itself by subscribing to the `SaleCompleted` event. All dependency edges point *from* `customer` toward `cart::api` and `sales::api`; nothing depends on `customer` (keeps the module graph acyclic).

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Modulith, Spring Data JPA, Flyway (store-server) / Hibernate ddl-auto (embedded), JUnit 5, MockMvc, Awaitility.

## Global Constraints

- **JDK 21 required.** Set `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` before any Maven command. Build with `./mvnw`, never Gradle.
- **After every task, run the affected module's tests AND `./mvnw test -Dtest=ModularityTests`.** The final task runs full `./mvnw verify` including the Testcontainers Postgres test.
- **Money is `BigDecimal`, never `double`.** Columns use `precision=19` (`scale=4` for unit-level, `scale=2` for sums), matching existing entities.
- **Cross-module imports are `api` only.** When a module gains a dependency, add it to that module's `package-info.java` `allowedDependencies` in the SAME task, and consume only the other module's `@NamedInterface("api")` package. Never import another module's `domain`/`application`/`infrastructure`.
- **Acyclic graph is mandatory.** `customer → { common, database, cart :: api, sales :: api }`. `cart` and `sales` MUST NOT import `customer` — `cart` stores the customer id opaquely; `sales` carries it opaquely through the event.
- **Authorization = method security, matching the codebase idiom.** Everyday operations carry NO `@PreAuthorize` (authenticated baseline = CASHIER, enforced by `SecurityConfig` which authenticates everything except `/auth/login`). Only elevated operations carry `@PreAuthorize("hasRole('MANAGER')")`. Roles are an explicit `Set<Role>` (`CASHIER`/`MANAGER`/`ADMIN`); do NOT use `hasRole('CASHIER')` — a MANAGER-only user lacks it.
- **Flyway versions are globally sequential.** This plan uses V20 (customer), V21 (cart), V22 (sales). Register the new `customer` migration folder in `application-store-server.yml`. Only `store-server` runs Flyway; `embedded` uses `ddl-auto: update`, so **migration column types MUST exactly match entity mappings** (UUID → `VARCHAR(36)`; the Phase 6 lesson: a `CHAR` vs `VARCHAR` mismatch fails Hibernate `validate` on Postgres but is invisible on embedded).
- **UUID id mapping (verbatim house pattern):** `@Id @JdbcTypeCode(SqlTypes.VARCHAR) @Column(length = 36) private UUID id;`. New UUIDs come from `com.company.pos.common.util.Identifiers.newId()`.
- **Errors** use `com.company.pos.common.exception.DomainException`: `validation(String)`, `notFound(String)`, `conflict(String)`.
- **Events for facts, calls for queries.** The history listener uses `@ApplicationModuleListener` (runs async, after-commit, in its own `REQUIRES_NEW` transaction). It must be **idempotent** (dedupe on `sale_id`) because outbox redelivery is at-least-once. Note: embedded SQLite is single-writer (pool=1) with `busy_timeout=5000`; the new listener is one more `SaleCompleted` subscriber alongside inventory/cashdrawer/audit and serializes through the busy-timeout like they do — do not raise the pool.

---

## File Structure

**New module `com.company.pos.customer`:**
- `customer/package-info.java` — module boundary + allowedDependencies
- `customer/api/CustomerService.java` — facade interface
- `customer/api/CustomerView.java`, `PurchaseHistoryEntry.java`, `RegisterCustomerCommand.java`, `UpdateCustomerCommand.java` — DTOs
- `customer/api/package-info.java` — `@NamedInterface("api")`
- `customer/domain/Customer.java`, `CustomerPurchase.java` — entities (package-private)
- `customer/infrastructure/CustomerRepository.java`, `CustomerPurchaseRepository.java` — Spring Data repos
- `customer/application/DefaultCustomerService.java` — facade impl (`@Transactional`)
- `customer/application/SaleCompletedCustomerListener.java` — history projection listener
- `customer/web/CustomerController.java` — HTTP surface
- `src/main/resources/db/migration/customer/V20__customer.sql` — customer + customer_purchase tables

**Modified:**
- `cart/api/CartView.java` (+ `customerId`), `cart/api/CartService.java` (+ `assignCustomer`/`clearCustomer`)
- `cart/domain/Cart.java` (+ `customerId` field + methods), `cart/application/DefaultCartService.java`, `cart/web/CartController.java` (+ detach)
- `db/migration/cart/V21__cart_customer_id.sql`
- `sales/api/SaleCompleted.java` (+ `customerId`, `occurredAt`), `sales/domain/Sale.java` (+ `customerId`), `sales/application/DefaultSalesService.java`
- `db/migration/sales/V22__sale_customer_id.sql`
- `src/main/resources/application-store-server.yml` (Flyway locations + customer)
- `docs/run-modes.md` (Customer section)

---

## Task 1: Customer module core — entities, repositories, facade, CRUD + search

Creates the module foundation: persistence, the `CustomerService` facade, and CRUD/search. No HTTP, no events yet. `purchaseHistory()` returns empty until Task 5 populates the projection.

**Files:**
- Create: `src/main/java/com/company/pos/customer/package-info.java`
- Create: `src/main/java/com/company/pos/customer/api/package-info.java`
- Create: `src/main/java/com/company/pos/customer/api/CustomerService.java`
- Create: `src/main/java/com/company/pos/customer/api/CustomerView.java`
- Create: `src/main/java/com/company/pos/customer/api/PurchaseHistoryEntry.java`
- Create: `src/main/java/com/company/pos/customer/api/RegisterCustomerCommand.java`
- Create: `src/main/java/com/company/pos/customer/api/UpdateCustomerCommand.java`
- Create: `src/main/java/com/company/pos/customer/domain/Customer.java`
- Create: `src/main/java/com/company/pos/customer/domain/CustomerPurchase.java`
- Create: `src/main/java/com/company/pos/customer/infrastructure/CustomerRepository.java`
- Create: `src/main/java/com/company/pos/customer/infrastructure/CustomerPurchaseRepository.java`
- Create: `src/main/java/com/company/pos/customer/application/DefaultCustomerService.java`
- Create: `src/main/resources/db/migration/customer/V20__customer.sql`
- Modify: `src/main/resources/application-store-server.yml:27`
- Test: `src/test/java/com/company/pos/customer/DefaultCustomerServiceTest.java`

**Interfaces:**
- Consumes: `Identifiers.newId()` (returns `UUID`), `DomainException.validation/notFound(String)`.
- Produces (later tasks rely on these):
  - `CustomerService.register(RegisterCustomerCommand) → CustomerView`
  - `CustomerService.update(UUID, UpdateCustomerCommand) → CustomerView`
  - `CustomerService.deactivate(UUID) → void`
  - `CustomerService.findById(UUID) → Optional<CustomerView>`
  - `CustomerService.search(String) → List<CustomerView>`
  - `CustomerService.purchaseHistory(UUID) → List<PurchaseHistoryEntry>`
  - `CustomerView(UUID id, String name, String phone, String email, String address, String notes, boolean active, Instant createdAt)`
  - `PurchaseHistoryEntry(UUID saleId, String receiptNumber, Instant occurredAt, BigDecimal grandTotal, String currencyCode)`
  - `RegisterCustomerCommand(String name, String phone, String email, String address, String notes)` and identical `UpdateCustomerCommand`
  - `CustomerPurchase(UUID id, UUID customerId, UUID saleId, String receiptNumber, Instant occurredAt, BigDecimal grandTotal, String currencyCode)` (public constructor — Task 5's listener calls it)
  - `CustomerPurchaseRepository.existsBySaleId(UUID) → boolean`, `findByCustomerIdOrderByOccurredAtDesc(UUID) → List<CustomerPurchase>`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/customer/DefaultCustomerServiceTest.java`:

```java
package com.company.pos.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.customer.api.CustomerService;
import com.company.pos.customer.api.CustomerView;
import com.company.pos.customer.api.RegisterCustomerCommand;
import com.company.pos.customer.api.UpdateCustomerCommand;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class DefaultCustomerServiceTest {

    @Autowired
    CustomerService customers;

    @Test
    void registersAndFindsCustomer() {
        CustomerView created = customers.register(
                new RegisterCustomerCommand("Aisha Khan", "0501234567", "aisha@example.com", "12 Palm St", "VIP"));

        assertThat(created.id()).isNotNull();
        assertThat(created.name()).isEqualTo("Aisha Khan");
        assertThat(created.active()).isTrue();

        CustomerView found = customers.findById(created.id()).orElseThrow();
        assertThat(found.phone()).isEqualTo("0501234567");
        assertThat(found.email()).isEqualTo("aisha@example.com");
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> customers.register(
                new RegisterCustomerCommand("  ", "0500000000", null, null, null)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void updatesMutableFields() {
        CustomerView created = customers.register(
                new RegisterCustomerCommand("Bilal", "0511111111", null, null, null));

        CustomerView updated = customers.update(created.id(),
                new UpdateCustomerCommand("Bilal Ahmed", "0512222222", "bilal@example.com", "9 Cedar Rd", "note"));

        assertThat(updated.name()).isEqualTo("Bilal Ahmed");
        assertThat(updated.phone()).isEqualTo("0512222222");
        assertThat(updated.email()).isEqualTo("bilal@example.com");
    }

    @Test
    void deactivateIsSoftAndHidesFromSearch() {
        CustomerView created = customers.register(
                new RegisterCustomerCommand("Zoya Malik", "0533333333", "zoya@example.com", null, null));

        customers.deactivate(created.id());

        // Row still exists (soft delete)...
        assertThat(customers.findById(created.id()).orElseThrow().active()).isFalse();
        // ...but is excluded from search.
        assertThat(customers.search("Zoya")).extracting(CustomerView::id).doesNotContain(created.id());
    }

    @Test
    void searchMatchesNamePhoneAndEmail() {
        CustomerView c = customers.register(
                new RegisterCustomerCommand("Omar Farouk", "0549998877", "omar@shop.com", null, null));

        assertThat(customers.search("Omar")).extracting(CustomerView::id).contains(c.id());
        assertThat(customers.search("99988")).extracting(CustomerView::id).contains(c.id());
        assertThat(customers.search("omar@shop")).extracting(CustomerView::id).contains(c.id());
    }

    @Test
    void updateUnknownIdThrows() {
        assertThatThrownBy(() -> customers.update(UUID.randomUUID(),
                new UpdateCustomerCommand("X", null, null, null, null)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void purchaseHistoryIsEmptyForNewCustomer() {
        CustomerView c = customers.register(
                new RegisterCustomerCommand("New Person", "0500001111", null, null, null));
        assertThat(customers.purchaseHistory(c.id())).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=DefaultCustomerServiceTest`
Expected: FAIL — compilation error, `package com.company.pos.customer.api does not exist` / `CustomerService` not found.

- [ ] **Step 3: Create the module boundary**

`src/main/java/com/company/pos/customer/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database" })
package com.company.pos.customer;
```

`src/main/java/com/company/pos/customer/api/package-info.java`:

```java
@org.springframework.modulith.NamedInterface("api")
package com.company.pos.customer.api;
```

- [ ] **Step 4: Create the api DTOs and facade**

`src/main/java/com/company/pos/customer/api/CustomerView.java`:

```java
package com.company.pos.customer.api;

import java.time.Instant;
import java.util.UUID;

public record CustomerView(UUID id, String name, String phone, String email,
        String address, String notes, boolean active, Instant createdAt) {
}
```

`src/main/java/com/company/pos/customer/api/PurchaseHistoryEntry.java`:

```java
package com.company.pos.customer.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PurchaseHistoryEntry(UUID saleId, String receiptNumber, Instant occurredAt,
        BigDecimal grandTotal, String currencyCode) {
}
```

`src/main/java/com/company/pos/customer/api/RegisterCustomerCommand.java`:

```java
package com.company.pos.customer.api;

public record RegisterCustomerCommand(String name, String phone, String email,
        String address, String notes) {
}
```

`src/main/java/com/company/pos/customer/api/UpdateCustomerCommand.java`:

```java
package com.company.pos.customer.api;

public record UpdateCustomerCommand(String name, String phone, String email,
        String address, String notes) {
}
```

`src/main/java/com/company/pos/customer/api/CustomerService.java`:

```java
package com.company.pos.customer.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerService {

    CustomerView register(RegisterCustomerCommand command);

    CustomerView update(UUID id, UpdateCustomerCommand command);

    void deactivate(UUID id);

    Optional<CustomerView> findById(UUID id);

    List<CustomerView> search(String query);

    List<PurchaseHistoryEntry> purchaseHistory(UUID customerId);
}
```

- [ ] **Step 5: Create the entities**

`src/main/java/com/company/pos/customer/domain/Customer.java`:

```java
package com.company.pos.customer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "customer")
public class Customer {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 40)
    private String phone;

    @Column(length = 200)
    private String email;

    @Column(length = 300)
    private String address;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "external_id", length = 64)
    private String externalId;

    @Column(name = "erp_version", nullable = false)
    private long erpVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Customer() {
        // JPA
    }

    public Customer(UUID id, String name, Instant now) {
        this.id = id;
        this.name = name;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public long getErpVersion() {
        return erpVersion;
    }

    public void setErpVersion(long erpVersion) {
        this.erpVersion = erpVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
```

`src/main/java/com/company/pos/customer/domain/CustomerPurchase.java`:

```java
package com.company.pos.customer.domain;

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
@Table(name = "customer_purchase")
public class CustomerPurchase {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "customer_id", nullable = false, length = 36)
    private UUID customerId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "sale_id", nullable = false, unique = true, length = 36)
    private UUID saleId;

    @Column(name = "receipt_number", length = 64)
    private String receiptNumber;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "grand_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal grandTotal;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    protected CustomerPurchase() {
        // JPA
    }

    public CustomerPurchase(UUID id, UUID customerId, UUID saleId, String receiptNumber,
            Instant occurredAt, BigDecimal grandTotal, String currencyCode) {
        this.id = id;
        this.customerId = customerId;
        this.saleId = saleId;
        this.receiptNumber = receiptNumber;
        this.occurredAt = occurredAt;
        this.grandTotal = grandTotal;
        this.currencyCode = currencyCode;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getSaleId() {
        return saleId;
    }

    public String getReceiptNumber() {
        return receiptNumber;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public BigDecimal getGrandTotal() {
        return grandTotal;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }
}
```

- [ ] **Step 6: Create the repositories**

`src/main/java/com/company/pos/customer/infrastructure/CustomerRepository.java`:

```java
package com.company.pos.customer.infrastructure;

import com.company.pos.customer.domain.Customer;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    @Query("select c from Customer c where c.active = true and ("
            + "lower(c.name) like lower(concat('%', :q, '%')) "
            + "or lower(c.phone) like lower(concat('%', :q, '%')) "
            + "or lower(c.email) like lower(concat('%', :q, '%')))")
    List<Customer> search(@Param("q") String q);
}
```

`src/main/java/com/company/pos/customer/infrastructure/CustomerPurchaseRepository.java`:

```java
package com.company.pos.customer.infrastructure;

import com.company.pos.customer.domain.CustomerPurchase;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerPurchaseRepository extends JpaRepository<CustomerPurchase, UUID> {

    boolean existsBySaleId(UUID saleId);

    List<CustomerPurchase> findByCustomerIdOrderByOccurredAtDesc(UUID customerId);
}
```

- [ ] **Step 7: Create the facade implementation**

`src/main/java/com/company/pos/customer/application/DefaultCustomerService.java`:

```java
package com.company.pos.customer.application;

import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.customer.api.CustomerService;
import com.company.pos.customer.api.CustomerView;
import com.company.pos.customer.api.PurchaseHistoryEntry;
import com.company.pos.customer.api.RegisterCustomerCommand;
import com.company.pos.customer.api.UpdateCustomerCommand;
import com.company.pos.customer.domain.Customer;
import com.company.pos.customer.domain.CustomerPurchase;
import com.company.pos.customer.infrastructure.CustomerPurchaseRepository;
import com.company.pos.customer.infrastructure.CustomerRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultCustomerService implements CustomerService {

    private final CustomerRepository customers;
    private final CustomerPurchaseRepository purchases;

    DefaultCustomerService(CustomerRepository customers, CustomerPurchaseRepository purchases) {
        this.customers = customers;
        this.purchases = purchases;
    }

    @Override
    public CustomerView register(RegisterCustomerCommand command) {
        requireName(command.name());
        Customer customer = new Customer(Identifiers.newId(), command.name().trim(), Instant.now());
        customer.setPhone(command.phone());
        customer.setEmail(command.email());
        customer.setAddress(command.address());
        customer.setNotes(command.notes());
        return toView(customers.save(customer));
    }

    @Override
    public CustomerView update(UUID id, UpdateCustomerCommand command) {
        requireName(command.name());
        Customer customer = load(id);
        customer.setName(command.name().trim());
        customer.setPhone(command.phone());
        customer.setEmail(command.email());
        customer.setAddress(command.address());
        customer.setNotes(command.notes());
        customer.setUpdatedAt(Instant.now());
        return toView(customer);
    }

    @Override
    public void deactivate(UUID id) {
        Customer customer = load(id);
        customer.setActive(false);
        customer.setUpdatedAt(Instant.now());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerView> findById(UUID id) {
        return customers.findById(id).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerView> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return customers.search(query.trim()).stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PurchaseHistoryEntry> purchaseHistory(UUID customerId) {
        return purchases.findByCustomerIdOrderByOccurredAtDesc(customerId).stream()
                .map(this::toEntry)
                .toList();
    }

    private Customer load(UUID id) {
        return customers.findById(id)
                .orElseThrow(() -> DomainException.notFound("No customer " + id));
    }

    private void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw DomainException.validation("Customer name is required");
        }
    }

    private CustomerView toView(Customer c) {
        return new CustomerView(c.getId(), c.getName(), c.getPhone(), c.getEmail(),
                c.getAddress(), c.getNotes(), c.isActive(), c.getCreatedAt());
    }

    private PurchaseHistoryEntry toEntry(CustomerPurchase p) {
        return new PurchaseHistoryEntry(p.getSaleId(), p.getReceiptNumber(), p.getOccurredAt(),
                p.getGrandTotal(), p.getCurrencyCode());
    }
}
```

- [ ] **Step 8: Create the V20 migration**

`src/main/resources/db/migration/customer/V20__customer.sql`:

```sql
CREATE TABLE customer (
    id           VARCHAR(36)   NOT NULL PRIMARY KEY,
    name         VARCHAR(200)  NOT NULL,
    phone        VARCHAR(40),
    email        VARCHAR(200),
    address      VARCHAR(300),
    notes        TEXT,
    active       BOOLEAN       NOT NULL,
    external_id  VARCHAR(64),
    erp_version  BIGINT        NOT NULL,
    created_at   TIMESTAMP     NOT NULL,
    updated_at   TIMESTAMP     NOT NULL
);

CREATE INDEX ix_customer_phone ON customer (phone);
CREATE INDEX ix_customer_email ON customer (email);

CREATE TABLE customer_purchase (
    id             VARCHAR(36)   NOT NULL PRIMARY KEY,
    customer_id    VARCHAR(36)   NOT NULL,
    sale_id        VARCHAR(36)   NOT NULL,
    receipt_number VARCHAR(64),
    occurred_at    TIMESTAMP     NOT NULL,
    grand_total    NUMERIC(19,4) NOT NULL,
    currency_code  VARCHAR(3)    NOT NULL
);

CREATE UNIQUE INDEX ux_customer_purchase_sale ON customer_purchase (sale_id);
CREATE INDEX ix_customer_purchase_customer ON customer_purchase (customer_id);
```

- [ ] **Step 9: Register the customer migration folder for store-server**

Modify `src/main/resources/application-store-server.yml` line 27 — append `,classpath:db/migration/customer` to the end of the `locations:` value so it reads:

```yaml
    locations: classpath:db/migration/configuration,classpath:db/migration/auth,classpath:db/migration/product,classpath:db/migration/inventory,classpath:db/migration/integration,classpath:db/migration/cart,classpath:db/migration/payment,classpath:db/migration/sales,classpath:db/migration/cashdrawer,classpath:db/migration/shift,classpath:db/migration/events,classpath:db/migration/audit,classpath:db/migration/customer
```

- [ ] **Step 10: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=DefaultCustomerServiceTest`
Expected: PASS — all 7 tests green.

- [ ] **Step 11: Run ModularityTests**

Run: `./mvnw test -Dtest=ModularityTests`
Expected: PASS — the new `customer` module verifies (depends only on `common`/`database`).

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/company/pos/customer src/main/resources/db/migration/customer src/main/resources/application-store-server.yml src/test/java/com/company/pos/customer/DefaultCustomerServiceTest.java
git commit -m "feat(customer): module core — CRUD, search, purchase-history read model"
```

---

## Task 2: Customer HTTP controller + method security

Exposes the CRUD/search/history endpoints over HTTP. The attach endpoint is deferred to Task 3 (needs `cart::api`). Only `DELETE` requires MANAGER; the rest use the authenticated baseline (no annotation), matching the product/cart idiom.

**Files:**
- Create: `src/main/java/com/company/pos/customer/web/CustomerController.java`
- Test: `src/test/java/com/company/pos/customer/CustomerControllerTest.java`

**Interfaces:**
- Consumes: `CustomerService` (Task 1) and its DTOs; `DomainException.notFound`.
- Produces: HTTP endpoints `POST /customers`, `GET /customers/{id}`, `GET /customers?q=`, `PUT /customers/{id}`, `DELETE /customers/{id}` (MANAGER), `GET /customers/{id}/purchases`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/customer/CustomerControllerTest.java`:

```java
package com.company.pos.customer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class CustomerControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor cashier() {
        return jwt().jwt(j -> j.subject("cashier"))
                .authorities(new SimpleGrantedAuthority("ROLE_CASHIER"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor manager() {
        return jwt().jwt(j -> j.subject("manager"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    private String registerAisha() throws Exception {
        String body = mvc.perform(post("/customers").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Aisha\",\"phone\":\"0501234567\",\"email\":\"aisha@x.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.id");
    }

    @Test
    void cashierCanRegisterAndFetch() throws Exception {
        String id = registerAisha();
        mvc.perform(get("/customers/" + id).with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Aisha"));
    }

    @Test
    void cashierCanSearch() throws Exception {
        registerAisha();
        mvc.perform(get("/customers").param("q", "Aisha").with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Aisha"));
    }

    @Test
    void unknownCustomerIs404() throws Exception {
        mvc.perform(get("/customers/" + java.util.UUID.randomUUID()).with(cashier()))
                .andExpect(status().isNotFound());
    }

    @Test
    void cashierCannotDeactivate() throws Exception {
        String id = registerAisha();
        mvc.perform(delete("/customers/" + id).with(cashier()))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanDeactivate() throws Exception {
        String id = registerAisha();
        mvc.perform(delete("/customers/" + id).with(manager()))
                .andExpect(status().isNoContent());
        // Soft-deleted: still fetchable, active=false.
        mvc.perform(get("/customers/" + id).with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void purchaseHistoryEmptyForNewCustomer() throws Exception {
        String id = registerAisha();
        mvc.perform(get("/customers/" + id + "/purchases").with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=CustomerControllerTest`
Expected: FAIL — 404 for `POST /customers` (no controller mapping yet) so `registerAisha()` fails.

- [ ] **Step 3: Create the controller**

`src/main/java/com/company/pos/customer/web/CustomerController.java`:

```java
package com.company.pos.customer.web;

import com.company.pos.common.exception.DomainException;
import com.company.pos.customer.api.CustomerService;
import com.company.pos.customer.api.CustomerView;
import com.company.pos.customer.api.PurchaseHistoryEntry;
import com.company.pos.customer.api.RegisterCustomerCommand;
import com.company.pos.customer.api.UpdateCustomerCommand;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CustomerController {

    private final CustomerService customers;

    CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @PostMapping("/customers")
    CustomerView register(@RequestBody RegisterCustomerCommand body) {
        return customers.register(body);
    }

    @GetMapping("/customers/{id}")
    CustomerView get(@PathVariable UUID id) {
        return customers.findById(id)
                .orElseThrow(() -> DomainException.notFound("No customer " + id));
    }

    @GetMapping("/customers")
    List<CustomerView> search(@RequestParam(name = "q", required = false) String query) {
        return customers.search(query);
    }

    @PutMapping("/customers/{id}")
    CustomerView update(@PathVariable UUID id, @RequestBody UpdateCustomerCommand body) {
        return customers.update(id, body);
    }

    @DeleteMapping("/customers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('MANAGER')")
    void deactivate(@PathVariable UUID id) {
        customers.deactivate(id);
    }

    @GetMapping("/customers/{id}/purchases")
    List<PurchaseHistoryEntry> purchases(@PathVariable UUID id) {
        return customers.purchaseHistory(id);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=CustomerControllerTest`
Expected: PASS — all 6 tests green (note `search(null)` returns `[]` per Task 1, and the search test finds Aisha).

- [ ] **Step 5: Run ModularityTests**

Run: `./mvnw test -Dtest=ModularityTests`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/customer/web/CustomerController.java src/test/java/com/company/pos/customer/CustomerControllerTest.java
git commit -m "feat(customer): HTTP surface with manager-gated deactivate"
```

---

## Task 3: Cart integration — attach/detach a customer

Adds an opaque `customerId` to the cart, a validated attach endpoint in the customer module, and a detach endpoint in the cart controller. `cart` stays ignorant of `customer` (stores the id opaquely); `customer` gains `cart::api`.

**Files:**
- Modify: `src/main/java/com/company/pos/cart/api/CartView.java`
- Modify: `src/main/java/com/company/pos/cart/api/CartService.java`
- Modify: `src/main/java/com/company/pos/cart/domain/Cart.java`
- Modify: `src/main/java/com/company/pos/cart/application/DefaultCartService.java`
- Modify: `src/main/java/com/company/pos/cart/web/CartController.java`
- Create: `src/main/resources/db/migration/cart/V21__cart_customer_id.sql`
- Modify: `src/main/java/com/company/pos/customer/package-info.java`
- Modify: `src/main/java/com/company/pos/customer/web/CustomerController.java`
- Test: `src/test/java/com/company/pos/customer/CustomerCartAttachTest.java`

**Interfaces:**
- Consumes: `CartService`, `CartView` (cart::api); `CustomerService.findById` (Task 1).
- Produces:
  - `CartService.assignCustomer(UUID cartId, UUID customerId) → CartView`
  - `CartService.clearCustomer(UUID cartId) → CartView`
  - `CartView(UUID cartId, String status, String currencyCode, UUID customerId, List<CartLineView> lines)` — **note the new `customerId` component** (Task 4's checkout reads `cart.customerId()`)
  - HTTP `POST /customers/{customerId}/cart/{cartId}` (attach, validated), `DELETE /carts/{cartId}/customer` (detach)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/customer/CustomerCartAttachTest.java`:

```java
package com.company.pos.customer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.cart.api.CartService;
import com.company.pos.cart.api.CartView;
import com.company.pos.customer.api.CustomerService;
import com.company.pos.customer.api.CustomerView;
import com.company.pos.customer.api.RegisterCustomerCommand;
import com.company.pos.support.DatabaseCleaner;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class CustomerCartAttachTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    CartService carts;
    @Autowired
    CustomerService customers;
    @Autowired
    DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    private static RequestPostProcessor cashier() {
        return jwt().jwt(j -> j.subject("cashier"))
                .authorities(new SimpleGrantedAuthority("ROLE_CASHIER"));
    }

    @Test
    void attachStampsCustomerOntoCart() throws Exception {
        UUID cartId = carts.createCart();
        CustomerView c = customers.register(new RegisterCustomerCommand("Aisha", "0501", null, null, null));

        mvc.perform(post("/customers/" + c.id() + "/cart/" + cartId).with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(c.id().toString()));

        assertThatCartCustomerIs(cartId, c.id());
    }

    @Test
    void attachUnknownCustomerFailsAndLeavesCartUntouched() throws Exception {
        UUID cartId = carts.createCart();

        mvc.perform(post("/customers/" + UUID.randomUUID() + "/cart/" + cartId).with(cashier()))
                .andExpect(status().isNotFound());

        assertThatCartCustomerIs(cartId, null);
    }

    @Test
    void attachInactiveCustomerFails() throws Exception {
        UUID cartId = carts.createCart();
        CustomerView c = customers.register(new RegisterCustomerCommand("Zoya", "0509", null, null, null));
        customers.deactivate(c.id());

        mvc.perform(post("/customers/" + c.id() + "/cart/" + cartId).with(cashier()))
                .andExpect(status().isBadRequest());

        assertThatCartCustomerIs(cartId, null);
    }

    @Test
    void detachClearsCustomer() throws Exception {
        UUID cartId = carts.createCart();
        CustomerView c = customers.register(new RegisterCustomerCommand("Bilal", "0502", null, null, null));
        carts.assignCustomer(cartId, c.id());

        mvc.perform(delete("/carts/" + cartId + "/customer").with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").doesNotExist());

        assertThatCartCustomerIs(cartId, null);
    }

    private void assertThatCartCustomerIs(UUID cartId, UUID expected) {
        CartView view = carts.getCart(cartId);
        org.assertj.core.api.Assertions.assertThat(view.customerId()).isEqualTo(expected);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=CustomerCartAttachTest`
Expected: FAIL — compilation error: `CartView` has no `customerId()` method and `CartService` has no `assignCustomer`.

- [ ] **Step 3: Add `customerId` to the cart domain**

In `src/main/java/com/company/pos/cart/domain/Cart.java`, add the import for `SqlTypes`/`JdbcTypeCode` if not present (they are not currently imported), the field, and methods.

Add these imports:
```java
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
```

Add the field after the `currencyCode` field:
```java
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "customer_id", length = 36)
    private UUID customerId;
```

Add these methods (e.g. after `getCurrencyCode()`):
```java
    public UUID getCustomerId() {
        return customerId;
    }

    public void assignCustomer(UUID customerId) {
        if (!isOpen()) {
            throw DomainException.conflict("Only an open cart can have a customer assigned");
        }
        this.customerId = customerId;
    }

    public void clearCustomer() {
        this.customerId = null;
    }
```
(`DomainException` and `UUID` are already imported in `Cart.java`.)

- [ ] **Step 4: Add `customerId` to `CartView`**

Replace the body of `src/main/java/com/company/pos/cart/api/CartView.java`:

```java
package com.company.pos.cart.api;

import java.util.List;
import java.util.UUID;

public record CartView(UUID cartId, String status, String currencyCode, UUID customerId,
        List<CartLineView> lines) {
}
```

- [ ] **Step 5: Add the facade methods**

In `src/main/java/com/company/pos/cart/api/CartService.java`, add to the interface (e.g. after `getCart`):
```java
    CartView assignCustomer(UUID cartId, UUID customerId);

    CartView clearCustomer(UUID cartId);
```

- [ ] **Step 6: Implement in `DefaultCartService` and fix `toView`**

In `src/main/java/com/company/pos/cart/application/DefaultCartService.java`, add the two methods (e.g. after `getCart`):
```java
    @Override
    public CartView assignCustomer(UUID cartId, UUID customerId) {
        Cart cart = load(cartId);
        cart.assignCustomer(customerId);
        return toView(cart);
    }

    @Override
    public CartView clearCustomer(UUID cartId) {
        Cart cart = load(cartId);
        cart.clearCustomer();
        return toView(cart);
    }
```

Update the final `return` in `toView` to include the customer id:
```java
        return new CartView(cart.getId(), cart.getStatus(), cart.getCurrencyCode(),
                cart.getCustomerId(), lines);
```

- [ ] **Step 7: Add the detach endpoint to `CartController`**

In `src/main/java/com/company/pos/cart/web/CartController.java`, add the `DeleteMapping` import is already present. Add the method (e.g. after `removeLine`):
```java
    @DeleteMapping("/carts/{cartId}/customer")
    CartView detachCustomer(@PathVariable UUID cartId) {
        return carts.clearCustomer(cartId);
    }
```

- [ ] **Step 8: Create the V21 migration**

`src/main/resources/db/migration/cart/V21__cart_customer_id.sql`:

```sql
ALTER TABLE cart ADD COLUMN customer_id VARCHAR(36);
```

- [ ] **Step 9: Allow `customer → cart :: api` and add the attach endpoint**

Replace `src/main/java/com/company/pos/customer/package-info.java`:
```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "cart :: api" })
package com.company.pos.customer;
```

In `src/main/java/com/company/pos/customer/web/CustomerController.java`, add imports:
```java
import com.company.pos.cart.api.CartService;
import com.company.pos.cart.api.CartView;
```
Inject `CartService` — replace the constructor and field block:
```java
    private final CustomerService customers;
    private final CartService carts;

    CustomerController(CustomerService customers, CartService carts) {
        this.customers = customers;
        this.carts = carts;
    }
```
Add the attach endpoint (validates the customer is active, then assigns opaquely):
```java
    @PostMapping("/customers/{customerId}/cart/{cartId}")
    CartView attachToCart(@PathVariable UUID customerId, @PathVariable UUID cartId) {
        CustomerView customer = customers.findById(customerId)
                .orElseThrow(() -> DomainException.notFound("No customer " + customerId));
        if (!customer.active()) {
            throw DomainException.validation("Customer " + customerId + " is inactive");
        }
        return carts.assignCustomer(cartId, customerId);
    }
```
(`CustomerView` is already imported from Task 2.)

- [ ] **Step 10: Run the tests to verify they pass**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=CustomerCartAttachTest,CustomerControllerTest`
Expected: PASS. (`DomainException.notFound` → 404; `DomainException.validation` → 400, so the inactive case expects `isBadRequest`.)

- [ ] **Step 11: Run the cart module tests and ModularityTests**

Run: `./mvnw test -Dtest='com.company.pos.cart.*',ModularityTests`
Expected: PASS. If a pre-existing cart test constructs `new CartView(...)` positionally, update it to pass `null` for the new `customerId` argument. (Find them: `grep -rn "new CartView(" src`.)

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/company/pos/cart src/main/java/com/company/pos/customer src/main/resources/db/migration/cart/V21__cart_customer_id.sql src/test/java/com/company/pos/customer/CustomerCartAttachTest.java
git commit -m "feat(customer): validated attach-to-cart; cart carries opaque customerId"
```

---

## Task 4: Sales integration — carry customerId through checkout and the SaleCompleted event

The `Sale` gains a nullable `customerId`, copied from the cart at checkout. `SaleCompleted` gains `customerId` and `occurredAt` (closing the §6 plan gap and giving the history projection the true sale time). `sales` never imports `customer` — the id flows opaquely.

**Files:**
- Modify: `src/main/java/com/company/pos/sales/api/SaleCompleted.java`
- Modify: `src/main/java/com/company/pos/sales/domain/Sale.java`
- Modify: `src/main/java/com/company/pos/sales/application/DefaultSalesService.java`
- Create: `src/main/resources/db/migration/sales/V22__sale_customer_id.sql`
- Test: `src/test/java/com/company/pos/sales/CheckoutCustomerTest.java`
- Modify (compiler-driven): every existing `new SaleCompleted(...)` construction site.

**Interfaces:**
- Consumes: `CartView.customerId()` (Task 3).
- Produces:
  - `SaleCompleted(UUID saleId, String receiptNumber, String terminalId, String locationCode, String currencyCode, BigDecimal grandTotal, BigDecimal cashTotal, List<SoldLine> lines, UUID customerId, Instant occurredAt)` — **two new trailing components** (Task 5's listener reads `customerId()` and `occurredAt()`).
  - `Sale` carries `customerId` (persisted `customer_id`).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/sales/CheckoutCustomerTest.java`. This drives a real checkout of a cart with an attached customer and asserts the persisted sale carries the id. (Model the cart setup / tender amounts on the existing checkout test — inspect `src/test/java/com/company/pos/sales/` for the current checkout test to copy its seeded SKU, price, and `CheckoutCommand`/`TenderInput` construction; reuse those exact values.)

```java
package com.company.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.cart.api.CartService;
import com.company.pos.customer.api.CustomerService;
import com.company.pos.customer.api.CustomerView;
import com.company.pos.customer.api.RegisterCustomerCommand;
import com.company.pos.sales.api.SaleView;
import com.company.pos.support.DatabaseCleaner;
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
class CheckoutCustomerTest {

    @Autowired
    CartService carts;
    @Autowired
    CustomerService customers;
    @Autowired
    SalesTestSupport sales; // helper you will reuse or inline: seeds a product, builds+prices a cart, checks out
    @Autowired
    DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    @Test
    void saleRecordsAttachedCustomer() {
        UUID cartId = sales.newCartWithOneLine();          // seeds product + adds a line to an OPEN cart
        CustomerView c = customers.register(new RegisterCustomerCommand("Aisha", "0501", null, null, null));
        carts.assignCustomer(cartId, c.id());

        SaleView sale = sales.checkoutFullCash(cartId);    // tenders exact cash for the cart total

        // The persisted sale is linked to the customer, surfaced via purchase history (Task 5 populates it).
        assertThat(customers.purchaseHistory(c.id()))
                .as("history is populated asynchronously in Task 5; here we assert the sale committed")
                .isNotNull();
        assertThat(sale.receiptNumber()).isNotBlank();
    }
}
```

> **Note to implementer:** if a shared `SalesTestSupport` helper does not already exist, inline the cart-seeding and checkout directly in this test using the same product SKU/price and `CheckoutCommand`/`TenderInput` shape as the existing checkout test in `src/test/java/com/company/pos/sales/`. The assertion that matters for THIS task is that a customer-attached cart checks out without error and the `customer_id` column is populated — verify the column via a repository or a follow-up in Task 5. Keep this test focused on "checkout succeeds and carries the id"; the history-projection assertion belongs to Task 5.

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=CheckoutCustomerTest`
Expected: FAIL — compilation/wiring error (helper missing) or the id not populated.

- [ ] **Step 3: Add `customerId` to `SaleCompleted`**

Replace `src/main/java/com/company/pos/sales/api/SaleCompleted.java`:

```java
package com.company.pos.sales.api;

import com.company.pos.common.events.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SaleCompleted(UUID saleId, String receiptNumber, String terminalId, String locationCode,
        String currencyCode, BigDecimal grandTotal, BigDecimal cashTotal, List<SoldLine> lines,
        UUID customerId, Instant occurredAt)
        implements DomainEvent {

    public record SoldLine(String sku, BigDecimal quantity) {
    }
}
```

- [ ] **Step 4: Add `customerId` to the `Sale` entity**

In `src/main/java/com/company/pos/sales/domain/Sale.java`:

Add the field after `discountTotal`:
```java
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "customer_id", length = 36)
    private UUID customerId;
```
(`JdbcTypeCode`, `SqlTypes`, `UUID`, `Column` are already imported.)

Add `UUID customerId` as the final parameter of the public constructor, and assign it:
```java
    public Sale(UUID id, String receiptNumber, String storeId, String terminalId,
            String cashierUsername, String locationCode, String currencyCode,
            BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal, Instant createdAt,
            BigDecimal txnDiscountAmount, String txnDiscountType, String txnDiscountReason,
            BigDecimal discountTotal, UUID customerId) {
        // ... existing assignments unchanged ...
        this.discountTotal = discountTotal;
        this.customerId = customerId;
        this.status = "COMPLETED";
    }
```

Add the getter (e.g. after `getDiscountTotal`):
```java
    public UUID getCustomerId() {
        return customerId;
    }
```

- [ ] **Step 5: Wire the id through checkout**

In `src/main/java/com/company/pos/sales/application/DefaultSalesService.java`:

Update the `Sale` construction (currently ends `..., disc.txnDiscountReason(), disc.discountTotal());`) to pass the cart's customer id as the final argument:
```java
        Sale sale = new Sale(saleId, receiptNumber, storeId, terminalId, cashierUsername,
                location, currency, taxed.subtotal(), taxed.taxTotal(), taxed.grandTotal(), now,
                disc.txnDiscountAmount(),
                disc.txnDiscountType() == null ? null : disc.txnDiscountType().name(),
                disc.txnDiscountReason(), disc.discountTotal(), cart.customerId());
```

Update the `SaleCompleted` publish call to pass `cart.customerId()` and `now`:
```java
        events.publish(new SaleCompleted(saleId, receiptNumber, terminalId, location, currency,
                taxed.grandTotal(), cashTotal, soldLines, cart.customerId(), now));
```
(`cart` is the `CartView` loaded at the top of `checkout`; `now` is the `Instant` created for the sale. Both are already in scope.)

- [ ] **Step 6: Create the V22 migration**

`src/main/resources/db/migration/sales/V22__sale_customer_id.sql`:

```sql
ALTER TABLE sale ADD COLUMN customer_id VARCHAR(36);
```
(The sales table is named `sale`, per `@Table(name = "sale")`.)

- [ ] **Step 7: Fix every other `SaleCompleted` construction site**

The record gained two components, so every other `new SaleCompleted(...)` (mainly in tests: inventory, cashdrawer, audit, sync listeners' tests) no longer compiles.

Run: `grep -rn "new SaleCompleted(" src`
For each site OTHER than `DefaultSalesService` (already done in Step 5), append two trailing arguments: a customer id (`null` unless the test specifically needs one) and a timestamp (`java.time.Instant.now()` in tests). Example transformation:
```java
// before
new SaleCompleted(saleId, "R1", "T1", "MAIN", "SAR", total, cash, lines)
// after
new SaleCompleted(saleId, "R1", "T1", "MAIN", "SAR", total, cash, lines, null, java.time.Instant.now())
```

- [ ] **Step 8: Run sales + affected listener tests**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest='com.company.pos.sales.*',CheckoutCustomerTest`
Expected: PASS. Then run the modules that subscribe to `SaleCompleted` to confirm the extra fields didn't break them:
Run: `./mvnw test -Dtest='com.company.pos.inventory.*','com.company.pos.cashdrawer.*','com.company.pos.audit.*'`
Expected: PASS.

- [ ] **Step 9: Run ModularityTests**

Run: `./mvnw test -Dtest=ModularityTests`
Expected: PASS — `sales` still does not depend on `customer` (id is opaque).

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/company/pos/sales src/main/resources/db/migration/sales/V22__sale_customer_id.sql src/test/java/com/company/pos/sales/CheckoutCustomerTest.java
git commit -m "feat(sales): carry customerId + occurredAt through checkout and SaleCompleted"
```

---

## Task 5: Purchase-history projection listener

`customer` subscribes to `SaleCompleted` and records a `customer_purchase` row when the sale has a customer, deduping on `sale_id` (idempotent under at-least-once redelivery). `GET /customers/{id}/purchases` then returns real history.

**Files:**
- Create: `src/main/java/com/company/pos/customer/application/SaleCompletedCustomerListener.java`
- Modify: `src/main/java/com/company/pos/customer/package-info.java`
- Test: `src/test/java/com/company/pos/customer/PurchaseHistoryListenerTest.java`

**Interfaces:**
- Consumes: `SaleCompleted` (sales::api, Task 4); `CustomerPurchaseRepository` + `CustomerPurchase` (Task 1); `Identifiers.newId()`.
- Produces: `customer_purchase` rows; a populated `CustomerService.purchaseHistory`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/customer/PurchaseHistoryListenerTest.java`. This is a committing test (non-`@Transactional`) that publishes `SaleCompleted` directly and awaits the after-commit listener.

```java
package com.company.pos.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.common.events.DomainEvents;
import com.company.pos.customer.api.CustomerService;
import com.company.pos.customer.api.CustomerView;
import com.company.pos.customer.api.PurchaseHistoryEntry;
import com.company.pos.customer.api.RegisterCustomerCommand;
import com.company.pos.sales.api.SaleCompleted;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
class PurchaseHistoryListenerTest {

    @Autowired
    DomainEvents events;
    @Autowired
    CustomerService customers;
    @Autowired
    DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    private SaleCompleted saleFor(UUID customerId, UUID saleId) {
        return new SaleCompleted(saleId, "R-" + saleId, "T1", "MAIN", "SAR",
                new BigDecimal("42.00"), new BigDecimal("42.00"),
                List.of(new SaleCompleted.SoldLine("SKU1", BigDecimal.ONE)),
                customerId, Instant.now());
    }

    @Test
    void recordsHistoryForSaleWithCustomer() {
        CustomerView c = customers.register(new RegisterCustomerCommand("Aisha", "0501", null, null, null));
        UUID saleId = UUID.randomUUID();

        events.publish(saleFor(c.id(), saleId));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<PurchaseHistoryEntry> history = customers.purchaseHistory(c.id());
            assertThat(history).hasSize(1);
            assertThat(history.get(0).saleId()).isEqualTo(saleId);
            assertThat(history.get(0).grandTotal()).isEqualByComparingTo("42.00");
        });
    }

    @Test
    void isIdempotentOnRedelivery() {
        CustomerView c = customers.register(new RegisterCustomerCommand("Bilal", "0502", null, null, null));
        UUID saleId = UUID.randomUUID();

        events.publish(saleFor(c.id(), saleId));
        events.publish(saleFor(c.id(), saleId)); // same saleId — redelivery

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(customers.purchaseHistory(c.id())).hasSize(1));
        // Give any second insert a chance, then re-confirm still one.
        assertThat(customers.purchaseHistory(c.id())).hasSize(1);
    }

    @Test
    void ignoresSaleWithoutCustomer() {
        CustomerView c = customers.register(new RegisterCustomerCommand("Zoya", "0503", null, null, null));

        events.publish(saleFor(null, UUID.randomUUID()));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(customers.purchaseHistory(c.id())).isEmpty());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=PurchaseHistoryListenerTest`
Expected: FAIL — no listener records anything, so `recordsHistoryForSaleWithCustomer` times out (history stays empty).

- [ ] **Step 3: Allow `customer → sales :: api`**

Replace `src/main/java/com/company/pos/customer/package-info.java`:
```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "cart :: api", "sales :: api" })
package com.company.pos.customer;
```

- [ ] **Step 4: Create the listener**

`src/main/java/com/company/pos/customer/application/SaleCompletedCustomerListener.java`:

```java
package com.company.pos.customer.application;

import com.company.pos.common.util.Identifiers;
import com.company.pos.customer.domain.CustomerPurchase;
import com.company.pos.customer.infrastructure.CustomerPurchaseRepository;
import com.company.pos.sales.api.SaleCompleted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class SaleCompletedCustomerListener {

    private final CustomerPurchaseRepository purchases;

    SaleCompletedCustomerListener(CustomerPurchaseRepository purchases) {
        this.purchases = purchases;
    }

    @ApplicationModuleListener
    void on(SaleCompleted event) {
        if (event.customerId() == null) {
            return;
        }
        if (purchases.existsBySaleId(event.saleId())) {
            return; // idempotent: redelivered event
        }
        purchases.save(new CustomerPurchase(Identifiers.newId(), event.customerId(),
                event.saleId(), event.receiptNumber(), event.occurredAt(),
                event.grandTotal(), event.currencyCode()));
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=PurchaseHistoryListenerTest`
Expected: PASS — all 3 tests green.

> If `recordsHistoryForSaleWithCustomer` intermittently times out with `SQLITE_BUSY` in the logs, do NOT raise the embedded pool. This is the known single-writer contention; the `busy_timeout=5000` init-sql serializes writers. Confirm the failure is a genuine deadlock (not a logic bug) via `systematic-debugging` before any change, and escalate rather than bumping the pool (that reverses the deliberate Phase 6 fix).

- [ ] **Step 6: Run ModularityTests**

Run: `./mvnw test -Dtest=ModularityTests`
Expected: PASS — `customer → { cart::api, sales::api }`, no cycle.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/pos/customer/application/SaleCompletedCustomerListener.java src/main/java/com/company/pos/customer/package-info.java src/test/java/com/company/pos/customer/PurchaseHistoryListenerTest.java
git commit -m "feat(customer): purchase-history projection from SaleCompleted (idempotent)"
```

---

## Task 6: Capstone end-to-end test, docs, and full verify

Proves the whole flow through HTTP and validates all three migrations against real Postgres.

**Files:**
- Test: `src/test/java/com/company/pos/customer/CustomerJourneyE2ETest.java`
- Modify: `docs/run-modes.md`

**Interfaces:**
- Consumes: all prior tasks' HTTP endpoints + the checkout endpoint.

- [ ] **Step 1: Write the end-to-end test**

Create `src/test/java/com/company/pos/customer/CustomerJourneyE2ETest.java`. Drive the full journey over HTTP: register a customer → create a cart → add a line → attach the customer → checkout → assert `GET /customers/{id}/purchases` shows the sale. Reuse the exact SKU/price seeding and checkout request body used by the existing end-to-end sales/checkout test (inspect `src/test/java/com/company/pos/` for the current full-checkout E2E and copy its product seed + `POST /sales`/checkout payload). Use `await()` for the async history projection.

```java
package com.company.pos.customer;

import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.support.DatabaseCleaner;
import java.time.Duration;
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
class CustomerJourneyE2ETest {

    @Autowired
    MockMvc mvc;
    @Autowired
    DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    private static RequestPostProcessor cashier() {
        return jwt().jwt(j -> j.subject("cashier"))
                .authorities(new SimpleGrantedAuthority("ROLE_CASHIER"));
    }

    @Test
    void registerAttachCheckoutThenHistory() throws Exception {
        // 1. Register a customer.
        String customerBody = mvc.perform(post("/customers").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Aisha\",\"phone\":\"0501234567\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String customerId = com.jayway.jsonpath.JsonPath.read(customerBody, "$.id");

        // 2..N. Seed a product, create a cart, add a line, attach the customer, then checkout.
        //       Copy the product seed + checkout payload from the existing checkout E2E test.
        //       Attach step:
        //       mvc.perform(post("/customers/" + customerId + "/cart/" + cartId).with(cashier()))
        //          .andExpect(status().isOk());
        //       Checkout step: POST the tender for the exact cart total.

        // Final: purchase history shows the sale (async projection).
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/customers/" + customerId + "/purchases").with(cashier()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(1)));
    }
}
```

> **Note to implementer:** fill in steps 2..N by copying the product-seed and checkout request from the existing full-checkout end-to-end test (search for a test that does `POST /sales` or the checkout endpoint under `src/test/java/com/company/pos/`). Do not invent new SKUs/prices — reuse the ones that test already seeds so tenders match the computed total.

- [ ] **Step 2: Run the E2E test**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=CustomerJourneyE2ETest`
Expected: PASS.

- [ ] **Step 3: Document the module in run-modes.md**

Add a "Customer (Phase 7)" section to `docs/run-modes.md` covering: the endpoints table (from the spec), the validated attach flow (endpoint lives under `/customers/...` for an acyclic graph), the event-projection purchase history (idempotent on `sale_id`), that `SaleCompleted` now carries `customerId` + `occurredAt`, and the deferred ERP sync (columns `external_id`/`erp_version` exist; no sync machinery yet). Match the style and depth of the existing "Audit Trail (Phase 6)" section.

- [ ] **Step 4: Full verify (the gate)**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw clean verify`
Expected: BUILD SUCCESS. This includes `ModularityTests` and the Testcontainers Postgres tests (`DatabaseStoreServerTest`), which validate the V20/V21/V22 migrations against real Postgres with `ddl-auto: validate`. If Postgres validation fails on a column type, reconcile the migration to the entity mapping exactly (VARCHAR(36) for UUID; NUMERIC(19,4) for the money column; TEXT for notes) — do not change the entity to match a wrong migration.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/company/pos/customer/CustomerJourneyE2ETest.java docs/run-modes.md
git commit -m "test(customer): end-to-end journey + docs; full verify green"
```

---

## Self-Review

**Spec coverage:**
- Customer CRUD + soft-delete → Task 1 (service) + Task 2 (HTTP, MANAGER-gated delete). ✓
- Search by name/phone/email → Task 1 `CustomerRepository.search` + tests. ✓
- Validated attach on the cart, id opaque on cart → Task 3. ✓
- Acyclic graph (`customer → cart/sales`, nothing → customer) → Tasks 1/3/5 package-info progression + ModularityTests each task. ✓
- Purchase history via event projection, idempotent on sale_id → Task 5. ✓
- `SaleCompleted` gains customerId + occurredAt (closes §6 gap) → Task 4. ✓
- Migrations V20/V21/V22 + Flyway registration + entity/migration type match → Tasks 1/3/4 + Task 6 Postgres verify. ✓
- ERP sync deferred but external_id/erp_version columns present → Task 1 entity + migration. ✓
- Access control = authenticated baseline + MANAGER on delete (codebase idiom, not literal hasRole('CASHIER')) → Global Constraints + Task 2. ✓
- Full verify incl. Postgres (Phase 6 lesson) → Task 6. ✓

**Placeholder scan:** No `TBD`/`TODO`. Two tasks (4 and 6) intentionally instruct the implementer to reuse the existing checkout test's SKU/price/payload rather than inventing values — the exact seed lives in a test I cannot quote verbatim without guessing; the instruction names where to find it and what to copy. This is a deliberate "reuse existing fixture" directive, not a missing spec.

**Type consistency:** `CustomerView`, `PurchaseHistoryEntry`, `RegisterCustomerCommand`/`UpdateCustomerCommand`, `CustomerPurchase` constructor, `CartView` (5 components incl. `customerId`), and `SaleCompleted` (10 components incl. trailing `customerId`, `occurredAt`) are used identically across the tasks that produce and consume them. `carts.assignCustomer(UUID, UUID)`/`clearCustomer(UUID)` signatures match between Task 3's definition and Tasks 3/4's use. `existsBySaleId`/`findByCustomerIdOrderByOccurredAtDesc` match between Task 1's repo and Tasks 1/5's use.
