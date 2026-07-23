# Sub-project #3f — Menu Modifier Builder (design)

_Date: 2026-07-23. Branch: `feat/terminal-ui-restaurant-slice`._
_Part of the [go-live roadmap](2026-07-19-go-live-roadmap.md) item 3 (Store setup console). Item 3's
four areas: store settings (#3e, shipped), dining tables (#3d, shipped), kitchen routing (#3c,
shipped), and the **menu builder — the last area**. The menu builder is itself decomposed into two
sub-projects: **modifier builder (this, #3f)** and variant builder (#3g, follow-up). Modifiers were
chosen first — they are what the dine-in order modifier-picker consumes._

## Problem

The `menu` module decorates existing product SKUs with **modifier groups** (name, min/max
selections) → **options** (name, `priceDelta`) → **assigned to SKUs**. The order screen's
modifier-picker and `dining` (`resolveSelections` at add-line) consume this. The backend can
**create** a group, **add** an option, **assign/unassign** a group to a SKU, and **deactivate** a
group — but there is **no way to edit** a group (name/min/max) or an option (name/price), no option
deactivate/reactivate, no group reactivate, and **no list-all-groups** read (only a per-SKU lookup).
The domain is near-immutable (only `active` is mutable), and there is **no UI** and **no audit** for
menu changes. This sub-project adds the missing edit/reactivate/list endpoints (with in-place
mutators + event-based audit) and a terminal admin area to manage modifier groups, their options,
and their SKU assignments.

**There is no "menu item" entity.** A product SKU *is* the item and its price is `Product.unitPrice`
(edited on the Products screen, #2). This sub-project never creates items or item prices — only the
modifier decoration.

## Decisions (locked)

- **In-place edits via new domain mutators** (not delete+recreate): stable IDs, simple edits; past
  orders are unaffected because `dining` snapshots each modifier's name/`priceDelta` into
  `OrderLineModifier` at add-line time. Mirrors the `DiningTable.rename/reseat` pattern (#3d).
- **Event-based audit** (mirrors products #2 / tables #3d): `menu` publishes `MenuChanged`; `audit`
  listens (async, post-commit). One-way `audit → menu :: api`; no cycle (`menu` doesn't depend on
  `audit`). Safe from the single-writer-SQLite deadlock because auditing is the async listener, not a
  synchronous `audit.record` inside `menu`'s class-level `@Transactional`.
- **Access: MANAGER or ADMIN** — matches the existing menu write endpoints
  (`@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")`) and the Kitchen/Tables tiles
  (`session.isManager()`).
- **Soft-delete options via `active`** (no hard delete) — consistent with groups; `resolveSelections`
  filters to active so a deactivated option cannot be ordered.
- **No migration** — every edit UPDATEs an existing column; the `active` columns already exist (V26).

## What already exists (build-on inventory)

**Backend — module `com.company.pos.menu` (`allowedDependencies = { common, database, product :: api }`):**
- `menu/domain/ModifierGroup.java` — `UUID id`, `String name` (≤100), `int minSelections`,
  `int maxSelections`, `boolean active` (default true). Constructor `(id, name, min, max)`. Only
  mutator: `setActive(boolean)`.
- `menu/domain/ModifierOption.java` — `UUID id`, `UUID groupId`, `String name` (≤100),
  `BigDecimal priceDelta` (NUMERIC(19,4)), `boolean active`. Constructor `(id, groupId, name,
  priceDelta)`. Only mutator: `setActive(boolean)`.
- `menu/domain/ModifierGroupAssignment.java` — `UUID id`, `UUID groupId`, `String sku` (≤64),
  unique `(group_id, sku)`. Fully immutable (no setters).
- `menu/api/MenuService.java` — writes: `createModifierGroup(CreateModifierGroupCommand)`,
  `addOption(UUID groupId, AddOptionCommand)`, `assignGroupToSku(UUID, String)`,
  `unassignGroupFromSku(UUID, String)`, `deactivateModifierGroup(UUID)` (+ variant methods, out of
  scope here). Reads: `groupsForSku(String)`, `resolveSelections(String, List<UUID>)`,
  `listVariantGroups()`.
- Commands/views (`menu/api/`): `CreateModifierGroupCommand(String name, int minSelections, int
  maxSelections)`, `AddOptionCommand(String name, BigDecimal priceDelta)`, `ModifierGroupView(UUID
  id, String name, int minSelections, int maxSelections, List<ModifierOptionView> options)`,
  `ModifierOptionView(UUID id, String name, BigDecimal priceDelta)`, `ModifierResolution`,
  `ResolvedModifier`.
- `menu/web/MenuController.java` — `POST /menu/modifier-groups`, `POST
  /menu/modifier-groups/{groupId}/options`, `POST|DELETE /menu/modifier-groups/{groupId}/assignments`
  (`?sku=`), `DELETE /menu/modifier-groups/{groupId}` (deactivate) — all MANAGER+ADMIN; `GET
  /menu/products/{sku}/modifier-groups` (any authenticated). (Plus variant endpoints, out of scope.)
- `menu/application/DefaultMenuService.java` — `@Service @Transactional`; injects
  `ModifierGroupRepository`, `ModifierOptionRepository`, `ModifierGroupAssignmentRepository`,
  `ProductCatalog`, variant repos. Write methods inherit class `@Transactional`; reads are
  `@Transactional(readOnly = true)`.
- `menu/infrastructure/` — `ModifierGroupRepository`, `ModifierOptionRepository`,
  `ModifierGroupAssignmentRepository` (JpaRepository). Derived finders `findByGroupId(UUID)` may need
  adding.
- Migration `db/migration/menu/V26__create_menu_tables.sql` (tables incl. `active` columns).
  Highest Flyway version across ALL modules: **V34**. (No migration needed here.)
- **No menu audit today** (`audit` has no menu listener).

**Audit — module `com.company.pos.audit`:** already event-driven (`ProductChanged`, `TableChanged`,
`SettingChanged` listeners → `DefaultAuditService.append`); `AuditAction` enum; `allowedDependencies`
already include `product :: api` / `dining :: api`. Adding `menu :: api` is the same one-way pattern.

**Terminal (`pos-terminal/`):**
- Read-only `api/MenuApi.java` — only `modifierGroupsForSku(sku)`; `dto/ModifierGroupView`,
  `dto/ModifierOptionView`. No menu editing.
- Admin area: `AdminController` + `admin.fxml` tiles Staff/Products/Settings (ADMIN),
  Kitchen/Tables (MANAGER+ADMIN); `Navigator` `toStaff/toProducts/toKitchenRouting/toTables/toSettings`;
  `Services` public-final `*Api`; `ProductApi.list()` → `List<dto.ProductView>` (sku, name, …). The
  Products (#2) / Tables (#3d) screens are the templates: thin admin API client, synchronous VM
  (`errorMessage`-only-via-`ui.accept`, async-dispatcher regression test), controller + fxml, I/O-free
  dialog, `FxTasks` off-thread with `holder[]` in `onDone`.

## Backend design (`menu` + `audit`)

### Domain (in-place mutators)
- `ModifierGroup`: add `rename(String name)` and `setSelections(int min, int max)`. Reactivate reuses
  `setActive(true)`.
- `ModifierOption`: add `rename(String name)` and `reprice(BigDecimal priceDelta)`.

### API (`menu :: api`)
- `UpdateModifierGroupCommand(String name, int minSelections, int maxSelections)` (new).
- `UpdateOptionCommand(String name, BigDecimal priceDelta)` (new).
- `ModifierGroupAdminView(UUID id, String name, int minSelections, int maxSelections, boolean active,
  List<ModifierOptionAdminView> options, List<String> assignedSkus)` (new).
- `ModifierOptionAdminView(UUID id, String name, BigDecimal priceDelta, boolean active)` (new).
- `MenuChangeType` (new): `GROUP_CREATED, GROUP_UPDATED, GROUP_DEACTIVATED, GROUP_REACTIVATED,
  OPTION_ADDED, OPTION_UPDATED, OPTION_DEACTIVATED, OPTION_REACTIVATED, GROUP_ASSIGNED,
  GROUP_UNASSIGNED`.
- `MenuChanged(String entityRef, MenuChangeType type, String actor, BigDecimal oldPrice, BigDecimal
  newPrice)` (new) — `entityRef` is the group id (or option id for OPTION_*; for ASSIGN/UNASSIGN the
  group id, with the SKU in the audit detail); `oldPrice`/`newPrice` non-null only for OPTION_UPDATED
  price changes.
- `MenuService` additions:
  - `ModifierGroupView updateModifierGroup(UUID groupId, UpdateModifierGroupCommand command)`
  - `void reactivateModifierGroup(UUID groupId)`
  - `ModifierOptionView updateOption(UUID groupId, UUID optionId, UpdateOptionCommand command)`
  - `void deactivateOption(UUID groupId, UUID optionId)`
  - `void reactivateOption(UUID groupId, UUID optionId)`
  - `List<ModifierGroupAdminView> listModifierGroups()`

### Application (`DefaultMenuService`)
- Inject `DomainEvents events`; add `private static String actor()` via `SecurityContextHolder`
  (→ `"system"` when unauthenticated).
- Validation helpers: group — name non-blank, `min ≥ 0`, `max ≥ 1`, `min ≤ max`; option — name
  non-blank, `priceDelta != null`.
- `updateModifierGroup`: find-or-404, validate, `rename` + `setSelections`, publish
  `MenuChanged(GROUP_UPDATED,…)`, return `ModifierGroupView`.
- `reactivateModifierGroup`: find-or-404, `setActive(true)`, publish `MenuChanged(GROUP_REACTIVATED,…)`.
- `updateOption`: find option-or-404 (verify it belongs to `groupId`), validate; capture `oldPrice`
  before `reprice`; `rename` + `reprice`; publish `MenuChanged(OPTION_UPDATED, …, oldPrice, newPrice)`
  (prices set only when the delta changed), return `ModifierOptionView`.
- `deactivateOption` / `reactivateOption`: find-or-404 (belongs to group), `setActive`, publish
  `OPTION_DEACTIVATED`/`OPTION_REACTIVATED`.
- `listModifierGroups`: `groups.findAll()` → for each, `options.findByGroupId(id)` (all, incl.
  inactive) + `assignments.findByGroupId(id)` SKUs → `ModifierGroupAdminView`.
- Add `MenuChanged` publication to the EXISTING writes too (retro-audit): `createModifierGroup`
  (GROUP_CREATED), `addOption` (OPTION_ADDED), `assignGroupToSku` (GROUP_ASSIGNED),
  `unassignGroupFromSku` (GROUP_UNASSIGNED), `deactivateModifierGroup` (GROUP_DEACTIVATED).
- Repos: add `List<ModifierOption> findByGroupId(UUID groupId)` and `List<ModifierGroupAssignment>
  findByGroupId(UUID groupId)` derived finders if absent.

### Web (`MenuController`, all MANAGER+ADMIN)
- `GET /menu/modifier-groups` → `List<ModifierGroupAdminView>` (admin list; gated MANAGER+ADMIN
  because it exposes inactive + assignments).
- `PUT /menu/modifier-groups/{groupId}` body `UpdateModifierGroupCommand` → `ModifierGroupView`.
- `POST /menu/modifier-groups/{groupId}/reactivate` → 204.
- `PUT /menu/modifier-groups/{groupId}/options/{optionId}` body `UpdateOptionCommand` →
  `ModifierOptionView`.
- `DELETE /menu/modifier-groups/{groupId}/options/{optionId}` → 204 (deactivate).
- `POST /menu/modifier-groups/{groupId}/options/{optionId}/reactivate` → 204.

### Audit
- `AuditAction` additions: `MENU_GROUP_CREATED, MENU_GROUP_UPDATED, MENU_GROUP_DEACTIVATED,
  MENU_GROUP_REACTIVATED, MENU_OPTION_ADDED, MENU_OPTION_UPDATED, MENU_OPTION_DEACTIVATED,
  MENU_OPTION_REACTIVATED, MENU_GROUP_ASSIGNED, MENU_GROUP_UNASSIGNED`.
- `MenuChangedAuditListener` (new) — `@ApplicationModuleListener void on(MenuChanged)` → maps
  `MenuChangeType` → `AuditAction`, `append(action, actor, entityRef, details)` where details carry
  `oldPrice`/`newPrice` for OPTION_UPDATED (mirrors `ProductChangedAuditListener`'s PRICE handling).
- `audit/package-info.java`: add `"menu :: api"` to `allowedDependencies`.

## Terminal UI design (`pos-terminal/`)

### API client + DTOs
- `api/MenuAdminApi.java` (new, non-final): `List<ModifierGroupAdminView> listGroups()` (GET
  `/menu/modifier-groups`); `createGroup(name, min, max)` (POST `/menu/modifier-groups`);
  `updateGroup(groupId, name, min, max)` (PUT); `deactivateGroup(groupId)` (DELETE
  `/menu/modifier-groups/{id}`); `reactivateGroup(groupId)` (POST `.../reactivate`);
  `addOption(groupId, name, priceDelta)` (POST `.../options`); `updateOption(groupId, optionId, name,
  priceDelta)` (PUT); `deactivateOption(groupId, optionId)` (DELETE); `reactivateOption(groupId,
  optionId)` (POST `.../reactivate`); `assignSku(groupId, sku)` (POST `.../assignments?sku=`);
  `unassignSku(groupId, sku)` (DELETE `.../assignments?sku=`). Void endpoints pass `null`
  `TypeReference` (the established convention).
- `api/dto/ModifierGroupAdminView.java`, `api/dto/ModifierOptionAdminView.java` (mirrors,
  `@JsonIgnoreProperties`). Request records `api/ModifierGroupRequest(String name, int minSelections,
  int maxSelections)`, `api/ModifierOptionRequest(String name, BigDecimal priceDelta)`.

### Screen 1 — Modifier groups (`view/ModifierGroupsController` + `fxml/modifier-groups.fxml`)
- Header: title "Modifier groups", search `TextField` (filter by name), bound `errorLabel`, Back →
  `navigator.toAdmin()`.
- `TableView<GroupRow>` columns: Name, Selections (`min–max`), Options (count), Status (Active/Inactive).
- Actions: **New** / **Edit** (both open `ModifierGroupFormDialog`) / **Deactivate** (active only) /
  **Reactivate** (inactive only) / **Manage** (→ `navigator.toModifierGroupDetail(groupId)`; enabled
  when a row is selected).
- `ModifierGroupsViewModel` (sync; `errorMessage`-only-via-`ui.accept`): `loadGroups()`,
  `createGroup(name,min,max)`, `updateGroup(id,name,min,max)`, `deactivateGroup(id)`,
  `reactivateGroup(id)`. Client-side validation (blank name; `0 ≤ min ≤ max`, `max ≥ 1`) before the
  call. `reload()` re-fetches; each mutation's `onDone` re-kicks `reload()`.
- `GroupRow` derivation: a pure helper (or a static in the VM) mapping `ModifierGroupAdminView` →
  row fields (name, `min–max` string, option count, active). Unit-tested.

### Screen 2 — Group detail (`view/ModifierGroupDetailController` + `fxml/modifier-group-detail.fxml`)
- Reached via `navigator.toModifierGroupDetail(UUID groupId)`. Title = the group name.
- **Options** `TableView<OptionRow>`: Name, Price delta, Status; actions **Add** / **Edit** (open
  `ModifierOptionFormDialog`) / **Deactivate** / **Reactivate**.
- **Assigned SKUs** list: shows `sku — productName` (name looked up from the products list, fallback
  to SKU); actions **Assign** (`SkuPickerDialog` over `ProductApi.list()`) / **Unassign** (selected).
- Back → `navigator.toModifierGroups()`.
- `ModifierGroupDetailViewModel` (sync): `loadDetail(groupId)` (calls `listGroups()`, finds the group
  → its `ModifierGroupAdminView`; the admin view already carries options + assignedSkus, so ONE
  fetch), `loadProducts()` (for the picker, `ProductApi.list()`), `addOption(name,priceDelta)`,
  `updateOption(optionId,name,priceDelta)`, `deactivateOption(optionId)`, `reactivateOption(optionId)`,
  `assign(sku)`, `unassign(sku)`. Validation (blank name; priceDelta parseable, negatives allowed)
  before the call. Each mutation's `onDone` re-kicks `loadDetail`.

### Dialogs (I/O-free)
- `view/ModifierGroupFormDialog.java` — create/edit: name `TextField`, min/max `Spinner`s (min ≥ 0,
  max ≥ 1); static `validate(name,min,max)` (the unit-tested surface) drives the Save gate.
- `view/ModifierOptionFormDialog.java` — create/edit: name `TextField`, priceDelta `TextField`
  (numeric, negatives allowed); static `parseDelta`/`validate` unit-tested.
- `view/SkuPickerDialog.java` — assign: a combo/list of products (`sku — name`) → `Optional<String
  sku>`; display-only, I/O-free (controller passes in the product list).

### Wiring
- `Services.menuAdminApi`; `Navigator.toModifierGroups()` + `toModifierGroupDetail(UUID groupId)`; a
  **Menu** tile in `admin.fxml` + `AdminController`, gated MANAGER+ADMIN (`services.session.isManager()`).
  Additive only; exact fx:id bijection in both new FXML/controller pairs. No screen owns a
  timer/socket, so no `Navigator.Screen` lifecycle work.

## Testing

**Backend**
- `DefaultMenuService` tests: `updateModifierGroup` renames + sets selections; `min > max` (and
  `max < 1`) rejected; `updateOption` renames + reprices (and captures old→new price); `deactivateOption`
  then `reactivateOption` flips `active`; `listModifierGroups` returns inactive groups AND inactive
  options AND assigned SKUs; each write path publishes the expected `MenuChanged` type (assert via
  `@RecordApplicationEvents`, as the dining/product tests do); a deactivated option is excluded by
  `resolveSelections`.
- `MenuChangedAuditListener` E2E: drive the real endpoints (MockMvc as MANAGER) → Awaitility-await
  the audit rows (mirrors `AuditEventListenersTest`); assert an OPTION_UPDATED price change records
  old/new.
- `ModularityTests` (new one-way `audit → menu :: api`, no cycle).

**Terminal**
- `ModifierGroupsViewModelTest` and `ModifierGroupDetailViewModelTest`: each incl. the async-dispatcher
  regression test (deferred, undrained `ui`), and a validation short-circuit test (blank name /
  min>max / unparseable priceDelta rejected **without** calling the API).
- Pure-helper tests: `GroupRow`/`OptionRow` derivation; `ModifierGroupFormDialog.validate`,
  `ModifierOptionFormDialog.parseDelta`/`validate`.
- Full terminal suite green.

## Non-goals

Variant groups/members (→ #3g); creating menu *items* or editing item base prices (Products screen,
#2); kitchen routing (#3c); nested/conditional modifiers or per-SKU option overrides; hard-deleting
options (soft-delete via `active`); a `DevMenuSeeder`; live-push of menu changes to running terminals.

## Definition of done

- Backend gains group edit/reactivate, option edit/deactivate/reactivate, and an admin
  `GET /menu/modifier-groups` (all MANAGER+ADMIN), plus `MenuChanged` events consumed by an `audit`
  listener.
- Terminal Admin area gains a **Menu** tile (MANAGER+ADMIN) → a modifier-groups list screen and a
  per-group detail screen that manage groups, options, and SKU assignments against a running backend.
- `./mvnw verify` green (new menu/audit tests + `ModularityTests`); `./mvnw -f pos-terminal/pom.xml
  clean test` green (new VM/helper/dialog tests + full suite).
- No migration; the only new module dependency is `audit → menu :: api`.
