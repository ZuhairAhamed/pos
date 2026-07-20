# Dining Tables Admin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add backend edit/reactivate for dining tables (plus a deactivate open-order guard and event-based auditing) and a terminal admin screen to create, edit, deactivate, and restore tables.

**Architecture:** `dining` gains `updateTable`/`reactivateTable` (service + `PUT`/`POST` MANAGER endpoints), a deactivate guard, and a `TableChanged` domain event published on every table write; `audit` listens (async, post-commit) — one-way `audit → dining :: api`, no cycle. The terminal adds a `TableAdminApi` thin client, a synchronous `TablesViewModel`, a pure `TableRows` join, an I/O-free `TableFormDialog` (Dine-in/Counter toggle reusing the existing counter prefix), a `TablesController`/`tables.fxml` screen, and a MANAGER+ADMIN **Tables** tile.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Modulith, JPA/Hibernate; JavaFX terminal (separate Maven build).

## Global Constraints

- **No migration.** `dining_table.label` and `seats` columns already exist; this plan changes no schema.
- **Terminal FX-threading convention.** ViewModel methods are **synchronous** on the calling thread and return plain values; the controller runs them off the FX thread via `FxTasks.run(work, onDone, onError)` and reads results in the FX-thread `onDone` via a `holder[]` array. The only observable a VM writes off-thread is `errorMessage`, and only inside `ui.accept(...)`. Never call a blocking VM/HTTP method inside `onDone` — re-kick a fresh `FxTasks` task (each mutation's `onDone` calls `reload()`). Every VM needs an async-dispatcher regression test (a deferred, undrained `ui` dispatcher — assert `errorMessage` "" pre-drain then the error post-drain).
- **Terminal dialogs are I/O-free** — collect input only; the controller does all HTTP.
- **Auditing is event-based** (mirrors sub-project #2): `dining` publishes `TableChanged`; `audit` listens. No synchronous `dining → audit` call (avoids a module cycle and the single-writer SQLite audit deadlock).
- **Access:** table endpoints stay `@PreAuthorize("hasRole('MANAGER')")`; the terminal **Tables** tile is gated MANAGER **or** ADMIN (`services.session.isManager()`).
- **Boundaries:** the only new module dependency is `audit → dining :: api`. `dining` must NOT depend on `audit`. Run `ModularityTests` after the audit change.
- **The terminal is a separate build:** `./mvnw -f pos-terminal/pom.xml clean test` (headless, no display). The backend is `./mvnw test` / `./mvnw verify` (JDK 21; `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`).
- **Counter pseudo-tables** are the label-prefix convention only (terminal `TerminalConfig.takeawayLabelPrefix`, default `"Counter "`). No `serviceType`/type column on `DiningTable`.

---

### Task 1: Backend — table edit/reactivate, deactivate guard, TableChanged events

**Files:**
- Modify: `src/main/java/com/company/pos/dining/domain/DiningTable.java`
- Create: `src/main/java/com/company/pos/dining/api/UpdateTableCommand.java`
- Create: `src/main/java/com/company/pos/dining/api/TableChangeType.java`
- Create: `src/main/java/com/company/pos/dining/api/TableChanged.java`
- Modify: `src/main/java/com/company/pos/dining/api/FloorChangeType.java`
- Modify: `src/main/java/com/company/pos/dining/api/DiningService.java`
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java`
- Modify: `src/main/java/com/company/pos/dining/web/DiningController.java`
- Test: `src/test/java/com/company/pos/dining/TableAdminServiceTest.java`

**Interfaces:**
- Produces (for Task 2 — audit): `TableChanged(String entityRef, TableChangeType type, String actor, String label, int seats, boolean active)` where `entityRef` is the table id string; `enum TableChangeType { CREATED, UPDATED, DEACTIVATED, REACTIVATED }`.
- Produces (for Task 3/5 — terminal): `PUT /dining/tables/{tableId}` body `{label, seats}` → `TableView`; `POST /dining/tables/{tableId}/reactivate` → `TableView`.
- Consumes: existing `DiningOrderRepository.existsByTableIdAndStatus(UUID, OrderStatus)`, `DomainEvents events`, `OrderStatus.OPEN`.

- [ ] **Step 1: Add domain mutators**

In `DiningTable.java`, add below `setActive`:

```java
    public void rename(String label) {
        this.label = label;
    }

    public void reseat(int seats) {
        this.seats = seats;
    }
```

- [ ] **Step 2: Add api DTO + event + enum**

Create `dining/api/UpdateTableCommand.java`:

```java
package com.company.pos.dining.api;

/** Edit a table's label and seat count. Both required (seats must be a positive number). */
public record UpdateTableCommand(String label, Integer seats) {
}
```

Create `dining/api/TableChangeType.java`:

```java
package com.company.pos.dining.api;

/** The kind of table-registry write that produced a {@link TableChanged} event. */
public enum TableChangeType {
    CREATED, UPDATED, DEACTIVATED, REACTIVATED
}
```

Create `dining/api/TableChanged.java`:

```java
package com.company.pos.dining.api;

import com.company.pos.common.events.DomainEvent;

/**
 * Published by {@code DefaultDiningService} on every table-registry write. Consumed by the
 * {@code audit} module's {@code TableChangedAuditListener} (async, after commit — via the outbox).
 * {@code entityRef} is the table id; {@code actor} is the user who made the change (captured on the
 * request thread so the async listener records the real user, not "system").
 */
public record TableChanged(String entityRef, TableChangeType type, String actor,
        String label, int seats, boolean active) implements DomainEvent {
}
```

- [ ] **Step 3: Extend FloorChangeType**

In `dining/api/FloorChangeType.java`, change the first line of the enum body to add the two new kinds:

```java
public enum FloorChangeType {
    TABLE_REGISTERED, TABLE_DEACTIVATED, TABLE_UPDATED, TABLE_REACTIVATED,
    ORDER_OPENED, LINE_ADDED, LINE_UPDATED, LINE_REMOVED, ORDER_FIRED,
    ORDER_CLOSED, ORDER_SPLIT_CLOSED, ORDER_TRANSFERRED, ORDER_MERGED, ORDER_VOIDED
}
```

- [ ] **Step 4: Extend the DiningService interface**

In `dining/api/DiningService.java`, in the `// --- table registry ---` section (after `deactivateTable`), add:

```java
    TableView updateTable(UUID tableId, UpdateTableCommand command);

    TableView reactivateTable(UUID tableId);
```

- [ ] **Step 5: Implement in DefaultDiningService**

Add imports (with the other `com.company.pos.dining.api.*` / security imports):

```java
import com.company.pos.dining.api.TableChangeType;
import com.company.pos.dining.api.TableChanged;
import com.company.pos.dining.api.UpdateTableCommand;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
```

In `registerTable`, immediately after the existing `publishFloorChanged(FloorChangeType.TABLE_REGISTERED, saved.getId(), null);` line, add:

```java
        publishTableChanged(TableChangeType.CREATED, saved);
```

Replace the whole existing `deactivateTable` method with:

```java
    @Override
    public void deactivateTable(UUID tableId) {
        DiningTable table = tables.findById(tableId)
                .orElseThrow(() -> DomainException.notFound("No table " + tableId));
        if (orders.existsByTableIdAndStatus(tableId, OrderStatus.OPEN)) {
            throw DomainException.conflict("Table has an open order and cannot be deactivated");
        }
        table.setActive(false);
        publishFloorChanged(FloorChangeType.TABLE_DEACTIVATED, tableId, null);
        publishTableChanged(TableChangeType.DEACTIVATED, table);
    }

    @Override
    public TableView updateTable(UUID tableId, UpdateTableCommand command) {
        DiningTable table = tables.findById(tableId)
                .orElseThrow(() -> DomainException.notFound("No table " + tableId));
        if (command.label() == null || command.label().isBlank()) {
            throw DomainException.validation("Table label is required");
        }
        String label = command.label().trim();
        if (!label.equals(table.getLabel())) {
            tables.findByLabel(label).ifPresent(t -> {
                throw DomainException.conflict("Table " + label + " already exists");
            });
        }
        if (command.seats() == null || command.seats() < 1) {
            throw DomainException.validation("Seats must be a positive number");
        }
        table.rename(label);
        table.reseat(command.seats());
        publishFloorChanged(FloorChangeType.TABLE_UPDATED, table.getId(), null);
        publishTableChanged(TableChangeType.UPDATED, table);
        return toTableView(table);
    }

    @Override
    public TableView reactivateTable(UUID tableId) {
        DiningTable table = tables.findById(tableId)
                .orElseThrow(() -> DomainException.notFound("No table " + tableId));
        table.setActive(true);
        publishFloorChanged(FloorChangeType.TABLE_REACTIVATED, table.getId(), null);
        publishTableChanged(TableChangeType.REACTIVATED, table);
        return toTableView(table);
    }
```

Add these two helpers next to the existing `private void publishFloorChanged(...)`:

```java
    private void publishTableChanged(TableChangeType type, DiningTable table) {
        events.publish(new TableChanged(table.getId().toString(), type, actor(),
                table.getLabel(), table.getSeats(), table.isActive()));
    }

    private static String actor() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null ? a.getName() : "system";
    }
```

> Note: the entity is loaded via `tables.findById` inside the class-level `@Transactional`, so `rename`/`reseat` are flushed by JPA dirty-checking — no explicit `tables.save` is needed (matching how order writes mutate managed entities elsewhere in this service).

- [ ] **Step 6: Add controller endpoints**

In `dining/web/DiningController.java`, add the import:

```java
import com.company.pos.dining.api.UpdateTableCommand;
```

After the existing `deactivateTable` handler (the `@DeleteMapping("/dining/tables/{tableId}")` method), add:

```java
    @PutMapping("/dining/tables/{tableId}")
    @PreAuthorize("hasRole('MANAGER')")
    TableView updateTable(@PathVariable UUID tableId, @RequestBody UpdateTableCommand body) {
        return dining.updateTable(tableId, body);
    }

    @PostMapping("/dining/tables/{tableId}/reactivate")
    @PreAuthorize("hasRole('MANAGER')")
    TableView reactivateTable(@PathVariable UUID tableId) {
        return dining.reactivateTable(tableId);
    }
```

(`@PutMapping`/`@PostMapping`/`@PathVariable`/`@RequestBody`/`@PreAuthorize` are already imported.)

- [ ] **Step 7: Write the service test**

Create `src/test/java/com/company/pos/dining/TableAdminServiceTest.java`:

```java
package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.ServiceType;
import com.company.pos.dining.api.TableChangeType;
import com.company.pos.dining.api.TableChanged;
import com.company.pos.dining.api.TableView;
import com.company.pos.dining.api.UpdateTableCommand;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@RecordApplicationEvents
@Transactional
class TableAdminServiceTest {

    @Autowired DiningService dining;
    @Autowired ApplicationEvents events;

    @Test
    void createPublishesTableChangedCreated() {
        TableView t = dining.registerTable(new RegisterTableCommand("TA-1", 4));
        assertThat(typesFor(t.id().toString())).contains(TableChangeType.CREATED);
    }

    @Test
    void updateRenamesReseatsAndPublishesUpdated() {
        TableView t = dining.registerTable(new RegisterTableCommand("TA-2", 2));
        TableView updated = dining.updateTable(t.id(), new UpdateTableCommand("TA-2b", 6));
        assertThat(updated.label()).isEqualTo("TA-2b");
        assertThat(updated.seats()).isEqualTo(6);
        assertThat(typesFor(t.id().toString())).contains(TableChangeType.UPDATED);
    }

    @Test
    void updateRejectsDuplicateLabel() {
        dining.registerTable(new RegisterTableCommand("TA-3", 2));
        TableView other = dining.registerTable(new RegisterTableCommand("TA-4", 2));
        assertThatThrownBy(() -> dining.updateTable(other.id(), new UpdateTableCommand("TA-3", 2)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void updateRejectsNonPositiveSeats() {
        TableView t = dining.registerTable(new RegisterTableCommand("TA-5", 2));
        assertThatThrownBy(() -> dining.updateTable(t.id(), new UpdateTableCommand("TA-5", 0)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void deactivateBlockedWhenTableHasOpenOrder() {
        TableView t = dining.registerTable(new RegisterTableCommand("TA-6", 4));
        dining.openOrder(new OpenOrderCommand(t.id(), ServiceType.DINE_IN), "tester");
        assertThatThrownBy(() -> dining.deactivateTable(t.id()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void deactivateThenReactivateFlips() {
        TableView t = dining.registerTable(new RegisterTableCommand("TA-7", 4));
        dining.deactivateTable(t.id());
        assertThat(typesFor(t.id().toString())).contains(TableChangeType.DEACTIVATED);
        TableView back = dining.reactivateTable(t.id());
        assertThat(back.active()).isTrue();
        assertThat(typesFor(t.id().toString())).contains(TableChangeType.REACTIVATED);
    }

    private List<TableChangeType> typesFor(String entityRef) {
        return events.stream(TableChanged.class)
                .filter(e -> e.entityRef().equals(entityRef))
                .map(TableChanged::type)
                .toList();
    }
}
```

- [ ] **Step 8: Run the tests**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=TableAdminServiceTest`
Expected: PASS (6 tests).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/company/pos/dining src/test/java/com/company/pos/dining/TableAdminServiceTest.java
git commit -m "feat(dining): table edit/reactivate + deactivate open-order guard + TableChanged events"
```

---

### Task 2: Backend — audit listener for table changes

**Files:**
- Modify: `src/main/java/com/company/pos/audit/api/AuditAction.java`
- Create: `src/main/java/com/company/pos/audit/application/TableChangedAuditListener.java`
- Modify: `src/main/java/com/company/pos/audit/package-info.java`
- Test: `src/test/java/com/company/pos/audit/TableChangedAuditTest.java`

**Interfaces:**
- Consumes: `TableChanged` / `TableChangeType` from Task 1; existing `DefaultAuditService.append(AuditAction, String actor, String entityRef, Map<String,String> details)`.

- [ ] **Step 1: Add audit actions**

In `audit/api/AuditAction.java`, add these four constants at the end of the enum (after `CATEGORY_CREATED`, adding a comma after it):

```java
    CATEGORY_CREATED,
    TABLE_CREATED,
    TABLE_UPDATED,
    TABLE_DEACTIVATED,
    TABLE_REACTIVATED
```

- [ ] **Step 2: Add the listener**

Create `audit/application/TableChangedAuditListener.java`:

```java
package com.company.pos.audit.application;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.dining.api.TableChangeType;
import com.company.pos.dining.api.TableChanged;
import java.util.Map;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Records dining-table admin actions into the audit trail. Runs async AFTER the publishing
 * transaction commits (the outbox redelivers on failure), mirroring {@code ProductChangedAuditListener}.
 * The actor rides on the event (captured on the request thread), so the real user is recorded.
 */
@Component
class TableChangedAuditListener {

    private final DefaultAuditService audit;

    TableChangedAuditListener(DefaultAuditService audit) {
        this.audit = audit;
    }

    @ApplicationModuleListener
    void on(TableChanged event) {
        audit.append(actionFor(event.type()), event.actor(), event.entityRef(),
                Map.of("label", event.label(),
                        "seats", String.valueOf(event.seats()),
                        "active", String.valueOf(event.active())));
    }

    private static AuditAction actionFor(TableChangeType type) {
        return switch (type) {
            case CREATED -> AuditAction.TABLE_CREATED;
            case UPDATED -> AuditAction.TABLE_UPDATED;
            case DEACTIVATED -> AuditAction.TABLE_DEACTIVATED;
            case REACTIVATED -> AuditAction.TABLE_REACTIVATED;
        };
    }
}
```

- [ ] **Step 3: Allow the dependency**

Replace `audit/package-info.java` with (adds `"dining :: api"` to `allowedDependencies`):

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "sales :: api", "product :: api",
                "dining :: api", "configuration :: api" })
package com.company.pos.audit;
```

- [ ] **Step 4: Write the E2E audit test**

The `@ApplicationModuleListener` fires only AFTER a transaction commits, so drive it through the real HTTP endpoints (each commits its own transaction) and Awaitility-await the audit rows — mirroring `AuditEventListenersTest`.

Create `src/test/java/com/company/pos/audit/TableChangedAuditTest.java`:

```java
package com.company.pos.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.audit.api.AuditRecordView;
import com.company.pos.audit.application.DefaultAuditService;
import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.util.Identifiers;
import com.company.pos.support.DatabaseCleaner;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class TableChangedAuditTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired DefaultAuditService audit;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        users.save(new User(Identifiers.newId(), "boss", "Boss User",
                encoder.encode("pw"), Set.of(Role.MANAGER)));
    }

    @AfterEach
    void tearDown() {
        cleaner.clean();
    }

    @Test
    void tableLifecycleIsAudited() throws Exception {
        String token = login("boss");

        String created = mvc.perform(post("/dining/tables")
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content("{\"label\":\"AUD-1\",\"seats\":4}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String tableId = JsonPath.read(created, "$.id");

        mvc.perform(put("/dining/tables/" + tableId)
                        .header("Authorization", token)
                        .contentType("application/json")
                        .content("{\"label\":\"AUD-1b\",\"seats\":6}"))
                .andExpect(status().isOk());

        mvc.perform(delete("/dining/tables/" + tableId)
                        .header("Authorization", token))
                .andExpect(status().isNoContent());

        mvc.perform(post("/dining/tables/" + tableId + "/reactivate")
                        .header("Authorization", token))
                .andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<AuditRecordView> recent = audit.recent(50);
            assertThat(recent).anySatisfy(r -> {
                assertThat(r.action()).isEqualTo("TABLE_CREATED");
                assertThat(r.entityRef()).isEqualTo(tableId);
                assertThat(r.actor()).isEqualTo("boss");
            });
            assertThat(recent).anyMatch(r -> r.action().equals("TABLE_UPDATED"));
            assertThat(recent).anyMatch(r -> r.action().equals("TABLE_DEACTIVATED"));
            assertThat(recent).anyMatch(r -> r.action().equals("TABLE_REACTIVATED"));
        });

        assertThat(audit.verify().intact()).isTrue();
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

- [ ] **Step 5: Run the audit test + boundary check**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=TableChangedAuditTest,ModularityTests`
Expected: PASS (`TableChangedAuditTest` 1 test; `ModularityTests` verifies no cycle with the new `audit → dining :: api` edge).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/audit src/test/java/com/company/pos/audit/TableChangedAuditTest.java
git commit -m "feat(audit): record dining-table changes via TableChanged listener"
```

---

### Task 3: Terminal — API client, view-model, row helper

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/TableChangeRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/TableAdminApi.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/TablesViewModel.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/TableRows.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/TablesViewModelTest.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/TableRowsTest.java`

**Interfaces:**
- Consumes: existing `ApiClient` (`get/post/put(path, body, TypeReference)`, `delete(path)`), existing `com.company.pos.terminal.api.dto.TableView` record `(UUID id, String label, int seats, boolean active)`, existing `ApiException`.
- Produces (for Task 4/5): `TableAdminApi` methods; `TablesViewModel(TableAdminApi, Consumer<Runnable>)` with `loadTables()/create(label,seats)/update(id,label,seats)/deactivate(id)/reactivate(id)` + `errorMessage()`; `TableRows.build(List<TableView>, String counterPrefix)` → `List<TableRow>` where `TableRow(UUID id, String label, int seats, boolean active, boolean counter)`.

- [ ] **Step 1: Outbound DTO**

Create `terminal/api/TableChangeRequest.java`:

```java
package com.company.pos.terminal.api;

/** Outbound body for create (POST /dining/tables) and update (PUT /dining/tables/{id}). */
public record TableChangeRequest(String label, Integer seats) {
}
```

- [ ] **Step 2: API client**

Create `terminal/api/TableAdminApi.java`:

```java
package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.TableView;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.UUID;

/** Typed client for the store server's dining-table registry endpoints (writes MANAGER-gated).
 *  Non-final so view-model tests can subclass with fakes. */
public class TableAdminApi {

    private final ApiClient client;

    public TableAdminApi(ApiClient client) {
        this.client = client;
    }

    /** GET /dining/tables — all tables, including inactive. */
    public List<TableView> list() {
        return client.get("/dining/tables", new TypeReference<List<TableView>>() {});
    }

    /** POST /dining/tables — create a table. */
    public TableView create(String label, Integer seats) {
        return client.post("/dining/tables", new TableChangeRequest(label, seats),
                new TypeReference<TableView>() {});
    }

    /** PUT /dining/tables/{id} — rename / reseat. */
    public TableView update(UUID id, String label, Integer seats) {
        return client.put("/dining/tables/" + id, new TableChangeRequest(label, seats),
                new TypeReference<TableView>() {});
    }

    /** DELETE /dining/tables/{id} — soft-delete (active=false). */
    public void deactivate(UUID id) {
        client.delete("/dining/tables/" + id);
    }

    /** POST /dining/tables/{id}/reactivate — restore a soft-deleted table. */
    public TableView reactivate(UUID id) {
        return client.post("/dining/tables/" + id + "/reactivate", null,
                new TypeReference<TableView>() {});
    }
}
```

- [ ] **Step 3: Row helper**

Create `terminal/viewmodel/TableRows.java`:

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.dto.TableView;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Pure mapping of tables into display rows, deriving the counter/dine-in type from the label prefix. */
public final class TableRows {

    private TableRows() {
    }

    /** {@code counter} is true when the label carries the takeaway prefix (a QUICK_SERVICE counter). */
    public record TableRow(UUID id, String label, int seats, boolean active, boolean counter) {
    }

    public static List<TableRow> build(List<TableView> tables, String counterPrefix) {
        List<TableRow> rows = new ArrayList<>();
        if (tables != null) {
            for (TableView t : tables) {
                boolean counter = counterPrefix != null && !counterPrefix.isBlank()
                        && t.label() != null && t.label().startsWith(counterPrefix);
                rows.add(new TableRow(t.id(), t.label(), t.seats(), t.active(), counter));
            }
        }
        return rows;
    }
}
```

- [ ] **Step 4: View-model**

Create `terminal/viewmodel/TablesViewModel.java`:

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.TableAdminApi;
import com.company.pos.terminal.api.dto.TableView;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the dining-tables admin screen. Synchronous like the other VMs — the controller runs
 * it off the FX thread via FxTasks and reads the return value; the only observable written off-thread
 * is {@code errorMessage}, inside the {@code ui} dispatcher.
 */
public class TablesViewModel {

    private final TableAdminApi api;
    private final Consumer<Runnable> ui;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public TablesViewModel(TableAdminApi api, Consumer<Runnable> ui) {
        this.api = api;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    public List<TableView> loadTables() {
        try {
            List<TableView> list = api.list();
            ui.accept(() -> errorMessage.set(""));
            return list;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public TableView create(String label, Integer seats) {
        String err = validate(label, seats);
        if (err != null) {
            ui.accept(() -> errorMessage.set(err));
            return null;
        }
        try {
            TableView v = api.create(label.trim(), seats);
            ui.accept(() -> errorMessage.set(""));
            return v;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public TableView update(UUID id, String label, Integer seats) {
        String err = validate(label, seats);
        if (err != null) {
            ui.accept(() -> errorMessage.set(err));
            return null;
        }
        try {
            TableView v = api.update(id, label.trim(), seats);
            ui.accept(() -> errorMessage.set(""));
            return v;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public boolean deactivate(UUID id) {
        try {
            api.deactivate(id);
            ui.accept(() -> errorMessage.set(""));
            return true;
        } catch (ApiException e) {
            fail(e);
            return false;
        }
    }

    public TableView reactivate(UUID id) {
        try {
            TableView v = api.reactivate(id);
            ui.accept(() -> errorMessage.set(""));
            return v;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    private static String validate(String label, Integer seats) {
        if (label == null || label.isBlank()) {
            return "Table label is required";
        }
        if (seats == null || seats < 1) {
            return "Seats must be a positive number";
        }
        return null;
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

- [ ] **Step 5: View-model test**

Create `terminal/src/test/java/com/company/pos/terminal/viewmodel/TablesViewModelTest.java`:

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.TableAdminApi;
import com.company.pos.terminal.api.dto.TableView;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TablesViewModelTest {

    private TablesViewModel vm(TableAdminApi api) {
        return new TablesViewModel(api, Runnable::run);
    }

    @Test
    void loadTablesReturnsListAndClearsError() {
        TableAdminApi api = new TableAdminApi(null) {
            @Override public List<TableView> list() {
                return List.of(new TableView(UUID.randomUUID(), "T1", 4, true));
            }
        };
        TablesViewModel vm = vm(api);
        assertEquals(1, vm.loadTables().size());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void createRejectsBlankLabelWithoutCallingApi() {
        boolean[] called = {false};
        TableAdminApi api = new TableAdminApi(null) {
            @Override public TableView create(String label, Integer seats) {
                called[0] = true;
                return null;
            }
        };
        TablesViewModel vm = vm(api);
        assertNull(vm.create("  ", 4));
        assertFalse(called[0]);
        assertEquals("Table label is required", vm.errorMessage().get());
    }

    @Test
    void createRejectsNonPositiveSeatsWithoutCallingApi() {
        boolean[] called = {false};
        TableAdminApi api = new TableAdminApi(null) {
            @Override public TableView create(String label, Integer seats) {
                called[0] = true;
                return null;
            }
        };
        TablesViewModel vm = vm(api);
        assertNull(vm.create("T2", 0));
        assertFalse(called[0]);
        assertEquals("Seats must be a positive number", vm.errorMessage().get());
    }

    @Test
    void createTrimsAndReturnsViewOnSuccess() {
        TableAdminApi api = new TableAdminApi(null) {
            @Override public TableView create(String label, Integer seats) {
                return new TableView(UUID.randomUUID(), label, seats, true);
            }
        };
        TableView v = vm(api).create("  T3 ", 2);
        assertEquals("T3", v.label());
    }

    @Test
    void reactivateReturnsViewOnSuccess() {
        UUID id = UUID.randomUUID();
        TableAdminApi api = new TableAdminApi(null) {
            @Override public TableView reactivate(UUID tableId) {
                return new TableView(tableId, "T4", 4, true);
            }
        };
        TableView v = vm(api).reactivate(id);
        assertTrue(v.active());
    }

    @Test
    void deferredDispatcherHoldsErrorUntilDrained() {
        TableAdminApi api = new TableAdminApi(null) {
            @Override public List<TableView> list() {
                throw new ApiException(500, new ProblemDetail("Error", 500, "boom"), "HTTP 500");
            }
        };
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        TablesViewModel vm = new TablesViewModel(api, queue::add);
        assertNull(vm.loadTables());
        assertEquals("", vm.errorMessage().get());   // deferred: not applied yet
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
        assertEquals("boom", vm.errorMessage().get());
    }
}
```

- [ ] **Step 6: Row-helper test**

Create `terminal/src/test/java/com/company/pos/terminal/viewmodel/TableRowsTest.java`:

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.dto.TableView;
import com.company.pos.terminal.viewmodel.TableRows.TableRow;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TableRowsTest {

    @Test
    void counterLabelDerivesCounterType() {
        List<TableRow> rows = TableRows.build(
                List.of(new TableView(UUID.randomUUID(), "Counter 1", 1, true)), "Counter ");
        assertTrue(rows.get(0).counter());
    }

    @Test
    void plainLabelIsDineIn() {
        List<TableRow> rows = TableRows.build(
                List.of(new TableView(UUID.randomUUID(), "T1", 4, true)), "Counter ");
        assertFalse(rows.get(0).counter());
    }

    @Test
    void nullListYieldsEmpty() {
        assertEquals(0, TableRows.build(null, "Counter ").size());
    }
}
```

- [ ] **Step 7: Run the terminal tests**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=TablesViewModelTest,TableRowsTest`
Expected: PASS (6 + 3 tests).

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/TableChangeRequest.java pos-terminal/src/main/java/com/company/pos/terminal/api/TableAdminApi.java pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/TablesViewModel.java pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/TableRows.java pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/TablesViewModelTest.java pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/TableRowsTest.java
git commit -m "feat(terminal): TableAdminApi + TablesViewModel + TableRows"
```

---

### Task 4: Terminal — table form dialog (Dine-in / Counter)

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/TableFormDialog.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/view/TableFormDialogTest.java`

**Interfaces:**
- Consumes: `TableRows.TableRow` (Task 3) for the edit pre-fill.
- Produces (for Task 5): `TableFormDialog.promptForTable(String counterPrefix, TableRow current /* null on create */)` → `Optional<Result>` where `Result(String label, Integer seats)`; static `normalizeLabel(String rawLabel, boolean counter, String prefix)`.

- [ ] **Step 1: The dialog**

Create `terminal/view/TableFormDialog.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.viewmodel.TableRows.TableRow;
import java.util.Optional;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Modal to create or edit a dining table. Pure view — collects input only; the controller performs
 * all HTTP. The Dine-in / Counter toggle reconciles the label with the takeaway prefix so the admin
 * never hand-types the magic string. Only {@link #normalizeLabel} is unit-tested headlessly.
 */
public final class TableFormDialog {

    private static final String DINE_IN = "Dine-in";
    private static final String COUNTER = "Counter";

    private TableFormDialog() {
    }

    public record Result(String label, Integer seats) {
    }

    public static Optional<Result> promptForTable(String counterPrefix, TableRow current) {
        boolean editing = current != null;
        Dialog<Result> dialog = new Dialog<>();
        dialog.setTitle(editing ? "Edit table" : "New table");
        dialog.setHeaderText(editing ? "Edit " + current.label() : "Create a table");
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = new ButtonType(editing ? "Save" : "Create", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);

        TextField label = new TextField();
        label.setPromptText("label (e.g. T7)");
        if (editing) {
            label.setText(current.label());
        }

        Spinner<Integer> seats = new Spinner<>(1, 99, editing ? current.seats() : 2);
        seats.setEditable(true);

        ComboBox<String> type = new ComboBox<>(FXCollections.observableArrayList(DINE_IN, COUNTER));
        type.setValue(editing && current.counter() ? COUNTER : DINE_IN);

        VBox box = new VBox(12, field("Label", label), field("Seats", seats), field("Type", type));
        box.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(
                normalizeLabel(label.getText(), COUNTER.equals(type.getValue()), counterPrefix) == null);
        label.textProperty().addListener((o, a, b) -> revalidate.run());
        type.valueProperty().addListener((o, a, b) -> revalidate.run());
        revalidate.run();

        dialog.setResultConverter(bt -> {
            if (bt != submit) {
                return null;
            }
            String finalLabel = normalizeLabel(label.getText(), COUNTER.equals(type.getValue()), counterPrefix);
            if (finalLabel == null) {
                return null;
            }
            return new Result(finalLabel, seats.getValue());
        });
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    /** Reconcile the typed label with the type toggle: a Counter carries the prefix exactly once, a
     *  Dine-in carries no prefix. Trims; returns null when blank (or when stripping leaves nothing). */
    static String normalizeLabel(String rawLabel, boolean counter, String prefix) {
        if (rawLabel == null) {
            return null;
        }
        String t = rawLabel.trim();
        if (t.isEmpty()) {
            return null;
        }
        boolean hasPrefix = prefix != null && !prefix.isBlank() && t.startsWith(prefix);
        if (counter) {
            if (prefix == null || prefix.isBlank()) {
                return t;
            }
            return hasPrefix ? t : prefix + t;
        }
        if (hasPrefix) {
            String base = t.substring(prefix.length()).trim();
            return base.isEmpty() ? null : base;
        }
        return t;
    }

    private static VBox field(String labelText, Node control) {
        Label l = new Label(labelText);
        l.getStyleClass().add("field-label");
        VBox b = new VBox(6, l, control);
        b.getStyleClass().add("field");
        return b;
    }
}
```

- [ ] **Step 2: The test**

Create `terminal/src/test/java/com/company/pos/terminal/view/TableFormDialogTest.java`:

```java
package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TableFormDialogTest {

    @Test
    void counterPrependsPrefixOnce() {
        assertEquals("Counter 5", TableFormDialog.normalizeLabel("5", true, "Counter "));
        assertEquals("Counter 5", TableFormDialog.normalizeLabel("Counter 5", true, "Counter "));
    }

    @Test
    void dineInStripsPrefix() {
        assertEquals("5", TableFormDialog.normalizeLabel("Counter 5", false, "Counter "));
        assertEquals("T3", TableFormDialog.normalizeLabel(" T3 ", false, "Counter "));
    }

    @Test
    void blankIsNull() {
        assertNull(TableFormDialog.normalizeLabel("   ", true, "Counter "));
        assertNull(TableFormDialog.normalizeLabel(null, false, "Counter "));
    }
}
```

- [ ] **Step 3: Run the test**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=TableFormDialogTest`
Expected: PASS (3 tests).

- [ ] **Step 4: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/TableFormDialog.java pos-terminal/src/test/java/com/company/pos/terminal/view/TableFormDialogTest.java
git commit -m "feat(terminal): I/O-free TableFormDialog with dine-in/counter toggle"
```

---

### Task 5: Terminal — screen, wiring, admin tile

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/TablesController.java`
- Create: `pos-terminal/src/main/resources/fxml/tables.fxml`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/AdminController.java`
- Modify: `pos-terminal/src/main/resources/fxml/admin.fxml`

**Interfaces:**
- Consumes: `TablesViewModel`, `TableRows`/`TableRow` (Task 3), `TableFormDialog` (Task 4), existing `Services`/`Navigator`/`FxTasks`, `services.config.takeawayLabelPrefix()`.

- [ ] **Step 1: Register the API client in Services**

In `terminal/app/Services.java`: add the import `import com.company.pos.terminal.api.TableAdminApi;`, add the field `public final TableAdminApi tableAdminApi;` next to `kitchenApi`, and initialize it in the constructor `this.tableAdminApi = new TableAdminApi(apiClient);` (after `this.kitchenApi = new KitchenApi(apiClient);`).

- [ ] **Step 2: The controller**

Create `terminal/view/TablesController.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.TableRows;
import com.company.pos.terminal.viewmodel.TableRows.TableRow;
import com.company.pos.terminal.viewmodel.TablesViewModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;

/**
 * MANAGER/ADMIN dining-tables screen. Loads tables off the FX thread via FxTasks, maps them into
 * rows (TableRows, deriving counter vs dine-in from the label prefix), and creates/edits/deactivates/
 * reactivates through the I/O-free TableFormDialog. Search filters the cached rows client-side.
 * (The dto TableView is fully-qualified in the reload holder to avoid a clash with javafx TableView.)
 */
public class TablesController {

    private static final System.Logger LOG = System.getLogger(TablesController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final TablesViewModel vm;
    private final String counterPrefix;

    private List<TableRow> allRows = new ArrayList<>();

    @FXML private Label errorLabel;
    @FXML private TextField searchField;
    @FXML private Button backButton;
    @FXML private Button newButton;
    @FXML private Button editButton;
    @FXML private Button deactivateButton;
    @FXML private Button reactivateButton;
    @FXML private TableView<TableRow> table;
    @FXML private TableColumn<TableRow, String> labelCol;
    @FXML private TableColumn<TableRow, String> seatsCol;
    @FXML private TableColumn<TableRow, String> typeCol;
    @FXML private TableColumn<TableRow, String> statusCol;

    public TablesController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.counterPrefix = services.config.takeawayLabelPrefix();
        this.vm = new TablesViewModel(services.tableAdminApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        labelCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().label()));
        seatsCol.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().seats())));
        typeCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().counter() ? "Counter" : "Dine-in"));
        statusCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().active() ? "Active" : "Inactive"));

        errorLabel.textProperty().bind(vm.errorMessage());
        searchField.textProperty().addListener((o, a, b) -> applyFilter());
        table.getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> refreshButtons(sel));

        backButton.setOnAction(e -> navigator.toAdmin());
        newButton.setOnAction(e -> createTable());
        editButton.setOnAction(e -> editSelected());
        deactivateButton.setOnAction(e -> deactivateSelected());
        reactivateButton.setOnAction(e -> reactivateSelected());

        refreshButtons(null);
        reload();
    }

    @SuppressWarnings("unchecked")
    private void reload() {
        final List<com.company.pos.terminal.api.dto.TableView>[] th = new List[1];
        FxTasks.run(
                () -> th[0] = vm.loadTables(),
                () -> {
                    if (th[0] != null) {
                        allRows = TableRows.build(th[0], counterPrefix);
                        applyFilter();
                    }
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Load tables failed", err));
    }

    private void applyFilter() {
        String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        List<TableRow> shown = allRows.stream()
                .filter(r -> q.isEmpty() || r.label().toLowerCase().contains(q))
                .toList();
        table.setItems(FXCollections.observableArrayList(shown));
    }

    private void refreshButtons(TableRow sel) {
        editButton.setDisable(sel == null);
        deactivateButton.setDisable(sel == null || !sel.active());
        reactivateButton.setDisable(sel == null || sel.active());
    }

    private void createTable() {
        Optional<TableFormDialog.Result> r = TableFormDialog.promptForTable(counterPrefix, null);
        r.ifPresent(res -> {
            final com.company.pos.terminal.api.dto.TableView[] holder =
                    new com.company.pos.terminal.api.dto.TableView[1];
            FxTasks.run(() -> holder[0] = vm.create(res.label(), res.seats()),
                    () -> { if (holder[0] != null) reload(); },
                    err -> LOG.log(System.Logger.Level.ERROR, "Create table failed", err));
        });
    }

    private void editSelected() {
        TableRow sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        Optional<TableFormDialog.Result> r = TableFormDialog.promptForTable(counterPrefix, sel);
        r.ifPresent(res -> {
            final com.company.pos.terminal.api.dto.TableView[] holder =
                    new com.company.pos.terminal.api.dto.TableView[1];
            FxTasks.run(() -> holder[0] = vm.update(sel.id(), res.label(), res.seats()),
                    () -> { if (holder[0] != null) reload(); },
                    err -> LOG.log(System.Logger.Level.ERROR, "Update table failed", err));
        });
    }

    private void deactivateSelected() {
        TableRow sel = table.getSelectionModel().getSelectedItem();
        if (sel == null || !sel.active()) {
            return;
        }
        final boolean[] holder = {false};
        FxTasks.run(() -> holder[0] = vm.deactivate(sel.id()),
                () -> { if (holder[0]) reload(); },
                err -> LOG.log(System.Logger.Level.ERROR, "Deactivate table failed", err));
    }

    private void reactivateSelected() {
        TableRow sel = table.getSelectionModel().getSelectedItem();
        if (sel == null || sel.active()) {
            return;
        }
        final com.company.pos.terminal.api.dto.TableView[] holder =
                new com.company.pos.terminal.api.dto.TableView[1];
        FxTasks.run(() -> holder[0] = vm.reactivate(sel.id()),
                () -> { if (holder[0] != null) reload(); },
                err -> LOG.log(System.Logger.Level.ERROR, "Reactivate table failed", err));
    }
}
```

- [ ] **Step 3: The FXML**

Create `terminal/src/main/resources/fxml/tables.fxml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.scene.control.Button?>
<?import javafx.scene.control.Label?>
<?import javafx.scene.control.TableColumn?>
<?import javafx.scene.control.TableView?>
<?import javafx.scene.control.TextField?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.layout.Pane?>
<?import javafx.scene.layout.VBox?>

<VBox styleClass="screen" spacing="16" xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml">
  <HBox spacing="16" alignment="CENTER_LEFT">
    <Label text="Dining tables" styleClass="title"/>
    <Pane HBox.hgrow="ALWAYS"/>
    <Label fx:id="errorLabel" styleClass="error-text"/>
    <TextField fx:id="searchField" promptText="Search label"/>
    <Button fx:id="backButton" text="Back" styleClass="btn-secondary"/>
  </HBox>

  <TableView fx:id="table" VBox.vgrow="ALWAYS">
    <columns>
      <TableColumn fx:id="labelCol" text="Label" prefWidth="260"/>
      <TableColumn fx:id="seatsCol" text="Seats" prefWidth="100"/>
      <TableColumn fx:id="typeCol" text="Type" prefWidth="140"/>
      <TableColumn fx:id="statusCol" text="Status" prefWidth="140"/>
    </columns>
  </TableView>

  <HBox spacing="12" alignment="CENTER_LEFT">
    <Button fx:id="newButton" text="New" styleClass="btn-primary"/>
    <Button fx:id="editButton" text="Edit"/>
    <Button fx:id="deactivateButton" text="Deactivate"/>
    <Button fx:id="reactivateButton" text="Reactivate"/>
  </HBox>
</VBox>
```

- [ ] **Step 4: Navigator route**

In `terminal/app/Navigator.java`, after the `toKitchenRouting()` method, add:

```java
    public void toTables() {
        com.company.pos.terminal.view.TablesController controller =
                new com.company.pos.terminal.view.TablesController(services, this);
        setScene("/fxml/tables.fxml", controller);
    }
```

- [ ] **Step 5: Admin tile — controller**

In `terminal/view/AdminController.java`, add the field (next to `kitchenButton`):

```java
    @FXML private Button tablesButton;
```

and in `initialize()`, after the `kitchenButton` block, add (same MANAGER+ADMIN gate):

```java
        tablesButton.setVisible(manager);
        tablesButton.setManaged(manager);
        tablesButton.setOnAction(e -> navigator.toTables());
```

- [ ] **Step 6: Admin tile — FXML**

In `terminal/src/main/resources/fxml/admin.fxml`, add the tile after the `kitchenButton` button, inside the tiles `HBox`:

```xml
      <Button fx:id="tablesButton" text="Tables" styleClass="home-tile"/>
```

- [ ] **Step 7: Run the full terminal suite**

Run: `./mvnw -f pos-terminal/pom.xml clean test`
Expected: PASS (full suite, including the new `TablesViewModelTest`, `TableRowsTest`, `TableFormDialogTest`; the FXML loads with the exact `fx:id` set matching the controller).

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/TablesController.java pos-terminal/src/main/resources/fxml/tables.fxml pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java pos-terminal/src/main/java/com/company/pos/terminal/view/AdminController.java pos-terminal/src/main/resources/fxml/admin.fxml
git commit -m "feat(terminal): Dining tables admin screen + Tables tile (MANAGER/ADMIN)"
```

---

## Self-Review

**Spec coverage:** create/edit/deactivate/reactivate backend (Task 1) ✓; deactivate open-order guard (Task 1) ✓; event-based audit + one-way dependency (Task 2) ✓; MANAGER endpoints + MANAGER/ADMIN tile (Tasks 1, 5) ✓; TableAdminApi / TablesViewModel / TableRows (Task 3) ✓; I/O-free dialog with dine-in/counter toggle reusing the prefix (Task 4) ✓; screen with Label/Seats/Type/Status + search + actions (Task 5) ✓; no migration ✓; async-dispatcher regression + blank/seats guards + prefix normalize tests ✓.

**Type consistency:** `TableChanged(entityRef, type, actor, label, seats, active)` produced in Task 1, consumed identically in Task 2. `TableRow(id, label, seats, active, counter)` produced in Task 3, consumed in Tasks 4/5. `TableFormDialog.Result(label, seats)` produced in Task 4, consumed in Task 5. `TableAdminApi` method signatures match the VM calls. `reactivateTable`/`updateTable` return `TableView` (the controller returns them) — this refines the design spec's `void reactivateTable` to `TableView` so the `POST …/reactivate` endpoint can return the updated view; noted as intentional.

**Placeholder scan:** none — every step carries full code or an exact edit target.

**Note on the dto vs javafx `TableView` name clash (Task 5):** the controller imports `javafx.scene.control.TableView` and fully-qualifies `com.company.pos.terminal.api.dto.TableView` only in the reload/mutation holders, so there is no ambiguous import.
