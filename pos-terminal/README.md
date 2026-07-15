# POS Terminal (JavaFX)

A standalone, touch-first **JavaFX desktop POS terminal** for the restaurant
seat-to-payment slice. It is a thin REST client: it holds no database and no
business rules — it talks to a running **store-server backend** over HTTP and
renders the screens (login → table map → order → payment). All authoritative
totals come from the server; the terminal only computes a client-side
*estimated* subtotal to preview before closing a check.

Package root: `com.company.pos.terminal`. Money is `BigDecimal` at scale 2.
There is one shared stylesheet, `src/main/resources/css/app.css`.

## Screens

After login the terminal shows a **Home** mode picker:

- **Dine-in** → table map → order → payment (seat-to-payment).
- **Retail sale** → a two-zone quick-service screen (category menu grid + live cart
  with a pinned estimated-total bar) → multi-tender payment → receipt.

The cart shows a client-side **estimated** pre-tax subtotal; tax, service charge, and
the grand total are authoritative only from the server's `SaleView` at checkout.
Barcode entry matches the cached catalogue client-side (the server search covers
name + SKU only).

## Prerequisites

- **JDK 21** (the repo default toolchain is 17 — set `JAVA_HOME` first).
- A **running backend** exposing the store HTTP API (the modular monolith in
  this repo), reachable at the configured base URL. Either persistence mode
  works: `store-server` (PostgreSQL) or `embedded` (SQLite).

## Build & test

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -f pos-terminal/pom.xml clean test
```

Tests are headless (JavaFX ViewModels + API clients against an in-JVM stub
server); no display, TestFX, or `Application.launch` is required.

## Run

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -f pos-terminal/pom.xml javafx:run
```

Configuration comes from `src/main/resources/pos-terminal.properties` and can be
overridden per-launch with `-D` system properties (same keys):

| Key | Default | Meaning |
| --- | --- | --- |
| `server.base-url` | `http://localhost:8080` | Backend HTTP base URL |
| `terminal.id` | `T01` | This terminal's id |
| `store.id` | `S01` | Store id |
| `poll.interval.seconds` | `5` | Table-map live-refresh interval |

Point the terminal at a non-default backend either by editing
`pos-terminal.properties` or on the command line:

```bash
./mvnw -f pos-terminal/pom.xml javafx:run \
  -Dserver.base-url=http://192.168.1.20:8080 -Dterminal.id=T02
```

If the backend is unreachable, screens surface a clear error banner
("Cannot reach store server") rather than crashing.

## Seed data (backend prerequisites)

The terminal creates nothing itself — it consumes what the backend exposes. All
seeding is done **against the backend's HTTP API** (or its dev bootstrap), not
from the terminal. Endpoints below are stated at the endpoint level; assume the
standard method-security roles apply (MANAGER for registration/provisioning).

- **Users / login** — users are provisioned on the backend; there is no default
  user shipped in a migration. As an operator step, create a cashier and/or
  manager account on the backend, then log in from the terminal with those
  credentials (password) or the staff PIN. Do not assume a hardcoded default.

- **Dining tables** — register at least one active DINE_IN table:
  `POST /dining/tables` with a `RegisterTableCommand` body (MANAGER). This
  produces a free table that appears on the terminal's table map.

- **Products / catalog** — the catalog is **read-only over HTTP**
  (`GET /products`); products are populated by the backend's ERP down-sync. In a
  dev backend the `FakeErpClient` supplies sample products, so ensure the backend
  has a product catalog seeded (the dev backend does so via `FakeErpClient`). For
  a full walkthrough you want **at least a few products across ≥2 categories and
  one product that has a modifier group** so the modifier picker can be exercised.

> Assumption: exact request bodies (field names for `RegisterTableCommand`, the
> ERP sync trigger, modifier-group setup) are owned by the backend modules and
> are not re-verified here. If a value is unknown for your environment, treat it
> as an operator-provided placeholder.

## Service-charge caveat (important for this slice)

The terminal's estimated total (the client subtotal passed to the payment
screen) is **pre-service-charge**. If the backend has the DINE_IN service charge
**enabled**, the server's authoritative `grandTotal` will *exceed* the estimate,
and an exact-match cash tender computed from the estimate will be rejected as a
short tender (the cash short-tender guard compares tendered against the
estimate). For this slice:

- Run with the **service charge OFF** (the store default), **or**
- Tender **extra** cash to cover the added service charge.

Service-charge display on the entry screen is deferred; the confirmation screen
*does* show the authoritative `serviceChargeAmount` line when it is greater than
zero, sourced from the returned `SaleView`.

## Slice 4 — shift, denominations, quote clarity, success state

- **Start shift**: on the first arrival at Home with no open shift on this terminal
  (`GET /shifts/open` → 404), a modal prompts for the opening cash float (numeric pad
  + optional count-by-denomination helper) and opens the shift via `POST /shifts`.
  Skipping is remembered for the session and shown as "No shift open" in the toolbar.
- **Denomination fast-cash**: Exact / 50 / 100 / 200 / 500 chips on the payment screen
  fill "Cash tendered" in one tap; the change preview reacts and Cash commits. Chips
  stay disabled until the server quote loads.
- **Estimate vs quote**: the client estimate renders as a muted "Estimate at order"
  line; the prominent total is only ever the server quote, marked with a "✓ server"
  badge ("Fetching total…" while it loads).
- **Success state**: the receipt opens with a success-tinted "Paid · <amount>" banner
  (checkmark scale-in honours `ui.reduced-motion`), and Done is labelled with its
  destination ("Done ▸ Tables" / "Done ▸ Home").

## Slice 5 — whole-sale discount + manager approval

- **Discount** (payment screen, both retail and dine-in): one whole-bill discount — percent or
  amount plus a required reason chip (reasons come from `GET /sales/discount-policy`). Applying
  re-fetches the server quote WITH the discount, so the big total is always the discounted
  authoritative number; a removable chip shows `Discount −6.00 SAR · LOYALTY`. The discount
  locks once any tender is added.
- **Manager approval**: a discount over the cashier cap (policy `cashierMaxPercent` /
  `cashierMaxAmount`) shows an amber hint in the modal and, at tender time, a manager code+PIN
  modal. The manager's token (via `POST /auth/pin-login`) rides exactly ONE call — the
  checkout/close POST — and is discarded; the cashier stays signed in. A rejected override
  never signs the cashier out.

Manual E2E (requires `--spring.profiles.active=embedded,dev` backend, login `manager`/`manager`
or a seeded cashier):

1. Retail sale → payment: apply 5% LOYALTY → total re-quotes lower, chip appears, tenders
   re-enable only after the re-quote lands. Pay cash. Receipt shows the discount row.
2. Apply 15% as a CASHIER (over the 10% cap): the modal shows the amber approval hint; tapping
   a tender opens the manager PIN modal; a manager approves; the sale closes; the toolbar user
   is still the cashier.
3. Wrong PIN → error surfaces on the payment screen, cashier still signed in, retry works.
4. Remove discount (✕) → total re-quotes back up.
5. Dine-in order → close via payment screen with a 15% discount → same approval path, service
   charge visibly computed on the discounted base.

## Slice 6 — split-bill (BY_ITEM and EVEN)

From the **order screen**, the **Split bill** button is enabled once the order has loaded and has
at least one line. Tapping it opens a two-phase split flow.

**Phase 1 — partition**

A mode toggle selects the split strategy:

- **By item** — guest tabs are shown (2–6 guests; add with "+ Guest"). Tap any line row to assign
  it to the currently-selected guest tab; tap again to unassign; tapping while a different guest
  is active reassigns the line. An "Unassigned: N" counter tracks lines not yet placed.
  **Continue** stays disabled until N = 0; guests with zero lines assigned are silently dropped
  at Continue.
- **Split evenly** — a ways stepper (2–8) divides the whole bill equally. No line assignment is
  needed.

**Phase 2 — per-guest tender**

Pressing Continue fetches server-authoritative amounts via
`POST /dining/orders/{id}/quote-split`, shown with a "✓ server" badge on each guest tile. A
short-tender or quota error fails immediately. Each guest gets exactly one tender choice
(Cash / Card / Wallet):

- On **BY_ITEM** bills, Cash shows a tendered-amount field and a live change preview.
- On **EVEN** bills, Cash is "exact amount" (no tendered field — the server already knows the
  shares sum exactly).

**Close all bills** fires a single atomic `POST /dining/orders/{id}/close-split`. If the server
rejects any bill the whole call is rolled back, the order stays OPEN, and Phase 2 remains
editable. **Back** returns to Phase 1 with all assignments preserved; any change to the partition
requires a new Continue (re-fetches the quote).

**Phase 3 — results**

A per-guest receipt list is shown:

- BY_ITEM: receipt number, total, and change due per guest.
- EVEN: one receipt and one payment line per guest.

**Done ▸ Tables** closes the flow and returns to the table map (the table is now free).

Manual E2E (backend running with `--spring.profiles.active=embedded,dev`, login `manager`/`manager`
— see the existing E2E preamble below):

1. **BY_ITEM, 2 guests** — seat a table, add 3 items, Split bill → By item → assign 1 item to
   Guest 1 and 2 items to Guest 2 → Continue. Verify the two amounts match a hand-check of those
   line totals (badge shows ✓ server). Tender Guest 1 by CASH with an over-tender (change
   previews correctly), Guest 2 by CARD → Close all bills → both receipts listed; Guest 1 shows
   change due → Done ▸ Tables → table is free.
2. **EVEN, 3 ways** — order → Split bill → Split evenly, ways = 3 → Continue → three shares;
   when the total is not exactly divisible, the last share differs by the rounding remainder. One
   CASH share shows "exact amount" (no tendered field) → Close all bills → one receipt, three
   payment lines.
3. **Partition error path** — leave an item unassigned; confirm Continue stays disabled. Reassign
   that item to another guest and verify the assignment badge moves to the new guest tab.
4. **Atomic-failure path** — hard to trigger from the UI alone (all validation fires at quote
   time before Close is enabled); covered by the backend test `aFailingBillRollsBackTheWholeSplit`.

## Manual end-to-end walkthrough

With a backend running and seeded per above, launch the terminal (`javafx:run`)
and walk the flow:

1. **Login** — sign in as a cashier/server (password or PIN).
2. **Table map** — the floor shows free vs. occupied tables; it refreshes on the
   poll interval. Tap a **free** table to open a new check, or an occupied one to
   resume it.
3. **Order** — add items from the touch menu grid. For a product with a modifier
   group, pick the modifiers when prompted. Adjust quantities / remove lines as
   needed; the running estimated subtotal updates.
4. **Fire** — send the order to the kitchen. Fired lines lock (shown muted).
5. **Pay** — tap **Pay** to open the payment screen (estimated total shown):
   - **Cash** — enter cash tendered; a live **change** preview appears once the
     amount covers the estimate. A short tender is rejected inline *before* any
     server call. Tap **Take cash payment**.
   - **Card** — tap **Take card payment** (no tender field).
6. **Confirmation** — on a successful close the screen shows the **authoritative
   `SaleView` totals** (subtotal, tax, service charge when > 0, and a prominent
   grand total), plus the receipt number and change due for cash.
7. **Reprint** — tap **Reprint receipt** to re-issue the receipt server-side
   (the dev backend's `InMemoryPrinter` logs it).
8. **Back to the floor** — tap **Done — new table** to return to the table map;
   the just-closed table is free again.

If the backend is stopped mid-flow, actions surface the unreachable-server error
rather than proceeding.

## Slice 7 — Table states + takeaway (manual E2E)

Run the backend with seed data (`--spring.profiles.active=embedded,dev` — this now seeds
tables `T1`–`T6` and `Counter 1`–`Counter 3`), then `./mvnw -f pos-terminal/pom.xml javafx:run`
and log in as `manager` / `manager`.

1. **Dine-in states.** On **Tables**, every table shows **Open**. Tap `T1` → order screen →
   Back to tables: `T1` now shows **Seated · 0m**. Add an item to `T1`, return: it shows
   **In use · Nm**. Leave it open past the dwell window (default 45 min, or set
   `-Ddining.dwell.attention.minutes=1`): the tile gains a ⏰ marker and thick accent border.
2. **New takeaway.** Switch to the **Takeaway** segment → **+ New takeaway** → the order screen
   opens on a counter. Add items and pay: confirm the quote shows **no service charge** (takeaway
   is `QUICK_SERVICE`). The counter tile never appears on the dine-in **Tables** grid.
3. **Takeaway list + resume.** Open two takeaway orders; both appear as rows under **Takeaway**
   with item count and dwell. Tap a row to resume that order.
4. **All counters busy.** Open takeaway orders on all three counters, then **+ New takeaway** →
   the error banner shows "All counters are busy" and no navigation occurs.
