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
- **Audit + price event:** product create/edit/lifecycle changes are audited, and
  an admin price edit publishes the existing `ProductPriceChanged` event (parity
  with the ERP path). This adds `audit :: api` to the `product` module's
  `allowedDependencies` (a `ModularityTests`-checked one-line change).

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

Separate from `DefaultProductCatalog` (keep read concerns isolated). Mirrors
`UserAdminService`'s **no class-level `@Transactional`** stance to avoid the
audit deadlock (SQLite single connection + audit write): perform the repository
write, then `audit.record(...)`. Uniqueness is pre-checked for a friendly error
and enforced for real by the DB unique constraint on `sku`.

Methods (each records an audit event):

| Method | Behavior |
|---|---|
| `createProduct(CreateProductCommand)` | conflict if SKU taken; resolve category (see below); default currency/UoM; validate; save; → `ProductView` |
| `listProducts(boolean includeInactive)` | all products, or only active; → `List<ProductView>` |
| `getProduct(String sku)` | → `ProductView`, else `notFound` |
| `updateProduct(String sku, UpdateProductCommand)` | rename + category + price + barcode + UoM; **publishes `ProductPriceChanged` if price changed** |
| `deactivate(String sku)` | `deactivate()` |
| `reactivate(String sku)` | `activate()` |
| `listCategories()` | → `List<CategoryView>` for the form dropdown |

**Validation (`DomainException.validation`):** `name` required (non-blank);
`unitPrice` required and `>= 0` on create; `sku` required and non-blank on create.
`barcode`, `unitOfMeasure` optional. `trimToNull` blank optional fields.

**Category resolution (inline create):** if `categoryCode` is provided, look it up
(`findByCode`) — `notFound`/`validation` if it does not exist. If `categoryCode`
is blank but `categoryName` is provided, derive a code = uppercased alphanumeric
of the name (e.g. `"Hot Drinks"` → `HOTDRINKS`); reuse an existing category with
that code, else create `new Category(newId, code, name)`. If both are blank, the
product has no category (`categoryId`/`categoryName` null — permitted). Record a
`CATEGORY_CREATED` audit event when a category is created.

**Defaults:** `currencyCode` defaults to the store currency setting from
`configuration :: api` if such a key exists, else `"SAR"` (the seeder's value);
`unitOfMeasure` defaults to `"EA"`. `erpVersion` stays `0` for admin-created
products (untouched thereafter by admin edits).

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
```

`ProductView` (existing) is the read model everywhere; it never exposes `id`.

### `ProductAdminController` (new, `product/web`) — every method `@PreAuthorize("hasRole('ADMIN')")`

| Method + path | Body | Result |
|---|---|---|
| `POST /products` | `CreateProductCommand` | `201` + `ProductView` |
| `GET /products?includeInactive=false` | — | `List<ProductView>` |
| `GET /products/{sku}` | — | `ProductView` |
| `PUT /products/{sku}` | `UpdateProductCommand` | `ProductView` |
| `POST /products/{sku}/deactivate` | — | `204` |
| `POST /products/{sku}/reactivate` | — | `204` |
| `GET /categories` | — | `List<CategoryView>` |

**Route-clash resolution:** the existing read-only `ProductController` already
owns `GET /products` and `GET /products/{sku}`. To avoid two mappings on the same
path, the plan **folds the existing reads into the new controller** (moving the
two GET handlers, with the admin variants gaining `includeInactive`) and deletes
`ProductController`, OR keeps `ProductController` for the two public reads and
gives the admin controller a distinct base path. **Decision for the plan: fold
the reads into `ProductAdminController`** so there is one products controller;
the plain `GET /products` / `GET /products/{sku}` stay **unauthenticated-by-role**
(any authenticated user — the terminal's catalogue browse uses them) while the
write and `includeInactive` paths are ADMIN-gated at the method level. Confirm no
other caller depends on `ProductController` being a separate bean.

### Audit

Add `AuditAction` enum values `PRODUCT_CREATED`, `PRODUCT_UPDATED`,
`PRODUCT_DEACTIVATED`, `PRODUCT_REACTIVATED`, `CATEGORY_CREATED`. Enum-only —
**no schema change**. Add `audit :: api` to `product`'s `allowedDependencies`.

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

- `ProductsApi` thin client: `list(includeInactive)`, `get(sku)`, `create`,
  `update`, `deactivate`, `reactivate`, `listCategories`. Void endpoints pass a
  null `TypeReference` (the `UsersApi` pattern).
- `ProductCatalogViewModel` — **synchronous methods returning plain values**; the
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
  before enabling Save, via a static `isValidCreate`/`isValidEdit` helper (the
  `UserFormDialog` pattern).

## Testing

**Backend**
- `ProductAdminServiceTest`: create (happy path, SKU conflict), update
  (rename + category + fields), **price change publishes `ProductPriceChanged`**,
  update with unchanged price does **not** publish, deactivate/reactivate,
  inline-category create, inline-category **reuse** (same derived code → no
  duplicate), blank-name rejected, negative-price rejected.
- `ProductAdminControllerTest` (`@SpringBootTest`): ADMIN gate returns 403 for
  CASHIER and MANAGER, 201/200 for ADMIN; create round-trips through
  `ProductView`.
- `ModularityTests` — the new `audit :: api` dependency is declared and the
  boundary stays green.

**Terminal**
- `ProductCatalogViewModelTest` including the **async-dispatcher regression test**
  (a deferred, undrained `ui` dispatcher) that a synchronous-only test would mask.
- `ProductFormDialogTest`: input validation (no HTTP), in the
  `UserFormDialogTest` style — Save disabled until required fields valid,
  negative price rejected, SKU disabled on edit.

## Non-goals

Variants/modifiers (roadmap #3, `menu` module), bulk/CSV import, image upload,
per-terminal or time-based pricing, tax-class field (Product has none today),
category rename/delete (inline create only), retail barcode-uniqueness
enforcement.

## Definition of done

- Backend endpoints live and ADMIN-gated for writes; full lifecycle works; inline
  category create/reuse works; price edit publishes `ProductPriceChanged`;
  create/edit/lifecycle audited; `./mvnw verify` (incl. `ModularityTests`) green.
- Terminal Admin area gains a functional Products screen against a running
  backend; `./mvnw -f pos-terminal/pom.xml clean test` green.
- No Flyway migration added; no new module; one `allowedDependencies` line added
  to `product` (`audit :: api`).
