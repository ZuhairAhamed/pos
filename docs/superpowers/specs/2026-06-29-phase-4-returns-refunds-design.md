# Phase 4 — Returns & Refunds Design

**Status:** Approved design (brainstorming output). Implementation plan to follow via `writing-plans`.
**Date:** 2026-06-29
**Branch (suggested):** `phase-4-returns-refunds`

## Goal

Let a store process a **receipted return** — refunding all or part of a prior sale — that cleanly
reverses every effect the original sale produced: stock is added back, the refunded cash leaves the
drawer, the card/wallet portion is refunded at the terminal, and a credit note is uploaded to the
ERP. Returns are **manager-only** and guarded against **over-refunding** across repeated partial
returns.

## Decisions locked in brainstorming

| Decision | Choice |
|---|---|
| Return model | **Receipted** (must reference an original sale) with a **cumulative over-return guard** |
| Module placement | **Extend the `sales` module** (no new module, no new module dependency) |
| Refund tender | **Mirror the original tender(s)**: cash→drawer pay-out, card/wallet→terminal refund |
| Mixed-tender sales | Refund **allocated proportionally** across the original tenders |
| Authorization | **Manager-only** (`hasRole('MANAGER')`) |
| Reversal wiring | **Approach A** — a dedicated `ReturnCompleted` event + parallel after-commit outbox listeners |
| Refund payments | Stored as `Payment` rows with an explicit **refund-direction** column |
| Restock policy | **Always restock** on return (no damaged-goods/no-restock path this phase) |

## Architecture

A return is an **immutable record that reverses a prior sale**, persisted in the `sales` module.

The write path is one synchronous transaction:

```
validate → record refund tenders → persist Return → publish ReturnCompleted → print credit note
```

The reversal of stock / drawer / ERP happens **after commit**, through three new
`@ApplicationModuleListener`s (mirroring the existing `SaleCompleted` fan-out), each tracked by the
Phase 3a Event Publication Registry and therefore replayable on failure. This keeps ERP credit-note
upload asynchronous and offline-resilient, exactly like sale upload (Phase 3b).

No new module is introduced. `sales` already depends on `payment`, `pricing`, `tax`, `receipt`,
`cart`, and `configuration` — returns add no new module dependency. The downstream listeners live in
the modules that already own each effect (`inventory`, `cashdrawer`, `sync`) and each consumes
`sales :: api` (the new `ReturnCompleted` event), which those modules already do for `SaleCompleted`.

### Why Approach A (vs. the alternatives)

- **A. Dedicated event + parallel listeners (chosen)** — consistent with the outbox/offline-resilient
  design every prior phase built toward; ERP upload stays async and replayable.
- **B. Synchronous in-service reversal** — breaks event-driven decoupling, makes ERP upload
  synchronous (loses Phase 3b offline resilience), forces `sales` to take new write-dependencies on
  `inventory`/`cashdrawer`. Rejected.
- **C. Overload `SaleCompleted` with negative quantities** — corrupts semantics (negative cashTotal,
  low-stock edge logic, "sale" vs credit note in ERP) and doesn't fit the immutable `Sale`. Rejected.

## Components

### `sales` module (extended)

**Domain**
- `Return` (aggregate) — `id`, `creditNoteNumber`, `originalSaleId`, `storeId`, `terminalId`,
  `managerUsername`, `locationCode`, `currencyCode`, `refundSubtotal`, `refundTaxTotal`,
  `refundGrandTotal`, `createdAt`, `status = "COMPLETED"`, `List<ReturnLine>`.
- `ReturnLine` — `id`, `return`, `lineNo`, `originalLineNo`, `sku`, `name`, `quantity`,
  `unitPrice`, `netAmount`, `taxAmount`, `lineTotal`, `currencyCode`. Amounts are the proportional
  share of the original sale line (positive magnitudes; the record's *meaning* is a refund).

**API (`sales.api`)**
- `ReturnService` — `ReturnView processReturn(ReturnCommand command, String managerUsername)`;
  `ReturnView getReturn(UUID returnId)`.
- `ReturnCommand` — original sale reference (`UUID originalSaleId` **or** `String receiptNumber`) +
  `List<ReturnLineRequest>` where `ReturnLineRequest(int lineNo, BigDecimal quantity)`.
- `ReturnView`, `SaleReturnLineView`, `ReturnPaymentView` (refund tenders).
- `ReturnCompleted` (`DomainEvent`) —
  `returnId, originalSaleId, creditNoteNumber, terminalId, locationCode, currencyCode,
  refundGrandTotal, cashRefundTotal, List<ReturnedLine{sku, quantity}>`.

**Application**
- `DefaultReturnService` (`@Service @Transactional`).
- Reuse `ReceiptNumbering` for the credit-note number (same store-terminal sequence mechanism).

**Infrastructure**
- `ReturnRepository`.
- `ReturnLineRepository` with the over-return guard query:
  `BigDecimal sumReturnedQuantity(UUID originalSaleId, int originalLineNo)` (returns 0 when none).
- `SaleRepository.findByReceiptNumber(String)` — **new**, for receipt lookup.

**Web**
- `ReturnController`:
  - `POST /returns` → `processReturn`, `@PreAuthorize("hasRole('MANAGER')")`, `201 Created`.
  - `GET /returns/{returnId}` → `getReturn`, `@PreAuthorize("hasRole('MANAGER')")`.
  - A manager-only receipt-lookup endpoint to fetch the sale being returned (e.g.
    `GET /sales/by-receipt/{receiptNumber}` on `SalesController`, or reuse existing
    `GET /sales/{saleId}`). Final shape decided in the plan.

### `payment` module (extended)

- `PaymentService.refundCash(UUID returnId, String currencyCode, BigDecimal amount)` and
  `refundTerminalPayment(UUID returnId, String currencyCode, BigDecimal amount, PaymentMethod method,
  String reference)`.
- Refunds are stored as `Payment` rows with an explicit **refund direction** (see migration) and a
  reference to the `returnId`. Card/wallet refunds call the new terminal refund op.
- A declined terminal refund throws `DomainException.validation` (rolls the return back).

### `device` port (extended)

- `PaymentTerminal.refund(PaymentRequest request) : PaymentResult` — **new** port operation.
- The in-memory fake (`InMemoryPaymentTerminal`) returns an approved `PaymentResult` (configurable
  approve/decline, mirroring `requestPayment`).

### Downstream listeners (new, after-commit, outbox-tracked)

- **inventory** `ReturnCompletedListener` — for each returned line, increment on-hand and append a
  positive `StockMovement` with reason `"RETURN"` (referenceId = returnId). No `LowStockDetected` on
  an increase.
- **cashdrawer** `ReturnCompletedCashListener` — when `cashRefundTotal > 0`, call
  `payOut(terminalId, cashRefundTotal, "REFUND", managerUsername-or-system)`. **Tolerates "no open
  session"** the same way the cash-sale listener tolerates it, so a cash refund recorded without an
  open drawer does not wedge an un-completable outbox publication.
- **sync** `ReturnUploadListener` — `erp.uploadReturn(ReturnUpload)` plus positive `"RETURN"` stock
  movements; idempotent on `returnId` so at-least-once replay never double-credits.

### `integration` API (extended)

- `ReturnUpload` record (credit-note shape: returnId, creditNoteNumber, originalSaleId, terminalId,
  locationCode, currency, refund totals, lines, refund payments).
- `ErpClient.uploadReturn(ReturnUpload)` — **new**; `FakeErpClient` implements it idempotently on
  `returnId` (mirrors `uploadSale`).

## Data flow — `processReturn(command, managerUsername)`

Single synchronous transaction:

1. **Resolve the original sale** — by `originalSaleId`, or by `receiptNumber` via
   `findByReceiptNumber`. `DomainException.notFound` if absent.
2. **Validate requested lines** — match each to its original `SaleLine` by `lineNo`; quantity must
   be `> 0`; enforce `requested + alreadyReturned ≤ sold` per line (`DomainException.conflict` on
   breach). Empty request → `DomainException.validation`.
3. **Compute proportional refund per line** — `factor = returnQty / soldQty`; refund `netAmount`,
   `taxAmount`, `lineTotal` = original × factor, each set to scale 2, `HALF_UP`. VAT stays exactly
   proportional. `refundGrandTotal = Σ refund lineTotal`; `refundSubtotal`/`refundTaxTotal` likewise.
4. **Allocate refund across original tenders** — proportional to each original payment's share of the
   original grand total. The **last allocated tender absorbs the rounding remainder** so the
   allocation sums exactly to `refundGrandTotal`. Cash share → cash refund; card/wallet share →
   terminal refund. `cashRefundTotal` = sum of the cash-method allocations.
5. **Execute refund tenders** — cash via negative `Payment` row; card/wallet via `terminal.refund(…)`
   then negative `Payment` row. A declined terminal refund throws → the whole transaction rolls back
   (no partial return, no money moved).
6. **Persist the immutable `Return`** with its credit-note number.
7. **Publish `ReturnCompleted`** in-process (recorded to the outbox for after-commit fan-out).
8. **Print the credit-note receipt** — best-effort; a print failure never fails the return (mirrors
   `DefaultSalesService.printReceipt`).
9. Return `ReturnView`.

After commit, the three outbox listeners fire independently: stock add-back, drawer pay-out, ERP
credit-note upload — each replayable on failure via the Phase 3a/3b machinery.

## Error handling

- All validation through `DomainException` (`notFound` / `validation` / `conflict`) → existing
  RFC-7807 `ApiExceptionHandler`.
- **Atomicity**: refund tendering + `Return` persistence share one transaction. A terminal-refund
  decline or an over-return breach rolls everything back — no money moves, no record persists.
- **Cash refund without an open drawer**: the cashdrawer `ReturnCompleted` listener tolerates the
  no-open-session case (does not throw), so the publication completes rather than wedging.
- **No negative-stock / low-stock path on return** — a return only increases on-hand.
- **ERP offline**: `uploadReturn` throws → publication stays incomplete → Phase 3b drain/replay
  retries; idempotent on `returnId`.
- **Authorization**: `POST /returns` and `GET /returns/{id}` are `hasRole('MANAGER')`; a cashier
  receives `403`.

## Persistence & migrations

- **V16** (`db/migration/sales`) — `sales_return` and `sales_return_line` tables. Money columns match
  the `sale`/`sale_line` precision/scale (`NUMERIC(19,2)` for amounts, `(19,3)` for quantity,
  `(19,4)` for unit price). `credit_note_number` unique.
- **Payment refund-direction migration** (`db/migration/payment`) — add a `direction` (or
  `txn_type`) column to `payment` distinguishing `SALE` vs `REFUND`, plus a nullable `return_id`
  reference. Existing rows default to `SALE`. Refund rows store the negative amount (or positive
  magnitude tagged `REFUND` — finalized in the plan, but the column is the source of truth).
- Embedded profile (SQLite, `ddl-auto: update`) adds the columns/tables automatically; store-server
  (`ddl-auto: validate`, Flyway) requires the migration to match the entity mappings, verified by
  `DatabaseStoreServerTest` over Flyway V1–V16.

## Testing (TDD, mirroring existing phase tests)

- **Guard & math (slice/unit)**: over-return guard for a single return and **cumulatively across two
  partial returns**; proportional tax math; mixed-tender allocation including the rounding remainder.
- **`@SpringBootTest` embedded** (non-`@Transactional`, `DatabaseCleaner` `clean()` in `@BeforeEach`
  *and* `@AfterEach`, Awaitility for async, reset singleton fakes):
  - return → stock add-back (positive `"RETURN"` movement, on-hand restored);
  - cash refund → drawer `payOut`;
  - card refund → `terminal.refund` called + negative/refund `Payment` row;
  - ERP offline → incomplete publication, then replay uploads the credit note.
- **`ModularityTests`**: boundaries stay green; no new module dependency; no cycle.
- **REST e2e**: manager logs in → sells → returns part of the sale → asserts stock/drawer/ERP
  effects; **cashier is forbidden (`403`)** from `POST /returns`.
- **`DatabaseStoreServerTest`**: V16 + the payment migration validate on the Postgres Testcontainer.
- **Phase gate**: full `./mvnw -q test` green, including `DatabaseStoreServerTest` on Docker.

## Scope boundary (explicitly deferred — do not build this phase)

- Blind / unreferenced returns (no original sale).
- Cashier returns with manager-approval thresholds (this phase is manager-only).
- Damaged-goods / no-restock flag (this phase always restocks).
- Exchanges (return + new sale in one transaction).
- Refunding to a tender different from the original.
- Reporting on returns (belongs to a later `reporting` module).

## Definition of complete

- A manager can return all or part of a receipted sale via `POST /returns`; a cashier gets `403`.
- Over-return is impossible across repeated partial returns.
- Stock is restored, cash refunds leave the drawer, card/wallet refunds hit the terminal, and a
  credit note uploads to the ERP (replayable when the ERP is offline).
- Refund VAT is exactly proportional; mixed-tender refunds allocate proportionally and sum exactly.
- `./mvnw -q test` is green including `ModularityTests` and `DatabaseStoreServerTest` (Flyway
  V1–V16) on Docker.
