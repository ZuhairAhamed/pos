# Phase 10 — Dining Floor (Tables & Open Orders) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `dining` module so a server can open a shared, store-wide dine-in order against a configured table, add lines with prep notes and course tags over time, and close the whole table as one bill through the existing checkout pipeline.

**Architecture:** `dining` is a Tier-2 hexagonal module (`api`/`web`/`application`/`domain`/`infrastructure`) that owns its own `DiningTable` / `DiningOrder` / `OrderLine` aggregate. It orchestrates other modules only through their `api` facades and reuses `SalesService.checkout(...)` at close by materializing order lines into a throwaway cart. `sales` and `cart` are not modified.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Modulith, Spring Data JPA, Flyway (store-server only), embedded SQLite (tests), JUnit 5 + AssertJ + MockMvc.

## Global Constraints

- **JDK 21.** Set `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` before any Maven command.
- **Money is `BigDecimal`** — never `double`. Quantities are `BigDecimal`.
- **Module boundaries are enforced.** `dining`'s `package-info.java` must declare `allowedDependencies`, and it may import only other modules' `:: api` named interfaces. Run `./mvnw test -Dtest=ModularityTests` after every task.
- **Authorization is method security** (`@PreAuthorize`), not URL rules. Roles: `CASHIER`, `MANAGER`, `ADMIN` (checked as `ROLE_*`).
- **Flyway versions are globally sequential.** Current max is **V22**; this phase uses **V23, V24, V25**. Only `store-server` runs Flyway; `embedded` uses Hibernate `ddl-auto` (tests run on `embedded`).
- **Typed config** lives in `configuration :: api SettingKey`, read via `ConfigurationService` — never hardcoded.
- **UUID PKs** are stored as `VARCHAR(36)` via `@JdbcTypeCode(SqlTypes.VARCHAR)` (SQLite has no UUID type).
- Tests are `@SpringBootTest @ActiveProfiles("embedded")`. Committing (non-`@Transactional`) tests must `@Import(DatabaseCleaner.class)` and clean before/after.

---

### Task 1: `dining` module scaffold — api layer + boundaries

Creates the public surface (enums, DTOs, commands, facade interface) and the module declaration, then proves the module is recognized and boundaries verify. No implementation yet.

**Files:**
- Create: `src/main/java/com/company/pos/dining/package-info.java`
- Create: `src/main/java/com/company/pos/dining/api/ServiceType.java`
- Create: `src/main/java/com/company/pos/dining/api/CourseTag.java`
- Create: `src/main/java/com/company/pos/dining/api/OrderStatus.java`
- Create: `src/main/java/com/company/pos/dining/api/TableView.java`
- Create: `src/main/java/com/company/pos/dining/api/OrderLineView.java`
- Create: `src/main/java/com/company/pos/dining/api/OrderView.java`
- Create: `src/main/java/com/company/pos/dining/api/OpenOrderView.java`
- Create: `src/main/java/com/company/pos/dining/api/RegisterTableCommand.java`
- Create: `src/main/java/com/company/pos/dining/api/OpenOrderCommand.java`
- Create: `src/main/java/com/company/pos/dining/api/AddLineCommand.java`
- Create: `src/main/java/com/company/pos/dining/api/CloseOrderCommand.java`
- Create: `src/main/java/com/company/pos/dining/api/DiningService.java`
- Modify: `src/test/java/com/company/pos/ModularityTests.java`

**Interfaces:**
- Consumes: `com.company.pos.sales.api.{TenderInput, DiscountInput}` (in `CloseOrderCommand`), `com.company.pos.sales.api.SaleView` (return of `closeOrder`).
- Produces: the entire `dining.api` surface used by every later task. Exact signatures below.

- [ ] **Step 1: Write the failing test** — assert the module exists and boundaries verify.

Add to `src/test/java/com/company/pos/ModularityTests.java`:

```java
    @Test
    void detectsTheDiningModule() {
        Set<String> names = modules.stream()
                .map(ApplicationModule::getName)
                .collect(Collectors.toSet());
        assertThat(names).contains("dining");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=ModularityTests#detectsTheDiningModule`
Expected: FAIL — no `dining` module found (package does not exist yet).

- [ ] **Step 3: Create the module declaration** — `dining/package-info.java`

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
            "common", "database",
            "product :: api", "cart :: api", "sales :: api", "configuration :: api" })
package com.company.pos.dining;
```

- [ ] **Step 4: Create the enums**

`dining/api/ServiceType.java`:
```java
package com.company.pos.dining.api;

public enum ServiceType {
    QUICK_SERVICE,
    DINE_IN
}
```

`dining/api/CourseTag.java`:
```java
package com.company.pos.dining.api;

public enum CourseTag {
    STARTER,
    MAIN,
    DESSERT,
    DRINK
}
```

`dining/api/OrderStatus.java`:
```java
package com.company.pos.dining.api;

public enum OrderStatus {
    OPEN,
    CLOSED,
    VOIDED
}
```

- [ ] **Step 5: Create the DTOs**

`dining/api/TableView.java`:
```java
package com.company.pos.dining.api;

import java.util.UUID;

public record TableView(UUID id, String label, int seats, boolean active) {
}
```

`dining/api/OrderLineView.java`:
```java
package com.company.pos.dining.api;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderLineView(UUID id, String sku, BigDecimal qty, String note, CourseTag course) {
}
```

`dining/api/OrderView.java`:
```java
package com.company.pos.dining.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderView(UUID id, UUID tableId, ServiceType serviceType, OrderStatus status,
        String openedBy, Instant openedAt, Instant closedAt, UUID saleId,
        List<OrderLineView> lines) {
}
```

`dining/api/OpenOrderView.java`:
```java
package com.company.pos.dining.api;

import java.time.Instant;
import java.util.UUID;

public record OpenOrderView(UUID orderId, UUID tableId, String tableLabel, Instant openedAt,
        int lineCount) {
}
```

- [ ] **Step 6: Create the commands**

`dining/api/RegisterTableCommand.java`:
```java
package com.company.pos.dining.api;

/** {@code seats} may be null — the module fills in the configured default. */
public record RegisterTableCommand(String label, Integer seats) {
}
```

`dining/api/OpenOrderCommand.java`:
```java
package com.company.pos.dining.api;

import java.util.UUID;

/** {@code serviceType} may be null — defaults to {@link ServiceType#DINE_IN}. */
public record OpenOrderCommand(UUID tableId, ServiceType serviceType) {
}
```

`dining/api/AddLineCommand.java`:
```java
package com.company.pos.dining.api;

import java.math.BigDecimal;

/** {@code note} and {@code course} are optional (may be null). */
public record AddLineCommand(String sku, BigDecimal qty, String note, CourseTag course) {
}
```

`dining/api/CloseOrderCommand.java`:
```java
package com.company.pos.dining.api;

import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.TenderInput;
import java.util.List;
import java.util.Map;

/**
 * Payment for the whole table as one bill. {@code lineDiscounts} is keyed by SKU (to match the
 * existing {@link com.company.pos.sales.api.CheckoutCommand} contract); {@code transactionDiscount}
 * may be null.
 */
public record CloseOrderCommand(List<TenderInput> tenders, Map<String, DiscountInput> lineDiscounts,
        DiscountInput transactionDiscount) {

    public CloseOrderCommand {
        if (lineDiscounts == null) {
            lineDiscounts = Map.of();
        }
    }
}
```

- [ ] **Step 7: Create the facade interface** — `dining/api/DiningService.java`

```java
package com.company.pos.dining.api;

import com.company.pos.sales.api.SaleView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface DiningService {

    // --- table registry ---
    TableView registerTable(RegisterTableCommand command);

    void deactivateTable(UUID tableId);

    List<TableView> listTables();

    // --- orders ---
    OrderView openOrder(OpenOrderCommand command, String openedBy);

    OrderView getOrder(UUID orderId);

    List<OpenOrderView> listOpenOrders();

    // --- lines ---
    OrderView addLine(UUID orderId, AddLineCommand command, String addedBy);

    OrderView updateLine(UUID orderId, UUID lineId, BigDecimal qty, String note, CourseTag course);

    OrderView removeLine(UUID orderId, UUID lineId);

    // --- close / void ---
    SaleView closeOrder(UUID orderId, CloseOrderCommand command, String cashierUsername,
            boolean callerIsManager);

    void voidOrder(UUID orderId, String reason);
}
```

- [ ] **Step 8: Run the module tests**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=ModularityTests`
Expected: PASS — `detectsTheDiningModule` passes and `verifiesModuleBoundaries` still passes (the api layer only imports allowed `sales :: api` types).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/company/pos/dining/ src/test/java/com/company/pos/ModularityTests.java
git commit -m "feat(dining): module scaffold — api enums, DTOs, commands, DiningService facade

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Table registry

Configured, admin-managed tables. Implements `registerTable` / `deactivateTable` / `listTables`; the remaining `DiningService` methods are temporary `UnsupportedOperationException` stubs (replaced in Tasks 3–5) so the module compiles. Adds the default-seats config key and the V23 migration.

**Files:**
- Create: `src/main/java/com/company/pos/dining/domain/DiningTable.java`
- Create: `src/main/java/com/company/pos/dining/infrastructure/DiningTableRepository.java`
- Create: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java`
- Create: `src/main/java/com/company/pos/dining/web/DiningController.java`
- Create: `src/main/resources/db/migration/dining/V23__create_dining_table.sql`
- Modify: `src/main/java/com/company/pos/configuration/api/SettingKey.java` (add `DINING_TABLE_DEFAULT_SEATS`)
- Modify: `src/main/resources/application-store-server.yml` (append the dining Flyway location)
- Create: `src/test/java/com/company/pos/dining/DiningTableServiceTest.java`
- Create: `src/test/java/com/company/pos/dining/DiningTableControllerTest.java`

**Interfaces:**
- Consumes: `DiningService` + api types (Task 1); `ConfigurationService.getInt(SettingKey)`; `com.company.pos.common.util.Identifiers.newId()`; `com.company.pos.common.exception.DomainException`.
- Produces: `DefaultDiningService` (`@Service`, package-private, implements `DiningService`); `DiningTable` entity with getters `getId()/getLabel()/getSeats()/isActive()`; `DiningTableRepository extends JpaRepository<DiningTable, UUID>` with `Optional<DiningTable> findByLabel(String label)`.

- [ ] **Step 1: Write the failing service test** — `DiningTableServiceTest.java`

```java
package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.TableView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class DiningTableServiceTest {

    @Autowired
    DiningService dining;

    @Test
    void registersTableWithExplicitSeats() {
        TableView t = dining.registerTable(new RegisterTableCommand("T1", 6));
        assertThat(t.id()).isNotNull();
        assertThat(t.label()).isEqualTo("T1");
        assertThat(t.seats()).isEqualTo(6);
        assertThat(t.active()).isTrue();
    }

    @Test
    void nullSeatsFallsBackToConfiguredDefault() {
        TableView t = dining.registerTable(new RegisterTableCommand("T2", null));
        assertThat(t.seats()).isEqualTo(4); // dining.table.default.seats default
    }

    @Test
    void rejectsBlankLabel() {
        assertThatThrownBy(() -> dining.registerTable(new RegisterTableCommand("  ", 2)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsDuplicateLabel() {
        dining.registerTable(new RegisterTableCommand("DUP", 2));
        assertThatThrownBy(() -> dining.registerTable(new RegisterTableCommand("DUP", 4)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void deactivateHidesFromActiveListButRowRemains() {
        TableView t = dining.registerTable(new RegisterTableCommand("T3", 2));
        dining.deactivateTable(t.id());
        assertThat(dining.listTables()).filteredOn(TableView::active)
                .extracting(TableView::id).doesNotContain(t.id());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=DiningTableServiceTest`
Expected: FAIL — `DefaultDiningService` / `DiningTable` do not exist (compilation error).

- [ ] **Step 3: Add the config key** — `SettingKey.java`

Add this constant to the enum (after `DASHBOARD_REVENUE_WINDOW_DAYS`):
```java
    DINING_TABLE_DEFAULT_SEATS("dining.table.default.seats", "4");
```
(Move the semicolon: the previous last constant `DASHBOARD_REVENUE_WINDOW_DAYS("dashboard.revenue.window.days", "7")` must now end with a comma, and this new line ends with the semicolon.)

- [ ] **Step 4: Create the entity** — `DiningTable.java`

```java
package com.company.pos.dining.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "dining_table")
public class DiningTable {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(nullable = false, length = 60, unique = true)
    private String label;

    @Column(nullable = false)
    private int seats;

    @Column(nullable = false)
    private boolean active = true;

    protected DiningTable() {
        // JPA
    }

    public DiningTable(UUID id, String label, int seats) {
        this.id = id;
        this.label = label;
        this.seats = seats;
    }

    public UUID getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public int getSeats() {
        return seats;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
```

- [ ] **Step 5: Create the repository** — `DiningTableRepository.java`

```java
package com.company.pos.dining.infrastructure;

import com.company.pos.dining.domain.DiningTable;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiningTableRepository extends JpaRepository<DiningTable, UUID> {

    Optional<DiningTable> findByLabel(String label);
}
```

- [ ] **Step 6: Create the service with table methods + stubs** — `DefaultDiningService.java`

```java
package com.company.pos.dining.application;

import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.CloseOrderCommand;
import com.company.pos.dining.api.CourseTag;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OpenOrderView;
import com.company.pos.dining.api.OrderView;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.TableView;
import com.company.pos.dining.domain.DiningTable;
import com.company.pos.dining.infrastructure.DiningTableRepository;
import com.company.pos.sales.api.SaleView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultDiningService implements DiningService {

    private final DiningTableRepository tables;
    private final ConfigurationService config;

    DefaultDiningService(DiningTableRepository tables, ConfigurationService config) {
        this.tables = tables;
        this.config = config;
    }

    @Override
    public TableView registerTable(RegisterTableCommand command) {
        if (command.label() == null || command.label().isBlank()) {
            throw DomainException.validation("Table label is required");
        }
        String label = command.label().trim();
        tables.findByLabel(label).ifPresent(t -> {
            throw DomainException.conflict("Table " + label + " already exists");
        });
        int seats = command.seats() != null
                ? command.seats()
                : config.getInt(SettingKey.DINING_TABLE_DEFAULT_SEATS);
        DiningTable table = new DiningTable(Identifiers.newId(), label, seats);
        return toTableView(tables.save(table));
    }

    @Override
    public void deactivateTable(UUID tableId) {
        DiningTable table = tables.findById(tableId)
                .orElseThrow(() -> DomainException.notFound("No table " + tableId));
        table.setActive(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TableView> listTables() {
        return tables.findAll().stream().map(this::toTableView).toList();
    }

    // --- orders (Task 3) ---
    @Override
    public OrderView openOrder(OpenOrderCommand command, String openedBy) {
        throw new UnsupportedOperationException("Implemented in Task 3");
    }

    @Override
    public OrderView getOrder(UUID orderId) {
        throw new UnsupportedOperationException("Implemented in Task 3");
    }

    @Override
    public List<OpenOrderView> listOpenOrders() {
        throw new UnsupportedOperationException("Implemented in Task 3");
    }

    // --- lines (Task 4) ---
    @Override
    public OrderView addLine(UUID orderId, AddLineCommand command, String addedBy) {
        throw new UnsupportedOperationException("Implemented in Task 4");
    }

    @Override
    public OrderView updateLine(UUID orderId, UUID lineId, BigDecimal qty, String note,
            CourseTag course) {
        throw new UnsupportedOperationException("Implemented in Task 4");
    }

    @Override
    public OrderView removeLine(UUID orderId, UUID lineId) {
        throw new UnsupportedOperationException("Implemented in Task 4");
    }

    // --- close / void (Task 5) ---
    @Override
    public SaleView closeOrder(UUID orderId, CloseOrderCommand command, String cashierUsername,
            boolean callerIsManager) {
        throw new UnsupportedOperationException("Implemented in Task 5");
    }

    @Override
    public void voidOrder(UUID orderId, String reason) {
        throw new UnsupportedOperationException("Implemented in Task 5");
    }

    private TableView toTableView(DiningTable t) {
        return new TableView(t.getId(), t.getLabel(), t.getSeats(), t.isActive());
    }
}
```

- [ ] **Step 7: Create the V23 migration** — `db/migration/dining/V23__create_dining_table.sql`

```sql
CREATE TABLE dining_table (
    id     VARCHAR(36) PRIMARY KEY,
    label  VARCHAR(60) NOT NULL UNIQUE,
    seats  INTEGER     NOT NULL,
    active BOOLEAN     NOT NULL DEFAULT TRUE
);
```

- [ ] **Step 8: Register the Flyway location** — `application-store-server.yml`

Append `,classpath:db/migration/dining` to the end of the existing `spring.flyway.locations` value (the line currently ending `...,classpath:db/migration/customer`).

- [ ] **Step 9: Run the service test**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=DiningTableServiceTest`
Expected: PASS (all 5 tests).

- [ ] **Step 10: Write the failing controller test** — `DiningTableControllerTest.java`

```java
package com.company.pos.dining;

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
class DiningTableControllerTest {

    @Autowired MockMvc mvc;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    private static RequestPostProcessor cashier() {
        return jwt().jwt(j -> j.subject("cashier"))
                .authorities(new SimpleGrantedAuthority("ROLE_CASHIER"));
    }

    private static RequestPostProcessor manager() {
        return jwt().jwt(j -> j.subject("manager"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    @Test
    void managerRegistersTable() throws Exception {
        mvc.perform(post("/dining/tables").with(manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"T10\",\"seats\":4}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("T10"));
    }

    @Test
    void cashierCannotRegisterTable() throws Exception {
        mvc.perform(post("/dining/tables").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"T11\",\"seats\":4}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anyCashierCanListTables() throws Exception {
        mvc.perform(get("/dining/tables").with(cashier()))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 11: Run the controller test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=DiningTableControllerTest`
Expected: FAIL — no `/dining/tables` endpoint (404), or compilation of `DiningController` missing.

- [ ] **Step 12: Create the controller (table endpoints)** — `DiningController.java`

```java
package com.company.pos.dining.web;

import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.TableView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DiningController {

    private final DiningService dining;

    DiningController(DiningService dining) {
        this.dining = dining;
    }

    @PostMapping("/dining/tables")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MANAGER')")
    TableView registerTable(@RequestBody RegisterTableCommand body) {
        return dining.registerTable(body);
    }

    @DeleteMapping("/dining/tables/{tableId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('MANAGER')")
    void deactivateTable(@PathVariable UUID tableId) {
        dining.deactivateTable(tableId);
    }

    @GetMapping("/dining/tables")
    List<TableView> listTables() {
        return dining.listTables();
    }
}
```

- [ ] **Step 13: Run both test classes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest='DiningTableServiceTest,DiningTableControllerTest,ModularityTests'`
Expected: PASS (all classes green).

- [ ] **Step 14: Commit**

```bash
git add src/main/java/com/company/pos/dining/ src/main/resources/db/migration/dining/ \
        src/main/java/com/company/pos/configuration/api/SettingKey.java \
        src/main/resources/application-store-server.yml \
        src/test/java/com/company/pos/dining/
git commit -m "feat(dining): configured table registry (manager-gated) + V23 migration

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Open orders + shared-floor views

Adds the `DiningOrder` / `OrderLine` entities (order with `@Version`), migrations V24/V25, and implements `openOrder` / `getOrder` / `listOpenOrders` plus their endpoints. Lines are added in Task 4; an order opens empty.

**Files:**
- Create: `src/main/java/com/company/pos/dining/domain/DiningOrder.java`
- Create: `src/main/java/com/company/pos/dining/domain/OrderLine.java`
- Create: `src/main/java/com/company/pos/dining/infrastructure/DiningOrderRepository.java`
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java`
- Modify: `src/main/java/com/company/pos/dining/web/DiningController.java`
- Create: `src/main/resources/db/migration/dining/V24__create_dining_order.sql`
- Create: `src/main/resources/db/migration/dining/V25__create_dining_order_line.sql`
- Create: `src/test/java/com/company/pos/dining/DiningOrderServiceTest.java`

**Interfaces:**
- Consumes: Task 1 api; Task 2 `DiningTableRepository`, `DiningTable`.
- Produces: `DiningOrder` (fields `id`, `tableId`, `serviceType`, `status`, `openedBy`, `openedAt`, `closedAt`, `saleId`, `@Version version`, `List<OrderLine> lines`; methods `getId()`, `getTableId()`, `getServiceType()`, `getStatus()`, `getOpenedBy()`, `getOpenedAt()`, `getClosedAt()`, `getSaleId()`, `getLines()`, `addLine(OrderLine)`, `removeLine(OrderLine)`, `close(UUID saleId, Instant when)`, `voidOrder()`). `OrderLine` (fields `id`, `sku`, `qty`, `note`, `course`, `addedBy`, `addedAt`; getters + `setQty`/`setNote`/`setCourse`). `DiningOrderRepository` with `List<DiningOrder> findByStatus(OrderStatus status)` and `boolean existsByTableIdAndStatus(UUID tableId, OrderStatus status)`.

- [ ] **Step 1: Write the failing service test** — `DiningOrderServiceTest.java`

```java
package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OpenOrderView;
import com.company.pos.dining.api.OrderStatus;
import com.company.pos.dining.api.OrderView;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.ServiceType;
import com.company.pos.dining.api.TableView;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class DiningOrderServiceTest {

    @Autowired
    DiningService dining;

    private UUID freshTable(String label) {
        return dining.registerTable(new RegisterTableCommand(label, 4)).id();
    }

    @Test
    void opensDineInOrderOnTable() {
        UUID tableId = freshTable("O1");
        OrderView order = dining.openOrder(new OpenOrderCommand(tableId, null), "alice");

        assertThat(order.id()).isNotNull();
        assertThat(order.tableId()).isEqualTo(tableId);
        assertThat(order.serviceType()).isEqualTo(ServiceType.DINE_IN); // null defaulted
        assertThat(order.status()).isEqualTo(OrderStatus.OPEN);
        assertThat(order.openedBy()).isEqualTo("alice");
        assertThat(order.lines()).isEmpty();
    }

    @Test
    void rejectsSecondOpenOrderOnSameTable() {
        UUID tableId = freshTable("O2");
        dining.openOrder(new OpenOrderCommand(tableId, null), "alice");
        assertThatThrownBy(() -> dining.openOrder(new OpenOrderCommand(tableId, null), "bob"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsOpenOnUnknownTable() {
        assertThatThrownBy(() -> dining.openOrder(new OpenOrderCommand(UUID.randomUUID(), null), "alice"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsOpenOnInactiveTable() {
        UUID tableId = freshTable("O3");
        dining.deactivateTable(tableId);
        assertThatThrownBy(() -> dining.openOrder(new OpenOrderCommand(tableId, null), "alice"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void openOrderIsVisibleOnSharedFloorAndById() {
        UUID tableId = freshTable("O4");
        OrderView opened = dining.openOrder(new OpenOrderCommand(tableId, ServiceType.DINE_IN), "alice");

        // Any terminal: fetch by id...
        assertThat(dining.getOrder(opened.id()).id()).isEqualTo(opened.id());
        // ...and see it in the store-wide open-orders list.
        assertThat(dining.listOpenOrders())
                .extracting(OpenOrderView::orderId).contains(opened.id());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=DiningOrderServiceTest`
Expected: FAIL — `openOrder` throws `UnsupportedOperationException` (and `DiningOrder` does not exist).

- [ ] **Step 3: Create the `OrderLine` entity** — `OrderLine.java`

```java
package com.company.pos.dining.domain;

import com.company.pos.dining.api.CourseTag;
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
@Table(name = "dining_order_line")
public class OrderLine {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "order_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID orderId;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal qty;

    @Column(length = 500)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CourseTag course;

    @Column(name = "added_by", nullable = false, length = 100)
    private String addedBy;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    protected OrderLine() {
        // JPA
    }

    public OrderLine(UUID id, UUID orderId, String sku, BigDecimal qty, String note,
            CourseTag course, String addedBy, Instant addedAt) {
        this.id = id;
        this.orderId = orderId;
        this.sku = sku;
        this.qty = qty;
        this.note = note;
        this.course = course;
        this.addedBy = addedBy;
        this.addedAt = addedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public void setQty(BigDecimal qty) {
        this.qty = qty;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public CourseTag getCourse() {
        return course;
    }

    public void setCourse(CourseTag course) {
        this.course = course;
    }
}
```

- [ ] **Step 4: Create the `DiningOrder` entity** — `DiningOrder.java`

```java
package com.company.pos.dining.domain;

import com.company.pos.dining.api.OrderStatus;
import com.company.pos.dining.api.ServiceType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "dining_order")
public class DiningOrder {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "table_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID tableId;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, length = 20)
    private ServiceType serviceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "opened_by", nullable = false, length = 100)
    private String openedBy;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "sale_id", length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID saleId;

    @Version
    private long version;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id")
    private List<OrderLine> lines = new ArrayList<>();

    protected DiningOrder() {
        // JPA
    }

    public DiningOrder(UUID id, UUID tableId, ServiceType serviceType, String openedBy,
            Instant openedAt) {
        this.id = id;
        this.tableId = tableId;
        this.serviceType = serviceType;
        this.status = OrderStatus.OPEN;
        this.openedBy = openedBy;
        this.openedAt = openedAt;
    }

    public void addLine(OrderLine line) {
        lines.add(line);
    }

    public void removeLine(OrderLine line) {
        lines.remove(line);
    }

    public void close(UUID saleId, Instant when) {
        this.status = OrderStatus.CLOSED;
        this.saleId = saleId;
        this.closedAt = when;
    }

    public void voidOrder() {
        this.status = OrderStatus.VOIDED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTableId() {
        return tableId;
    }

    public ServiceType getServiceType() {
        return serviceType;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getOpenedBy() {
        return openedBy;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public UUID getSaleId() {
        return saleId;
    }

    public List<OrderLine> getLines() {
        return lines;
    }
}
```

- [ ] **Step 5: Create the repository** — `DiningOrderRepository.java`

```java
package com.company.pos.dining.infrastructure;

import com.company.pos.dining.api.OrderStatus;
import com.company.pos.dining.domain.DiningOrder;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiningOrderRepository extends JpaRepository<DiningOrder, UUID> {

    List<DiningOrder> findByStatus(OrderStatus status);

    boolean existsByTableIdAndStatus(UUID tableId, OrderStatus status);
}
```

- [ ] **Step 6: Implement order methods in the service**

In `DefaultDiningService`, add fields + constructor params for `DiningOrderRepository orders` and a `java.time.Clock`-free `Instant.now()` usage (match the codebase — `customer` uses `Instant.now()` directly). Update the constructor:

```java
    private final DiningTableRepository tables;
    private final DiningOrderRepository orders;
    private final ConfigurationService config;

    DefaultDiningService(DiningTableRepository tables, DiningOrderRepository orders,
            ConfigurationService config) {
        this.tables = tables;
        this.orders = orders;
        this.config = config;
    }
```

Replace the three order-method stubs with:

```java
    @Override
    public OrderView openOrder(OpenOrderCommand command, String openedBy) {
        DiningTable table = tables.findById(command.tableId())
                .orElseThrow(() -> DomainException.notFound("No table " + command.tableId()));
        if (!table.isActive()) {
            throw DomainException.validation("Table " + table.getLabel() + " is inactive");
        }
        if (orders.existsByTableIdAndStatus(table.getId(), OrderStatus.OPEN)) {
            throw DomainException.conflict("Table " + table.getLabel() + " already has an open order");
        }
        ServiceType type = command.serviceType() != null ? command.serviceType() : ServiceType.DINE_IN;
        DiningOrder order = new DiningOrder(Identifiers.newId(), table.getId(), type, openedBy,
                Instant.now());
        return toOrderView(orders.save(order));
    }

    @Override
    @Transactional(readOnly = true)
    public OrderView getOrder(UUID orderId) {
        return toOrderView(load(orderId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OpenOrderView> listOpenOrders() {
        return orders.findByStatus(OrderStatus.OPEN).stream()
                .map(o -> new OpenOrderView(o.getId(), o.getTableId(),
                        tables.findById(o.getTableId()).map(DiningTable::getLabel).orElse(null),
                        o.getOpenedAt(), o.getLines().size()))
                .toList();
    }
```

Add these helpers (and the imports `java.time.Instant`, `com.company.pos.dining.domain.DiningOrder`, `com.company.pos.dining.domain.OrderLine`, `com.company.pos.dining.infrastructure.DiningOrderRepository`, `com.company.pos.dining.api.OrderLineView`, `com.company.pos.dining.api.OrderStatus`, `com.company.pos.dining.api.ServiceType`):

```java
    DiningOrder load(UUID orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> DomainException.notFound("No order " + orderId));
    }

    OrderView toOrderView(DiningOrder o) {
        List<OrderLineView> lineViews = o.getLines().stream()
                .map(l -> new OrderLineView(l.getId(), l.getSku(), l.getQty(), l.getNote(),
                        l.getCourse()))
                .toList();
        return new OrderView(o.getId(), o.getTableId(), o.getServiceType(), o.getStatus(),
                o.getOpenedBy(), o.getOpenedAt(), o.getClosedAt(), o.getSaleId(), lineViews);
    }
```

- [ ] **Step 7: Create the V24 migration** — `db/migration/dining/V24__create_dining_order.sql`

```sql
CREATE TABLE dining_order (
    id           VARCHAR(36) PRIMARY KEY,
    table_id     VARCHAR(36) NOT NULL,
    service_type VARCHAR(20) NOT NULL,
    status       VARCHAR(20) NOT NULL,
    opened_by    VARCHAR(100) NOT NULL,
    opened_at    TIMESTAMP   NOT NULL,
    closed_at    TIMESTAMP,
    sale_id      VARCHAR(36),
    version      BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT fk_dining_order_table FOREIGN KEY (table_id) REFERENCES dining_table (id)
);
CREATE INDEX idx_dining_order_status ON dining_order (status);
```

- [ ] **Step 8: Create the V25 migration** — `db/migration/dining/V25__create_dining_order_line.sql`

```sql
CREATE TABLE dining_order_line (
    id       VARCHAR(36) PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL,
    sku      VARCHAR(64) NOT NULL,
    qty      NUMERIC(19, 3) NOT NULL,
    note     VARCHAR(500),
    course   VARCHAR(20),
    added_by VARCHAR(100) NOT NULL,
    added_at TIMESTAMP   NOT NULL,
    CONSTRAINT fk_dining_order_line_order FOREIGN KEY (order_id) REFERENCES dining_order (id)
);
CREATE INDEX idx_dining_order_line_order ON dining_order_line (order_id);
```

- [ ] **Step 9: Add the order endpoints to the controller**

In `DiningController`, add imports (`com.company.pos.dining.api.OpenOrderCommand`, `OpenOrderView`, `OrderView`, `org.springframework.security.core.Authentication`) and these methods:

```java
    @PostMapping("/dining/orders")
    @ResponseStatus(HttpStatus.CREATED)
    OrderView openOrder(@RequestBody OpenOrderCommand body, Authentication authentication) {
        return dining.openOrder(body, authentication.getName());
    }

    @GetMapping("/dining/orders")
    List<OpenOrderView> listOpenOrders() {
        return dining.listOpenOrders();
    }

    @GetMapping("/dining/orders/{orderId}")
    OrderView getOrder(@PathVariable UUID orderId) {
        return dining.getOrder(orderId);
    }
```

- [ ] **Step 10: Run the service test**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=DiningOrderServiceTest`
Expected: PASS (all 5 tests).

- [ ] **Step 11: Run module boundary + regression**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest='com.company.pos.dining.*,ModularityTests'`
Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/company/pos/dining/ src/main/resources/db/migration/dining/ \
        src/test/java/com/company/pos/dining/DiningOrderServiceTest.java
git commit -m "feat(dining): open dine-in orders on tables, shared-floor views + V24/V25

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Order lines — add / update / remove

Implements line editing with sku validation via `ProductCatalog`, capturing optional note + course. `removeLine` is manager-gated at the web layer.

**Files:**
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java`
- Modify: `src/main/java/com/company/pos/dining/web/DiningController.java`
- Create: `src/test/java/com/company/pos/dining/DiningLineServiceTest.java`
- Create: `src/test/java/com/company/pos/dining/DiningLineControllerTest.java`

**Interfaces:**
- Consumes: `com.company.pos.product.api.ProductCatalog.findBySku(String)` returning `Optional<ProductView>`; Task 3 `DiningOrder.addLine/removeLine/getLines`, `OrderLine` ctor + setters, `load(UUID)`, `toOrderView(...)`.
- Produces: implemented `addLine` / `updateLine` / `removeLine`; a private `OrderLine requireLine(DiningOrder, UUID)` helper and `void requireOpen(DiningOrder)` guard reused by Task 5.

- [ ] **Step 1: Verify the `ProductCatalog` signature.**

Run: `grep -n "findBySku" src/main/java/com/company/pos/product/api/ProductCatalog.java`
Expected: a method `Optional<ProductView> findBySku(String sku)`. Use exactly this signature below. (If it differs, adapt the call in Step 4 accordingly.)

- [ ] **Step 2: Write the failing service test** — `DiningLineServiceTest.java`

```java
package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.CourseTag;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OrderLineView;
import com.company.pos.dining.api.OrderView;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
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
class DiningLineServiceTest {

    @Autowired DiningService dining;
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

    private UUID openOrderOnFreshTable() {
        UUID tableId = dining.registerTable(new RegisterTableCommand("L" + UUID.randomUUID(), 4)).id();
        return dining.openOrder(new OpenOrderCommand(tableId, null), "alice").id();
    }

    @Test
    void addsLineWithNoteAndCourse() {
        UUID orderId = openOrderOnFreshTable();
        OrderView order = dining.addLine(orderId,
                new AddLineCommand("BURGER", new BigDecimal("2"), "no onions", CourseTag.MAIN), "alice");

        assertThat(order.lines()).hasSize(1);
        OrderLineView line = order.lines().get(0);
        assertThat(line.sku()).isEqualTo("BURGER");
        assertThat(line.qty()).isEqualByComparingTo("2");
        assertThat(line.note()).isEqualTo("no onions");
        assertThat(line.course()).isEqualTo(CourseTag.MAIN);
    }

    @Test
    void rejectsUnknownSku() {
        UUID orderId = openOrderOnFreshTable();
        assertThatThrownBy(() -> dining.addLine(orderId,
                new AddLineCommand("NOPE", new BigDecimal("1"), null, null), "alice"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNonPositiveQty() {
        UUID orderId = openOrderOnFreshTable();
        assertThatThrownBy(() -> dining.addLine(orderId,
                new AddLineCommand("BURGER", BigDecimal.ZERO, null, null), "alice"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void updatesLineQtyNoteAndCourse() {
        UUID orderId = openOrderOnFreshTable();
        UUID lineId = dining.addLine(orderId,
                new AddLineCommand("BURGER", new BigDecimal("1"), null, null), "alice")
                .lines().get(0).id();

        OrderView updated = dining.updateLine(orderId, lineId, new BigDecimal("3"), "extra cheese",
                CourseTag.MAIN);

        OrderLineView line = updated.lines().get(0);
        assertThat(line.qty()).isEqualByComparingTo("3");
        assertThat(line.note()).isEqualTo("extra cheese");
        assertThat(line.course()).isEqualTo(CourseTag.MAIN);
    }

    @Test
    void removesLine() {
        UUID orderId = openOrderOnFreshTable();
        UUID lineId = dining.addLine(orderId,
                new AddLineCommand("BURGER", new BigDecimal("1"), null, null), "alice")
                .lines().get(0).id();

        OrderView afterRemove = dining.removeLine(orderId, lineId);
        assertThat(afterRemove.lines()).isEmpty();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=DiningLineServiceTest`
Expected: FAIL — `addLine` throws `UnsupportedOperationException`.

- [ ] **Step 4: Implement the line methods**

In `DefaultDiningService`, add constructor injection for `ProductCatalog products` (add field, param, and assignment; import `com.company.pos.product.api.ProductCatalog`). Replace the three line-method stubs with:

```java
    @Override
    public OrderView addLine(UUID orderId, AddLineCommand command, String addedBy) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        if (command.qty() == null || command.qty().signum() <= 0) {
            throw DomainException.validation("Line quantity must be positive");
        }
        products.findBySku(command.sku())
                .orElseThrow(() -> DomainException.validation("Unknown sku " + command.sku()));
        OrderLine line = new OrderLine(Identifiers.newId(), order.getId(), command.sku(),
                command.qty(), command.note(), command.course(), addedBy, Instant.now());
        order.addLine(line);
        return toOrderView(order);
    }

    @Override
    public OrderView updateLine(UUID orderId, UUID lineId, BigDecimal qty, String note,
            CourseTag course) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        if (qty == null || qty.signum() <= 0) {
            throw DomainException.validation("Line quantity must be positive");
        }
        OrderLine line = requireLine(order, lineId);
        line.setQty(qty);
        line.setNote(note);
        line.setCourse(course);
        return toOrderView(order);
    }

    @Override
    public OrderView removeLine(UUID orderId, UUID lineId) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        OrderLine line = requireLine(order, lineId);
        order.removeLine(line);
        return toOrderView(order);
    }
```

Add these private helpers (import `com.company.pos.dining.api.CourseTag` if not already present):

```java
    private void requireOpen(DiningOrder order) {
        if (order.getStatus() != OrderStatus.OPEN) {
            throw DomainException.validation("Order " + order.getId() + " is not open");
        }
    }

    private OrderLine requireLine(DiningOrder order, UUID lineId) {
        return order.getLines().stream()
                .filter(l -> l.getId().equals(lineId))
                .findFirst()
                .orElseThrow(() -> DomainException.notFound("No line " + lineId));
    }
```

- [ ] **Step 5: Run the service test**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=DiningLineServiceTest`
Expected: PASS (all 5 tests).

- [ ] **Step 6: Add line endpoints to the controller**

In `DiningController` add imports (`com.company.pos.dining.api.AddLineCommand`, `com.company.pos.dining.api.CourseTag`, `java.math.BigDecimal`, `org.springframework.web.bind.annotation.PutMapping`, `org.springframework.web.bind.annotation.RequestParam`) and:

```java
    @PostMapping("/dining/orders/{orderId}/lines")
    OrderView addLine(@PathVariable UUID orderId, @RequestBody AddLineCommand body,
            Authentication authentication) {
        return dining.addLine(orderId, body, authentication.getName());
    }

    @PutMapping("/dining/orders/{orderId}/lines/{lineId}")
    OrderView updateLine(@PathVariable UUID orderId, @PathVariable UUID lineId,
            @RequestParam BigDecimal qty,
            @RequestParam(required = false) String note,
            @RequestParam(required = false) CourseTag course) {
        return dining.updateLine(orderId, lineId, qty, note, course);
    }

    @DeleteMapping("/dining/orders/{orderId}/lines/{lineId}")
    @PreAuthorize("hasRole('MANAGER')")
    OrderView removeLine(@PathVariable UUID orderId, @PathVariable UUID lineId) {
        return dining.removeLine(orderId, lineId);
    }
```

- [ ] **Step 7: Write the failing controller test (manager gate on remove)** — `DiningLineControllerTest.java`

```java
package com.company.pos.dining;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
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
class DiningLineControllerTest {

    @Autowired MockMvc mvc;
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

    private static RequestPostProcessor cashier() {
        return jwt().jwt(j -> j.subject("cashier"))
                .authorities(new SimpleGrantedAuthority("ROLE_CASHIER"));
    }

    private static RequestPostProcessor manager() {
        return jwt().jwt(j -> j.subject("manager"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    private String openOrderWithLine() throws Exception {
        String table = mvc.perform(post("/dining/tables").with(manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"CT1\",\"seats\":4}"))
                .andReturn().getResponse().getContentAsString();
        String tableId = com.jayway.jsonpath.JsonPath.read(table, "$.id");

        String order = mvc.perform(post("/dining/orders").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":\"" + tableId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = com.jayway.jsonpath.JsonPath.read(order, "$.id");

        mvc.perform(post("/dining/orders/" + orderId + "/lines").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"BURGER\",\"qty\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].sku").value("BURGER"));
        return orderId;
    }

    @Test
    void cashierCannotRemoveLine() throws Exception {
        String orderId = openOrderWithLine();
        String lineBody = mvc.perform(post("/dining/orders/" + orderId + "/lines").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"BURGER\",\"qty\":1}"))
                .andReturn().getResponse().getContentAsString();
        String lineId = com.jayway.jsonpath.JsonPath.read(lineBody, "$.lines[1].id");

        mvc.perform(delete("/dining/orders/" + orderId + "/lines/" + lineId).with(cashier()))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanRemoveLine() throws Exception {
        String orderId = openOrderWithLine();
        String lineBody = mvc.perform(post("/dining/orders/" + orderId + "/lines").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"BURGER\",\"qty\":1}"))
                .andReturn().getResponse().getContentAsString();
        String lineId = com.jayway.jsonpath.JsonPath.read(lineBody, "$.lines[1].id");

        mvc.perform(delete("/dining/orders/" + orderId + "/lines/" + lineId).with(manager()))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 8: Run the controller test**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=DiningLineControllerTest`
Expected: PASS (both tests).

- [ ] **Step 9: Run module boundary check**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=ModularityTests`
Expected: PASS — `dining → product :: api` is within `allowedDependencies`.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/company/pos/dining/ src/test/java/com/company/pos/dining/DiningLineServiceTest.java \
        src/test/java/com/company/pos/dining/DiningLineControllerTest.java
git commit -m "feat(dining): add/update/remove order lines with notes + course tags (manager-gated remove)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Close order via checkout + void

Implements `closeOrder` (aggregate lines by sku → throwaway cart → existing multi-tender checkout → stamp CLOSED + free table) and `voidOrder` (manager-gated). This is the payoff task: it reuses the entire committed sales pipeline.

**Files:**
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java`
- Modify: `src/main/java/com/company/pos/dining/web/DiningController.java`
- Create: `src/test/java/com/company/pos/dining/DiningCloseServiceTest.java`

**Interfaces:**
- Consumes: `com.company.pos.cart.api.CartService.{createCart(), addLine(UUID, String, BigDecimal), close(UUID)}`; `com.company.pos.sales.api.SalesService.checkout(CheckoutCommand, String, boolean)`; `com.company.pos.sales.api.CheckoutCommand(UUID, List<TenderInput>, Map<String,DiscountInput>, DiscountInput)`; `SaleView.id()`; Task 3 `DiningOrder.close/voidOrder`; Task 4 `requireOpen`.
- Produces: implemented `closeOrder` / `voidOrder`.

- [ ] **Step 1: Write the failing service test** — `DiningCloseServiceTest.java`

```java
package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.CloseOrderCommand;
import com.company.pos.dining.api.CourseTag;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OpenOrderView;
import com.company.pos.dining.api.OrderStatus;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.product.api.ProductSync;
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
class DiningCloseServiceTest {

    @Autowired DiningService dining;
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

    private UUID openOrderWithTwoBurgers(String tableLabel) {
        UUID tableId = dining.registerTable(new RegisterTableCommand(tableLabel, 4)).id();
        UUID orderId = dining.openOrder(new OpenOrderCommand(tableId, null), "alice").id();
        // two separate lines, same sku — must aggregate to one cart line at close
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("1"), "medium", CourseTag.MAIN), "alice");
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("1"), "well done", CourseTag.MAIN), "alice");
        return orderId;
    }

    @Test
    void closingProducesSaleFreesTableAndMarksClosed() {
        UUID orderId = openOrderWithTwoBurgers("C1");

        // 2 x 30.00 = 60.00 net, +15% tax = 9.00, grand 69.00
        SaleView sale = dining.closeOrder(orderId,
                new CloseOrderCommand(List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100.00"))),
                        Map.of(), null),
                "alice", false);

        assertThat(sale.id()).isNotNull();
        assertThat(sale.grandTotal()).isEqualByComparingTo("69.00");

        // order is now CLOSED and stamped with the sale id
        assertThat(dining.getOrder(orderId).status()).isEqualTo(OrderStatus.CLOSED);
        assertThat(dining.getOrder(orderId).saleId()).isEqualTo(sale.id());

        // table is freed — it no longer appears among open orders
        assertThat(dining.listOpenOrders())
                .extracting(OpenOrderView::orderId).doesNotContain(orderId);
    }

    @Test
    void multiTenderClosesAsOneBill() {
        UUID orderId = openOrderWithTwoBurgers("C2");

        SaleView sale = dining.closeOrder(orderId,
                new CloseOrderCommand(List.of(
                        new TenderInput(PaymentMethod.CARD, new BigDecimal("40.00"), null),
                        new TenderInput(PaymentMethod.CASH, null, new BigDecimal("40.00"))),
                        Map.of(), null),
                "alice", false);

        assertThat(sale.payments()).hasSize(2);
        assertThat(sale.grandTotal()).isEqualByComparingTo("69.00");
    }

    @Test
    void doubleCloseIsRejected() {
        UUID orderId = openOrderWithTwoBurgers("C3");
        dining.closeOrder(orderId,
                new CloseOrderCommand(List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100.00"))),
                        Map.of(), null), "alice", false);

        assertThatThrownBy(() -> dining.closeOrder(orderId,
                new CloseOrderCommand(List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100.00"))),
                        Map.of(), null), "alice", false))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void voidedOrderCannotBeClosed() {
        UUID orderId = openOrderWithTwoBurgers("C4");
        dining.voidOrder(orderId, "walked out");

        assertThat(dining.getOrder(orderId).status()).isEqualTo(OrderStatus.VOIDED);
        assertThatThrownBy(() -> dining.closeOrder(orderId,
                new CloseOrderCommand(List.of(new TenderInput(PaymentMethod.CASH, null, new BigDecimal("100.00"))),
                        Map.of(), null), "alice", false))
                .isInstanceOf(DomainException.class);
        // voiding frees the table too
        assertThat(dining.listOpenOrders())
                .extracting(OpenOrderView::orderId).doesNotContain(orderId);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=DiningCloseServiceTest`
Expected: FAIL — `closeOrder` / `voidOrder` throw `UnsupportedOperationException`.

- [ ] **Step 3: Implement close + void**

In `DefaultDiningService`, add constructor injection for `CartService carts` and `SalesService sales` (add fields, params, assignments; imports `com.company.pos.cart.api.CartService`, `com.company.pos.sales.api.SalesService`, `com.company.pos.sales.api.CheckoutCommand`, `java.util.LinkedHashMap`, `java.util.Map`). Replace the two stubs with:

```java
    @Override
    public SaleView closeOrder(UUID orderId, CloseOrderCommand command, String cashierUsername,
            boolean callerIsManager) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        if (order.getLines().isEmpty()) {
            throw DomainException.validation("Cannot close an empty order");
        }

        // Aggregate lines by sku so the throwaway cart has one line per sku — this keeps the
        // cart's one-line-per-sku shape and lets sku-keyed line discounts map cleanly.
        Map<String, BigDecimal> bySku = new LinkedHashMap<>();
        for (OrderLine line : order.getLines()) {
            bySku.merge(line.getSku(), line.getQty(), BigDecimal::add);
        }

        UUID cartId = carts.createCart();
        bySku.forEach((sku, qty) -> carts.addLine(cartId, sku, qty));

        SaleView sale = sales.checkout(
                new CheckoutCommand(cartId, command.tenders(), command.lineDiscounts(),
                        command.transactionDiscount()),
                cashierUsername, callerIsManager);

        carts.close(cartId);
        order.close(sale.id(), Instant.now());
        return sale;
    }

    @Override
    public void voidOrder(UUID orderId, String reason) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        order.voidOrder();
    }
```

- [ ] **Step 4: Run the service test**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=DiningCloseServiceTest`
Expected: PASS (all 4 tests). If the tax total differs, confirm `tax.rate` is `0.15` (default) — the expected `69.00` assumes 15%.

- [ ] **Step 5: Add close + void endpoints to the controller**

In `DiningController` add imports (`com.company.pos.dining.api.CloseOrderCommand`, `com.company.pos.sales.api.SaleView`) and:

```java
    @PostMapping("/dining/orders/{orderId}/close")
    @ResponseStatus(HttpStatus.CREATED)
    SaleView closeOrder(@PathVariable UUID orderId, @RequestBody CloseOrderCommand body,
            Authentication authentication) {
        boolean isManager = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_MANAGER".equals(a.getAuthority()));
        return dining.closeOrder(orderId, body, authentication.getName(), isManager);
    }

    @PostMapping("/dining/orders/{orderId}/void")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('MANAGER')")
    void voidOrder(@PathVariable UUID orderId,
            @RequestParam(required = false, defaultValue = "") String reason) {
        dining.voidOrder(orderId, reason);
    }
```

- [ ] **Step 6: Run the whole dining module + boundaries + a broad regression**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest='com.company.pos.dining.*,ModularityTests'`
Expected: PASS (all dining tests + boundary verification).

- [ ] **Step 7: Full verify**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw verify`
Expected: BUILD SUCCESS — compile + all tests + module-boundary verification green. (Testcontainers PostgreSQL tests need Docker; if Docker is unavailable, run `./mvnw test` and note the skipped container tests.)

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/company/pos/dining/ src/test/java/com/company/pos/dining/DiningCloseServiceTest.java
git commit -m "feat(dining): close order via existing checkout (one bill) + manager void

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Configured table registry → Task 2 ✅
- Shared-floor, store-scoped open orders + `listOpenOrders()` → Task 3 ✅
- Add/update/remove lines with notes + course tags → Task 4 ✅
- Manager-gated line remove & order void ("voiding requires a manager") → Task 4 (remove) + Task 5 (void) ✅
- Close via existing multi-tender checkout as one bill (aggregate by sku) → Task 5 ✅
- Migrations V23–V25 + Flyway location registration → Tasks 2 & 3 ✅
- Config keys: `dining.table.default.seats` → Task 2 ✅. **Gap noted:** the spec also lists `dining.course.tags`; the course vocabulary is enforced by the `CourseTag` enum in phase-10 (no free-text course), so that key is **deferred to the phase-12 kitchen work** where a configurable vocabulary is actually consumed. This is a deliberate YAGNI trim — flagged here rather than adding an unused key.
- `@Version` optimistic locking → Task 3 entity ✅ (double-close is tested via the deterministic status guard; the `@Version` column is the store-server concurrency backstop).
- Module boundaries (`allowedDependencies`, named-interface-only) → Task 1 + `ModularityTests` gate every task ✅
- Dashboard consumer of `listOpenOrders()` → out of scope for this plan (the edge is added in the dashboard module's own change, per the spec); `listOpenOrders()` is delivered here so it's ready.

**Placeholder scan:** No TBD/TODO; every code and test step shows complete code; no "add error handling" hand-waves (validation is explicit in each method).

**Type consistency:** `DiningService` signatures in Task 1 match every call site in Tasks 2–5 (`openOrder(cmd, openedBy)`, `addLine(orderId, cmd, addedBy)`, `updateLine(orderId, lineId, qty, note, course)`, `closeOrder(orderId, cmd, username, isManager)`). Entity method names (`addLine`/`removeLine`/`close`/`voidOrder`/`getLines`) are defined in Task 3 and used unchanged in Tasks 4–5. `CheckoutCommand(UUID, List<TenderInput>, Map<String,DiscountInput>, DiscountInput)` and `SalesService.checkout(command, username, boolean)` match the real signatures verified against `sales :: api`.

One assumption to verify during Task 4 Step 1: `ProductCatalog.findBySku` returns `Optional<ProductView>`. The step includes a grep to confirm and adapt if the real signature differs.
