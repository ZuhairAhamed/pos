# Sub-project #1 — Staff / User Management (design)

_Date: 2026-07-19. Branch: `feat/terminal-ui-restaurant-slice`._
_Part of the [go-live roadmap](2026-07-19-go-live-roadmap.md) (item 1 of 8)._

## Problem

A restaurant cannot open in production because there is **no way to create staff
logins**. The only user today is created by `DevUserSeeder` (`@Profile("dev")`,
hardcoded `manager`/`manager`, PIN `1234`). There is no user-management endpoint
anywhere and no admin UI. This sub-project adds the backend and the terminal UI
to manage staff accounts over their full lifecycle, and establishes the shared
**Admin area** in the terminal that roadmap items 3–6 will hang off.

## Decisions (locked)

- **Access:** staff management is **ADMIN only** (`@PreAuthorize("hasRole('ADMIN')")`),
  matching the existing convention (config and audit are ADMIN-only).
- **First-admin bootstrap:** **documented manual insert** — no bootstrap code path.
- **Operations:** **full lifecycle** — create, list, view, edit (display name + roles),
  reset password, reset PIN, deactivate, reactivate.

## What already exists (build-on inventory)

- `auth/domain/User.java` — fields: `id (UUID)`, `username (unique, ≤100)`,
  `displayName (≤200)`, `passwordHash (≤100)`, `pinHash (nullable ≤100)`,
  `cashierCode (unique, nullable ≤20)`, `roles (Set<Role>` via `RoleSetConverter`,
  comma-separated), `enabled (boolean, default true)`.
  Constructor `User(id, username, displayName, passwordHash, Set<Role>)`; public
  setters only for `cashierCode` and `pinHash` today.
- `auth/api/Role.java` — enum `CASHIER, MANAGER, ADMIN`.
- `auth/infrastructure/UserRepository.java` — `JpaRepository<User,UUID>` with
  `findByUsername`, `findByCashierCode`.
- `auth/infrastructure/PasswordConfig.java` — `BCryptPasswordEncoder`; used for
  **both** password and PIN.
- `auth/application/AuthService.java` — login-only; **deliberately no class-level
  `@Transactional`** (SQLite single connection + `REQUIRES_NEW` audit writes would
  deadlock). New write service must respect this.
- `auth/application/DevUserSeeder.java` — the creation template (hash before persist).
- Method security enabled (`@EnableMethodSecurity`); JWT `roles` claim → `ROLE_*`
  authorities; friendly errors via `DomainException.{validation,conflict,notFound}`
  → `ApiExceptionHandler`.
- Auditing via `AuditService` (a current `auth` dependency).

## Backend design (all inside the `auth` module)

No new module and no new `allowedDependencies` — `auth` already depends on what it
needs. `ModularityTests` must stay green.

### Domain — add encapsulated behavior to `User`

`username` stays **immutable** (it is the actor key recorded on sales, shifts, and
audit rows). Add:

- `rename(String displayName)`
- `changeRoles(Set<Role> roles)`
- `resetPassword(String passwordHash)`
- `resetPin(String pinHash)` — `null` clears the PIN
- `enable()` / `disable()`

(`setCashierCode` already exists; keep it, but changes go through the service so
uniqueness is checked.)

### `UserAdminService` (new)

Separate from `AuthService` (keep login concerns isolated). Mirrors AuthService's
**no class-level `@Transactional`** stance to avoid the audit deadlock: perform the
repository write, then `audit.record(...)`. Uniqueness is pre-checked for friendly
errors and enforced for real by DB unique constraints on `username` / `cashier_code`.

Methods (each records an audit event):

| Method | Behavior |
|---|---|
| `createUser(CreateUserCommand)` | conflict if username/cashierCode taken; hash password (+ PIN if given); save; → `UserView` |
| `listUsers(boolean includeDisabled)` | all users, or only enabled; → `List<UserView>` |
| `getUser(UUID)` | → `UserView`, else `notFound` |
| `updateUser(UUID, UpdateUserCommand)` | rename + changeRoles; guarded by last-admin rule |
| `resetPassword(UUID, ResetCredentialRequest)` | hash + `resetPassword` |
| `resetPin(UUID, ResetCredentialRequest)` | hash + `resetPin` (blank ⇒ clear) |
| `deactivate(UUID)` | `disable()`; guarded by last-admin rule |
| `reactivate(UUID)` | `enable()` |

**Lockout guards (invariant):** the system must always retain at least one enabled
ADMIN. Reject — with a clear `DomainException.validation` — any `deactivate`,
`updateUser` (role change), that would remove ADMIN from, or disable, the **last
enabled ADMIN**. Deactivation is soft (`enabled=false`); users are **never
hard-deleted** (sales/shift/audit history reference the username).

### `auth.api` DTOs (new, public named interface)

```java
public record CreateUserCommand(String username, String displayName,
        String password, Set<Role> roles, String cashierCode, String pin) {}

public record UpdateUserCommand(String displayName, Set<Role> roles) {}

public record ResetCredentialRequest(String value) {}   // new password or new PIN

public record UserView(UUID id, String username, String displayName,
        String cashierCode, Set<Role> roles, boolean enabled) {}
```

`UserView` never exposes `passwordHash`/`pinHash`.

### `UsersController` (new) — every method `@PreAuthorize("hasRole('ADMIN')")`

| Method + path | Body | Result |
|---|---|---|
| `POST /users` | `CreateUserCommand` | `201` + `UserView` |
| `GET /users?includeDisabled=false` | — | `List<UserView>` |
| `GET /users/{id}` | — | `UserView` |
| `PUT /users/{id}` | `UpdateUserCommand` | `UserView` |
| `POST /users/{id}/reset-password` | `ResetCredentialRequest` | `204` |
| `POST /users/{id}/reset-pin` | `ResetCredentialRequest` | `204` |
| `POST /users/{id}/deactivate` | — | `204` |
| `POST /users/{id}/reactivate` | — | `204` |

Password required on create (entity mandates `passwordHash`); `cashierCode` + `pin`
optional but recommended for anyone who works the terminal (PIN login + manager-PIN
approval both need `cashierCode`+`pin`).

### Audit

Add `AuditAction` enum values `USER_CREATED`, `USER_UPDATED`, `USER_DEACTIVATED`,
`USER_REACTIVATED`, `USER_CREDENTIAL_RESET`. Enum-only — **no schema change**.

### Persistence / migration

**No Flyway migration.** The `users` table and `enabled`/`roles` columns already
exist; full-lifecycle operations need no new columns. Add repository query
`findAllByEnabledTrue()` (or filter in service) and a count/exists helper for the
last-admin guard (e.g. count enabled users having ADMIN in roles — implemented in
the service over `findAll` given the small single-store user set, to avoid encoding
the comma-separated role query in JPQL).

## First-admin bootstrap (documented manual insert)

Deliverable: a runbook section (e.g. `docs/run-modes.md` or a new
`docs/operations/first-admin.md`) covering store-server/Postgres:

1. Generate a BCrypt hash using the app's own encoder so the format matches — a
   one-line invocation (a tiny `main`/`mvnw exec` snippet or a documented JBang/CLI
   line calling `BCryptPasswordEncoder.encode`). Document generating both the
   password hash and (optionally) a PIN hash.
2. The exact `INSERT INTO users (...)` with a `roles` value the `RoleSetConverter`
   accepts (comma-separated `ADMIN` etc.) and `enabled = true`.
3. Note: run once before first login; thereafter all users are managed through the
   Admin UI. `embedded,dev` still seeds a manager for local dev — this runbook is
   the production path only.

## Terminal UI design

Establishes the **shared Admin area** for the roadmap.

### Admin area entry (shared, role-filtered)

- Add an **"Admin"** entry on the Home screen, visible when the session holds
  MANAGER **or** ADMIN (so items 3–6 can attach later). Each screen inside enforces
  its own role; for #1 the sole screen is **Staff**, shown to ADMIN only. A
  MANAGER-only user sees the Admin area but not Staff (placeholder/empty until later
  items land).
- Navigator gets `toAdmin()` and `toStaff()`; screens implement `Navigator.Screen`
  for lifecycle cleanup where they own timers/sockets (Staff owns none).

### API + view-model

- `UsersApi` thin client: `create`, `list(includeDisabled)`, `get`, `update`,
  `resetPassword`, `resetPin`, `deactivate`, `reactivate`.
- `UserAdminViewModel` — **synchronous methods returning plain values**; controller
  runs them off-thread via `FxTasks.run(work, onDone, onError)` and reads results in
  `onDone` via a holder; the only off-thread observable write is `errorMessage`
  inside `ui.accept(...)`.

### Screens / dialogs

- **Staff list screen** — table (display name, username, roles, cashier code,
  active/disabled badge), an *include-disabled* toggle, and actions: New,
  Edit, Reset password, Reset PIN, Deactivate/Reactivate.
- **Create/Edit User dialog** — **I/O-free** (collects input only; controller does
  all HTTP): display name, username (create-only, disabled on edit), password
  (create-only field; edits use Reset), roles as multi-select toggle chips,
  cashier code, PIN. Validates required fields before enabling Save.
- **Reset Password / Reset PIN dialogs** — single-field, I/O-free.
- **Deactivate / Reactivate confirm** — confirmation; surfaces the last-admin-guard
  error returned by the server if it fires.

## Testing

**Backend**
- `UserAdminServiceTest`: create (happy path, username conflict, cashierCode
  conflict), edit (rename + roles), reset password, reset PIN (set + clear),
  deactivate/reactivate, and the **last-enabled-admin guard** (deactivate blocked,
  demote-out-of-ADMIN blocked).
- `UsersControllerTest` (slice / `@WebMvcTest`-style or `@SpringBootTest`): ADMIN
  gate returns 403 for CASHIER/MANAGER, 200/201 for ADMIN; `UserView` omits hashes.
- `ModularityTests` (auth boundary unchanged).

**Terminal**
- `UserAdminViewModelTest` including the **async-dispatcher regression test** (a
  deferred, undrained `ui` dispatcher) that a synchronous-only test would mask.
- Dialog tests (input validation; no HTTP), in the `DrawerActivityDialogTest` style.

## Non-goals

Self-service password change, password-strength policy, email/2FA, per-terminal
user restrictions, user photo/PIN-pad avatars. Deferred to later or out of scope.

## Definition of done

- Backend endpoints live and ADMIN-gated; full lifecycle works; last-admin guard
  enforced; `./mvnw verify` (incl. `ModularityTests`) green.
- Terminal Admin area + Staff screen functional against a running backend;
  `./mvnw -f pos-terminal/pom.xml clean test` green.
- First-admin runbook written and verified against a fresh store-server DB.
