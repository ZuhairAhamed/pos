# Sub-project #3e — Store Settings Admin (design)

_Date: 2026-07-20. Branch: `feat/terminal-ui-restaurant-slice`._
_Part of the [go-live roadmap](2026-07-19-go-live-roadmap.md) item 3 (Store setup console), whose four
independent config areas are: **store settings (this sub-project)**, dining tables (shipped, #3d),
kitchen routing (shipped, #3c), and the menu builder (not yet started)._

## Problem

Typed store configuration (tax rate, currency, service-charge toggle/percent, discount caps, dining
and kitchen defaults, …) lives in the `configuration` module as 18 `SettingKey`s. The write path
exists — `PUT /config/{key}` (ADMIN-only) publishes `SettingChanged`, which the `audit` module
already records as `SETTING_CHANGED` — but there is **no read-over-HTTP endpoint** (`GET /config`
does not exist), so no client can list settings. There is also **no UI**, and **no value
validation**: `ConfigurationService.put` stores the raw string, and consumers coerce on read
(`Integer.parseInt`/`new BigDecimal`), so a malformed value (e.g. a non-numeric `tax.rate`) is
accepted silently and then throws `NumberFormatException` at checkout. This sub-project adds a typed
`GET /config` + write-time validation and an ADMIN terminal console to view and edit the
store-operational settings.

## Decisions (locked)

- **Typed settings + server-side validation.** Each `SettingKey` gains a `SettingType`
  (STRING/BOOLEAN/INT/DECIMAL/PERCENT/CSV); `GET /config` returns the type so the terminal renders
  the right editor, and `ConfigurationService.put` validates the value against the type and rejects
  a bad one with 400 — closing the "bad numeric setting throws at checkout" hole for every caller.
- **Curated editable set, grouped by category.** The terminal shows the store-operational settings
  grouped into sections (Store info / Tax / Service charge / Discounts / Operations) and **hides**
  per-terminal / infra keys (`STORE_ID`, `TERMINAL_ID`, `RECEIPT_PRINTER_PORT`, `INVENTORY_LOCATION`).
  Curation, friendly labels, and the live-critical flag are **terminal-side presentation** — the
  server stays the value+type source of truth and returns the full typed list.
- **ADMIN only.** The Settings tile and `GET /config` are ADMIN-gated, matching the existing
  `PUT /config` gate and the Staff/Products tiles (unlike the MANAGER+ADMIN Kitchen/Tables tiles).
- **Confirm live-critical edits.** Changing a pricing-affecting key (`CURRENCY_CODE`,
  `TAX_INCLUSIVE`, `VAT_RATE`, `SERVICE_CHARGE_ENABLED`, `SERVICE_CHARGE_PERCENT`,
  `DISCOUNT_CASHIER_MAX_PERCENT`, `DISCOUNT_CASHIER_MAX_AMOUNT`) prompts a lightweight confirm before
  the PUT.
- **No new audit work.** `PUT /config/{key}` already publishes `SettingChanged` and the audit
  listener already records it — do NOT add a second audit path.
- **No migration.** The `setting` table exists; `SettingType` is enum code, not a stored column.

## What already exists (build-on inventory)

**Backend — module `com.company.pos.configuration`:**
- `configuration/api/SettingKey.java` — `enum` of 18 keys, each `(String key, String defaultValue)`
  with `key()` / `defaultValue()`. Full current list (key → default):
  `STORE_NAME`=`store.name`/`My Store`, `CURRENCY_CODE`=`currency.code`/`SAR`, `LOCALE`=`locale`/`en`,
  `TAX_INCLUSIVE`=`tax.inclusive`/`false`, `RECEIPT_PRINTER_PORT`=`printer.port`/`COM1`,
  `VAT_RATE`=`tax.rate`/`0.15`, `STORE_ID`=`store.id`/`S01`, `TERMINAL_ID`=`terminal.id`/`T01`,
  `INVENTORY_LOCATION`=`inventory.location`/`MAIN`,
  `DISCOUNT_REASON_CODES`=`discount.reason.codes`/`DAMAGED,PRICE_MATCH,LOYALTY,MANAGER_COMP`,
  `DISCOUNT_CASHIER_MAX_PERCENT`=`discount.cashier.max.percent`/`10`,
  `DISCOUNT_CASHIER_MAX_AMOUNT`=`discount.cashier.max.amount`/`20.00`,
  `DASHBOARD_REVENUE_WINDOW_DAYS`=`dashboard.revenue.window.days`/`7`,
  `DINING_TABLE_DEFAULT_SEATS`=`dining.table.default.seats`/`4`,
  `KITCHEN_DEFAULT_STATION`=`kitchen.default.station`/`Kitchen`,
  `SERVICE_CHARGE_ENABLED`=`service.charge.enabled`/`false`,
  `SERVICE_CHARGE_PERCENT`=`service.charge.percent`/`0`,
  `SERVICE_CHARGE_LABEL`=`service.charge.label`/`Service Charge`.
- `configuration/api/ConfigurationService.java` — `getString/getInt/getBoolean(SettingKey)` and
  `put(SettingKey, String)`. `getString` returns the stored value or `key.defaultValue()`. No
  list-all method; no validation.
- `configuration/application/DefaultConfigurationService.java` — `@Service @Transactional`;
  `getString` is `@Transactional(readOnly = true)` over `SettingRepository`; `put` upserts a
  `Setting` row.
- `configuration/web/ConfigurationController.java` — `@RestController`, `PUT /config/{key}`
  (`@PreAuthorize("hasRole('ADMIN')")`, body `record UpdateRequest(String value)`, 204): resolves the
  enum via `SettingKey.valueOf(key)` (unknown → `DomainException.validation`), reads `oldValue`, calls
  `config.put`, then `events.publish(new SettingChanged(settingKey.key(), oldValue, value, principal.getName()))`.
- `configuration/api/SettingChanged.java` — `record SettingChanged(String key, String oldValue,
  String newValue, String actor)`; consumed by `audit/application/SettingChangedAuditListener`
  (`@ApplicationModuleListener` → `AuditAction.SETTING_CHANGED`).
- `configuration/domain/Setting.java` (`setting` table: `setting_key` PK, `setting_value`,
  `updated_at`); `V1__configuration_setting.sql` creates it empty (defaults live in the enum, no
  seeded rows).
- Security: `/config/*` falls under `.anyRequest().authenticated()`; the ADMIN gate is the
  method-level `@PreAuthorize`. `SecurityConfig` needs NO change.

**Terminal (`pos-terminal/`):**
- No `ConfigApi`; the terminal reads no typed server config today (only `GET /sales/discount-policy`).
- Admin area: `AdminController` + `admin.fxml` tiles Staff/Products (ADMIN), Kitchen/Tables
  (MANAGER+ADMIN); `Navigator` `toStaff/toProducts/toKitchenRouting/toTables`; `Services` holds
  public-final `*Api` clients. The Products (#2) / Tables (#3d) screens are the pattern: thin admin
  API client, synchronous VM (`errorMessage`-only-via-`ui.accept`, async-dispatcher regression test),
  controller + fxml, `FxTasks` off-thread with `holder[]` in `onDone`.

## Backend design (`configuration`)

### API
- `configuration/api/SettingType.java` (new):
  ```
  enum SettingType { STRING, BOOLEAN, INT, DECIMAL, PERCENT, CSV;
      void validate(String value)  // throws DomainException.validation on a bad value
  }
  ```
  Validation rules: `STRING` non-blank; `BOOLEAN` equalsIgnoreCase "true"/"false"; `INT` parseable
  integer ≥ 0; `DECIMAL` parseable BigDecimal ≥ 0; `PERCENT` parseable BigDecimal in [0, 100]; `CSV`
  at least one non-blank comma token. (`validate` lives in `api` because it is the key's contract;
  it depends only on `common`'s `DomainException`.)
- `configuration/api/SettingKey.java` (modify): add a third constructor arg `SettingType type` and a
  `SettingType type()` accessor. Type per key: `STRING` — STORE_NAME, CURRENCY_CODE, LOCALE,
  RECEIPT_PRINTER_PORT, STORE_ID, TERMINAL_ID, INVENTORY_LOCATION, KITCHEN_DEFAULT_STATION,
  SERVICE_CHARGE_LABEL; `BOOLEAN` — TAX_INCLUSIVE, SERVICE_CHARGE_ENABLED; `DECIMAL` — VAT_RATE,
  DISCOUNT_CASHIER_MAX_AMOUNT; `PERCENT` — SERVICE_CHARGE_PERCENT, DISCOUNT_CASHIER_MAX_PERCENT;
  `INT` — DASHBOARD_REVENUE_WINDOW_DAYS, DINING_TABLE_DEFAULT_SEATS; `CSV` — DISCOUNT_REASON_CODES.
- `configuration/api/SettingView.java` (new): `record SettingView(String name, String key, String
  value, String defaultValue, String type)` — `name` is the enum constant (the PUT path segment),
  `type` is `SettingType.name()`.
- `ConfigurationService` (modify): add `List<SettingView> list()`.

### Application
- `DefaultConfigurationService.put` (modify): call `key.type().validate(value)` **before** upserting.
- `DefaultConfigurationService.list()` (new, `@Transactional(readOnly = true)`): map every
  `SettingKey.values()` to `new SettingView(k.name(), k.key(), getString(k), k.defaultValue(),
  k.type().name())`.

### Web
- `ConfigurationController` (modify): add `GET /config` → `List<SettingView>`,
  `@PreAuthorize("hasRole('ADMIN')")`, delegating to `config.list()`. The existing `PUT /config/{key}`
  is unchanged (it now benefits from `put`'s validation — an invalid value 400s before the event).

## Terminal UI design (`pos-terminal/`)

### API client
- `api/ConfigApi.java` (new, non-final): `List<SettingView> list()` (GET `/config`), `void
  update(String name, String value)` (PUT `/config/{name}`, body `SettingUpdateRequest`, null
  `TypeReference` for the 204).
- `api/dto/SettingView.java` (new, `@JsonIgnoreProperties(ignoreUnknown = true)`): mirror
  `(String name, String key, String value, String defaultValue, String type)`.
- `api/SettingUpdateRequest.java` (new): `record SettingUpdateRequest(String value)`.

### Validation + catalog (pure, unit-tested)
- `viewmodel/SettingValidation.java` (new): `String validate(String type, String value)` returning a
  friendly error message or null — mirrors the server `SettingType` rules per type. Used client-side
  before PUT (the server re-validates).
- `viewmodel/SettingsCatalog.java` (new): the presentation table. A static map of curated enum name →
  `Entry(String category, String label, boolean liveCritical)`, plus `List<Group> group(List<SettingView>
  serverList)` → sections in a fixed category order, each holding the `SettingView`s whose `name` is in
  the catalog (server keys NOT in the catalog are dropped — that is the hide-infra-keys filter). A
  `boolean isLiveCritical(String name)` helper. `Group(String category, List<Row> rows)`,
  `Row(SettingView view, String label, boolean liveCritical)`.

Curated set (name → category, label, liveCritical★):
- Store info: `STORE_NAME`("Store name"), `CURRENCY_CODE`("Currency code")★, `LOCALE`("Locale")
- Tax: `TAX_INCLUSIVE`("Prices include tax")★, `VAT_RATE`("VAT rate (fraction, e.g. 0.15)")★
- Service charge: `SERVICE_CHARGE_ENABLED`("Service charge enabled")★,
  `SERVICE_CHARGE_PERCENT`("Service charge %")★, `SERVICE_CHARGE_LABEL`("Service charge label")
- Discounts: `DISCOUNT_CASHIER_MAX_PERCENT`("Cashier max discount %")★,
  `DISCOUNT_CASHIER_MAX_AMOUNT`("Cashier max discount amount")★,
  `DISCOUNT_REASON_CODES`("Discount reason codes (CSV)")
- Operations: `DINING_TABLE_DEFAULT_SEATS`("Default table seats"),
  `KITCHEN_DEFAULT_STATION`("Default kitchen station"),
  `DASHBOARD_REVENUE_WINDOW_DAYS`("Dashboard revenue window (days)")
- Hidden (not in catalog): `STORE_ID`, `TERMINAL_ID`, `RECEIPT_PRINTER_PORT`, `INVENTORY_LOCATION`.

### View-model
- `viewmodel/SettingsViewModel.java` (new) — synchronous; only off-thread write is `errorMessage`
  inside `ui.accept`. Methods: `List<SettingView> loadSettings()` (GET, null on failure);
  `boolean update(String name, String value, String type)` — validate via `SettingValidation` first
  (invalid → `errorMessage`, return false, **no PUT**), else `configApi.update` and return true.

### Screen
- `resources/fxml/settings.fxml` + `view/SettingsController.java` — header (title "Store settings",
  bound `errorLabel`, Back → `navigator.toAdmin()`) and a `ScrollPane` wrapping a `VBox settingsBox`
  (fx:id) that the controller **populates programmatically** (mixed editor types make a static FXML /
  TableView awkward). `reload()` fetches the settings in one `FxTasks` work lambda; in `onDone` it
  rebuilds `settingsBox` from `SettingsCatalog.group(...)`: one titled section per category; each row
  = label + a type-appropriate editor (`CheckBox` for BOOLEAN, `TextField` otherwise, seeded from the
  current value) + a **Save** button. Save handler: read the editor's string value → if the key is
  live-critical, `showAndWait` a confirm dialog (I/O-free, FX thread) → on OK run `vm.update(name,
  value, type)` in a fresh `FxTasks` task whose `onDone` re-kicks `reload()` (never a blocking VM/HTTP
  call in `onDone`). No manager-PIN flow (the session is already ADMIN; PUT is ADMIN-gated).

### Admin area entry
- `Navigator.toSettings()`; `Services` gains `configApi`; a **Settings** tile in `admin.fxml` +
  `AdminController`, gated ADMIN-only (`services.session.roles().contains("ADMIN")`, like
  Staff/Products). Additive only; exact fx:id bijection in the new FXML/controller pair.

## Testing

**Backend**
- `SettingType` per-type validate tests: valid passes; invalid throws (`INT` "x", `DECIMAL` "-1",
  `PERCENT` "150", `BOOLEAN` "yes", `CSV` "", `STRING` "  ").
- `ConfigurationService.put` rejects an invalid value (e.g. `put(VAT_RATE, "abc")` → `DomainException`)
  and stores a valid one.
- Web: `GET /config` returns all keys with `type`/`default`, ADMIN 200 / non-admin 403; `PUT
  /config/{key}` with an invalid value → 400 and publishes NO `SettingChanged`; a valid PUT → 204 and
  the existing audit path still fires (reuse the `AuditEventListenersTest` MockMvc+Awaitility pattern
  or assert the 204 + a follow-up `GET` reflects the new value).
- `ModularityTests`.

**Terminal**
- `SettingsViewModelTest` incl. the async-dispatcher regression test (deferred, undrained `ui`
  dispatcher — `errorMessage` "" pre-drain then the error post-drain) and a test that `update` with an
  invalid value is rejected **without** calling the API.
- `SettingValidationTest` (pure): per-type valid/invalid → null vs message.
- `SettingsCatalogTest` (pure): grouping into the fixed section order; hidden keys dropped;
  live-critical flags correct; a server key absent from the catalog is not shown.

## Non-goals

Editing per-terminal identity (`TERMINAL_ID`, `RECEIPT_PRINTER_PORT`) from this store console; a raw
"advanced / all keys" mode; live-push of setting changes to other running terminals (they re-read on
their own cadence / next screen entry); changing `VAT_RATE` semantics (stays a fraction, e.g. 0.15);
a new audit path (the existing `SettingChanged` → `SETTING_CHANGED` already covers it); a migration or
seeded rows.

## Definition of done

- Backend gains `SettingType` + `SettingKey.type()`, write-time validation in
  `ConfigurationService.put`, and an ADMIN `GET /config` returning typed `SettingView`s.
- Terminal Admin area gains a functional **Settings** tile (ADMIN) → grouped console that lists the
  curated settings with type-appropriate editors, validates before saving, confirms live-critical
  edits, and writes via `PUT /config/{name}` against a running backend.
- `./mvnw verify` green (new configuration tests + `ModularityTests`); `./mvnw -f pos-terminal/pom.xml
  clean test` green (new VM/validation/catalog tests + full suite).
- No migration; no new module dependency.
