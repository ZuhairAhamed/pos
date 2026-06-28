# Phase 1 Identity & Master Data — Progress Ledger

Plan: docs/superpowers/plans/2026-06-27-phase-1-identity-master-data.md
Branch: phase-1-identity
Base commit: a487ea10f81111ae9322a271e5a0ae654750ea2a

## Tasks
- [x] Task 1: Auth foundation (user/roles/repo/migration/encoder)
- [x] Task 2: JWT service + security config
- [x] Task 3: Password login endpoint
- [x] Task 4: Cashier PIN login endpoint
- [x] Task 5: RBAC (/auth/me + manager-only)
- [x] Task 6: Product module (domain + facade)
- [x] Task 7: Product REST (secured)
- [x] Task 8: Inventory module
- [x] Task 9: Integration module (ErpClient + fake + cursors)
- [x] Task 10: Product ERP down-sync
- [x] Task 11: Inventory ERP down-sync
- [x] Task 12: Sync orchestration (coordinator/scheduler/controller)
- [x] Task 13: End-to-end + docs + verify

## Minor findings (final-review triage)

## Log

Task 1: complete (commits a487ea1..831d06a, review clean)
  Minor (final triage): User.getRoles() returns mutable live Set (encapsulation leak); RoleSetConverter could use autoApply=true; password_hash VARCHAR(100) fine for bcrypt but tight for argon2/scrypt later.

Task 2: complete (commits 831d06a..28f73d8, review clean)
  Plan bug fixed: JwtSupportConfig JwtAuthenticationConverter import was wrong package; corrected in code AND in plan doc.
  Minor (final triage): unused java.util.List import in JwtServiceTest; secret.getBytes() no explicit UTF-8; no iss claim (optional hardening).

Task 3: complete (commits ace90d5..0182a01, review clean)
  Minor (final triage, out-of-scope hardening): sad-path test doesn't assert RFC-7807 body; LoginRequest has no Bean Validation; handler method package-private.

Task 4: complete (commits 0182a01..756a123, review clean — no real issues)

Task 5: complete (commits 756a123..865f5e2, review clean; auth module done, full suite 12/12)
  Minor (final triage, informational): AuthController import ordering; /auth/manager-check "ok" body is content-negotiation dependent.

Task 6: complete (commits 865f5e2..ac74433, review clean)
  Minor (final triage): no FK constraint on product.category_id (intentional denormalized design); search delegates query twice (Spring Data idiom); toView formatting.

Task 7: complete (commits ac74433..0c7b6e2, review clean)
  Minor (final triage): list test asserts [0] ordering; no @RequestMapping class prefix; the ?q= search branch has no test coverage.

Task 8: complete (commits 0c7b6e2..a0b159d, review clean — no real issues)

Task 9: complete (commits a0b159d..e32129c, review clean)
  Minor (final triage): FakeErpClient is a non-thread-safe shared singleton (fine for tests w/ clear()); SyncCursorRepository visibility; redundant setValue on new cursor (verbatim from brief).

Task 10: complete (commits e32129c..96bd970, review clean; integration::api named interface verified)
  Minor (final triage): category upsert+save runs even when product upsert is skipped (no-op writes); no sync logging/observability.

Task 11: complete (commits 96bd970..3c56405, review clean — only style minor orElse(null))

Task 12: complete (commits 3c56405..b027f83, review clean)
  PLAN CORRECTION (resolved in code): the plan's claim that the root package com.company.pos is "not boundary-constrained" by Modulith is WRONG. Modulith enforces it as root:com.company.pos. Implementer correctly added @NamedInterface("api") to product/api/package-info.java and inventory/api/package-info.java (matching integration/api) so the root coordinator can reference ProductSync/InventorySync. TODO: fix plan doc Global Constraints + Task 12 to reflect this.
  Minor (final triage): scheduler initialDelay reuses fixed-delay (60s startup wait, no comment); SyncController lacks explicit produces=JSON.

Task 13: complete (commits b027f83..f5d1411, review clean)

ALL 13 TASKS COMPLETE. ./mvnw -B verify: BUILD SUCCESS, 46 tests, target/pos.jar built. 8 modules, ModularityTests green.

## Final whole-branch review (opus)
Verdict: Ready to merge — Yes. No Critical. Two Important + one correctness minor landed (commit 8f10aff), then a follow-up correction (commit 8243d02):
  - JwtSupportConfig: secret.getBytes(UTF_8) (cross-env HMAC correctness)
  - JwtSecretGuard @Profile("store-server"): fail-fast if JWT secret == dev default
  - ErpSyncCoordinator: per-stream SLF4J logging (partial-failure observability)
  - DatabaseStoreServerTest: @TestPropertySource secret override (replaced a shadowing test application-store-server.yml that had silently dropped the prod store-server Flyway/pos-schema config)
Re-verified: ./mvnw -B verify BUILD SUCCESS, 46 tests.
Deferred to Phase 2 backlog: User.getRoles() mutable Set; Bean Validation @NotBlank on login DTOs; search-branch test; product/inventory no-op sync writes; @RequestMapping prefixes; carry-forward Phase 0 minors (serialVersionUID, byte-buddy warning). When sync module lands (Phase 3), move root-package coordinator/scheduler/controller out and narrow ProductSync/InventorySync exposure.

PHASE 1 COMPLETE. Branch phase-1-identity: a487ea1..8243d02. ./mvnw -B verify: 46 tests green, target/pos.jar built.


=== PHASE 2a — CHECKOUT CORE (cash sell path) ===
Branch phase-2a-checkout off phase-1-identity at 8243d02. Plan: docs/superpowers/plans/2026-06-28-phase-2a-checkout-core.md (12 tasks).
Task 1: complete (8243d02..90a6059, review clean)
Task 2: complete (90a6059..aaea61b, review clean)
Task 3: complete (aaea61b..6c183d8, review Approved; +fix 6c183d8 ID-seam)
Task 4: complete (6c183d8..44c8c51, review Approved)
Task 5: complete (44c8c51..f0223b6, review Approved)
Task 6: complete (f0223b6..ffa1259, review Approved; +fix ffa1259 sale_id length)
Task 7: complete (ffa1259..8b71fc0, review Approved)
Task 8: complete (8b71fc0..8778bf3, review Approved)
Task 9: complete (8778bf3..b4908c3, review Approved; +fix b4908c3 REQUIRES_NEW->REQUIRED tx deadlock, reverted embedded pool to 1)
Task 10: complete (b4908c3..e0aa1f4, review Approved)
Task 11: complete (e0aa1f4..f5acad1, review Approved)
Task 12: complete (f5acad1..c0c3ebd, review Approved)
Task 3 MINOR (final-review triage): explicit @OneToMany FetchType.LAZY on Cart.lines; removeLine no-op on missing sku (no guard); close() not idempotency-guarded; lineNo gaps after removeLine. CartLine made public (forced by Java visibility for lambda mapping; matches StockLevel pattern) — accepted.
Task 8 MINOR (final-review triage): Sale.getLines() returns live mutable list (same pattern as Cart) — consider unmodifiableList before public read paths.
Task 9 MINOR (final-review triage): dead import CartLineView in DefaultSalesService (cosmetic); catch(RuntimeException) on best-effort print could be catch(Exception). Plan doc still says REQUIRES_NEW — update plan doc to REQUIRED at wrap-up.
Task 11 MINOR (final-review triage): listener @Transactional redundant (joins checkout tx) — intentional per plan; StockMovement lacks getReferenceId/getLocationCode getters (forward-compat); latent: DB constraint violation in create-if-absent would mark checkout tx rollback-only under concurrent same-sku checkouts (single-threaded non-issue; Phase 3 outbox resolves).
FINAL whole-branch review (opus): no Critical, no blocking Important. Verdict With-fixes. Fix wave a184d53: Sale.getLines() unmodifiable; +inclusive-tax reconciliation test; listener Javadoc tightened; stale REQUIRES_NEW comment fixed; dead import removed. Deferred (acceptable this phase / Phase-3 outbox): listener rollback-only-under-concurrency, cart removeLine/close guards, lineNo gaps. PHASE 2a tasks 1-12 COMPLETE.


=== PHASE 2b — ADVANCED CHECKOUT (card/wallet · split payment · hold/resume) ===
Branch phase-2b-advanced-checkout off phase-2a-checkout at a184d53 (branch base commit 933a1c3 = plans docs).
Plan: docs/superpowers/plans/2026-06-28-phase-2b-advanced-checkout.md (6 plan tasks).
EXECUTION ADJUSTMENT (single-module Maven compile coupling): plan Tasks 2,3,4 (payment+receipt+sales) executed as ONE merged dispatch + one green commit (they don't compile standalone). Plan Task1=>exec Task1, Tasks2-4=>exec Task2(merged), Task5(cart)=>exec Task3, Task6(capstone)=>exec Task4.
Task 1 (device fake PaymentTerminal): complete (commits 933a1c3..8a995c1, review clean — no issues)
Task 2 (MERGED payment+receipt+sales multi-tender): complete (commits 8a995c1..bd7ede0, review Approved by opus, 89/89 green). No Critical/Important.
  Task 2 MINOR (for final triage): card branch passes raw tender.amount() to recordTerminalPayment (re-scaled internally; cash branch pre-scales) — cosmetic asymmetry; no guard rejecting stray tendered on card tender; receipt prints Tendered/Change only when changeDue>0 (per brief, intended).
Task 3 (cart hold/resume/void, terminal scoping): complete (commits bd7ede0..befbaff, review Approved; 2 Important test-gap findings fixed in befbaff, re-review clean). 95/95 green.
  Task 3 NOTE: renumber lineNo is internal — CartLineView intentionally has no lineNo (per brief); renumber proven observably via order-by-sku, not numeric assertion.
Task 4 (capstone e2e + docs): complete. ./mvnw -B verify: 96 tests (0 failures), BUILD SUCCESS. DatabaseStoreServerTest RAN (Docker available, Testcontainers Postgres 16-alpine, Flyway V1–V11 applied and ddl-auto=validate passed — V10 masked_pan/auth_token and V11 terminal_id columns verified against Payment/Cart entities).

API changes delivered this phase:
- PaymentMethod: CASH → CASH, CARD, WALLET (enum extended)
- CashPaymentView deleted; unified PaymentView(saleId, method, amount, amountTendered, changeDue, maskedPan, currencyCode) added
- SaleView.payments: single SalePaymentView → List<SalePaymentView>; SalePaymentView gains maskedPan
- CheckoutCommand: (cartId, amountTendered) → (cartId, List<TenderInput>); TenderInput(method, amount, tendered) added
- Cart: status "HELD" added; terminalId field (V11 migration); CartService.hold/resume/listHeld added
- Migrations: V10 (payment: masked_pan VARCHAR(25), auth_token VARCHAR(64)); V11 (cart: terminal_id VARCHAR(16))

KNOWN LIMITATION: a card tender that is approved by the fake terminal but then causes the total-match validation to fail (e.g. float precision or a misconfigured split) rolls back the whole checkout transaction — leaving the terminal in an "approved" state with no corresponding sale. For a real terminal this requires a compensating void to prevent phantom charges. Deferred to the payment-integration adapter / Phase 3 outbox work.

PHASE 2b COMPLETE. ./mvnw -B verify: 96 tests green, BUILD SUCCESS, target/pos.jar built, DatabaseStoreServerTest passed.
