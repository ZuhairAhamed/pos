# Staff / User Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add full-lifecycle staff/user management (backend endpoints + terminal Admin UI) so a restaurant can onboard, edit, and deactivate staff logins in production.

**Architecture:** New `UsersController` + `UserAdminService` inside the existing `auth` module (no new module, no new module dependency — `auth` already allows `audit :: api`). New DTOs in `auth.api`. On the terminal, a new `UsersApi` + `UserAdminViewModel` follow the established synchronous-VM / `FxTasks` / I/O-free-dialog conventions, surfaced through a new shared **Admin area** (Home → Admin → Staff). First-admin bootstrap is a documented manual DB insert.

**Tech Stack:** Java 21, Spring Boot 3.3 (Spring Modulith, Spring Security method security, Spring Data JPA, BCrypt), JavaFX 21 (terminal, separate Maven build), JUnit 5 + AssertJ + MockMvc.

## Global Constraints

- **JDK 21 required.** Set `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` before any Maven command (system default is 17).
- **Backend build/test:** `./mvnw` from repo root. Terminal is a **separate build**: `./mvnw -f pos-terminal/pom.xml ...` — the root reactor does not touch it.
- **Module boundaries are enforced** by `ModularityTests` (`./mvnw test -Dtest=ModularityTests`). This work adds no cross-module dependency; keep it that way.
- **Authorization is method security** — role checks are `@PreAuthorize` on the web/application layer, exact form `@PreAuthorize("hasRole('ADMIN')")`. Spring maps JWT `roles` claim → `ROLE_*` authorities.
- **Passwords and PINs are BCrypt-hashed** via the shared `PasswordEncoder` bean (`BCryptPasswordEncoder`) before persist — never store raw.
- **No Flyway migration** in this sub-project — the `users` table and its `enabled`/`roles` columns already exist; `embedded` uses Hibernate `ddl-auto`, only `store-server` runs Flyway.
- **`UserAdminService` must NOT carry a class-level `@Transactional`** — mirrors `AuthService`: on SQLite (single connection) an outer transaction plus the audit trail's `REQUIRES_NEW` write would deadlock.
- **Terminal FX-threading convention:** ViewModel methods are synchronous and return plain values; the controller runs them off the FX thread via `FxTasks.run(work, onDone, onError)` and reads results in `onDone` via a `holder` array. The only observable a VM writes off-thread is `errorMessage`, inside `ui.accept(...)`. Every VM gets an async-dispatcher regression test (a deferred, undrained `ui` dispatcher). Dialogs are I/O-free (collect input only; the controller does all HTTP).
- **Money is `BigDecimal`** (not relevant to this sub-project — no money handling here).

---

## File Structure

**Backend (all under `src/main/java/com/company/pos/auth/`):**
- `api/CreateUserCommand.java`, `api/UpdateUserCommand.java`, `api/ResetCredentialRequest.java`, `api/UserView.java` — new public DTOs.
- `domain/User.java` — add behavior methods (modify).
- `application/UserAdminService.java` — new use-case service.
- `web/UsersController.java` — new REST controller.
- `../audit/api/AuditAction.java` — add `USER_*` enum values (modify).

**Backend tests (under `src/test/java/com/company/pos/auth/`):**
- `domain/UserBehaviorTest.java`, `UserAdminServiceTest.java`, `UsersControllerTest.java`.

**Terminal (all under `pos-terminal/src/main/java/com/company/pos/terminal/`):**
- `api/UserView.java`, `api/CreateUserRequest.java`, `api/UpdateUserRequest.java`, `api/ResetCredentialRequest.java`, `api/UsersApi.java` — new.
- `viewmodel/UserAdminViewModel.java` — new.
- `view/UserFormDialog.java`, `view/ResetCredentialDialog.java` — new I/O-free dialogs.
- `view/StaffController.java`, `view/AdminController.java` — new screens.
- `view/HomeController.java` — add Admin button (modify).
- `app/Navigator.java` — add `toAdmin()` / `toStaff()` (modify).
- `app/Services.java` — add `usersApi` (modify).
- `resources/fxml/staff.fxml`, `resources/fxml/admin.fxml` — new.
- `resources/fxml/home.fxml` — add Admin button (modify).

**Terminal tests (under `pos-terminal/src/test/java/com/company/pos/terminal/`):**
- `viewmodel/UserAdminViewModelTest.java`, `view/UserFormDialogTest.java`.

**Docs:**
- `docs/operations/first-admin.md` — new runbook.

---

## Task 1: Backend — DTOs, audit actions, and User domain behavior

**Files:**
- Create: `src/main/java/com/company/pos/auth/api/CreateUserCommand.java`
- Create: `src/main/java/com/company/pos/auth/api/UpdateUserCommand.java`
- Create: `src/main/java/com/company/pos/auth/api/ResetCredentialRequest.java`
- Create: `src/main/java/com/company/pos/auth/api/UserView.java`
- Modify: `src/main/java/com/company/pos/audit/api/AuditAction.java`
- Modify: `src/main/java/com/company/pos/auth/domain/User.java`
- Test: `src/test/java/com/company/pos/auth/domain/UserBehaviorTest.java`

**Interfaces:**
- Produces (consumed by Tasks 2–3): the four `auth.api` records; `User` methods `rename(String)`, `changeRoles(Set<Role>)`, `resetPassword(String)`, `resetPin(String)`, `enable()`, `disable()`; `AuditAction.USER_CREATED/USER_UPDATED/USER_DEACTIVATED/USER_REACTIVATED/USER_CREDENTIAL_RESET`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/auth/domain/UserBehaviorTest.java`:

```java
package com.company.pos.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.auth.api.Role;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserBehaviorTest {

    private User newUser() {
        return new User(UUID.randomUUID(), "alice", "Alice", "hash", Set.of(Role.CASHIER));
    }

    @Test
    void renameChangesDisplayName() {
        User u = newUser();
        u.rename("Alice Cooper");
        assertThat(u.getDisplayName()).isEqualTo("Alice Cooper");
    }

    @Test
    void changeRolesReplacesRoleSet() {
        User u = newUser();
        u.changeRoles(Set.of(Role.MANAGER, Role.ADMIN));
        assertThat(u.getRoles()).containsExactlyInAnyOrder(Role.MANAGER, Role.ADMIN);
    }

    @Test
    void disableThenEnableTogglesEnabled() {
        User u = newUser();
        u.disable();
        assertThat(u.isEnabled()).isFalse();
        u.enable();
        assertThat(u.isEnabled()).isTrue();
    }

    @Test
    void resetPinCanClearIt() {
        User u = newUser();
        u.resetPin("pinHash");
        assertThat(u.getPinHash()).isEqualTo("pinHash");
        u.resetPin(null);
        assertThat(u.getPinHash()).isNull();
    }

    @Test
    void resetPasswordReplacesHash() {
        User u = newUser();
        u.resetPassword("newHash");
        assertThat(u.getPasswordHash()).isEqualTo("newHash");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=UserBehaviorTest`
Expected: COMPILE FAILURE — `rename`, `changeRoles`, `resetPassword`, `resetPin`, `enable`, `disable` do not exist on `User`.

- [ ] **Step 3: Add the domain behavior methods**

In `src/main/java/com/company/pos/auth/domain/User.java`, add these methods to the class body (field names `displayName`, `roles`, `passwordHash`, `pinHash`, `enabled` already exist). `username` stays immutable — no setter:

```java
    /** Changes the human-facing name. Username stays immutable (it is the actor key on history rows). */
    public void rename(String displayName) {
        this.displayName = displayName;
    }

    /** Replaces the role set wholesale. */
    public void changeRoles(java.util.Set<com.company.pos.auth.api.Role> roles) {
        this.roles = roles;
    }

    /** Replaces the BCrypt password hash (already encoded by the caller). */
    public void resetPassword(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /** Replaces (or clears, when null) the BCrypt PIN hash (already encoded by the caller). */
    public void resetPin(String pinHash) {
        this.pinHash = pinHash;
    }

    /** Marks the account usable. */
    public void enable() {
        this.enabled = true;
    }

    /** Soft-disables the account (never hard-deleted — history references the username). */
    public void disable() {
        this.enabled = false;
    }
```

All of `getId()`, `getUsername()`, `getCashierCode()`, `getPasswordHash()`, `getPinHash()`, `getDisplayName()`, `getRoles()`, `isEnabled()` already exist on `User` (verified) — the new behavior methods are the only additions. `username` has no setter and gets none. The `enabled` field already defaults to `true`, so a newly created user is enabled without any extra call.

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=UserBehaviorTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Add the audit actions and the four DTOs**

Modify `src/main/java/com/company/pos/audit/api/AuditAction.java` — append to the enum:

```java
    USER_CREATED,
    USER_UPDATED,
    USER_DEACTIVATED,
    USER_REACTIVATED,
    USER_CREDENTIAL_RESET
```

(Ensure the value before your first addition ends with a comma.)

Create `src/main/java/com/company/pos/auth/api/CreateUserCommand.java`:

```java
package com.company.pos.auth.api;

import java.util.Set;

/** Request to create a staff user. Password required; cashierCode + pin optional
 *  (needed only for PIN login and manager-PIN approval at the terminal). */
public record CreateUserCommand(String username, String displayName, String password,
        Set<Role> roles, String cashierCode, String pin) {}
```

Create `src/main/java/com/company/pos/auth/api/UpdateUserCommand.java`:

```java
package com.company.pos.auth.api;

import java.util.Set;

/** Request to edit a user's display name and roles. Username is immutable; credentials
 *  are changed via the dedicated reset endpoints. */
public record UpdateUserCommand(String displayName, Set<Role> roles) {}
```

Create `src/main/java/com/company/pos/auth/api/ResetCredentialRequest.java`:

```java
package com.company.pos.auth.api;

/** New credential value: a new password, or a new PIN (blank clears the PIN). */
public record ResetCredentialRequest(String value) {}
```

Create `src/main/java/com/company/pos/auth/api/UserView.java`:

```java
package com.company.pos.auth.api;

import java.util.Set;
import java.util.UUID;

/** Read model for a staff user. Never exposes password or PIN hashes. */
public record UserView(UUID id, String username, String displayName, String cashierCode,
        Set<Role> roles, boolean enabled) {}
```

- [ ] **Step 6: Compile to verify DTOs and enum**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -q test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/pos/auth/api src/main/java/com/company/pos/auth/domain/User.java src/main/java/com/company/pos/audit/api/AuditAction.java src/test/java/com/company/pos/auth/domain/UserBehaviorTest.java
git commit -m "feat(auth): user DTOs, audit actions, and User lifecycle methods

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Backend — UserAdminService

**Files:**
- Create: `src/main/java/com/company/pos/auth/application/UserAdminService.java`
- Test: `src/test/java/com/company/pos/auth/UserAdminServiceTest.java`

**Interfaces:**
- Consumes: `auth.api` DTOs + `User` methods (Task 1); `UserRepository` (`findByUsername`, `findByCashierCode`, `findAll`, `findById`, `save`); `PasswordEncoder` bean; `com.company.pos.audit.api.AuditService.record(AuditAction, String, String, Map<String,String>)`; `DomainException.{validation,conflict,notFound}`.
- Produces (consumed by Task 3): public methods `createUser(CreateUserCommand) → UserView`, `listUsers(boolean) → List<UserView>`, `getUser(UUID) → UserView`, `updateUser(UUID, UpdateUserCommand) → UserView`, `resetPassword(UUID, ResetCredentialRequest)`, `resetPin(UUID, ResetCredentialRequest)`, `deactivate(UUID)`, `reactivate(UUID)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/auth/UserAdminServiceTest.java`:

```java
package com.company.pos.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.auth.api.CreateUserCommand;
import com.company.pos.auth.api.Role;
import com.company.pos.auth.api.ResetCredentialRequest;
import com.company.pos.auth.api.UpdateUserCommand;
import com.company.pos.auth.api.UserView;
import com.company.pos.auth.application.UserAdminService;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.exception.DomainException;
import com.company.pos.support.DatabaseCleaner;
import java.util.Set;
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
class UserAdminServiceTest {

    @Autowired
    UserAdminService svc;

    @Autowired
    UserRepository repo;

    @Autowired
    DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    @Test
    void createsUserWithHashedCredentials() {
        UserView v = svc.createUser(new CreateUserCommand(
                "alice", "Alice", "pw", Set.of(Role.CASHIER), "0002", "1234"));
        assertThat(v.id()).isNotNull();
        assertThat(v.username()).isEqualTo("alice");
        var saved = repo.findByUsername("alice").orElseThrow();
        assertThat(saved.getPasswordHash()).isNotEqualTo("pw");   // hashed
        assertThat(saved.getPinHash()).isNotNull();
    }

    @Test
    void rejectsDuplicateUsername() {
        svc.createUser(new CreateUserCommand("bob", "Bob", "pw", Set.of(Role.CASHIER), null, null));
        assertThatThrownBy(() -> svc.createUser(
                new CreateUserCommand("bob", "Bob2", "pw", Set.of(Role.CASHIER), null, null)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsDuplicateCashierCode() {
        svc.createUser(new CreateUserCommand("bob", "Bob", "pw", Set.of(Role.CASHIER), "0005", null));
        assertThatThrownBy(() -> svc.createUser(
                new CreateUserCommand("carol", "Carol", "pw", Set.of(Role.CASHIER), "0005", null)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void listExcludesDisabledUnlessAsked() {
        UserView admin = svc.createUser(new CreateUserCommand("root", "Root", "pw", Set.of(Role.ADMIN), null, null));
        UserView bob = svc.createUser(new CreateUserCommand("bob", "Bob", "pw", Set.of(Role.CASHIER), null, null));
        svc.deactivate(bob.id());
        assertThat(svc.listUsers(false)).extracting(UserView::username).containsExactlyInAnyOrder("root");
        assertThat(svc.listUsers(true)).extracting(UserView::username).containsExactlyInAnyOrder("root", "bob");
        assertThat(admin).isNotNull();
    }

    @Test
    void cannotDeactivateLastAdmin() {
        UserView admin = svc.createUser(new CreateUserCommand("root", "Root", "pw", Set.of(Role.ADMIN), null, null));
        assertThatThrownBy(() -> svc.deactivate(admin.id())).isInstanceOf(DomainException.class);
    }

    @Test
    void canDeactivateAdminWhenAnotherEnabledAdminExists() {
        UserView a1 = svc.createUser(new CreateUserCommand("root", "Root", "pw", Set.of(Role.ADMIN), null, null));
        svc.createUser(new CreateUserCommand("root2", "Root2", "pw", Set.of(Role.ADMIN), null, null));
        svc.deactivate(a1.id());
        assertThat(svc.getUser(a1.id()).enabled()).isFalse();
    }

    @Test
    void updateCannotRemoveLastAdminRole() {
        UserView admin = svc.createUser(new CreateUserCommand("root", "Root", "pw", Set.of(Role.ADMIN), null, null));
        assertThatThrownBy(() -> svc.updateUser(admin.id(),
                new UpdateUserCommand("Root", Set.of(Role.CASHIER))))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void resetPinBlankClearsIt() {
        UserView v = svc.createUser(new CreateUserCommand("cara", "Cara", "pw", Set.of(Role.CASHIER), "0003", "1111"));
        svc.resetPin(v.id(), new ResetCredentialRequest(""));
        assertThat(repo.findById(v.id()).orElseThrow().getPinHash()).isNull();
    }

    @Test
    void reactivateReenablesUser() {
        svc.createUser(new CreateUserCommand("root", "Root", "pw", Set.of(Role.ADMIN), null, null));
        UserView bob = svc.createUser(new CreateUserCommand("bob", "Bob", "pw", Set.of(Role.CASHIER), null, null));
        svc.deactivate(bob.id());
        svc.reactivate(bob.id());
        assertThat(svc.getUser(bob.id()).enabled()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=UserAdminServiceTest`
Expected: COMPILE FAILURE — `UserAdminService` does not exist.

- [ ] **Step 3: Write the service**

Create `src/main/java/com/company/pos/auth/application/UserAdminService.java`:

```java
package com.company.pos.auth.application;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.audit.api.AuditService;
import com.company.pos.auth.api.CreateUserCommand;
import com.company.pos.auth.api.ResetCredentialRequest;
import com.company.pos.auth.api.Role;
import com.company.pos.auth.api.UpdateUserCommand;
import com.company.pos.auth.api.UserView;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.exception.DomainException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Staff-user lifecycle use cases. Deliberately carries NO class-level {@code @Transactional}:
 * like {@link AuthService}, it writes to the tamper-evident audit trail via {@code REQUIRES_NEW},
 * which on single-connection SQLite would deadlock inside an outer transaction. Each method does
 * its repository write (auto-committed) then records audit separately. Uniqueness is pre-checked
 * for a friendly error and backed by DB unique constraints for the race.
 */
@Service
public class UserAdminService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public UserAdminService(UserRepository users, PasswordEncoder encoder, AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
    }

    public UserView createUser(CreateUserCommand cmd) {
        if (cmd.username() == null || cmd.username().isBlank()) {
            throw DomainException.validation("Username is required");
        }
        if (cmd.password() == null || cmd.password().isBlank()) {
            throw DomainException.validation("Password is required");
        }
        if (cmd.roles() == null || cmd.roles().isEmpty()) {
            throw DomainException.validation("At least one role is required");
        }
        String username = cmd.username().trim();
        if (users.findByUsername(username).isPresent()) {
            throw DomainException.conflict("Username already in use");
        }
        String cashierCode = trimToNull(cmd.cashierCode());
        if (cashierCode != null && users.findByCashierCode(cashierCode).isPresent()) {
            throw DomainException.conflict("Cashier code already in use");
        }
        User u = new User(UUID.randomUUID(), username, cmd.displayName(),
                encoder.encode(cmd.password()), Set.copyOf(cmd.roles()));
        if (cashierCode != null) {
            u.setCashierCode(cashierCode);
        }
        String pin = trimToNull(cmd.pin());
        if (pin != null) {
            u.setPinHash(encoder.encode(pin));
        }
        users.save(u);
        audit.record(AuditAction.USER_CREATED, actor(), username, Map.of("roles", rolesStr(cmd.roles())));
        return toView(u);
    }

    public List<UserView> listUsers(boolean includeDisabled) {
        return users.findAll().stream()
                .filter(u -> includeDisabled || u.isEnabled())
                .map(this::toView)
                .toList();
    }

    public UserView getUser(UUID id) {
        return users.findById(id).map(this::toView)
                .orElseThrow(() -> DomainException.notFound("No user " + id));
    }

    public UserView updateUser(UUID id, UpdateUserCommand cmd) {
        if (cmd.roles() == null || cmd.roles().isEmpty()) {
            throw DomainException.validation("At least one role is required");
        }
        User u = users.findById(id).orElseThrow(() -> DomainException.notFound("No user " + id));
        boolean losesAdmin = u.getRoles().contains(Role.ADMIN) && !cmd.roles().contains(Role.ADMIN);
        if (u.isEnabled() && losesAdmin && isLastEnabledAdmin(u)) {
            throw DomainException.validation("Cannot remove the last administrator");
        }
        u.rename(cmd.displayName());
        u.changeRoles(Set.copyOf(cmd.roles()));
        users.save(u);
        audit.record(AuditAction.USER_UPDATED, actor(), u.getUsername(), Map.of("roles", rolesStr(cmd.roles())));
        return toView(u);
    }

    public void resetPassword(UUID id, ResetCredentialRequest req) {
        if (req == null || req.value() == null || req.value().isBlank()) {
            throw DomainException.validation("Password is required");
        }
        User u = users.findById(id).orElseThrow(() -> DomainException.notFound("No user " + id));
        u.resetPassword(encoder.encode(req.value()));
        users.save(u);
        audit.record(AuditAction.USER_CREDENTIAL_RESET, actor(), u.getUsername(), Map.of("credential", "password"));
    }

    public void resetPin(UUID id, ResetCredentialRequest req) {
        User u = users.findById(id).orElseThrow(() -> DomainException.notFound("No user " + id));
        String pin = req == null ? null : trimToNull(req.value());
        u.resetPin(pin == null ? null : encoder.encode(pin));
        users.save(u);
        audit.record(AuditAction.USER_CREDENTIAL_RESET, actor(), u.getUsername(), Map.of("credential", "pin"));
    }

    public void deactivate(UUID id) {
        User u = users.findById(id).orElseThrow(() -> DomainException.notFound("No user " + id));
        if (u.isEnabled() && u.getRoles().contains(Role.ADMIN) && isLastEnabledAdmin(u)) {
            throw DomainException.validation("Cannot deactivate the last administrator");
        }
        u.disable();
        users.save(u);
        audit.record(AuditAction.USER_DEACTIVATED, actor(), u.getUsername(), Map.of());
    }

    public void reactivate(UUID id) {
        User u = users.findById(id).orElseThrow(() -> DomainException.notFound("No user " + id));
        u.enable();
        users.save(u);
        audit.record(AuditAction.USER_REACTIVATED, actor(), u.getUsername(), Map.of());
    }

    /** True when {@code candidate} is the only enabled user still holding ADMIN. */
    private boolean isLastEnabledAdmin(User candidate) {
        return users.findAll().stream()
                .filter(User::isEnabled)
                .filter(u -> u.getRoles().contains(Role.ADMIN))
                .noneMatch(u -> !u.getId().equals(candidate.getId()));
    }

    private UserView toView(User u) {
        return new UserView(u.getId(), u.getUsername(), u.getDisplayName(),
                u.getCashierCode(), u.getRoles(), u.isEnabled());
    }

    private static String rolesStr(Set<Role> roles) {
        return roles.stream().map(Role::name).sorted().collect(Collectors.joining(","));
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

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=UserAdminServiceTest`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/pos/auth/application/UserAdminService.java src/test/java/com/company/pos/auth/UserAdminServiceTest.java
git commit -m "feat(auth): UserAdminService with full lifecycle and last-admin guard

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Backend — UsersController (ADMIN-gated REST)

**Files:**
- Create: `src/main/java/com/company/pos/auth/web/UsersController.java`
- Test: `src/test/java/com/company/pos/auth/UsersControllerTest.java`

**Interfaces:**
- Consumes: `UserAdminService` (Task 2), `auth.api` DTOs (Task 1).
- Produces (consumed by the terminal over HTTP): `POST /users`, `GET /users?includeDisabled=`, `GET /users/{id}`, `PUT /users/{id}`, `POST /users/{id}/reset-password`, `POST /users/{id}/reset-pin`, `POST /users/{id}/deactivate`, `POST /users/{id}/reactivate` — all `hasRole('ADMIN')`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/auth/UsersControllerTest.java`:

```java
package com.company.pos.auth;

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
class UsersControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("root"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor cashier() {
        return jwt().jwt(j -> j.subject("cashier"))
                .authorities(new SimpleGrantedAuthority("ROLE_CASHIER"));
    }

    private static final String ALICE =
            "{\"username\":\"alice\",\"displayName\":\"Alice\",\"password\":\"pw\",\"roles\":[\"CASHIER\"]}";

    @Test
    void cashierCannotCreateUser() throws Exception {
        mvc.perform(post("/users").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON).content(ALICE))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCreatesUserAndHashesAreNeverExposed() throws Exception {
        mvc.perform(post("/users").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(ALICE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.pinHash").doesNotExist());
    }

    @Test
    void adminListsUsers() throws Exception {
        mvc.perform(post("/users").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(ALICE)).andExpect(status().isCreated());
        mvc.perform(get("/users").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("alice"));
    }

    @Test
    void cashierCannotListUsers() throws Exception {
        mvc.perform(get("/users").with(cashier())).andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=UsersControllerTest`
Expected: FAIL — no handler mapped for `/users` (404), because `UsersController` does not exist yet.

- [ ] **Step 3: Write the controller**

Create `src/main/java/com/company/pos/auth/web/UsersController.java`:

```java
package com.company.pos.auth.web;

import com.company.pos.auth.api.CreateUserCommand;
import com.company.pos.auth.api.ResetCredentialRequest;
import com.company.pos.auth.api.UpdateUserCommand;
import com.company.pos.auth.api.UserView;
import com.company.pos.auth.application.UserAdminService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class UsersController {

    private final UserAdminService users;

    UsersController(UserAdminService users) {
        this.users = users;
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    UserView create(@RequestBody CreateUserCommand body) {
        return users.createUser(body);
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    List<UserView> list(@RequestParam(name = "includeDisabled", defaultValue = "false") boolean includeDisabled) {
        return users.listUsers(includeDisabled);
    }

    @GetMapping("/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    UserView get(@PathVariable UUID id) {
        return users.getUser(id);
    }

    @PutMapping("/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    UserView update(@PathVariable UUID id, @RequestBody UpdateUserCommand body) {
        return users.updateUser(id, body);
    }

    @PostMapping("/users/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void resetPassword(@PathVariable UUID id, @RequestBody ResetCredentialRequest body) {
        users.resetPassword(id, body);
    }

    @PostMapping("/users/{id}/reset-pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void resetPin(@PathVariable UUID id, @RequestBody ResetCredentialRequest body) {
        users.resetPin(id, body);
    }

    @PostMapping("/users/{id}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void deactivate(@PathVariable UUID id) {
        users.deactivate(id);
    }

    @PostMapping("/users/{id}/reactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void reactivate(@PathVariable UUID id) {
        users.reactivate(id);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=UsersControllerTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Run the module boundary check**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=ModularityTests`
Expected: PASS — no new cross-module dependency was introduced (`auth` already allows `audit :: api`, `common`, `database`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/auth/web/UsersController.java src/test/java/com/company/pos/auth/UsersControllerTest.java
git commit -m "feat(auth): ADMIN-gated /users REST endpoints

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Terminal — UsersApi + UserAdminViewModel

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/UserView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/CreateUserRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/UpdateUserRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/ResetCredentialRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/UsersApi.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/UserAdminViewModel.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/UserAdminViewModelTest.java`

**Interfaces:**
- Consumes: `ApiClient` (`get/post/put` with `TypeReference`), `ApiException`, `ProblemDetail`.
- Produces (consumed by Tasks 5–6): terminal records `UserView(UUID,String,String,String,Set<String>,boolean)`, `CreateUserRequest`, `UpdateUserRequest`, `ResetCredentialRequest`; `UsersApi` methods (non-final, subclassable) `list(boolean)`, `create(CreateUserRequest)`, `update(UUID,UpdateUserRequest)`, `resetPassword(UUID,String)`, `resetPin(UUID,String)`, `deactivate(UUID)`, `reactivate(UUID)`; `UserAdminViewModel` methods `load(boolean) → List<UserView>`, `create(CreateUserRequest) → UserView`, `update(UUID,UpdateUserRequest) → UserView`, `resetPassword/resetPin/deactivate/reactivate → boolean`, `errorMessage()`; `Services.usersApi`.

- [ ] **Step 1: Write the failing test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/UserAdminViewModelTest.java`:

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.CreateUserRequest;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.UserView;
import com.company.pos.terminal.api.UsersApi;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserAdminViewModelTest {

    private UserView sampleUser() {
        return new UserView(UUID.randomUUID(), "alice", "Alice", "0002", Set.of("CASHIER"), true);
    }

    @Test
    void loadReturnsUsersAndClearsError() {
        UsersApi api = new UsersApi(null) {
            @Override public List<UserView> list(boolean includeDisabled) { return List.of(sampleUser()); }
        };
        UserAdminViewModel vm = new UserAdminViewModel(api, Runnable::run);
        List<UserView> r = vm.load(false);
        assertEquals(1, r.size());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void createSurfacesConflictAndReturnsNull() {
        UsersApi api = new UsersApi(null) {
            @Override public UserView create(CreateUserRequest req) {
                throw new ApiException(409, new ProblemDetail("Conflict", 409, "Username already in use"), "HTTP 409");
            }
        };
        UserAdminViewModel vm = new UserAdminViewModel(api, Runnable::run);
        assertNull(vm.create(new CreateUserRequest("bob", "Bob", "pw", Set.of("CASHIER"), null, null)));
        assertEquals("Username already in use", vm.errorMessage().get());
    }

    @Test
    void deactivateReturnsTrueOnSuccess() {
        UsersApi api = new UsersApi(null) {
            @Override public void deactivate(UUID id) { /* ok */ }
        };
        UserAdminViewModel vm = new UserAdminViewModel(api, Runnable::run);
        org.junit.jupiter.api.Assertions.assertTrue(vm.deactivate(UUID.randomUUID()));
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void deferredDispatcherHoldsErrorUntilDrained() {
        UsersApi api = new UsersApi(null) {
            @Override public List<UserView> list(boolean includeDisabled) {
                throw new ApiException(500, new ProblemDetail("Error", 500, "boom"), "HTTP 500");
            }
        };
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        UserAdminViewModel vm = new UserAdminViewModel(api, queue::add);
        assertNull(vm.load(false));                  // synchronous null return
        assertEquals("", vm.errorMessage().get());   // deferred: not applied yet
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals("boom", vm.errorMessage().get());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml test -Dtest=UserAdminViewModelTest`
Expected: COMPILE FAILURE — the records, `UsersApi`, and `UserAdminViewModel` do not exist.

- [ ] **Step 3: Create the terminal DTO records**

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/UserView.java`:

```java
package com.company.pos.terminal.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Set;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UserView(UUID id, String username, String displayName, String cashierCode,
        Set<String> roles, boolean enabled) {}
```

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/CreateUserRequest.java`:

```java
package com.company.pos.terminal.api;

import java.util.Set;

public record CreateUserRequest(String username, String displayName, String password,
        Set<String> roles, String cashierCode, String pin) {}
```

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/UpdateUserRequest.java`:

```java
package com.company.pos.terminal.api;

import java.util.Set;

public record UpdateUserRequest(String displayName, Set<String> roles) {}
```

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/ResetCredentialRequest.java`:

```java
package com.company.pos.terminal.api;

public record ResetCredentialRequest(String value) {}
```

- [ ] **Step 4: Create UsersApi**

Create `pos-terminal/src/main/java/com/company/pos/terminal/api/UsersApi.java`:

```java
package com.company.pos.terminal.api;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.UUID;

/** Typed client for the store server's ADMIN-gated {@code /users} endpoints.
 *  Methods are non-final so view-model tests can subclass with fakes. */
public class UsersApi {

    private final ApiClient client;

    public UsersApi(ApiClient client) {
        this.client = client;
    }

    /** GET /users?includeDisabled= — all staff users (optionally including deactivated ones). */
    public List<UserView> list(boolean includeDisabled) {
        return client.get("/users?includeDisabled=" + includeDisabled,
                new TypeReference<List<UserView>>() {});
    }

    /** POST /users — create a staff user. */
    public UserView create(CreateUserRequest req) {
        return client.post("/users", req, new TypeReference<UserView>() {});
    }

    /** PUT /users/{id} — edit display name + roles. */
    public UserView update(UUID id, UpdateUserRequest req) {
        return client.put("/users/" + id, req, new TypeReference<UserView>() {});
    }

    /** POST /users/{id}/reset-password. */
    public void resetPassword(UUID id, String value) {
        client.post("/users/" + id + "/reset-password", new ResetCredentialRequest(value), null);
    }

    /** POST /users/{id}/reset-pin — blank value clears the PIN. */
    public void resetPin(UUID id, String value) {
        client.post("/users/" + id + "/reset-pin", new ResetCredentialRequest(value), null);
    }

    /** POST /users/{id}/deactivate. */
    public void deactivate(UUID id) {
        client.post("/users/" + id + "/deactivate", null, null);
    }

    /** POST /users/{id}/reactivate. */
    public void reactivate(UUID id) {
        client.post("/users/" + id + "/reactivate", null, null);
    }
}
```

- [ ] **Step 5: Create UserAdminViewModel**

Create `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/UserAdminViewModel.java`:

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.CreateUserRequest;
import com.company.pos.terminal.api.UpdateUserRequest;
import com.company.pos.terminal.api.UserView;
import com.company.pos.terminal.api.UsersApi;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for staff administration. Synchronous like the other VMs — the controller runs it off
 * the FX thread via FxTasks and reads the return value; the only observable written off-thread is
 * {@code errorMessage}, inside the {@code ui} dispatcher. Read/mutate methods return a plain value
 * (list / view / boolean) or null-or-false on failure, with {@code errorMessage} set.
 */
public class UserAdminViewModel {

    private final UsersApi api;
    private final Consumer<Runnable> ui;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public UserAdminViewModel(UsersApi api, Consumer<Runnable> ui) {
        this.api = api;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    public List<UserView> load(boolean includeDisabled) {
        try {
            List<UserView> list = api.list(includeDisabled);
            ui.accept(() -> errorMessage.set(""));
            return list;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public UserView create(CreateUserRequest req) {
        try {
            UserView v = api.create(req);
            ui.accept(() -> errorMessage.set(""));
            return v;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public UserView update(UUID id, UpdateUserRequest req) {
        try {
            UserView v = api.update(id, req);
            ui.accept(() -> errorMessage.set(""));
            return v;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public boolean resetPassword(UUID id, String value) {
        return voidCall(() -> api.resetPassword(id, value));
    }

    public boolean resetPin(UUID id, String value) {
        return voidCall(() -> api.resetPin(id, value));
    }

    public boolean deactivate(UUID id) {
        return voidCall(() -> api.deactivate(id));
    }

    public boolean reactivate(UUID id) {
        return voidCall(() -> api.reactivate(id));
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

- [ ] **Step 6: Wire UsersApi into Services**

In `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`:

Add the import near the other `api` imports:
```java
import com.company.pos.terminal.api.UsersApi;
```
Add the field alongside the other `*Api` fields:
```java
    public final UsersApi usersApi;
```
Add the construction at the end of the constructor (after `this.cashDrawerApi = ...`):
```java
        this.usersApi = new UsersApi(apiClient);
```

- [ ] **Step 7: Run test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml test -Dtest=UserAdminViewModelTest`
Expected: PASS (4 tests).

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/UserView.java pos-terminal/src/main/java/com/company/pos/terminal/api/CreateUserRequest.java pos-terminal/src/main/java/com/company/pos/terminal/api/UpdateUserRequest.java pos-terminal/src/main/java/com/company/pos/terminal/api/ResetCredentialRequest.java pos-terminal/src/main/java/com/company/pos/terminal/api/UsersApi.java pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/UserAdminViewModel.java pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/UserAdminViewModelTest.java
git commit -m "feat(terminal): UsersApi + UserAdminViewModel

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Terminal — I/O-free user dialogs

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/UserFormDialog.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/ResetCredentialDialog.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/view/UserFormDialogTest.java`

**Interfaces:**
- Consumes: terminal records `CreateUserRequest`, `UpdateUserRequest`, `UserView` (Task 4).
- Produces (consumed by Task 6): `UserFormDialog.promptCreate() → Optional<CreateUserRequest>`, `UserFormDialog.promptEdit(UserView) → Optional<UpdateUserRequest>`, static `UserFormDialog.isValidCreate(String username, String displayName, String password, Set<String> roles) → boolean`; `ResetCredentialDialog.prompt(String title, String fieldLabel, boolean allowBlank) → Optional<String>`.

- [ ] **Step 1: Write the failing test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/view/UserFormDialogTest.java`:

```java
package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class UserFormDialogTest {

    @Test
    void validWhenAllRequiredPresent() {
        assertTrue(UserFormDialog.isValidCreate("alice", "Alice", "pw", Set.of("CASHIER")));
    }

    @Test
    void invalidWhenUsernameBlank() {
        assertFalse(UserFormDialog.isValidCreate("  ", "Alice", "pw", Set.of("CASHIER")));
    }

    @Test
    void invalidWhenPasswordBlank() {
        assertFalse(UserFormDialog.isValidCreate("alice", "Alice", "", Set.of("CASHIER")));
    }

    @Test
    void invalidWhenNoRolesSelected() {
        assertFalse(UserFormDialog.isValidCreate("alice", "Alice", "pw", Set.of()));
    }

    @Test
    void invalidWhenDisplayNameBlank() {
        assertFalse(UserFormDialog.isValidCreate("alice", " ", "pw", Set.of("CASHIER")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml test -Dtest=UserFormDialogTest`
Expected: COMPILE FAILURE — `UserFormDialog` does not exist.

- [ ] **Step 3: Create UserFormDialog**

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/UserFormDialog.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.CreateUserRequest;
import com.company.pos.terminal.api.UpdateUserRequest;
import com.company.pos.terminal.api.UserView;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Modal to create or edit a staff user. Pure view — collects input only; the controller performs
 * all HTTP. Create mode returns a {@link CreateUserRequest}; edit mode returns an
 * {@link UpdateUserRequest} (username and password are not editable here — password is changed via
 * {@link ResetCredentialDialog}). Only {@link #isValidCreate} is unit-tested headlessly.
 */
public final class UserFormDialog {

    private static final List<String> ROLES = List.of("CASHIER", "MANAGER", "ADMIN");

    private UserFormDialog() {}

    public static Optional<CreateUserRequest> promptCreate() {
        Dialog<CreateUserRequest> dialog = new Dialog<>();
        dialog.setTitle("New staff user");
        dialog.setHeaderText("Create a staff login");
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);

        TextField username = textField("username");
        TextField displayName = textField("full name");
        PasswordField password = new PasswordField();
        password.setPromptText("password");
        TextField cashierCode = textField("cashier code (optional)");
        PasswordField pin = new PasswordField();
        pin.setPromptText("PIN (optional)");
        List<ToggleButton> roleToggles = roleToggles(Set.of());

        VBox box = new VBox(12,
                field("Username", username),
                field("Full name", displayName),
                field("Password", password),
                field("Roles", roleRow(roleToggles)),
                field("Cashier code", cashierCode),
                field("PIN", pin));
        box.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(!isValidCreate(
                username.getText(), displayName.getText(), password.getText(), selectedRoles(roleToggles)));
        username.textProperty().addListener((o, a, b) -> revalidate.run());
        displayName.textProperty().addListener((o, a, b) -> revalidate.run());
        password.textProperty().addListener((o, a, b) -> revalidate.run());
        roleToggles.forEach(t -> t.selectedProperty().addListener((o, a, b) -> revalidate.run()));
        revalidate.run();

        dialog.setResultConverter(bt -> {
            if (bt != submit) {
                return null;
            }
            Set<String> roles = selectedRoles(roleToggles);
            if (!isValidCreate(username.getText(), displayName.getText(), password.getText(), roles)) {
                return null;
            }
            return new CreateUserRequest(username.getText().trim(), displayName.getText().trim(),
                    password.getText(), roles, trimToNull(cashierCode.getText()), trimToNull(pin.getText()));
        });
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    public static Optional<UpdateUserRequest> promptEdit(UserView existing) {
        Dialog<UpdateUserRequest> dialog = new Dialog<>();
        dialog.setTitle("Edit staff user");
        dialog.setHeaderText("Edit " + existing.username());
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);

        TextField displayName = textField("full name");
        displayName.setText(existing.displayName());
        List<ToggleButton> roleToggles = roleToggles(existing.roles());

        VBox box = new VBox(12,
                field("Full name", displayName),
                field("Roles", roleRow(roleToggles)));
        box.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(box);

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        Runnable revalidate = () -> submitNode.setDisable(
                displayName.getText().isBlank() || selectedRoles(roleToggles).isEmpty());
        displayName.textProperty().addListener((o, a, b) -> revalidate.run());
        roleToggles.forEach(t -> t.selectedProperty().addListener((o, a, b) -> revalidate.run()));
        revalidate.run();

        dialog.setResultConverter(bt -> {
            if (bt != submit) {
                return null;
            }
            Set<String> roles = selectedRoles(roleToggles);
            if (displayName.getText().isBlank() || roles.isEmpty()) {
                return null;
            }
            return new UpdateUserRequest(displayName.getText().trim(), roles);
        });
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    /** Headless-testable create validity: username, display name, password non-blank and ≥1 role. */
    static boolean isValidCreate(String username, String displayName, String password, Set<String> roles) {
        return username != null && !username.isBlank()
                && displayName != null && !displayName.isBlank()
                && password != null && !password.isBlank()
                && roles != null && !roles.isEmpty();
    }

    private static List<ToggleButton> roleToggles(Set<String> selected) {
        return ROLES.stream().map(r -> {
            ToggleButton t = new ToggleButton(r);
            t.getStyleClass().add("chip");
            t.setSelected(selected.contains(r));
            return t;
        }).toList();
    }

    private static HBox roleRow(List<ToggleButton> toggles) {
        HBox row = new HBox(8);
        row.getChildren().addAll(toggles);
        return row;
    }

    private static Set<String> selectedRoles(List<ToggleButton> toggles) {
        Set<String> out = new LinkedHashSet<>();
        for (ToggleButton t : toggles) {
            if (t.isSelected()) {
                out.add(t.getText());
            }
        }
        return out;
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

- [ ] **Step 4: Create ResetCredentialDialog**

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/ResetCredentialDialog.java`:

```java
package com.company.pos.terminal.view;

import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;

/**
 * Single-field modal for a new password or PIN. Pure view — collects input only. When
 * {@code allowBlank} is true (PIN), an empty value is returned as an empty string (the caller
 * treats blank as "clear the PIN"); otherwise Submit stays disabled until the field is non-blank.
 */
public final class ResetCredentialDialog {

    private ResetCredentialDialog() {}

    public static Optional<String> prompt(String title, String fieldLabel, boolean allowBlank) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(title);
        dialog.getDialogPane().getStyleClass().add("drawer-modal");
        ButtonType submit = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, cancel);

        PasswordField value = new PasswordField();
        value.setPromptText(fieldLabel);
        Label l = new Label(fieldLabel);
        l.getStyleClass().add("field-label");
        VBox box = new VBox(8, l, value);
        box.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(box);

        if (!allowBlank) {
            Node submitNode = dialog.getDialogPane().lookupButton(submit);
            Runnable revalidate = () -> submitNode.setDisable(value.getText().isBlank());
            value.textProperty().addListener((o, a, b) -> revalidate.run());
            revalidate.run();
        }

        dialog.setResultConverter(bt -> {
            if (bt != submit) {
                return null;
            }
            return value.getText();   // may be "" when allowBlank (clears the PIN)
        });
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml test -Dtest=UserFormDialogTest`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/UserFormDialog.java pos-terminal/src/main/java/com/company/pos/terminal/view/ResetCredentialDialog.java pos-terminal/src/test/java/com/company/pos/terminal/view/UserFormDialogTest.java
git commit -m "feat(terminal): I/O-free user form + reset-credential dialogs

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Terminal — Admin area, Staff screen, and Home entry

**Files:**
- Create: `pos-terminal/src/main/resources/fxml/admin.fxml`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/AdminController.java`
- Create: `pos-terminal/src/main/resources/fxml/staff.fxml`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/StaffController.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/HomeController.java`
- Modify: `pos-terminal/src/main/resources/fxml/home.fxml`

**Interfaces:**
- Consumes: `Navigator.toAdmin()` / `Navigator.toStaff()` / `Navigator.toHome()`; `Services.usersApi`, `Services.session`; `UserAdminViewModel` (Task 4); `UserFormDialog`, `ResetCredentialDialog` (Task 5); `FxTasks.run`.
- Produces: navigable Admin area (Home → Admin → Staff) with working create/edit/reset/deactivate/reactivate.

- [ ] **Step 1: Add Navigator methods**

In `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java`, add two methods next to `toHome()`:

```java
    public void toAdmin() {
        com.company.pos.terminal.view.AdminController controller =
                new com.company.pos.terminal.view.AdminController(services, this);
        setScene("/fxml/admin.fxml", controller);
    }

    public void toStaff() {
        com.company.pos.terminal.view.StaffController controller =
                new com.company.pos.terminal.view.StaffController(services, this);
        setScene("/fxml/staff.fxml", controller);
    }
```

- [ ] **Step 2: Create the Admin area screen**

Create `pos-terminal/src/main/resources/fxml/admin.fxml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.scene.control.Button?>
<?import javafx.scene.control.Label?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.layout.Pane?>
<?import javafx.scene.layout.StackPane?>
<?import javafx.scene.layout.VBox?>

<!-- Shared manager/admin area. Tiles are shown per-role; Staff is ADMIN-only. -->
<StackPane styleClass="screen" xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml">
  <VBox alignment="CENTER" spacing="32" maxWidth="-Infinity">
    <HBox spacing="16" alignment="CENTER" maxWidth="Infinity">
      <Label text="Admin" styleClass="title"/>
      <Pane HBox.hgrow="ALWAYS"/>
      <Button fx:id="backButton" text="Back" styleClass="btn-secondary"/>
    </HBox>
    <HBox fx:id="tileRow" spacing="32" alignment="CENTER">
      <Button fx:id="staffButton" text="Staff" styleClass="home-tile"/>
    </HBox>
  </VBox>
</StackPane>
```

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/AdminController.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import javafx.fxml.FXML;
import javafx.scene.control.Button;

/**
 * Landing screen for the manager/admin area. Reachable by MANAGER or ADMIN; individual tiles are
 * shown per-role. For now the only tile is Staff (ADMIN-only) — later go-live items add tiles here.
 */
public class AdminController {

    private final Services services;
    private final Navigator navigator;

    @FXML private Button backButton;
    @FXML private Button staffButton;

    public AdminController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
    }

    @FXML
    public void initialize() {
        backButton.setOnAction(e -> navigator.toHome());
        boolean admin = services.session.roles().contains("ADMIN");
        staffButton.setVisible(admin);
        staffButton.setManaged(admin);
        staffButton.setOnAction(e -> navigator.toStaff());
    }
}
```

- [ ] **Step 3: Create the Staff screen FXML**

Create `pos-terminal/src/main/resources/fxml/staff.fxml`:

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
    <Label text="Staff" styleClass="title"/>
    <Pane HBox.hgrow="ALWAYS"/>
    <Label fx:id="errorLabel" styleClass="error-text"/>
    <CheckBox fx:id="includeDisabled" text="Show deactivated"/>
    <Button fx:id="backButton" text="Back" styleClass="btn-secondary"/>
  </HBox>

  <TableView fx:id="table" VBox.vgrow="ALWAYS">
    <columns>
      <TableColumn fx:id="nameCol" text="Name" prefWidth="200"/>
      <TableColumn fx:id="usernameCol" text="Username" prefWidth="160"/>
      <TableColumn fx:id="rolesCol" text="Roles" prefWidth="220"/>
      <TableColumn fx:id="codeCol" text="Cashier code" prefWidth="140"/>
      <TableColumn fx:id="statusCol" text="Status" prefWidth="120"/>
    </columns>
  </TableView>

  <HBox spacing="12" alignment="CENTER_LEFT">
    <Button fx:id="newButton" text="New user" styleClass="btn-primary"/>
    <Button fx:id="editButton" text="Edit"/>
    <Button fx:id="resetPasswordButton" text="Reset password"/>
    <Button fx:id="resetPinButton" text="Reset PIN"/>
    <Button fx:id="toggleActiveButton" text="Deactivate"/>
  </HBox>
</VBox>
```

- [ ] **Step 4: Create the Staff controller**

Create `pos-terminal/src/main/java/com/company/pos/terminal/view/StaffController.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.CreateUserRequest;
import com.company.pos.terminal.api.UpdateUserRequest;
import com.company.pos.terminal.api.UserView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.UserAdminViewModel;
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
 * ADMIN-only staff list. Loads users off the FX thread via FxTasks, edits through the I/O-free
 * dialogs (the controller performs the HTTP), and reloads after every mutation. Dialog-driven
 * flows re-kick a fresh FxTasks task rather than calling the VM inside onDone.
 */
public class StaffController {

    private static final System.Logger LOG = System.getLogger(StaffController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final UserAdminViewModel vm;

    @FXML private Label errorLabel;
    @FXML private CheckBox includeDisabled;
    @FXML private Button backButton;
    @FXML private Button newButton;
    @FXML private Button editButton;
    @FXML private Button resetPasswordButton;
    @FXML private Button resetPinButton;
    @FXML private Button toggleActiveButton;
    @FXML private TableView<UserView> table;
    @FXML private TableColumn<UserView, String> nameCol;
    @FXML private TableColumn<UserView, String> usernameCol;
    @FXML private TableColumn<UserView, String> rolesCol;
    @FXML private TableColumn<UserView, String> codeCol;
    @FXML private TableColumn<UserView, String> statusCol;

    public StaffController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new UserAdminViewModel(services.usersApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().displayName()));
        usernameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().username()));
        rolesCol.setCellValueFactory(c -> new SimpleStringProperty(String.join(", ", c.getValue().roles())));
        codeCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().cashierCode() == null ? "" : c.getValue().cashierCode()));
        statusCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().enabled() ? "Active" : "Disabled"));

        errorLabel.textProperty().bind(vm.errorMessage());
        includeDisabled.selectedProperty().addListener((o, a, b) -> reload());
        table.getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> refreshButtons(sel));

        backButton.setOnAction(e -> navigator.toAdmin());
        newButton.setOnAction(e -> createUser());
        editButton.setOnAction(e -> editSelected());
        resetPasswordButton.setOnAction(e -> resetPasswordSelected());
        resetPinButton.setOnAction(e -> resetPinSelected());
        toggleActiveButton.setOnAction(e -> toggleActiveSelected());

        refreshButtons(null);
        reload();
    }

    private void reload() {
        boolean incl = includeDisabled.isSelected();
        final List<UserView>[] holder = new List[1];
        FxTasks.run(() -> holder[0] = vm.load(incl),
                () -> {
                    if (holder[0] != null) {
                        table.setItems(FXCollections.observableArrayList(holder[0]));
                    }
                },
                err -> LOG.log(System.Logger.Level.ERROR, "Load users failed", err));
    }

    private void refreshButtons(UserView sel) {
        boolean has = sel != null;
        editButton.setDisable(!has);
        resetPasswordButton.setDisable(!has);
        resetPinButton.setDisable(!has);
        toggleActiveButton.setDisable(!has);
        toggleActiveButton.setText(has && !sel.enabled() ? "Reactivate" : "Deactivate");
    }

    private void createUser() {
        Optional<CreateUserRequest> req = UserFormDialog.promptCreate();
        req.ifPresent(r -> {
            final UserView[] holder = new UserView[1];
            FxTasks.run(() -> holder[0] = vm.create(r),
                    () -> { if (holder[0] != null) reload(); },
                    err -> LOG.log(System.Logger.Level.ERROR, "Create user failed", err));
        });
    }

    private void editSelected() {
        UserView sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        Optional<UpdateUserRequest> req = UserFormDialog.promptEdit(sel);
        req.ifPresent(r -> {
            final UserView[] holder = new UserView[1];
            FxTasks.run(() -> holder[0] = vm.update(sel.id(), r),
                    () -> { if (holder[0] != null) reload(); },
                    err -> LOG.log(System.Logger.Level.ERROR, "Update user failed", err));
        });
    }

    private void resetPasswordSelected() {
        UserView sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        ResetCredentialDialog.prompt("Reset password", "New password", false).ifPresent(value -> {
            final boolean[] holder = {false};
            FxTasks.run(() -> holder[0] = vm.resetPassword(sel.id(), value),
                    () -> { /* errorLabel is bound; nothing to do on success */ },
                    err -> LOG.log(System.Logger.Level.ERROR, "Reset password failed", err));
        });
    }

    private void resetPinSelected() {
        UserView sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        ResetCredentialDialog.prompt("Reset PIN", "New PIN (blank clears)", true).ifPresent(value -> {
            final boolean[] holder = {false};
            FxTasks.run(() -> holder[0] = vm.resetPin(sel.id(), value),
                    () -> { /* bound errorLabel surfaces failures */ },
                    err -> LOG.log(System.Logger.Level.ERROR, "Reset PIN failed", err));
        });
    }

    private void toggleActiveSelected() {
        UserView sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        boolean reactivating = !sel.enabled();
        final boolean[] holder = {false};
        FxTasks.run(
                () -> holder[0] = reactivating ? vm.reactivate(sel.id()) : vm.deactivate(sel.id()),
                () -> { if (holder[0]) reload(); },
                err -> LOG.log(System.Logger.Level.ERROR, "Toggle active failed", err));
    }
}
```

- [ ] **Step 5: Add the Admin button to Home**

In `pos-terminal/src/main/resources/fxml/home.fxml`, add an Admin button to the top `HBox` (before `signOutButton`):

```xml
      <Button fx:id="adminButton" text="Admin" styleClass="btn-secondary"/>
```

In `pos-terminal/src/main/java/com/company/pos/terminal/view/HomeController.java`:

Add the field with the other `@FXML` buttons:
```java
    @FXML private Button adminButton;
```
In `initialize()`, wire it visible only to managers/admins (after the existing button wiring, before `checkShift()`):
```java
        boolean showAdmin = services.session.isManager();   // MANAGER or ADMIN
        adminButton.setVisible(showAdmin);
        adminButton.setManaged(showAdmin);
        adminButton.setOnAction(e -> navigator.toAdmin());
```

- [ ] **Step 6: Build the terminal and run the full terminal test suite**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml clean test`
Expected: BUILD SUCCESS — all existing tests plus the new VM/dialog tests pass, and the new FXML/controllers compile.

- [ ] **Step 7: Manual smoke test (wiring not covered by headless tests)**

Start the backend seeded (`--spring.profiles.active=embedded,dev`, login `manager`/`manager` — the seeded manager has ADMIN), then run the terminal (`./mvnw -f pos-terminal/pom.xml javafx:run`). Verify: Home shows **Admin** (manager is MANAGER+ADMIN) → Admin shows **Staff** → create a cashier with a cashier code + PIN → it appears in the list → edit roles → reset PIN → deactivate/reactivate → toggling "Show deactivated" reveals the disabled user. Sign in as a plain cashier and confirm **Admin** is hidden.

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/resources/fxml/admin.fxml pos-terminal/src/main/java/com/company/pos/terminal/view/AdminController.java pos-terminal/src/main/resources/fxml/staff.fxml pos-terminal/src/main/java/com/company/pos/terminal/view/StaffController.java pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java pos-terminal/src/main/java/com/company/pos/terminal/view/HomeController.java pos-terminal/src/main/resources/fxml/home.fxml
git commit -m "feat(terminal): Admin area + Staff screen + Home entry

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Docs — first-admin bootstrap runbook

**Files:**
- Create: `docs/operations/first-admin.md`

**Interfaces:**
- Consumes: the `users` table schema (from the auth Flyway migration) and the shared `BCryptPasswordEncoder`.
- Produces: a documented one-time procedure to create the first ADMIN in a production (store-server / Postgres) deployment.

- [ ] **Step 1: Confirm the exact table + column names**

Run: `ls src/main/resources/db/migration/auth/ && grep -rin "app_user" src/main/resources/db/migration/auth/`
Expected: the auth migration that creates the user table. The entity is `@Table(name = "app_user")` with columns `id`, `username`, `cashier_code`, `password_hash`, `pin_hash`, `display_name`, `roles`, `enabled` (verified against `User.java`). Confirm the store-server migration matches these exact names before running the INSERT in Step 2 — do not guess if the migration differs.

- [ ] **Step 2: Write the runbook**

Create `docs/operations/first-admin.md` with the following content (table `app_user` and its columns are confirmed against `User.java`; still re-check the store-server migration per Step 1 before running):

````markdown
# Creating the first admin (production)

`store-server` deployments have **no seed path** — the `dev` profile's `DevUserSeeder`
(`manager`/`manager`) does not run in production. Before anyone can log in and use the
terminal's Admin → Staff screen to create the rest of the staff, one ADMIN account must
be inserted directly into the database, once.

## 1. Generate a BCrypt hash

The app verifies credentials with Spring's `BCryptPasswordEncoder`, which accepts the
`$2a$`/`$2b$`/`$2y$` bcrypt variants. Generate a hash for the chosen password (and,
optionally, a PIN):

Using `htpasswd` (from apache2-utils / httpd-tools):

```bash
htpasswd -bnBC 10 "" 'YOUR_PASSWORD' | tr -d ':\n' | sed 's/^\$2y/\$2a/'
```

Or with Python:

```bash
python3 -c "import bcrypt; print(bcrypt.hashpw(b'YOUR_PASSWORD', bcrypt.gensalt(10)).decode())"
```

Repeat for the PIN if you want the first admin to also log in by cashier code + PIN.

## 2. Insert the admin row

Connect to the store-server Postgres database and run (substitute the hash from step 1;
the table is `app_user` — re-confirm against `db/migration/auth/`):

```sql
INSERT INTO app_user (id, username, display_name, password_hash, pin_hash,
                      cashier_code, roles, enabled)
VALUES (gen_random_uuid(), 'admin', 'Store Admin',
        '$2a$10$....replace.with.generated.hash....',
        NULL, NULL, 'ADMIN', true);
```

Notes:
- `roles` is a comma-separated list of role names (`RoleSetConverter`), e.g. `ADMIN` or
  `CASHIER,MANAGER,ADMIN`.
- `gen_random_uuid()` requires the `pgcrypto` extension; otherwise supply a literal UUID.
- Set `pin_hash` and `cashier_code` if you generated a PIN in step 1 (both, or neither).

## 3. Verify

```bash
curl -s -X POST "$POS_BASE_URL/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"YOUR_PASSWORD"}'
```

Expected: a `200` with a JWT token. Then log in on the terminal as `admin`, open
**Admin → Staff**, and create the rest of the staff there. This manual step is never
needed again.
````

- [ ] **Step 3: Commit**

```bash
git add docs/operations/first-admin.md
git commit -m "docs: first-admin bootstrap runbook for production

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Final verification

- [ ] **Backend full build + boundary check**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw verify`
Expected: BUILD SUCCESS (compiles, all tests incl. `ModularityTests` pass).

- [ ] **Terminal full build**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw -f pos-terminal/pom.xml clean test`
Expected: BUILD SUCCESS.

- [ ] **Manual end-to-end** (Task 6, Step 7) confirmed against a running backend.

---

## Self-review notes (traceability to the spec)

- **Backend endpoints (spec §"UsersController")** → Task 3 (all 8 endpoints, ADMIN-gated).
- **UserAdminService + no-@Transactional + last-admin guard (spec §"UserAdminService")** → Task 2.
- **auth.api DTOs + User domain methods + audit actions (spec §"Domain"/"DTOs"/"Audit")** → Task 1.
- **No migration (spec §"Persistence / migration")** → honored; Task 1 changes are enum + methods only.
- **First-admin runbook (spec §"First-admin bootstrap")** → Task 7.
- **Terminal Admin area + Staff screen + Home entry (spec §"Terminal UI design")** → Task 6; role-filtered entry (MANAGER+ADMIN sees Admin, Staff is ADMIN-only) implemented in AdminController + HomeController.
- **UsersApi + UserAdminViewModel incl. async-dispatcher regression test (spec §"API + view-model"/"Testing")** → Task 4.
- **I/O-free dialogs (spec §"Screens / dialogs")** → Task 5.
- **Non-goals (spec §"Non-goals")** → nothing in the plan implements self-service password change, strength policy, or 2FA.
