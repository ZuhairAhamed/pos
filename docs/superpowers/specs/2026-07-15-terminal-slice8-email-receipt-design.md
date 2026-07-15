# Slice 8 — Email Receipt (Design)

**Date:** 2026-07-15
**Branch:** `feat/terminal-ui-restaurant-slice`
**Scope:** A cashier-initiated **Email receipt** action on the terminal payment
success screen, backed by a new fake `Emailer` port and a per-sale backend
endpoint that renders the receipt server-side. Single-sale closes only (retail +
whole-order dine-in).

## Goal

After a sale closes, the success screen offers **Email receipt** beside the
existing **Reprint** button. Tapping it reveals an inline dialog with a free-type
address field; **Send** POSTs to a sales endpoint that loads the stored sale,
renders the same receipt content the printer produces, and hands it to a fake
`Emailer`. The delivery adapter is a fake — a real SMTP adapter drops in later
with no change to callers, exactly like `InMemoryPrinter` / `FakeErpClient`.

## Decisions (settled during brainstorming)

| Decision | Choice |
|---|---|
| Trigger model | **Manual button on the success screen.** Cashier taps *Email receipt*, types an address, terminal POSTs to a backend endpoint. (Automatic-on-customer-email and auto+manual both rejected.) |
| Delivery port | **New fake `Emailer` port** (`Emailer.send(EmailMessage)`) with an `InMemoryEmailer` fake that logs and records sent mail. Matches the fakes-only convention. (Reusing `Notifier` and adding real SMTP now both rejected.) |
| Split scope | **Single-sale only.** The email button appears on the single-`SaleView` success screen (retail + whole-order dine-in close). Split-bill success (multiple sales) is out of scope; the endpoint is per-`saleId`, so split can be added later with no backend change. |
| Send semantics | **Synchronous, not via the outbox.** The sale already committed; emailing is a separate follow-up request the cashier waits on for a Sent/Failed result. Unlike print, failures are **not** swallowed — they propagate so the terminal can surface them. |
| Address source | **Free-type only, no prefill.** The terminal holds no customer email (`SaleView` carries none), so the dialog field starts empty. Prefill is out of scope. |

## Architecture

Everything reuses the existing receipt pipeline. `sales` already builds
`ReceiptData` from a stored sale for its `/reprint` endpoint and already depends
on `receipt :: api`; `receipt` already depends on `device` for `Printer`. The new
`Emailer` port sits in `device.api` beside `Printer`, so **no module's
`allowedDependencies` changes** and `ModularityTests` stays green. No schema
migration, no config keys, no new Maven dependency.

Server renders authoritatively: the terminal passes only an address. The endpoint
loads the sale, builds `ReceiptData`, and the `receipt` module renders the body —
the terminal never holds receipt content.

## Global Constraints (inherited by every task)

- **Terminal is a separate build**, not in the root reactor:
  `./mvnw -f pos-terminal/pom.xml clean test` (headless; no TestFX/display). Backend:
  `./mvnw test`. `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` first.
- **No new module dependencies.** The `Emailer` port rides existing edges
  (`receipt → device`, `sales → receipt`). No `allowedDependencies` edits; re-run
  `ModularityTests` after any change to confirm.
- **No schema migration, no config keys, no new Maven dependency** in this slice.
  The fake `Emailer` needs no SMTP settings.
- **Fakes-only convention.** `Emailer` is a port; `InMemoryEmailer` is its only
  implementation this slice, logging and recording sent mail for tests. A real
  `SmtpEmailer implements Emailer` replaces it later with no caller change.
- **Terminal holds no business rules and renders no receipt content.** It passes an
  address; the server renders. Money/enums cross as Strings in DTOs; DTOs carry
  `@JsonIgnoreProperties(ignoreUnknown = true)`.
- **MVVM sync-VM convention:** ViewModel logic is synchronous on the calling thread;
  the controller runs it off the FX thread via `FxTasks`. Plain fields are control-flow
  truth; the only observable written outside the FX thread is `errorMessage`, written
  inside `ui.accept(...)`. An async-dispatcher regression test is mandatory.
- **CSS uses existing emerald tokens / `derive()` only**; new tap targets ≥ 56px.
- Commit trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. Never stage
  or commit the pre-existing `M CLAUDE.md`.

## Part 1 — Backend

### 1.1 `Emailer` port + `InMemoryEmailer` fake

`device.api` gains:

```java
public record EmailMessage(String to, String subject, String body) {}

public interface Emailer {
    void send(EmailMessage message);
}
```

Both tagged under the existing `device` `api` `@NamedInterface`.

`device.infrastructure.InMemoryEmailer` (a `@Component`, package-private, beside
`InMemoryPrinter`): appends to a thread-safe `List<EmailMessage>`, logs
`log.info("EMAIL to={} subject={}", ...)`, and exposes `List<EmailMessage> sent()`
and `void clear()` for test inspection — mirroring `InMemoryNotifier`.

### 1.2 `receipt` module renders + sends

`ReceiptService` (`receipt.api`) gains:

```java
void emailReceipt(String to, ReceiptData data);
```

`DefaultReceiptService` injects `Emailer` (constructor arg alongside the existing
`Printer` + `ConfigurationService`). The existing print-line construction is
extracted into a shared `List<String> renderBody(ReceiptData)` (plain content
strings, no bold flag) so that:

- `print(ReceiptData)` maps each line to a `PrintLine` (as today), then `cut()`.
- `emailReceipt(to, data)` joins the same lines with `\n` into the body, builds the
  subject `"Your receipt " + data.receiptNumber()`, and calls
  `emailer.send(new EmailMessage(to, subject, body))`.

The emailed body therefore matches the printed slip line-for-line. Existing
`DefaultReceiptService` print tests guard the shared renderer against regression.

### 1.3 `sales` module exposes the action

`SalesService` (`sales.api`) gains:

```java
void emailReceipt(UUID saleId, String toAddress);
```

`DefaultSalesService.emailReceipt`:

1. Load the sale (`sales.findById(saleId).orElseThrow(() -> DomainException.notFound(...))`
   — same as `reprint`) → **404** when unknown.
2. Validate `toAddress`: reject blank / missing `@` via the existing validation
   exception (whatever `DomainException` variant maps to **400**; confirm the exact
   factory during planning) → **400**.
3. Build `ReceiptData` via a new private helper `buildReceiptData(sale, salePayments)`
   extracted from the current `printReceipt` body (lines 313–330). `printReceipt`
   is refactored to call the helper then `receipts.print(...)` inside its existing
   try/catch (print stays best-effort). `emailReceipt` calls the helper then
   `receipts.emailReceipt(toAddress, data)` **without** a swallowing try/catch —
   failures propagate to the endpoint.

`SalesController` gains:

```java
record EmailReceiptRequest(String email) {}

@PostMapping("/sales/{saleId}/send-receipt")
@ResponseStatus(HttpStatus.NO_CONTENT)
void sendReceipt(@PathVariable UUID saleId, @RequestBody EmailReceiptRequest body) {
    sales.emailReceipt(saleId, body.email());
}
```

No `@PreAuthorize` — matches `reprint` (any authenticated cashier).

## Part 2 — Terminal

### 2.1 `SalesApi.emailReceipt`

```java
public void emailReceipt(UUID saleId, String email) {
    client.post("/sales/" + saleId + "/send-receipt", new EmailReceiptRequest(email),
            new TypeReference<Void>() {});
}
```

`EmailReceiptRequest(String email)` is a small terminal request record. Used in
**both** payment modes — dine-in close also yields a real `saleId`, and the endpoint
is sales-owned. (If `ApiClient.post` cannot express a void/204 response cleanly,
add a void `post` overload or return-and-ignore; confirm during planning.)

### 2.2 `PaymentViewModel` — email action

Add an `EmailGateway` functional interface and a sync method:

```java
@FunctionalInterface
public interface EmailGateway {
    void email(UUID saleId, String toAddress);   // wired to salesApi::emailReceipt
}

/** Returns true when the address was accepted and the send call succeeded.
 *  Blank/malformed address or ApiException → sets errorMessage, returns false. */
public boolean emailReceipt(String toAddress) { ... }
```

`emailReceipt`:
- Reads the closed sale's id from the stored `SaleView` (the VM already holds the
  successful `sale`). If no sale yet, returns false (defensive; the button only
  shows post-success).
- Validates locally: blank or no `@` → `errorMessage` set inside `ui.accept(...)`,
  gateway **not** called, return false.
- Else calls `gateway.email(saleId, toAddress)`; on `ApiException` → `errorMessage`
  via the existing `messageOf` helper, return false; success → return true.

Synchronous on the calling thread (no `Instant.now()`, no observable writes except
`errorMessage` inside `ui.accept`). The existing constructor gains the `EmailGateway`
parameter; existing constructors/tests that build a `PaymentViewModel` get the new
arg (a no-op lambda in tests that don't exercise email).

### 2.3 Success screen: `payment.fxml` + `PaymentController`

- Add an **Email receipt** button beside the existing **Reprint** button in the
  success section.
- Add an inline **email dialog** region (hidden by default via `visible`/`managed`),
  containing a `TextField` (fx:id `emailField`, promptText `"name@example.com"`), a
  **Send** button, and a **Cancel** button. Tapping **Email receipt** shows the
  region and focuses the field; **Cancel** hides it and clears the field.
- **Send** → `FxTasks.run` of `vm.emailReceipt(emailField.getText())`; read a
  `boolean[] holder` in the FX-thread `onDone`: `true` → hide the dialog, clear the
  field, show a transient confirmation (`"Sent to " + address`); `false` → leave the
  dialog open (the VM already surfaced the error via `errorMessage`, bound to the
  existing error label). FX-threading follows the established pattern — VM call
  off-thread, result read only in `onDone`.
- Polling / navigation unaffected; this is a leaf action on the terminal success
  screen.

### 2.4 CSS

Reuse existing button and modal/emerald tokens. Only add a class if the inline
dialog needs distinct framing (e.g. `.email-dialog`), derived from existing tokens,
tap targets ≥ 56px. `.table-*` and other prior classes are untouched.

## Part 3 — Testing

**Backend:**
- `InMemoryEmailerTest` (or fold into an existing device fake test): `send` records
  the message; `sent()` returns it; `clear()` empties.
- `DefaultReceiptServiceTest`: `emailReceipt` calls the fake `Emailer` once with the
  expected `to`, a subject containing the receipt number, and a body whose lines match
  the printed content (assert against `InMemoryEmailer.sent()`).
- `DefaultSalesServiceTest`: `emailReceipt(saleId, addr)` on a seeded sale records one
  message; unknown `saleId` throws not-found; blank / malformed address throws the
  validation exception (no message recorded).
- `SalesController` web test (MockMvc): `POST /sales/{id}/send-receipt` → **204** and
  a recorded message for a valid address; malformed email → **400**; unknown sale →
  **404**.
- `ModularityTests` (must stay green with no `allowedDependencies` edits).

**Terminal (headless):**
- `SalesApiTest` (StubServer): `emailReceipt(id, addr)` POSTs `/sales/{id}/send-receipt`
  with body `{"email":"…"}`.
- `PaymentViewModelTest`: success path (gateway invoked, returns true); blank and
  no-`@` addresses short-circuit (gateway **not** invoked, `errorMessage` set, returns
  false); `ApiException` from the gateway → `errorMessage` set, returns false;
  async-dispatcher regression test (VM run under a deferred `ui` dispatcher).
- `FxmlContractTest`: new fx:ids (`emailReceiptButton`/equivalent, `emailField`, the
  dialog region, Send/Cancel). `AppCssTest`: any new class.
- Manual GUI E2E section in `pos-terminal/README.md`: close a retail sale → tap **Email
  receipt** → enter an address → **Send** → confirmation; repeat for a dine-in
  whole-order close; enter a blank/garbage address → inline error, dialog stays open.
  Run backend with `--spring.profiles.active=embedded,dev`, login `manager`/`manager`.

## Out of scope

Split-bill emailing (per-bill; the endpoint is already per-`saleId`, added later with
no backend change); automatic send on an attached customer's email; real SMTP delivery
(`SmtpEmailer implements Emailer` drops in later); customer-email prefill of the address
field; PDF/HTML bodies or attachments; email configuration keys; retry/outbox durability
for a failed send.
