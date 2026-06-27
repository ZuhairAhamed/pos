# POS Application — Combined Architecture Plan

**Stack:** Java 21 + Spring Boot 3.x · **Terminal:** Windows desktop (JavaFX) · **Topology:** Single store · **Mode:** Offline-first with ERP sync

> **Scope:** Single-industry, single-store POS. This plan consolidates the engineering architecture (`pos-architecture-plan-1.md`) with the complete module/feature inventory from the requirement documents (`pos-requirement.md`, `pos-requirement-1.md`). The multi-industry platform / feature-flag / licensing vision is intentionally **out of scope** for this plan; the modular boundaries below keep that path open for the future without building it now.

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
  sales · dashboard · reporting
        │
TIER 2 — Domain capabilities
  cart · pricing · promotion · tax · payment · receipt · loyalty
  inventory · product · customer · supplier · purchasing
  cashdrawer · employee · shift · barcode
        │
TIER 3 — Platform services
  auth · sync · integration(erp/payment/fiscal) · notification · device · configuration · audit
        │
TIER 0 — Shared kernel (no business logic)
  common(config·exception·security·util·events) · database
```

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
| `payment` | 2 | Tender handling | Cash · credit/debit card (semi-integrated) · QR payment · mobile wallet · gift card · store credit · mixed/split payment · refunds |
| `receipt` | 2 | Receipt production | Templates · thermal print · reprint · email · SMS · PDF |
| `pricing` | 2 | Price resolution | Discounts · coupons · customer-specific pricing · happy-hour pricing · buy-X-get-Y |
| `promotion` | 2 | Promotion engine | BOGO · bundle promotions · time-based discounts · category discounts · customer discounts |
| `tax` | 2 | Tax calculation | VAT · multiple rates · inclusive/exclusive tax · tax exemptions |
| `loyalty` | 2 | Rewards | Points accrual/redemption · rewards · membership levels · loyalty coupons |
| `cashdrawer` | 2 | Till operations | Open/close drawer · cash in · cash out · cash count · cash reconciliation |
| `shift` | 2 | Shift lifecycle | Shift open/close · shift summary · break management · cash handover |
| `employee` | 2 | Staff records | Cashier records · attendance · role assignment (consumes `auth`) |
| `supplier` | 2 | Supplier master | Supplier mgmt · supplier payments · goods receiving link |
| `purchasing` | 2 | Procurement | Purchase orders · receive inventory · purchase returns · vendor invoices |
| `barcode` | 2 | Barcode ops | Barcode generation · barcode printing · barcode lookup |
| `reporting` | 1 | Business reports | Sales · product performance · hourly sales · cashier · inventory · profit · tax · payment reports |
| `dashboard` | 1 | Operational insight | Today's sales · best sellers · low-stock items · active cashiers · open shifts · revenue summary |
| `sync` | 3 | Offline sync engine | Transactional outbox · queue mgmt · retry/backoff · conflict resolution · delta sync · background + manual sync |
| `integration/erp` | 3 | ERP adapter | Product/price/inventory/customer down-sync · sales/purchase upload · master-data sync · idempotent delivery |
| `integration/payment` | 3 | Payment terminal adapter | Semi-integrated terminal SDK · tokenized auth/capture/void/refund |
| `integration/fiscal` | 3 | Fiscal/e-invoice adapter | (Reserved) jurisdiction-specific fiscalization / signed receipts |
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

## 14. Build phasing / MVP roadmap

Sequenced by dependency. The MVP scope reconciles both requirement docs' MVP lists — every Phase-1 MVP item from `pos-requirement.md` / `pos-requirement-1.md` lands by the end of Phase 4.

**Phase 0 — Foundation (no business value, but everything rests on it)**
`common` · `database` · `configuration` · `device` ports · module skeleton + Modulith boundary tests · CI · packaging for both run modes.

**Phase 1 — Identity & master data (down-sync first)**
`auth` (login, RBAC, PIN, shift login) → `product` + `inventory` baseline → `integration/erp` **down-sync** (products, prices, stock). Goal: a terminal that authenticates and shows the live catalog from ERP.

**Phase 2 — Checkout core**
`cart` → `pricing` + `tax` → `payment` (cash + semi-integrated card + QR/wallet) → `receipt` → `sales` orchestration → `cashdrawer`/`shift`. Goal: ring up and complete a sale offline (incl. hold/resume, void, returns/exchanges, split payment), print a receipt, reconcile the till.

**Phase 3 — Sync up & resilience**
`sync` outbox + worker → ERP **upload** of sales/movements (idempotent) → conflict handling → `notification` (sync-error, low-stock alerts). Goal: trade fully offline; drain cleanly when the link returns.

**Phase 4 — Visibility & ops (completes the MVP)**
`customer` (registration/search/history) → `reporting` (sales, product, cashier, tax, payment) → `dashboard` → `audit` hardening → backup/restore runbook. Goal: a production-ready, operable single-store POS matching the requirement-doc MVP.

**Phase 5+ — Incremental (no core changes, thanks to event seams)**
`loyalty` · `promotion` engine · `supplier` · `purchasing` · `barcode` printing · `employee` attendance/breaks · advanced analytics · `integration/fiscal` (if jurisdiction requires).

---

## 15. Open questions / risks to confirm

1. **Payment integration model** — Is a semi-integrated/P2PE terminal acceptable? This is the single biggest scope decision (PCI-DSS). If you must do in-app card capture, the security architecture changes substantially.
2. **Which ERP?** (SAP B1, Odoo, Dynamics, custom…) Its API style (REST/SOAP/file/DB), rate limits, and idempotency support shape the `integration/erp` adapter and conflict strategy.
3. **Inventory ownership** — Does the store decrement its own stock authoritatively, or must the ERP confirm? Affects whether we can sell when stock data is stale.
4. **Number of registers** in the target store → confirms Mode A vs Mode B as the primary path.
5. **Fiscal/compliance** — Any government fiscalization, e-invoicing, or signed-receipt requirements? (`integration/fiscal` is reserved.) These are jurisdiction-specific and can be hard requirements.
6. **Returns/refunds offline** — Allowed without the original sale present, or must the original be locally available?
7. **Future multi-industry direction** — This plan is deliberately single-industry. If a multi-industry/licensable platform becomes a goal later, the module seams support it, but an industry-plugin SPI, feature-flag/licensing layer, and config-driven UI would be added as a separate initiative.
