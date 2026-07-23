# Menu Variant Builder — Design Spec (#3g)

**Date:** 2026-07-23
**Branch:** `feat/terminal-ui-restaurant-slice` (unmerged, standing preference)
**Roadmap:** Go-live item 3 (Store setup console). Item 3 is decomposed into four config areas: kitchen routing (#3c ✅), dining tables (#3d ✅), store settings (#3e ✅), menu builder. The menu builder was itself split into **modifiers (#3f ✅, merge-ready at `6df262f`)** and **variants (#3g — this spec)**. #3g is the second and final half of the menu builder.

## Goal

Give MANAGER/ADMIN users a terminal screen to manage product **variant groups** and their **members** — create/rename/deactivate/reactivate a group, and add/relabel/deactivate/reactivate its members — with an event-based audit trail. Full-stack, structured as the direct parallel of the modifier builder (#3f), adapted to the simpler variant domain.

## Background: how variants differ from modifiers

A variant is a **SKU swap**, not a price delta. Each `VariantMember` points to a distinct product SKU that carries its own catalogue price (e.g. a "Sizes" group has members `BEER-S` → "Small" and `BEER-L` → "Large", each priced in the product catalogue). Selecting a variant simply adds an order line with the chosen member's SKU. Consequences:

- **No price to edit in the variant builder.** Price lives on the Products screen (#2). The variant builder never touches money.
- **In-place edits are fully safe against historical orders.** The order line stores the chosen SKU (its own immutable snapshot); there is no price-delta column that could drift. Renaming a group, relabelling a member, or deactivating a member never rewrites a past order.
- The variant domain is **minimal** vs modifiers: no `minSelections`/`maxSelections`, no `priceDelta`, no group↔SKU junction table (the member *is* the SKU assignment).

## Current state (what exists) vs the gap

**Exists today:** `VariantGroup(id, name, active)`; `VariantMember(id, variantGroupId, sku, displayLabel)` — member has **no `active` flag**. Service: `createVariantGroup`, `addVariantMember`, `deactivateVariantGroup`, `listVariantGroups()` (active-groups-only). Endpoints: `POST /menu/variant-groups`, `POST /menu/variant-groups/{id}/members`, `DELETE /menu/variant-groups/{id}` (all MANAGER|ADMIN), and **ungated** `GET /menu/variant-groups` (cashiers read it at order time). No variant writes publish any event — **variant audit is entirely absent**. Flyway ceiling is **V34**.

**Gap to close:** admin list incl. inactive; group rename + reactivate; member relabel + per-member soft-delete/restore (needs an `active` column); audit on all variant writes; terminal builder screen.

## Design decisions (approved)

1. **Member lifecycle = soft-delete + reactivate.** Add an `active` column to `variant_member` (Flyway **V35**). Members deactivate/restore exactly like modifier options.
2. **Full editing parity with the modifier builder.** In-place mutators on both entities.
3. **Audit = event-based**, reusing the existing `MenuChanged` event + async `MenuChangedAuditListener`. No new module edge (`audit → menu::api` already exists from #3f).
4. **Access = MANAGER or ADMIN.** New gated admin endpoints; the existing ungated ordering GET is untouched.

## Architecture

Three layers, mirroring #3f:

### Backend `menu` — domain / api / application / web

**Migration (the only schema change):**
- `src/main/resources/db/migration/menu/V35__variant_member_active.sql`:
  `ALTER TABLE variant_member ADD COLUMN active BOOLEAN NOT NULL DEFAULT true;`
- Store-server runs Flyway; embedded uses Hibernate `ddl-auto` and picks the column up from the new entity field. V35 is the next global version after V34.

**Domain mutators (in-place; stable IDs):**
- `VariantGroup.rename(String name)`.
- `VariantMember`: new `boolean active` field (default true) + `relabel(String displayLabel)` + `deactivate()` + `reactivate()`. **The SKU is identity and is not editable** — changing a member's product means remove + re-add.

**`menu.api` additions:**
- `UpdateVariantGroupCommand(String name)`.
- `UpdateVariantMemberCommand(String displayLabel)`.
- `VariantGroupAdminView(UUID id, String name, boolean active, List<VariantMemberAdminView> members)`.
- `VariantMemberAdminView(UUID id, String sku, String displayLabel, boolean active)`.
- Reuse the existing `MenuChanged(String entityRef, MenuChangeType type, String actor, String detail, BigDecimal oldPrice, BigDecimal newPrice)` — variants pass `null` for `oldPrice`/`newPrice`; `detail` carries the name / sku / label.
- `MenuChangeType` gains **8** values: `VARIANT_GROUP_CREATED`, `VARIANT_GROUP_UPDATED`, `VARIANT_GROUP_DEACTIVATED`, `VARIANT_GROUP_REACTIVATED`, `VARIANT_MEMBER_ADDED`, `VARIANT_MEMBER_UPDATED`, `VARIANT_MEMBER_DEACTIVATED`, `VARIANT_MEMBER_REACTIVATED`.

**`MenuService` / `DefaultMenuService` additions** (`DefaultMenuService` is class-level `@Transactional`; write methods inherit it, reads use `@Transactional(readOnly = true)`; it already holds the injected `DomainEvents events` and `actor()` helper from #3f):
- `updateVariantGroup(UUID id, UpdateVariantGroupCommand)` → `VariantGroupAdminView` (blank-name reject).
- `reactivateVariantGroup(UUID id)` → `VariantGroupAdminView`.
- `updateVariantMember(UUID groupId, UUID memberId, UpdateVariantMemberCommand)` → relabel (blank-label reject); `loadMember(groupId, memberId)` helper verifies the member belongs to the group (mirrors `loadOption`).
- `deactivateVariantMember(UUID groupId, UUID memberId)`, `reactivateVariantMember(UUID groupId, UUID memberId)`.
- `listVariantGroupsAdmin()` → `List<VariantGroupAdminView>` (all groups incl. inactive, all members incl. inactive), `@Transactional(readOnly = true)`.
- **Retro-audit** the three existing writes: `createVariantGroup`/`addVariantMember`/`deactivateVariantGroup` now `events.publish(new MenuChanged(...))`.
- **Duplicate-SKU guard:** `addVariantMember` rejects a SKU that already exists **active** in the group (service-level, not a DB constraint — avoids migration complexity with soft-deleted rows).

**Ordering read-path change (the one behavioural subtlety):** `listVariantGroups()` (the ungated ordering read) currently filters only `VariantGroup::isActive`. It must **now also filter members to `active`** (`.filter(VariantMember::isActive)`) so a deactivated size never appears on the ordering screen. `listVariantGroupsAdmin()` does **not** filter — it returns everything.

**Repos:** `VariantMemberRepository.findByVariantGroupId(UUID)` already exists and returns all members (admin uses it directly; the ordering service filters active in code). No new finder strictly required; add one only if a step needs it.

**`MenuController` — 6 new endpoints, all `@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")`:**
- `GET /menu/variant-groups/admin` → `listVariantGroupsAdmin`.
- `PUT /menu/variant-groups/{id}` → `updateVariantGroup`.
- `POST /menu/variant-groups/{id}/reactivate` → `reactivateVariantGroup`.
- `PUT /menu/variant-groups/{groupId}/members/{memberId}` → `updateVariantMember`.
- `DELETE /menu/variant-groups/{groupId}/members/{memberId}` → `deactivateVariantMember` (204).
- `POST /menu/variant-groups/{groupId}/members/{memberId}/reactivate` → `reactivateVariantMember`.
- Existing `POST` create, `POST` add-member, `DELETE` deactivate-group, and the **ungated** `GET /menu/variant-groups` stay exactly as they are. Authorization is method security — **SecurityConfig is not touched**.

### Backend `audit`

- `AuditAction` += 8 constants: `MENU_VARIANT_GROUP_CREATED/UPDATED/DEACTIVATED/REACTIVATED`, `MENU_VARIANT_MEMBER_ADDED/UPDATED/DEACTIVATED/REACTIVATED`.
- Extend the existing `MenuChangedAuditListener` switch over `MenuChangeType` (exhaustive, no `default` → the compiler forces the 8 new cases). Details carry sku/label/name; `oldPrice`/`newPrice` are null for variants.
- **No new module dependency** — `audit → menu::api` already exists from #3f, and it remains one-way (no cycle; `ModularityTests` proves it).
- E2E `MenuChangedVariantAuditTest` — MockMvc drives the real committing endpoints as a MANAGER, Awaitility awaits the audit rows (the async `@ApplicationModuleListener` fires only post-commit; a bare publish would not fire it). Parallel to #3f's `MenuChangedAuditTest`.

### Terminal (`pos-terminal`)

- `VariantAdminApi` (non-final/subclassable; void endpoints pass `null` TypeReference): `listAdmin()`, `createGroup`, `updateGroup`, `reactivateGroup`, `addMember`, `updateMember`, `deactivateMember`, `reactivateMember` — verbs/paths matching the backend.
- DTO mirrors `VariantGroupAdminView`, `VariantMemberAdminView` + request records (`VariantGroupRequest(name)`, `VariantMemberRequest(sku, displayLabel)` / label-only for relabel).
- `VariantBuilderViewModel` — synchronous, returns plain values (mutations → `boolean`); the **only** off-thread observable write is `errorMessage` inside `ui.accept(...)`; validation short-circuits before the API call (tests assert the API was not called on invalid input); an async-dispatcher regression test uses a deferred `ArrayDeque::add` dispatcher.
- Pure `VariantRows` — no JavaFX/HTTP imports: `memberLabel` = `"sku — displayLabel"`, `statusLabel` = Active/Inactive, null-safe.
- I/O-free dialogs: `VariantGroupFormDialog` (name; static validate) and `VariantMemberFormDialog` (label; static validate). **Adding a member reuses #3f's `SkuPickerDialog`** to choose the SKU, then prompts for the label; relabelling uses the label-only path.
- `VariantBuilderController` + `variant-builder.fxml` — single master/detail screen: groups `TableView<VariantGroupAdminView>` (master) → members `TableView<VariantMemberAdminView>` (detail). Selecting a group renders its members from the **already-loaded** admin view (no per-selection fetch). FX-threading identical to #3f: the controller runs VM methods off-thread via `FxTasks.run`, reads results only in the FX-thread `onDone` via a holder; **no blocking VM/HTTP call inside any `onDone`**; every mutation re-kicks a fresh `FxTasks` task to reload; dialogs are I/O-free (`showAndWait()` allowed in `onDone`).
- Wiring: `Navigator.toVariants`, `Services.variantAdminApi`, a new **Variants** tile gated on the `manager` boolean (MANAGER||ADMIN). Additionally relabel #3f's existing modifier tile **"Menu" → "Modifiers"** (a one-word FXML edit in `admin.fxml`) so the two builder tiles read as a pair. The screen owns no socket/timer, so it does **not** implement `Navigator.Screen` (matches `TablesController`/`ModifierBuilderController`).

## Data flow

1. Manager opens Admin → **Variants** → `VariantBuilderController` calls `vm.load()` off-thread → `VariantAdminApi.listAdmin()` → `GET /menu/variant-groups/admin` → renders groups table.
2. Selecting a group renders its members (incl. inactive) from the cached `VariantGroupAdminView`.
3. Create/rename/deactivate/reactivate group and add/relabel/deactivate/reactivate member each: collect input in an I/O-free dialog (add-member first picks a SKU via `SkuPickerDialog`), then the controller kicks a fresh `FxTasks` task → `VariantAdminApi` → endpoint → on success re-kicks `reload()` and reselects.
4. Every write publishes `MenuChanged`; the async `MenuChangedAuditListener` records an audit row post-commit.
5. The ordering flow is unaffected except that deactivated members no longer appear (they are filtered out of `listVariantGroups()`).

## Error handling

- Server is authoritative: blank name/label → `DomainException.validation` → 400; unknown group/member → 404/validation; a member not belonging to the named group → validation error; adding a duplicate active SKU to a group → validation error. The terminal surfaces the server message via `errorMessage`; `VariantBuilderViewModel` mirrors the blank-input checks for a friendly pre-flight message but the server re-validates as the authority.
- Deactivating a member or group never affects historical orders (SKU-on-line snapshot).

## Testing

- `VariantAdminServiceTest` (`@RecordApplicationEvents`, product-free where possible or seeded via the existing helper): rename/reactivate group, relabel/deactivate/reactivate member, admin-list-includes-inactive, ordering-list-excludes-inactive-member, duplicate-active-SKU rejected, blank rejects, and the published `MenuChanged` type per write (including the group-reactivate and member-reactivate paths — full coverage up front).
- `MenuControllerTest` — happy path **and** a 403 (cashier) for **every** one of the 6 new endpoints (full coverage stated in the plan up front; #3f's Task 2 under-specified this and needed a fix wave — do not repeat).
- `MenuChangedVariantAuditTest` — async E2E via Awaitility.
- Terminal: `VariantRowsTest`, `VariantBuilderViewModelTest` (incl. deferred-dispatcher async regression + validation-short-circuits-before-API), the two form dialogs' static-method tests, and the full terminal suite green. (`QuoteApiTest` StubServer 403 is a known unrelated flake — rerun in isolation if it is the only failure.)
- `ModularityTests` green (proves no cycle from the audit switch extension).

## Global constraints

- **One migration (Flyway V35)** for the `variant_member.active` column — store-server only; the version is the next global number after V34.
- No new module dependency (the `audit → menu::api` edge already exists; keep it one-way).
- Scope is **variants only** (modifiers are already done in #3f; do not touch modifier code except the tile relabel).
- In-place mutators (stable IDs); safe against historical orders (SKU-on-line snapshot; no price delta).
- Event-based audit (async `@ApplicationModuleListener`); `DefaultMenuService` only `events.publish(...)` inside its transaction — no synchronous audit call (would deadlock single-writer SQLite).
- Ordering read path (`listVariantGroups`) must filter members to active.
- MANAGER+ADMIN on all new endpoints + the Variants tile; the ungated ordering GET stays ungated; SecurityConfig untouched.
- Money is BigDecimal (not applicable to variants — no price field — but the shared `MenuChanged` price fields stay BigDecimal, passed null).
- FX-threading convention (sync VM, `errorMessage`-only via `ui.accept`, holder-in-`onDone`, mutations re-kick a fresh task, dialogs I/O-free, async-dispatcher regression test).
- JDK 21; the terminal is a separate build (`./mvnw -f pos-terminal/pom.xml`), not the root reactor.

## Out of scope / deferred

- Reordering members within a group (display order); the ordering UI shows members in insertion order.
- A `(variant_group_id, sku)` DB unique constraint (handled by a service-level active-SKU guard instead).
- Editing a member's target SKU in place (do remove + re-add).
- Any change to how the ordering client renders or prices variants (unchanged apart from the active-member filter).
