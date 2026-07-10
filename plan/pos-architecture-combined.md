# POS Application — Combined Architecture Plan

**Stack:** Java 21 + Spring Boot 3.x · **Terminal:** Windows desktop (JavaFX — planned, ⚠️ not yet built) · **Topology:** Single store · **Mode:** Offline-first with ERP sync

> **Status (reconciled 2026-07-10):** This plan has been revised **in place** to match what has actually been built. The retail MVP (branches for Phases 0–9) **and** a restaurant dine-in track (Phases 10–13b) are implemented and tested; the headless backend is complete but no UI exists yet, and several production-hardening items remain. See §14 for the phase-by-phase status and §15 for the state of the original open questions. Status markers used below: ✅ built & tested · 🍽️ restaurant-track extension (beyond original scope) · ⏳ deferred (not built) · ⚠️ planned but unbuilt.

> **Scope:** Single-**store** POS that now serves **two service formats in one deployment** — retail quick-service checkout *and* restaurant dine-in (tables, open orders, menu modifiers/variants, kitchen station routing, split billing, taxable service charge). It remains a single-store, single-deployment product: the multi-industry **platform** vision (feature-flag / licensing / config-driven UI SPI) is still intentionally **out of scope**. Notably, the restaurant capability was added **directly on the retail module seams** (reusing `cart`/`sales`/`pricing`/`tax`/`receipt`), *not* through an industry-plugin layer — so the code covers two formats without becoming the licensable platform. This plan consolidates the engineering architecture with the module/feature inventory from `pos-requirement.md`.

---

## 1. Key decisions at a glance

| Decision | Choice | Why |
|---|---|---|
| Architecture style | Modular monolith with enforced boundaries (Spring Modulith) | One deployable, clean module seams, microservice-extractable later |
| Deployment | **Store-server** topology; same artifact runs **embedded** for single-register stores | Single store needs simplicity but LAN concurrency for >1 register |
| Local DB | PostgreSQL on store server; SQLite only in embedded mode | SQLite struggles with concurrent terminal writes |
| Offline / sync | Transactional **outbox** (Spring Modulith event registry) + idempotent ERP adapter | Reliable, ordered, replayable sync; no lost or double-posted sales |
| Inter-module comms | Direct calls via module facades **+ in-process domain events** | Decoupling, easy future extraction |
| Devices | **JavaPOS (UnifiedPOS)** behind port interfaces | Vendor-neutral hardware integration |
| Payments | **Semi-integrated** card terminal (P2PE) | Keeps card data (PAN) out of the app → minimal PCI-DSS scope |
| Money | `BigDecimal` / JavaMoney (Moneta) — never `double` | Correctness of financial math |

The two items most worth confirming early are the **payment integration model** (PCI scope) and the **store-server vs embedded** packaging — see §15.

---

## 2. Architecture principles

1. **Modular monolith, not distributed.** A single store does not justify the operational cost of microservices. We get the maintainability benefits of clear modules without network failure modes between them.
2. **Boundaries are enforced, not just documented.** Each module exposes a small public API; internals are package-private. Spring Modulith fails the build if a module reaches into another's internals.
3. **Offline is the default, not the exception.** The store is the source of truth for transactions; the ERP/cloud link is treated as intermittent. Nothing in the sell path may block on connectivity.
4. **Events for facts, calls for queries.** State changes are published as domain events; read/lookup needs are direct synchronous calls.
5. **Money and inventory are sacred.** Immutable transaction records, append-only ledgers, idempotent writes, no floating-point money.

---

## 3. Deployment topology

A single store, but a store can have one register or several. We support both with **one build, two run modes**.

### Mode A — Store-server (≥ 2 registers, recommended default)
```
        ┌─────────────────────── In-store LAN ───────────────────────┐
        │                                                             │
  ┌───────────┐   ┌───────────┐   ┌───────────┐                      │
  │ Terminal 1│   │ Terminal 2│   │ Terminal 3│   (JavaFX desktop)    │
  │  (thin UI)│   │  (thin UI)│   │  (thin UI)│                       │
  └─────┬─────┘   └─────┬─────┘   └─────┬─────┘                       │
        └───────────────┼───────────────┘  REST/WebSocket over TLS    │
                        │                                             │
              ┌─────────▼──────────┐                                  │
              │   STORE SERVER     │  Spring Boot modular monolith    │
              │  (mini-PC / reg-0) │  + PostgreSQL + Sync engine      │
              └─────────┬──────────┘                                  │
        └───────────────┼─────────────────────────────────────────────┘
                        │ Internet (intermittent)
                        ▼
              ┌────────────────────┐
              │   ERP / Cloud      │
              └────────────────────┘
```
The store server is the local source of truth and the only thing that talks to the ERP. Terminals are UI clients on the LAN. LAN is reliable; "offline" means the **server↔ERP** link is down, during which the store keeps trading and queues work.

### Mode B — Embedded (single register)
The JavaFX app embeds the monolith in-process with a SQLite database — no separate server box. Same modules, same code; only the persistence profile and transport (in-VM vs REST) differ. This maps directly to the requirement docs' "SQLite + Sync Queue."

> **Resilience note:** In Mode A, the store server is a single point of failure for the trading floor. Mitigate with a UPS and a documented "promote a terminal to embedded mode" fallback rather than building terminal-level peer replication for an MVP.

---

## 4. Logical architecture (per module)

Every module follows a light hexagonal (ports-and-adapters) layout so business logic never depends on framework or I/O:

```
module/
├── api/            ← public: facade interface(s) + DTOs + published events  (the ONLY thing other modules see)
├── domain/         ← entities, value objects, domain services  (package-private)
├── application/    ← use-case services, @Transactional boundaries, event handlers
└── infrastructure/ ← JPA repositories, adapters, mappers  (package-private)
```

Rule: a module may depend only on another module's `api` package. Enforced by Spring Modulith's `verifies()` test.

---

## 5. Module map, dependency rules & feature inventory

Package layout under `com.company.pos`, grouped by tier. Arrows = allowed dependency direction (downward only).

```
TIER 1 — Edge / UI orchestration
  sales ✅ · dining 🍽️ · dashboard ✅ · reporting ✅
        │
TIER 2 — Domain capabilities
  cart ✅ · pricing ✅ · tax ✅ · payment ✅ · receipt ✅ · menu 🍽️ · kitchen 🍽️
  inventory ✅ · product ✅ · customer ✅ · cashdrawer ✅ · shift ✅
  promotion ⏳ · loyalty ⏳ · supplier ⏳ · purchasing ⏳ · employee ⏳ · barcode ⏳
        │
TIER 3 — Platform services
  auth ✅ · sync ✅ · integration(erp ✅ · payment ⚠️ · fiscal ⏳) · notification ✅ · device ✅ · configuration ✅ · audit ✅
        │
TIER 0 — Shared kernel (no business logic)
  common ✅ (config·exception·security·util·events) · database ✅
```

Legend: ✅ built & tested · 🍽️ restaurant-track extension (Phases 10–13b) · ⏳ deferred, not built · ⚠️ port defined but only an in-memory fake adapter exists.

Guidelines:
- **`sales` is the orchestrator** of the checkout use case — it composes `cart`, `pricing`, `promotion`, `tax`, `payment`, `receipt`. Those capability modules don't call back up.
- **`audit`, `notification`, `sync` are passive subscribers** — they react to events and never block the publisher.
- **`common` and `database` may be used by anyone; they depend on no one.**
- No module imports another module's JPA entities or repositories — only its `api` DTOs.

### Complete feature inventory (every feature from the requirement docs has a home here)

| Module | Tier | Responsibility | Features |
|---|---|---|---|
| `auth` | 3 | User access & security | Login/logout · RBAC · cashier PIN auth · shift login · password reset · session/JWT issuance |
| `sales` | 1 | Checkout orchestration | New sale · hold/resume sale · cancel sale · void item · returns · exchanges · apply discounts · apply promotions · split/mixed payment · receipt generation trigger |
| `cart` | 2 | In-progress basket state | Add/remove/modify line · quantity & UoM · line-level notes · running subtotal |
| `product` | 2 | Product catalog (ERP-mastered) | Catalog · categories · brands · variants · SKU mgmt · barcode mapping · unit of measure · price lists · product images |
| `inventory` | 2 | Stock control | Stock levels · warehouse/location · stock transfer · stock adjustment · stock count · goods receipt · reorder levels · movement ledger |
| `customer` | 2 | Customer master | Registration · search · groups · purchase history · credit customers · loyalty linkage |
| `payment` | 2 | Tender handling | Cash ✅ · card ✅ · mobile wallet ✅ · mixed/split payment ✅ · refunds ✅ — QR ⏳ · gift card ⏳ · store credit ⏳ (card/wallet via **fake** terminal — see `integration/payment`) |
| `receipt` | 2 | Receipt production | Thermal print ✅ · reprint ✅ · itemized discounts/modifiers/service-charge ✅ — templates ⏳ · email ⏳ · SMS ⏳ · PDF ⏳ |
| `pricing` | 2 | Price resolution | Discounts ✅ · coupons ⏳ · customer-specific pricing ⏳ · happy-hour pricing ⏳ · buy-X-get-Y ⏳ (only manual line/transaction discounts built) |
| `promotion` ⏳ | 2 | Promotion engine | BOGO · bundle promotions · time-based discounts · category discounts · customer discounts — **deferred, not built** |
| `tax` | 2 | Tax calculation | Single-rate VAT ✅ · inclusive/exclusive ✅ — multiple rates ⏳ · tax exemptions ⏳ (runs KSA 15% flat today) |
| `loyalty` ⏳ | 2 | Rewards | Points accrual/redemption · rewards · membership levels · loyalty coupons — **deferred** (only a `loyaltyCode` string is stored on the customer; no engine) |
| `cashdrawer` | 2 | Till operations | Open/close drawer · cash in · cash out · cash count · cash reconciliation |
| `shift` | 2 | Shift lifecycle | Shift open/close · shift summary — break management ⏳ · cash handover ✅ |
| `employee` ⏳ | 2 | Staff records | Cashier records · attendance · role assignment — **deferred** (auth provides generic RBAC only) |
| `supplier` ⏳ | 2 | Supplier master | Supplier mgmt · supplier payments · goods receiving link — **deferred, not built** |
| `purchasing` ⏳ | 2 | Procurement | Purchase orders · receive inventory · purchase returns · vendor invoices — **deferred, not built** |
| `barcode` ⏳ | 2 | Barcode ops | Barcode generation · barcode printing · barcode lookup — **deferred** (SKU-based only) |
| `reporting` | 1 | Business reports | Sales · product performance · hourly sales · cashier · inventory · profit · tax · payment reports |
| `dashboard` | 1 | Operational insight | Today's sales · best sellers · low-stock items · active cashiers · open shifts · revenue summary |
| `dining` 🍽️ | 1 | Restaurant floor orchestration | Table registry · shared store-wide open dine-in orders (tickets) · course tags & prep notes · close via existing checkout · single / by-item / even split billing · manager-waivable **taxable** service charge |
| `menu` 🍽️ | 2 | Menu modifiers & variants | Forced/optional modifier groups with priced options · size variants · SKU→group assignment · effective-price folding into the unchanged pricing→tax pipeline |
| `kitchen` 🍽️ | 2 | Kitchen station routing | Incremental fire · per-line fired/locked state · SKU→station overlay · station-grouped ticket printing (`KitchenPrinter` port) · async resilient routing |
| `sync` | 3 | Offline sync engine | Transactional outbox · queue mgmt · retry/backoff · conflict resolution · delta sync · background + manual sync |
| `integration/erp` | 3 | ERP adapter | Product/price/inventory down-sync ✅ · sales/movement upload ✅ · idempotent delivery ✅ — **but only `FakeErpClient`; no real vendor adapter** ⚠️. Customer sync ⏳, purchase upload ⏳ |
| `integration/payment` | 3 | Payment terminal adapter | Semi-integrated terminal SDK · tokenized auth/capture/void/refund — ⚠️ **port only; `InMemoryPaymentTerminal` fake, no real SDK** |
| `integration/fiscal` | 3 | Fiscal/e-invoice adapter | ⏳ Reserved, **not built**. **KSA deployment ⇒ ZATCA/Fatoora e-invoicing is a legal requirement** (see §15.5) |
| `notification` | 3 | Alerts & messaging | SMS · email · push · low-stock alerts · sync-error alerts |
| `device` | 3 | Hardware ports | Barcode scanner · thermal printer · cash drawer · card reader · customer display · weighing scale |
| `configuration` | 3 | Typed settings store | Store · tax · printer · payment · POS settings · user preferences · localization · currency |
| `audit` | 3 | Tamper-evident trail | Login history · price changes · inventory changes · refunds · voids · configuration changes |
| `common` | 0 | Shared kernel | Config · exception handling · security primitives · util · domain-event infrastructure |
| `database` | 0 | Persistence kernel | DataSource/profiles · migration wiring · transaction support |

Recommended package structure:
```
com.company.pos
├── auth · sales · cart · payment · receipt · inventory · product
├── pricing · promotion · tax · customer · supplier · purchasing
├── loyalty · employee · shift · cashdrawer · barcode
├── reporting · dashboard · sync
├── integration
│   ├── erp
│   ├── payment
│   └── fiscal
├── notification · device · configuration · audit
├── common
│   ├── config · exception · security · util · events
└── database
```

---

## 6. Inter-module communication

**Synchronous (queries / commands within one user action):** call the target module's facade interface, e.g. `ProductCatalog.findBySku(sku)`. Used when the caller needs an immediate result inside the same transaction (price lookup, stock check).

**Asynchronous (state-change notifications):** publish a domain event; interested modules subscribe. Spring's `ApplicationEventPublisher` + Modulith's persistent event registry.

Example — completing a sale fans out cleanly:
```
SaleCompleted(saleId, lines, payments, customerId, storeSeqNo)
   ├─► inventory      : decrement on-hand, write stock movements
   ├─► loyalty        : award points
   ├─► cashdrawer     : record cash movement (if cash payment)
   ├─► audit          : append transaction record
   ├─► receipt        : (already produced synchronously in checkout)
   └─► sync           : enqueue sale for ERP upload (outbox)
```
This keeps `sales` ignorant of who cares about a completed sale, and lets you later move `loyalty` or `inventory` out of process with no change to the publisher.

---

## 7. Data architecture

- **Engine:** PostgreSQL (store server) / SQLite via SQLCipher (embedded). Abstracted by Spring profiles; repositories use Spring Data JPA.
- **Schema ownership:** one logical schema per module (`sales`, `inventory`, …). Cross-module reads go through facades, not foreign keys across module schemas — this preserves the seams that make extraction possible.
- **Migrations:** Flyway, with per-module migration paths so modules own their DDL.
- **Money:** `BigDecimal` columns with explicit scale; JavaMoney (`Money`/`MonetaryAmount`) in the domain. Store currency code alongside every amount.
- **Identity:** client-generated UUIDs for all transactional aggregates (sales, payments, stock movements) — see §8 idempotency.
- **Receipt/sequence numbers:** locally assignable, collision-free across terminals via a `{storeId}-{terminalId}-{seq}` scheme, so the store can issue receipts while offline.
- **Read models for reporting:** keep heavy report queries off the transactional path; either a denormalized reporting schema updated from events, or read replicas / jOOQ projections. JasperReports for printable output.
- **File storage:** product images, receipt PDFs, and report exports stored via a `common` file-storage abstraction (local filesystem on the store server; pluggable to object storage later), with paths/metadata in the DB rather than blobs.

---

## 8. Offline-first & ERP sync (the hard part)

### Data flow directions
| Data | Master | Direction | Strategy |
|---|---|---|---|
| Products, categories, brands, barcodes | **ERP** | down | versioned overwrite (ERP authoritative) |
| Prices, promotions, tax rules | **ERP** | down | versioned, effective-dated |
| Customers | shared | both | field-level merge; ERP authoritative on shared fields |
| Inventory | shared | both | **movement-based** (send deltas, not absolute counts) |
| Sales, payments, refunds | **Store** | up | append-only, idempotent, immutable |
| Purchase orders, goods receipts | shared | both | document-state machine, idempotent up |

### Mechanism — transactional outbox
1. A business operation commits its data **and** an outbox row in the **same DB transaction** (via Modulith's Event Publication Registry). No "saved the sale but lost the sync record" gap.
2. A background **sync worker** drains the outbox in order, calls the ERP adapter, and marks rows acknowledged on success.
3. Failures are retried with exponential backoff; the row stays until acknowledged → at-least-once delivery.

### Idempotency
Every outbound transaction carries its client-generated UUID. The ERP adapter (or an idempotency table on the ERP side) treats a repeat UUID as a no-op. This is what makes "retry failed sync" safe — you can resend freely without double-posting a sale.

### Conflict resolution by type (avoid blanket last-write-wins)
- **Master data (down):** ERP wins; local copy is a versioned cache. Compare by ERP version/`updatedAt`.
- **Inventory:** never sync absolute stock counts (they'll fight). Sync **movements** (sold −2, received +10, adjusted −1); ERP and store each apply movements to their own baseline and reconcile. A periodic stock-count reconciliation corrects drift.
- **Transactions (up):** immutable facts, so no conflict — only idempotent delivery.
- **Customers:** ERP authoritative on shared identity fields; loyalty/local fields owned by store.

### Delta sync
Per-stream high-watermark cursors (`lastSyncedVersion` / `lastSyncedAt`) so each pull/push transfers only changes since the last success. Background + manual-trigger sync, with low-stock and **sync-error alerts** routed through `notification`.

---

## 9. Device / hardware integration (Windows)

Abstract all peripherals behind `device` module **port interfaces**, with **JavaPOS (UnifiedPOS)** adapters — the standard most POS hardware vendors ship service objects for.

```
device.api:   Printer · Scanner · CashDrawer · LineDisplay · Scale · PaymentTerminal
device.infra: JavaPosPrinterAdapter, JavaPosCashDrawerAdapter, ...  (ESC/POS fallback for thermal)
```
- **Barcode scanner:** usually HID keyboard-wedge (no driver) or JavaPOS for programmatic control.
- **Thermal printer:** JavaPOS or raw ESC/POS over serial/USB (jSerialComm).
- **Cash drawer:** kicked open via the printer's drawer port.
- **Customer display & scale:** JavaPOS line display / scale devices.
- **Card reader:** integrated via the **payment terminal SDK** (see §10), not as a generic device.

Keeping these behind ports means the rest of the app is hardware-agnostic and testable with fakes.

---

## 10. Payments & PCI-DSS

**Strong recommendation: semi-integrated payments.** The POS sends an *amount* to a certified payment terminal (PIN pad); the terminal handles card read, EMV, PIN, and talks to the acquirer directly, returning only an approval + masked PAN + token. The POS application **never sees the card number.**

Why it matters: this keeps card data out of your application and database, dropping you to the smallest PCI-DSS scope (largely SAQ-C/P2PE rather than full PCI-DSS). Designing the `payment` + `integration/payment` modules around a tokenized, terminal-mediated flow from day one avoids a painful retrofit. Cash, gift card, store credit, QR/mobile-wallet, and mixed/split payments are handled in-app; only the card rail goes through the terminal.

---

## 11. Security

- **Transport:** TLS for terminal↔server even on LAN.
- **AuthN:** user login + cashier PIN issue a short-lived JWT/session scoped to the shift; `auth` module owns this.
- **AuthZ:** RBAC enforced in the application-service layer (method security), not the UI. UI hides what it can; the server denies what it must.
- **Audit:** `audit` subscribes to domain events (price changes, voids, refunds, logins, inventory changes) — tamper-evident, append-only.
- **At rest:** encrypt the local DB (PostgreSQL TDE / SQLCipher for SQLite); customer PII and any tokens encrypted.
- **Secrets:** externalized config + OS credential store; never in source.

---

## 12. Cross-cutting concerns

| Concern | Approach |
|---|---|
| Transactions | `@Transactional` at application-service boundary; one aggregate per tx |
| Logging | SLF4J + structured logs; correlation id per transaction |
| Exceptions | Central handler → RFC-7807 problem details to terminals |
| Localization | Spring `MessageSource`; locale per store/terminal config |
| Currency | JavaMoney; store currency in `configuration` |
| Scheduling | Spring `@Scheduled` for sync drain, backups, reorder checks |
| File storage | `common` file-storage abstraction (images, PDFs, exports) |
| Backup/restore | Scheduled DB dump + documented restore runbook (store server) |
| Config | `configuration` module as typed settings store (POS/store/tax/printer/payment) |

---

## 13. Recommended tech stack

| Layer | Choice | Notes |
|---|---|---|
| Language | Java 21 (LTS) | virtual threads help the sync worker |
| Core | Spring Boot 3.x + **Spring Modulith** | boundary enforcement, event registry/outbox, module docs |
| Desktop UI | **JavaFX** | one-language stack; alt: Compose for Desktop |
| Persistence | Spring Data JPA (+ jOOQ for reports) | |
| DB | PostgreSQL / SQLite(+SQLCipher) | by run mode |
| Migrations | Flyway | per-module paths |
| Events/outbox | Spring Modulith Event Publication Registry | reliable, replayable |
| Devices | JavaPOS / jSerialComm | vendor service objects |
| Money | JavaMoney (Moneta) | |
| Reporting/receipts | JasperReports | printable templates |
| Testing | JUnit 5, Spring Modulith tests, Testcontainers | verify boundaries + sync |
| Build | Gradle (or Maven) | multi-module |

*(Pin exact framework versions at project setup; the choices above are stable, the version numbers move.)*

---

## 14. Build phasing / roadmap — actual status (reconciled 2026-07-10)

The original plan sketched five phases (0–4 MVP, then 5+ incremental). Delivery subdivided those into finer branches, then added a **restaurant track** in place of the planned "Phase 5+" retail-incremental work. Status legend: ✅ built & tested · 🍽️ restaurant extension · ⏳ deferred · ⚠️ planned, not built.

> **Integration note:** all work below lives on cumulative `phase-*` branches; `main` currently holds only the plan/requirement docs. **Nothing has been merged to trunk yet.**

### Retail MVP — ✅ delivered (original Phases 0–4, built as branches 0–9)

- **Phase 0 — Foundation** ✅ `common` · `database` · `configuration` · `device` ports · Modulith boundary tests · dual run-mode packaging (PostgreSQL store-server / embedded SQLite).
- **Phase 1 — Identity & master data** ✅ `auth` (JWT login, RBAC, PIN) · `product` + `inventory` baseline · `integration/erp` **down-sync** (against the fake ERP adapter).
- **Phase 2 (a/b/c) — Checkout core** ✅ `cart` → `pricing` + `tax` → `payment` (cash + card/wallet via **fake** terminal) → `receipt` (thermal) → `sales` → `cashdrawer`/`shift`. Hold/resume, void, mixed/split tender. *QR tender not built (§5).*
- **Phase 3 (a/b/c) — Sync up & resilience** ✅ `sync` transactional outbox → idempotent ERP **upload** of sales/movements → `notification` (sync-error, low-stock alerts).
- **Phase 4 — Returns & refunds** ✅ receipted returns, proportional refund, mirror-tender refund (its own branch, beyond the original Phase-2 sketch). *Exchanges & blind returns deferred.*
- **Phase 5 — Manual discounts** ✅ line & transaction discounts, reason codes, role-based caps.
- **Phase 6 — Audit trail** ✅ append-only, hash-chained audit log + `verify` endpoint.
- **Phase 7 — Customer** ✅ registration/search/history/cart-attach. *No customer groups or credit accounts (§5).*
- **Phase 8 — Reporting** ✅ sales / payments / tax / cashier / product reports (JSON + CSV). *No profit/COGS or hourly breakdown.*
- **Phase 9 — Dashboard** ✅ today's sales, revenue, best sellers, low stock, open shifts.

### Restaurant dine-in track — 🍽️ delivered (Phases 10–13b, beyond original scope)

Added directly on the retail seams (reusing `cart`/`sales`/`pricing`/`tax`), *not* via an industry-plugin layer:

- **Phase 10 — Dining floor** 🍽️ `dining`: table registry, shared store-wide open orders, close via existing checkout.
- **Phase 11 — Menu modifiers & variants** 🍽️ `menu`: forced/optional modifier groups, size variants, effective-price folding; line-id-keyed cart lines.
- **Phase 12 — Kitchen station routing** 🍽️ `kitchen`: incremental fire, fired-line locking, SKU→station overlay, per-station ticket printing.
- **Phase 13 — Split billing** 🍽️ by-item (N itemized sales) and even (one sale, N payments) split, atomic; adds read-only `sales.quote(cartId)`.
- **Phase 13b — Service charge** 🍽️ config-driven, taxable, DINE_IN-only, manager-waivable auto-gratuity (current branch).

### Deferred retail-incremental modules — ⏳ not built

The original "Phase 5+" retail list was **not** built (the restaurant track took its place): `promotion` engine (BOGO/bundle/time-based) · `loyalty` (points/rewards/tiers) · `supplier` · `purchasing` (PO/receiving/returns) · `barcode` printing/generation · `employee` (attendance/breaks).

### Production-hardening backlog — ⚠️ planned but unbuilt

Called for in this plan but not yet implemented — the gap between "backend works" and "shippable":

- **UI** — the JavaFX desktop terminal (§3, §13). Only a headless REST backend exists today. *(Largest missing layer.)*
- **`integration/fiscal`** — reserved (§5, §15.5). **The store runs KSA / SAR / 15% VAT, so ZATCA e-invoicing (Fatoora) is a legal requirement, not optional.**
- **Real hardware & payment adapters** — only in-memory fakes; JavaPOS/ESC-POS printers and the semi-integrated payment-terminal SDK (§9, §10) are unbuilt.
- **Real ERP adapter** — still `FakeErpClient` (§15.2).
- **Security at rest / in transit** — DB encryption (SQLCipher/TDE) and TLS (§11) not configured.
- **Platform services** — file-storage abstraction, backup/restore tooling, localization/i18n (§7, §12) not built (multi-currency *is* wired).
- **Outbox hardening** — retry cap/dead-letter, bounded async executor, idempotent local listeners (see `docs/run-modes.md`).

---

## 15. Open questions / risks to confirm

1. **Payment integration model** — ⚠️ **still open.** The code assumes semi-integrated/P2PE (masked PAN + token, no in-app card capture — good for PCI scope), but only the `InMemoryPaymentTerminal` fake exists. A real certified-terminal SDK must be integrated before go-live.
2. **Which ERP?** — ⚠️ **still open.** Sync is built against `FakeErpClient`; no real vendor (SAP B1/Odoo/Dynamics/custom) has been chosen or adapted. API style, rate limits, and idempotency support still shape the eventual `integration/erp` adapter.
3. **Inventory ownership** — ✅ **resolved in code:** the store decrements its own stock authoritatively and syncs **movements** (not absolute counts) up to the ERP; it keeps selling on stale stock data.
4. **Number of registers** — ⚠️ **partially settled.** Both run modes are built and the tested/default path is **embedded SQLite (Mode B, single register, pool=1)**; the multi-register store-server (Mode A) path is implemented but is not the exercised default.
5. **Fiscal/compliance** — 🔴 **RESOLVED as a hard requirement, UNADDRESSED in code.** The store operates in **KSA (SAR, 15% VAT)**, so **ZATCA/Fatoora e-invoicing** is legally mandatory, yet `integration/fiscal` is still an empty reserved slot. This is now a **go-live blocker**, not an open question.
6. **Returns/refunds offline** — ✅ **resolved:** only **receipted** returns are supported (the original sale must be locally available). Blind/unreferenced returns are explicitly deferred.
7. **Future multi-industry direction** — 🔶 **partially realized, but not as the platform.** A second service format (restaurant dine-in) now ships in the same deployment (Phases 10–13b), added on the existing module seams. The multi-industry **platform** (industry-plugin SPI, feature-flag/licensing, config-driven UI) remains out of scope and unbuilt.
