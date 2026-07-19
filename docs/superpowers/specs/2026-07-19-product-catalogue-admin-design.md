# Sub-project #2 — Product Catalogue Admin (design)

_Date: 2026-07-19. Branch: `feat/terminal-ui-restaurant-slice`._
_Part of the [go-live roadmap](2026-07-19-go-live-roadmap.md) (item 2 of 8)._

## Problem

A restaurant cannot open in production because there is **no way to create or
edit products and prices**. Today the catalogue has exactly one write path —
`ProductErpSyncService`, driven by `ErpClient`. In production the ERP is a fake
(`FakeErpClient` returns nothing) and the only seeded data comes from
`DevCatalogueSeeder` (`@Profile("dev")`). There is no product create/edit
endpoint and no admin UI. No menu means no restaurant. This sub-project adds the
backend write surface and the terminal UI to manage products over their full
lifecycle, hanging off the shared **Admin area** established in sub-project #1.

## Decisions (locked)

- **Source of truth:** the store runs **without a real ERP** (the roadmap's
  premise). Admin CRUD is authoritative and writes products directly. **No
  "locally managed" protection flag and no migration** — the ERP-sync overwrite
  risk is dormant (prod `FakeErpClient` returns no products, so sync is a no-op).
  If a real ERP is wired later, that integration owns reconciliation.
- **Access:** **ADMIN only** (`@PreAuthorize("hasRole('ADMIN')")`), reusing the
  Staff-management gate and the ADMIN-only Admin tiles.
- **Categories:** **pick existing + add-new inline** — no separate category CRUD
  screen. The product form offers existing categories plus an "add new" path that
  creates a `Category` on the fly.
- **Lifecycle:** create · edit · deactivate · reactivate. **No hard delete** —
  sales and dining order lines reference products by SKU, so removal is soft via
  the existing `active` flag (mirrors Staff `enabled`).
- **Audit (event-based, full lifecycle):** `product` **cannot call `audit`
  directly** — `audit` already depends on `product :: api` (its
  `ProductPriceChanged` listener), so a `product → audit` call would be a module
  cycle that `modules.verify()` rejects. Auditing therefore uses the system's
  established "events for facts" pattern: `ProductAdminService` publishes a
  `ProductChanged` domain event for every action (create / update / price change /
  deactivate / reactivate / inline-category create) and a **new
  `@ApplicationModuleListener` in the `audit` module** records each as the matching
  `AuditAction`. Because auditing is async **after commit** (never a synchronous
  `REQUIRES_NEW` call), `ProductAdminService` **is `@Transactional`** with no
  deadlock — the same pattern `ProductErpSyncService`/`SaleCompleted` already use.
  The event carries the `actor` (captured on the request thread) so the async
  listener records the real admin, not `"system"`. `product` gains only
  `configuration :: api` (store-currency default) in `allowedDependencies`; it does
  **not** depend on `audit`.

## What already exists (build-on inventory)

Module `com.company.pos.product` (light hexagonal layout).

- `product/domain/Product.java` — table `product`. Fields: `id (UUID)`,
  `sku (unique, ≤64, NOT NULL)`, `name (≤300, NOT NULL)`, `categoryId (UUID,
  nullable)`, `categoryName (≤200, nullable)`, `barcode (≤64, nullable)`,
  `unitOfMeasure (≤16, nullable)`, `unitPrice (BigDecimal precision 19 scale 4,
  nullable)`, `currencyCode (≤3, nullable)`, `erpVersion (long, default 0)`,
  `active (boolean, default true)`. Public constructor `Product(UUID id, String
  sku, String name)`. Setters exist for every field **except** `id` and `sku`
  (both immutable). No value objects.
- `product/domain/Category.java` — table `category`. Fields: `id (UUID)`,
  `code (unique, ≤50, NOT NULL)`, `name (≤200, NOT NULL)`, `erpVersion (long)`.
  Constructor `Category(UUID id, String code, String name)`; `setName` exists.
- `product/api/ProductCatalog.java` — read-only facade: `Optional<ProductView>
  findBySku(String)`, `List<ProductView> search(String)`, `List<ProductView>
  findAll()`.
- `product/api/ProductView.java` — `record ProductView(String sku, String name,
  String categoryName, String barcode, String unitOfMeasure, BigDecimal
  unitPrice, String currencyCode, boolean active)`. Exposes nothing sensitive;
  reused as-is. (Omits `id` and `categoryId` — intentional.)
- `product/infrastructure/ProductRepository.java` — `JpaRepository<Product,UUID>`
  with `findBySku`, `findByNameContainingIgnoreCaseOrSkuContainingIgnoreCase`.
- `product/infrastructure/CategoryRepository.java` — `JpaRepository<Category,
  UUID>` with `findByCode`.
- `product/web/ProductController.java` — package-private `@RestController`,
  read-only: `GET /products` (list / `?q=` search), `GET /products/{sku}`.
- `product/application/ProductErpSyncService.java` — the ERP write path. Upserts
  products and categories by code/SKU; **publishes `ProductPriceChanged` when a
  product's price differs** on sync. This path is left untouched.
- `product/package-info.java` — `allowedDependencies = { common, database,
  integration :: api }`. **This sub-project adds `audit :: api`.**
- Auditing via `audit :: api` `AuditService.record(AuditAction, actor,
  entityRef, Map<String,String>)` — the same facade used by Staff management.
- Method security enabled; JWT `roles` claim → `ROLE_*`; friendly errors via
  `DomainException.{validation,conflict,notFound}` → `ApiExceptionHandler`.
- The same `Product`/SKU serves **both** retail and dining/menu; `dining` and
  `menu` reference products by SKU (`menu` adds modifiers/variants keyed on SKU).
  Editing a product therefore affects both tracks — expected and correct.

## Backend design (all inside the `product` module)

No new module. One `allowedDependencies` addition (`audit :: api`);
`ModularityTests` must stay green.

### Domain — add encapsulated behavior to `Product`

`sku` stays **immutable** (it is the key recorded on sales and dining lines).
Add methods (thin wrappers over the existing setters, for intent + a single
mutation surface):

- `rename(String name)`
- `changeCategory(UUID categoryId, String categoryName)`
- `changePrice(BigDecimal unitPrice, String currencyCode)`
- `changeBarcode(String barcode)`
- `changeUnitOfMeasure(String unitOfMeasure)`
- `activate()` / `deactivate()`

### `ProductAdminService` (new, `product/application`)

Separate from `DefaultProductCatalog` (keep read concerns isolated). It **is
`@Transactional`** (class-level): each method does its repository write and
publishes `ProductChanged` event(s) in the same transaction; the outbox captures
the publications and the `audit` listener records them async **after commit** — so
there is no synchronous `REQUIRES_NEW` audit call and thus no single-writer-SQLite
deadlock (the `ProductErpSyncService` pattern). It depends on `DomainEvents`
(`common`) and `ConfigurationService` — **not** `AuditService`. Uniqueness is
pre-checked for a friendly error and enforced for real by the DB unique constraint
on `sku`.

Methods (each publishes a `ProductChanged` event):

| Method | Behavior |
|---|---|
| `createProduct(CreateProductCommand)` | conflict if SKU taken; resolve category (see below); default currency/UoM; validate; save; publish `CREATED`; → `ProductView` |
| `updateProduct(String sku, UpdateProductCommand)` | rename + category + price + barcode + UoM; save; publish `UPDATED`, and **`PRICE_CHANGED` (with old/new price) if the price changed**; → `ProductView` |
| `deactivate(String sku)` | `deactivate()`; publish `DEACTIVATED` |
| `reactivate(String sku)` | `activate()`; publish `REACTIVATED` |
| `listCategories()` | → `List<CategoryView>` for the form dropdown (read) |

`listProducts`/`getProduct` are **not** added — the admin screen lists via the
existing read-only `GET /products` (which already returns every field, including
`active`) and edits from the row already in hand, filtering inactive client-side.

**Validation (`DomainException.validation`):** `name` required (non-blank);
`unitPrice` required and `>= 0` on create; `sku` required and non-blank on create.
`barcode`, `unitOfMeasure` optional. `trimToNull` blank optional fields.

**Category resolution (inline create):** if `categoryCode` is provided, look it up
(`findByCode`) — `notFound`/`validation` if it does not exist. If `categoryCode`
is blank but `categoryName` is provided, derive a code = uppercased alphanumeric
of the name (e.g. `"Hot Drinks"` → `HOTDRINKS`); reuse an existing category with
that code, else create `new Category(newId, code, name)`. If both are blank, the
product has no category (`categoryId`/`categoryName` null — permitted). Publish a
`ProductChanged(CATEGORY_CREATED)` event (entityRef = the category code) when a
category is created.

**Defaults:** `currencyCode` defaults to the store currency setting
`ConfigurationService.getString(SettingKey.CURRENCY_CODE)` (default `"SAR"`) on
create; on update a blank currency keeps the product's existing currency.
`unitOfMeasure` defaults to `"EA"` on create; a blank keeps existing on update.
`erpVersion` stays `0` for admin-created products (untouched by admin edits).

### `product.api` DTOs (new, public named interface)

```java
public record CreateProductCommand(String sku, String name,
        String categoryCode, String categoryName,
        BigDecimal unitPrice, String currencyCode,
        String unitOfMeasure, String barcode) {}

public record UpdateProductCommand(String name,
        String categoryCode, String categoryName,
        BigDecimal unitPrice, String currencyCode,
        String unitOfMeasure, String barcode) {}

public record CategoryView(String code, String name) {}

public enum ProductChangeType { CREATED, UPDATED, PRICE_CHANGED, DEACTIVATED,
        REACTIVATED, CATEGORY_CREATED }

public record ProductChanged(String entityRef, ProductChangeType type, String actor,
        BigDecimal oldPrice, BigDecimal newPrice) implements DomainEvent {}
```

`ProductView` (existing) is the read model everywhere; it never exposes `id`.
`ProductChanged` is a published event (part of the `product :: api` named
interface); `oldPrice`/`newPrice` are non-null only for `PRICE_CHANGED`.

### `ProductAdminController` (new, `product/web`) — every method `@PreAuthorize("hasRole('ADMIN')")`

| Method + path | Body | Result |
|---|---|---|
| `POST /products` | `CreateProductCommand` | `201` + `ProductView` |
| `PUT /products/{sku}` | `UpdateProductCommand` | `200` + `ProductView` |
| `POST /products/{sku}/deactivate` | — | `204` |
| `POST /products/{sku}/reactivate` | — | `204` |
| `GET /categories` | — | `List<CategoryView>` |

**No route clash:** the existing read-only `ProductController` (`GET /products`,
`GET /products/{sku}`) is **left untouched** — Spring maps by method **and** path,
so `POST /products` and `PUT /products/{sku}` do not collide with the existing
`GET` mappings. The admin screen **lists via the existing `GET /products`** (which
already returns all products with every field, `active` included) and filters
inactive client-side; no new list endpoint and no behavior change. `GET
/categories` is new and unowned. Lifecycle endpoints key on the immutable `sku`.

### Audit

Add `AuditAction` enum values `PRODUCT_CREATED`, `PRODUCT_UPDATED`,
`PRODUCT_DEACTIVATED`, `PRODUCT_REACTIVATED`, `CATEGORY_CREATED` (`PRICE_CHANGED`
already exists). Enum-only — **no schema change**. Add a new
`ProductChangedAuditListener` in `audit/application` (`@ApplicationModuleListener
void on(ProductChanged)`) that maps the event's `type` → `AuditAction` and calls
`DefaultAuditService.append(action, event.actor(), event.entityRef(), details)` —
mirroring the existing `ProductPriceChangedAuditListener`. `audit` already allows
`product :: api`, so **no `audit` dependency change**. `product`'s
`allowedDependencies` gains only `configuration :: api`.

### Persistence / migration

**No Flyway migration.** The `product` and `category` tables and every column
needed already exist. (For the record, the next free global version would be
**V35** — not used here.)

## Terminal UI design (`pos-terminal/`)

Extends the shared Admin area from sub-project #1.

### Admin area entry

- Add a **"Products"** tile to the Admin screen, ADMIN-gated (same visibility
  rule as the Staff tile). Navigator gains `toProducts()`. The Products screen
  owns no timers/sockets, so no `Navigator.Screen` lifecycle work is required.

### API + view-model

- `ProductAdminApi` thin client (distinct from the existing read-only
  `ProductApi`): `list()` (GET `/products`), `listCategories()`, `create`,
  `update(sku,…)`, `deactivate(sku)`, `reactivate(sku)`. Void endpoints pass a
  null `TypeReference` (the `UsersApi` pattern). Deserializes into a dedicated
  `ProductAdminView` record (the existing `dto.ProductView` drops `active`/UoM/
  currency, which the admin screen needs).
- `ProductAdminViewModel` — **synchronous methods returning plain values**; the
  controller runs them off-thread via `FxTasks.run(work, onDone, onError)` and
  reads results in `onDone` via a `holder`; the only off-thread observable write
  is `errorMessage` inside `ui.accept(...)`. Any inter-thread time source is an
  injected `Supplier<Instant> clock` (none expected here).

### Screens / dialogs

- **Products list screen** — table (SKU, name, category, price, active/inactive
  badge), an *include-inactive* toggle, and actions: New, Edit,
  Deactivate/Reactivate.
- **Create/Edit Product dialog** — **I/O-free** (collects input only; the
  controller does all HTTP): SKU (create-only, disabled on edit), name, category
  as a combo of existing categories plus an "Add new…" option that reveals a
  name field, price, currency (defaulted), unit of measure (defaulted "EA"),
  barcode. Validates required fields (name, non-negative price, SKU on create)
  before enabling Save, via static `isValidCreate` + `parsePrice`/`isValidPrice`
  helpers (the `UserFormDialog` pattern). On edit the SKU is not shown (immutable).

## Testing

**Backend**
- `ProductAdminServiceTest` (`@SpringBootTest`, `@Transactional`, with an
  `@EventListener` capture bean for `ProductChanged` — the `ProductPriceChangedTest`
  pattern): create (happy path, publishes `CREATED`; SKU conflict), currency/UoM
  defaults, update (rename + category + fields + price; publishes `UPDATED` +
  `PRICE_CHANGED`), update with unchanged price publishes `UPDATED` but **not**
  `PRICE_CHANGED`, deactivate/reactivate, inline-category **reuse** (same derived
  code → no duplicate), pick **existing** category by code (no new category),
  blank-name rejected, negative-price rejected.
- `ProductAdminControllerTest` (`@SpringBootTest @AutoConfigureMockMvc`, `jwt()`
  post-processors): ADMIN gate returns 403 for CASHIER and MANAGER, 201/200 for
  ADMIN; `GET /categories` 403 for CASHIER; create round-trips through
  `ProductView`.
- `ModularityTests` — the new `configuration :: api` dependency on `product` is
  declared and the boundary stays green (no `product → audit` cycle).

**Terminal**
- `ProductAdminViewModelTest` including the **async-dispatcher regression test**
  (a deferred, undrained `ui` dispatcher) that a synchronous-only test would mask.
- `ProductFormDialogTest`: input validation (no HTTP), in the
  `UserFormDialogTest` style — `isValidCreate` true only with SKU + name + valid
  price; blank SKU/name, negative price, and non-numeric price rejected;
  `parsePrice` parses/blank-nulls.

## Non-goals

Variants/modifiers (roadmap #3, `menu` module), bulk/CSV import, image upload,
per-terminal or time-based pricing, tax-class field (Product has none today),
category rename/delete (inline create only), retail barcode-uniqueness
enforcement.

## Definition of done

- Backend endpoints live and ADMIN-gated for writes; full lifecycle works; inline
  category create/reuse works; every action publishes `ProductChanged` and the new
  `audit` listener records it (create/update/price/deactivate/reactivate/category);
  `./mvnw verify` (incl. `ModularityTests`) green.
- Terminal Admin area gains a functional Products screen against a running
  backend; `./mvnw -f pos-terminal/pom.xml clean test` green.
- No Flyway migration added; no new module; **one** `allowedDependencies` line
  added to `product` (`configuration :: api`); no `product → audit` cycle.
