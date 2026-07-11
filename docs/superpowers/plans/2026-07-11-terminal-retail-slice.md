# Terminal Retail Happy-Path Slice + Emerald Visual System — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Implementation of the CSS/screen visuals should be driven with the **frontend-design** skill.

**Goal:** Bring the retail / quick-service sales path to the JavaFX terminal (menu grid → live cart → pinned total bar → multi-tender payment → authoritative receipt) and rewrite `app.css` into a semantic emerald design system applied across every screen.

**Architecture:** Extends the existing hexagonal thin-client layering — typed `api/` facades → observable `viewmodel/` → thin FXML `view/` controllers; off-thread work via `FxTasks`; one shared `css/app.css`. New `CartApi` + `RetailViewModel` + `retail.fxml` mirror the dine-in `DiningApi`/`OrderViewModel`/`order.fxml` trio. Payment gains a `CheckoutGateway` seam so one `PaymentViewModel` serves both dine-in (`/dining/orders/{id}/close`) and retail (`/sales`) with multi-tender support.

**Tech Stack:** Java 21, JavaFX 21 (FXML + programmatic), Jackson 2.17, JUnit 5. Maven (`./mvnw -f pos-terminal/pom.xml`). No backend changes.

## Global Constraints

- **JDK 21 required.** Always `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` before any Maven command. System default is 17.
- **Money is `BigDecimal` at scale 2, never `double`.** New DTOs mirror the wire as `BigDecimal`.
- **Thin client.** No DB, no business rules, no backend changes. Authoritative money comes only from the server's `SaleView`; the cart shows a client-side pre-tax **"est."** only.
- **Never call `HttpClient`/`ApiClient` from a controller.** REST goes through `api/` facades; controllers dispatch VM calls off the FX thread via `FxTasks.run`; property writes go through the VM's injected UI dispatcher (`Platform::runLater` in production, `Runnable::run` in tests).
- **New DTOs use `@JsonIgnoreProperties(ignoreUnknown = true)`** (the wire carries more fields than the terminal reads).
- **Tests are headless** — JavaFX ViewModels + API clients against the in-JVM `StubServer`. No TestFX, no `Application.launch`. Controllers and FXML are **not** unit-tested (existing convention); their logic lives in tested ViewModels, and they are verified by `clean test` compiling + a manual smoke run.
- **`PaymentMethod` wire values are the strings `"CASH"`, `"CARD"`, `"WALLET"`** (terminal `TenderInput.method` is a `String`).
- **Status is always colour + word**, never colour alone (existing table-map convention).
- **Full build check after each task:** `./mvnw -f pos-terminal/pom.xml clean test` must stay green (baseline: 67 tests). `ModularityTests` is backend-only and untouched.

---

### Task 1: Emerald visual system rewrite (`app.css`)

Rewrite the single-blue token system into the semantic emerald palette and add the classes the retail screen + total bar will consume. This is a **styling** task; its guard test asserts the token/class contract so a later edit can't silently delete the design system.

**Files:**
- Modify: `pos-terminal/src/main/resources/css/app.css` (full rewrite of tokens; keep all existing screen classes, restyled)
- Create: `pos-terminal/src/main/resources/fonts/README.md` (drop-in hook for `.ttf` files)
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java`

**Interfaces:**
- Produces (CSS style classes later tasks attach in FXML/controllers): `.top-nav`, `.home-tile`, `.cat-drinks`, `.cat-food`, `.cat-merch`, `.cat-sides`, `.cat-other`, `.cart-pane`, `.cart-line`, `.qty-stepper`, `.total-bar`, `.total-bar-grand`, `.total-bar-pulse`, `.empty-cart`, `.search-field`, `.tender-cash`, `.tender-card`, `.tender-wallet`, `.tender-chip`, `.money` (tabular). Root looked-up colors: `-fx-canvas`, `-fx-card`, `-fx-ink`, `-fx-primary`, `-fx-primary-press`, `-fx-accent`, `-fx-success`, `-fx-danger`, `-fx-muted`, `-fx-border`.

- [ ] **Step 1: Write the failing guard test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java`:

```java
package com.company.pos.terminal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the shared stylesheet: guards that the semantic emerald token
 * system and the retail/total-bar style classes remain present. Not a visual test —
 * it asserts the design-system vocabulary later screens depend on cannot silently
 * vanish. Loading the resource also fails loudly if the file goes missing.
 */
class AppCssTest {

    private static String css() throws Exception {
        try (InputStream in = AppCssTest.class.getResourceAsStream("/css/app.css")) {
            assertNotNull(in, "app.css must be on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void definesSemanticEmeraldTokens() throws Exception {
        String css = css();
        assertTrue(css.contains("-fx-primary: #0E7C66"), "deep emerald primary token");
        assertTrue(css.contains("-fx-primary-press: #0A5E4D"), "primary pressed token");
        assertTrue(css.contains("-fx-accent: #E8A32C"), "amber accent token");
        assertTrue(css.contains("-fx-canvas: #F6F4EF"), "warm canvas token");
        assertTrue(css.contains("-fx-ink: #16202E"), "ink token");
    }

    @Test
    void definesCategoryAndTenderAndTotalBarClasses() throws Exception {
        String css = css();
        for (String cls : new String[] {
            ".cat-drinks", ".cat-food", ".cat-merch", ".cat-sides",
            ".tender-cash", ".tender-card", ".tender-wallet",
            ".total-bar", ".total-bar-grand", ".total-bar-pulse",
            ".cart-line", ".qty-stepper", ".empty-cart", ".home-tile", ".money"
        }) {
            assertTrue(css.contains(cls), "missing style class: " + cls);
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -f pos-terminal/pom.xml test -Dtest=AppCssTest
```
Expected: FAIL — the current `app.css` uses `-fx-primary: #1565c0` and has none of the new classes.

- [ ] **Step 3: Rewrite `app.css`**

Replace the whole file. Preserve every existing screen class (`.screen`, `.card`, `.title`, `.subtitle`, `.field-label`, `.field`, `.btn-primary`, `.btn-secondary`, `.pin-*`, `.error-banner`, `.table-*`, `.order-*`, `.menu-*`, `.modifier-*`, `.pay-*`) — restyled onto the new tokens — and add the new tokens/classes below. Use the frontend-design skill to make the restyle intentional. Key requirements, all of which the test and later tasks depend on:

```css
/*
 * app.css — single shared stylesheet. Semantic emerald design system: hue encodes
 * meaning (category edges, tender colours, status), professionalism from restraint.
 * No gradients on chrome, no decorative colour. Money uses tabular figures so columns
 * align. Fonts: a system stack now; drop real Space Grotesk/Inter .ttf into
 * resources/fonts/ and load via Font.loadFont to upgrade with no code change.
 */
.root {
    -fx-canvas: #F6F4EF;
    -fx-card: #FFFFFF;
    -fx-ink: #16202E;
    -fx-primary: #0E7C66;
    -fx-primary-press: #0A5E4D;
    -fx-accent: #E8A32C;
    -fx-success: #1E7A46;
    -fx-danger: #C0362C;
    -fx-text: #16202E;
    -fx-muted: #5F6B7A;
    -fx-border: #E2DED5;
    /* Aliases so the existing screens' -fx-surface/-fx-primary-hover refs keep working. */
    -fx-surface: #F6F4EF;
    -fx-primary-hover: #0A5E4D;
    -fx-font-size: 16px;
    -fx-font-family: "Inter", "Segoe UI", "Helvetica Neue", Arial, sans-serif;
}

/* Display face for titles/prices/totals; system fallback until .ttf is bundled. */
.display, .title, .total-bar-grand, .pay-grand-value {
    -fx-font-family: "Space Grotesk", "Archivo", "Segoe UI Semibold", "Inter", sans-serif;
}

/* Tabular figures for money so columns line up. */
.money { -fx-font-family: "Inter", "Segoe UI", monospace; }
```

Add the type scale (13/16/20/34/44), the top nav bar (`.top-nav` on `-fx-ink`), and these new blocks (exact class names required):

```css
/* ---- Home mode picker ---------------------------------------------------- */
.home-tile {
    -fx-min-width: 280px; -fx-min-height: 200px;
    -fx-background-color: -fx-card; -fx-background-radius: 16;
    -fx-border-color: -fx-border; -fx-border-radius: 16; -fx-border-width: 2;
    -fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;
    -fx-cursor: hand;
}
.home-tile:focused, .home-tile:pressed { -fx-border-color: -fx-primary; -fx-border-width: 3; }

/* ---- Category edges on white tiles (4px coloured edge) -------------------- */
.cat-drinks { -fx-border-color: transparent transparent transparent #1B8A9E; -fx-border-width: 0 0 0 4; }
.cat-food   { -fx-border-color: transparent transparent transparent #C0653A; -fx-border-width: 0 0 0 4; }
.cat-merch  { -fx-border-color: transparent transparent transparent #7A4FB5; -fx-border-width: 0 0 0 4; }
.cat-sides  { -fx-border-color: transparent transparent transparent #6B8E23; -fx-border-width: 0 0 0 4; }
.cat-other  { -fx-border-color: transparent transparent transparent -fx-border; -fx-border-width: 0 0 0 4; }

/* ---- Live cart ----------------------------------------------------------- */
.cart-pane { -fx-background-color: -fx-card; -fx-background-radius: 12; -fx-border-color: -fx-border; -fx-border-radius: 12; }
.cart-line { -fx-padding: 12 8 12 8; -fx-border-color: transparent transparent -fx-border transparent; -fx-border-width: 0 0 1 0; }
.qty-stepper {
    -fx-min-width: 48px; -fx-min-height: 48px; -fx-font-size: 22px; -fx-font-weight: bold;
    -fx-background-color: -fx-canvas; -fx-text-fill: -fx-ink; -fx-background-radius: 8;
    -fx-border-color: -fx-border; -fx-border-radius: 8; -fx-cursor: hand;
}
.empty-cart { -fx-font-size: 16px; -fx-text-fill: -fx-muted; -fx-alignment: center; -fx-padding: 48 16 48 16; }
.search-field { -fx-font-size: 16px; -fx-pref-height: 52px; }

/* ---- Signature total bar (pinned, ink) ----------------------------------- */
.total-bar { -fx-background-color: -fx-ink; -fx-background-radius: 12; -fx-padding: 16 20 16 20; }
.total-bar .pay-line-label, .total-bar .field-label { -fx-text-fill: derive(-fx-card, -20%); }
.total-bar-grand { -fx-font-size: 44px; -fx-font-weight: bold; -fx-text-fill: -fx-card; }
/* Static reduced-motion / peak-of-pulse highlight. */
.total-bar-pulse { -fx-background-color: -fx-accent; }
.total-bar-pulse .total-bar-grand { -fx-text-fill: -fx-ink; }

/* ---- Tender buttons (colour = method) ------------------------------------ */
.tender-cash   { -fx-background-color: #1E7A46; -fx-text-fill: white; }
.tender-card   { -fx-background-color: #2340C4; -fx-text-fill: white; }
.tender-wallet { -fx-background-color: #B0338C; -fx-text-fill: white; }
.tender-cash, .tender-card, .tender-wallet {
    -fx-font-size: 18px; -fx-font-weight: bold; -fx-min-height: 64px; -fx-background-radius: 8; -fx-cursor: hand;
}
.tender-chip { -fx-background-color: -fx-canvas; -fx-text-fill: -fx-ink; -fx-padding: 8 12 8 12; -fx-background-radius: 8; }
```

Restyle `.btn-primary` to `-fx-primary` / `:pressed -fx-primary-press` (keep the `:hover` rule harmless for mouse but rely on pressed/focused per the no-hover-reliance floor). Keep ≥48px tap targets and 8-multiple spacing.

- [ ] **Step 4: Create the font drop-in hook**

Create `pos-terminal/src/main/resources/fonts/README.md`:

```markdown
# Bundled fonts (drop-in)

JavaFX needs local font files, not web fonts. Drop `SpaceGrotesk-*.ttf` (or
`Archivo-*.ttf`) and `Inter-*.ttf` here, then load them once at startup in
`PosTerminalApp.init()`:

```java
javafx.scene.text.Font.loadFont(getClass().getResourceAsStream("/fonts/Inter-Regular.ttf"), 16);
javafx.scene.text.Font.loadFont(getClass().getResourceAsStream("/fonts/SpaceGrotesk-Bold.ttf"), 44);
```

`app.css` already names "Inter" / "Space Grotesk" first in its font stacks, so the
bundled faces take effect automatically once loaded. Both families are OFL-licensed
and redistributable. Until then the system fallback stack is used.
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
./mvnw -f pos-terminal/pom.xml test -Dtest=AppCssTest
```
Expected: PASS.

- [ ] **Step 6: Full build to confirm nothing else broke**

```bash
./mvnw -f pos-terminal/pom.xml clean test
```
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add pos-terminal/src/main/resources/css/app.css \
        pos-terminal/src/main/resources/fonts/README.md \
        pos-terminal/src/test/java/com/company/pos/terminal/AppCssTest.java
git commit -m "feat(terminal): emerald semantic design system in app.css

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `ProductView.barcode` + `MenuCache.skuForBarcode`

Give the terminal the barcode field (currently dropped) and a client-side barcode→sku lookup, since the backend `?q=` search matches name+SKU only.

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ProductView.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/order/MenuCache.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/order/MenuCacheTest.java` (create)
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/CatalogApiTest.java` (extend — add barcode assertion)

**Interfaces:**
- Produces: `ProductView(String sku, String name, String categoryName, String barcode, BigDecimal unitPrice)`; `MenuCache.skuForBarcode(String barcode)` → sku `String` or `null`.
- Consumes: nothing new.

- [ ] **Step 1: Write the failing test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/order/MenuCacheTest.java`:

```java
package com.company.pos.terminal.order;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.ProductView;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuCacheTest {

    private static ProductView p(String sku, String cat, String barcode, String price) {
        return new ProductView(sku, sku + " name", cat, barcode, new BigDecimal(price));
    }

    @Test
    void skuForBarcodeResolvesKnownBarcode() {
        MenuCache cache = new MenuCache(List.of(
                p("LATTE", "Drinks", "6291041500213", "12.00"),
                p("FRIES", "Sides", "6291041500299", "8.00")));
        assertEquals("FRIES", cache.skuForBarcode("6291041500299"));
    }

    @Test
    void skuForBarcodeReturnsNullForUnknownOrBlank() {
        MenuCache cache = new MenuCache(List.of(p("LATTE", "Drinks", "6291041500213", "12.00")));
        assertNull(cache.skuForBarcode("0000000000000"));
        assertNull(cache.skuForBarcode(""));
        assertNull(cache.skuForBarcode(null));
    }

    @Test
    void productsWithNullBarcodeAreIndexedByCategoryButNotBarcode() {
        MenuCache cache = new MenuCache(List.of(p("LATTE", "Drinks", null, "12.00")));
        assertEquals(1, cache.productsInCategory("Drinks").size());
        assertNull(cache.skuForBarcode("anything"));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -f pos-terminal/pom.xml test -Dtest=MenuCacheTest
```
Expected: FAIL — `ProductView` has no 5-arg constructor with barcode; `skuForBarcode` does not exist.

- [ ] **Step 3: Add `barcode` to `ProductView`**

Replace the record in `ProductView.java`:

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductView(String sku, String name, String categoryName, String barcode,
        BigDecimal unitPrice) {
}
```

- [ ] **Step 4: Add barcode indexing to `MenuCache`**

In `MenuCache.java`, add a barcode index and lookup. Add the field beside `bySku`:

```java
    private final Map<String, ProductView> byBarcode = new LinkedHashMap<>();
```

In the constructor loop, after `bySku.put(...)`, add:

```java
                if (p.barcode() != null && !p.barcode().isBlank()) {
                    byBarcode.put(p.barcode(), p);
                }
```

Add the lookup method:

```java
    /** Sku for an exact barcode, or {@code null} for unknown/blank input. */
    public String skuForBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return null;
        }
        ProductView p = byBarcode.get(barcode);
        return p == null ? null : p.sku();
    }
```

- [ ] **Step 5: Fix the existing `ProductView` call site in `CatalogApiTest`**

The 4-arg `ProductView` constructor is gone. Open `pos-terminal/src/test/java/com/company/pos/terminal/api/CatalogApiTest.java`, find where it asserts parsed products, and add a barcode assertion using a product JSON that includes `"barcode"`. If the test builds `ProductView` directly anywhere, update to the 5-arg form. Add this assertion to the parse test (adjust the fx:id/variable to the test's existing parsed product):

```java
        assertEquals("6291041500213", products.get(0).barcode());
```
and ensure the stub JSON for that product contains `"barcode":"6291041500213"`.

- [ ] **Step 6: Run tests to verify they pass**

```bash
./mvnw -f pos-terminal/pom.xml test -Dtest=MenuCacheTest,CatalogApiTest
```
Expected: PASS. If any other file used the old 4-arg `ProductView` constructor, the compiler will name it — fix those to pass `null`/a barcode as the 4th arg.

- [ ] **Step 7: Full build**

```bash
./mvnw -f pos-terminal/pom.xml clean test
```
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ProductView.java \
        pos-terminal/src/main/java/com/company/pos/terminal/order/MenuCache.java \
        pos-terminal/src/test/java/com/company/pos/terminal/order/MenuCacheTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/CatalogApiTest.java
git commit -m "feat(terminal): ProductView.barcode + client-side barcode lookup

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Cart DTOs + `CartApi`

The typed REST facade for the retail cart path. `CartView` carries lines only (no totals — the terminal estimates). `PUT /carts/{id}/lines/{lineId}` takes a JSON body `{quantity}` (unlike dining's query param). `DELETE` returns the cart body, but `ApiClient.delete` is void — so re-fetch via `GET`, exactly like `DiningApi.removeLine`.

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/CartView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/CartLineView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/CartLineModifierView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/CartLineRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/QuantityRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/CartApi.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/CartApiTest.java`

**Interfaces:**
- Produces:
  - `CartView(UUID cartId, String status, String currencyCode, UUID customerId, List<CartLineView> lines)`
  - `CartLineView(UUID lineId, String sku, String name, BigDecimal quantity, BigDecimal basePrice, BigDecimal unitPrice, String currencyCode, List<CartLineModifierView> modifiers)`
  - `CartLineModifierView(UUID optionId, String name, BigDecimal priceDelta)`
  - `CartApi.createCart()` → `UUID`; `getCart(UUID)` → `CartView`; `addLine(UUID cartId, String sku, BigDecimal qty, List<UUID> optionIds)` → `CartView`; `updateLine(UUID cartId, UUID lineId, BigDecimal qty)` → `CartView`; `removeLine(UUID cartId, UUID lineId)` → `CartView`.
- Consumes: `ApiClient` (constructor injection, mirrors `DiningApi`).

- [ ] **Step 1: Write the failing test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/api/CartApiTest.java`:

```java
package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.CartView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CartApiTest {

    private static final UUID CART_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LINE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OPT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final String CART_JSON =
            "{\"cartId\":\"11111111-1111-1111-1111-111111111111\",\"status\":\"OPEN\","
            + "\"currencyCode\":\"SAR\",\"customerId\":null,\"lines\":["
            + "{\"lineId\":\"22222222-2222-2222-2222-222222222222\",\"sku\":\"LATTE\",\"name\":\"Latte\","
            + "\"quantity\":2,\"basePrice\":12.00,\"unitPrice\":14.00,\"currencyCode\":\"SAR\","
            + "\"modifiers\":[{\"optionId\":\"33333333-3333-3333-3333-333333333333\","
            + "\"name\":\"Oat milk\",\"priceDelta\":2.00}]}]}";

    @Test
    void createCartParsesCartId() throws Exception {
        try (StubServer stub = new StubServer(201,
                "{\"cartId\":\"11111111-1111-1111-1111-111111111111\"}", "application/json")) {
            CartApi api = new CartApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            assertEquals(CART_ID, api.createCart());
            assertEquals("POST", stub.lastMethod);
            assertEquals("/carts", stub.lastPath);
        }
    }

    @Test
    void addLinePostsSkuQtyAndModifiers() throws Exception {
        try (StubServer stub = new StubServer(200, CART_JSON, "application/json")) {
            CartApi api = new CartApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            CartView v = api.addLine(CART_ID, "LATTE", new BigDecimal("2"), List.of(OPT_ID));
            assertEquals(1, v.lines().size());
            assertEquals("Latte", v.lines().get(0).name());
            assertEquals(0, new BigDecimal("14.00").compareTo(v.lines().get(0).unitPrice()));
            assertEquals("Oat milk", v.lines().get(0).modifiers().get(0).name());
            assertEquals("POST", stub.lastMethod);
            assertEquals("/carts/11111111-1111-1111-1111-111111111111/lines", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"sku\":\"LATTE\""));
            assertTrue(stub.lastBody.contains("\"quantity\":2"));
            assertTrue(stub.lastBody.contains("modifierOptionIds"));
        }
    }

    @Test
    void updateLinePutsQuantityAsBody() throws Exception {
        try (StubServer stub = new StubServer(200, CART_JSON, "application/json")) {
            CartApi api = new CartApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            api.updateLine(CART_ID, LINE_ID, new BigDecimal("3"));
            assertEquals("PUT", stub.lastMethod);
            assertEquals("/carts/11111111-1111-1111-1111-111111111111/lines/"
                    + "22222222-2222-2222-2222-222222222222", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"quantity\":3"));
        }
    }

    @Test
    void removeLineDeletesThenRefetches() throws Exception {
        try (StubServer stub = new StubServer(200, CART_JSON, "application/json")) {
            CartApi api = new CartApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            CartView v = api.removeLine(CART_ID, LINE_ID);
            assertEquals("OPEN", v.status());
            String linePath = "/carts/11111111-1111-1111-1111-111111111111/lines/"
                    + "22222222-2222-2222-2222-222222222222";
            assertNotNull(stub.requestTo("DELETE", linePath));
            assertNotNull(stub.requestTo("GET", "/carts/11111111-1111-1111-1111-111111111111"));
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
./mvnw -f pos-terminal/pom.xml test -Dtest=CartApiTest
```
Expected: FAIL — `CartApi`, `CartView`, etc. do not exist (compile error).

- [ ] **Step 3: Create the DTOs**

`CartLineModifierView.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CartLineModifierView(UUID optionId, String name, BigDecimal priceDelta) {
}
```

`CartLineView.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CartLineView(UUID lineId, String sku, String name, BigDecimal quantity,
        BigDecimal basePrice, BigDecimal unitPrice, String currencyCode,
        List<CartLineModifierView> modifiers) {
}
```

`CartView.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CartView(UUID cartId, String status, String currencyCode, UUID customerId,
        List<CartLineView> lines) {
}
```

`CartLineRequest.java`:

```java
package com.company.pos.terminal.api.dto;

import java.math.BigDecimal;
import java.util.List;

/** POST /carts/{id}/lines body. modifierOptionIds may be empty. */
public record CartLineRequest(String sku, BigDecimal quantity, List<java.util.UUID> modifierOptionIds) {
}
```

`QuantityRequest.java`:

```java
package com.company.pos.terminal.api.dto;

import java.math.BigDecimal;

/** PUT /carts/{id}/lines/{lineId} body. */
public record QuantityRequest(BigDecimal quantity) {
}
```

- [ ] **Step 4: Create `CartApi`**

`pos-terminal/src/main/java/com/company/pos/terminal/api/CartApi.java`:

```java
package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.CartLineRequest;
import com.company.pos.terminal.api.dto.CartView;
import com.company.pos.terminal.api.dto.QuantityRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Typed client for the store server's {@code /carts} retail endpoints. */
public class CartApi {

    private final ApiClient client;

    public CartApi(ApiClient client) {
        this.client = client;
    }

    /** POST /carts → {@code {"cartId": ...}}; returns the new cart id. */
    public UUID createCart() {
        Map<String, UUID> res =
                client.post("/carts", null, new TypeReference<Map<String, UUID>>() {});
        return res.get("cartId");
    }

    public CartView getCart(UUID cartId) {
        return client.get("/carts/" + cartId, new TypeReference<CartView>() {});
    }

    public CartView addLine(UUID cartId, String sku, BigDecimal qty, List<UUID> optionIds) {
        CartLineRequest body = new CartLineRequest(sku, qty, optionIds == null ? List.of() : optionIds);
        return client.post("/carts/" + cartId + "/lines", body, new TypeReference<CartView>() {});
    }

    public CartView updateLine(UUID cartId, UUID lineId, BigDecimal qty) {
        return client.put("/carts/" + cartId + "/lines/" + lineId, new QuantityRequest(qty),
                new TypeReference<CartView>() {});
    }

    /** DELETE returns the cart server-side, but {@link ApiClient#delete} is void, so re-fetch. */
    public CartView removeLine(UUID cartId, UUID lineId) {
        client.delete("/carts/" + cartId + "/lines/" + lineId);
        return getCart(cartId);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
./mvnw -f pos-terminal/pom.xml test -Dtest=CartApiTest
```
Expected: PASS.

- [ ] **Step 6: Wire `CartApi` into `Services`**

In `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`, add the import `com.company.pos.terminal.api.CartApi`, the field `public final CartApi cartApi;`, and in the constructor `this.cartApi = new CartApi(apiClient);`.

- [ ] **Step 7: Full build**

```bash
./mvnw -f pos-terminal/pom.xml clean test
```
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/CartApi.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/Cart*.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/QuantityRequest.java \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/CartApiTest.java
git commit -m "feat(terminal): CartApi + cart DTOs for the retail path

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Expand `SaleView` receipt DTOs

The completed-transaction screen must show lines-with-modifiers, discount total, and per-tender payments. The terminal `SaleView` is currently a 7-field subset — extend it with `lines`, `payments`, and `discountTotal`, and add the supporting DTOs. This changes the `SaleView` constructor, so the one factory that builds it in `PaymentViewModelTest` is updated here.

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/SaleView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/SaleLineView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/SaleLineModifierView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/SalePaymentView.java`
- Modify: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/PaymentViewModelTest.java` (update `sale28_75()` factory to the new constructor)
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/SaleViewParseTest.java` (create)

**Interfaces:**
- Produces:
  - `SaleView(UUID id, String receiptNumber, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal serviceChargeAmount, BigDecimal grandTotal, String currencyCode, BigDecimal discountTotal, List<SaleLineView> lines, List<SalePaymentView> payments)`
  - `SaleLineView(String sku, String name, BigDecimal quantity, BigDecimal lineTotal, List<SaleLineModifierView> modifiers)`
  - `SaleLineModifierView(String name, BigDecimal priceDelta)`
  - `SalePaymentView(String method, BigDecimal amount, BigDecimal tendered, BigDecimal changeGiven)`

- [ ] **Step 1: Write the failing parse test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/api/SaleViewParseTest.java`:

```java
package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.SaleView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SaleViewParseTest {

    private static final String JSON =
            "{\"id\":\"55555555-5555-5555-5555-555555555555\",\"receiptNumber\":\"S01-T01-7\","
            + "\"status\":\"COMPLETED\",\"currencyCode\":\"SAR\",\"subtotal\":25.00,\"taxTotal\":3.75,"
            + "\"grandTotal\":28.75,\"createdAt\":\"2026-07-11T10:30:00Z\","
            + "\"discountTotal\":1.00,\"txnDiscountAmount\":null,\"serviceChargeAmount\":0.00,"
            + "\"lines\":[{\"lineNo\":1,\"sku\":\"LATTE\",\"name\":\"Latte\",\"quantity\":2,"
            + "\"unitPrice\":14.00,\"lineTotal\":28.00,\"currencyCode\":\"SAR\","
            + "\"modifiers\":[{\"name\":\"Oat milk\",\"priceDelta\":2.00}]}],"
            + "\"payments\":[{\"method\":\"CASH\",\"amount\":28.75,\"tendered\":30.00,\"changeGiven\":1.25}]}";

    @Test
    void parsesLinesPaymentsAndDiscountTolerantOfExtraFields() throws Exception {
        ObjectMapper mapper = ApiClient.defaultMapper();
        SaleView v = mapper.readValue(JSON, new TypeReference<SaleView>() {});
        assertEquals("S01-T01-7", v.receiptNumber());
        assertEquals(0, new java.math.BigDecimal("1.00").compareTo(v.discountTotal()));
        assertEquals(1, v.lines().size());
        assertEquals("Latte", v.lines().get(0).name());
        assertEquals("Oat milk", v.lines().get(0).modifiers().get(0).name());
        assertEquals(1, v.payments().size());
        assertEquals("CASH", v.payments().get(0).method());
        assertEquals(0, new java.math.BigDecimal("1.25").compareTo(v.payments().get(0).changeGiven()));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -f pos-terminal/pom.xml test -Dtest=SaleViewParseTest
```
Expected: FAIL — `SaleView` has no `discountTotal()/lines()/payments()`; supporting DTOs missing.

- [ ] **Step 3: Create the supporting DTOs**

`SaleLineModifierView.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SaleLineModifierView(String name, BigDecimal priceDelta) {
}
```

`SaleLineView.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SaleLineView(String sku, String name, BigDecimal quantity, BigDecimal lineTotal,
        List<SaleLineModifierView> modifiers) {
}
```

`SalePaymentView.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SalePaymentView(String method, BigDecimal amount, BigDecimal tendered,
        BigDecimal changeGiven) {
}
```

- [ ] **Step 4: Expand `SaleView`**

Replace the record in `SaleView.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SaleView(UUID id, String receiptNumber, BigDecimal subtotal, BigDecimal taxTotal,
        BigDecimal serviceChargeAmount, BigDecimal grandTotal, String currencyCode,
        BigDecimal discountTotal, List<SaleLineView> lines, List<SalePaymentView> payments) {
}
```

- [ ] **Step 5: Update the `sale28_75()` factory in `PaymentViewModelTest`**

The old 7-arg constructor no longer compiles. In `PaymentViewModelTest.java` replace the factory:

```java
    private static SaleView sale28_75() {
        return new SaleView(UUID.randomUUID(), "S01-T01-1", new BigDecimal("25.00"),
                new BigDecimal("3.75"), BigDecimal.ZERO, new BigDecimal("28.75"), "SAR",
                BigDecimal.ZERO, java.util.List.of(), java.util.List.of());
    }
```

If any other test/source built a `SaleView` with the old constructor, the compiler will list it — update those call sites to append `BigDecimal.ZERO, List.of(), List.of()`.

- [ ] **Step 6: Run tests to verify they pass**

```bash
./mvnw -f pos-terminal/pom.xml test -Dtest=SaleViewParseTest,PaymentViewModelTest,DiningApiTest
```
Expected: PASS.

- [ ] **Step 7: Full build**

```bash
./mvnw -f pos-terminal/pom.xml clean test
```
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/dto/Sale*.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/PaymentViewModelTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/SaleViewParseTest.java
git commit -m "feat(terminal): expand SaleView with lines, payments, discountTotal

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: `SalesApi.checkout` (retail `POST /sales`)

Add the retail checkout call. Body mirrors the backend `CheckoutCommand` shape used by dining's `CloseOrderRequest` (opaque discount fields — this slice sends none).

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/api/SalesApi.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/CheckoutRequest.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/SalesApiTest.java` (create)

**Interfaces:**
- Produces:
  - `CheckoutRequest(UUID cartId, List<TenderInput> tenders, Map<String,Object> lineDiscounts, Object transactionDiscount, boolean applyServiceCharge)`
  - `SalesApi.checkout(CheckoutRequest req)` → `SaleView`

- [ ] **Step 1: Write the failing test**

Create `pos-terminal/src/test/java/com/company/pos/terminal/api/SalesApiTest.java`:

```java
package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.CheckoutRequest;
import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.api.dto.TenderInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SalesApiTest {

    private static final UUID CART_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final String SALE_JSON =
            "{\"id\":\"55555555-5555-5555-5555-555555555555\",\"receiptNumber\":\"S01-T01-9\","
            + "\"status\":\"COMPLETED\",\"currencyCode\":\"SAR\",\"subtotal\":25.00,\"taxTotal\":3.75,"
            + "\"grandTotal\":28.75,\"discountTotal\":0.00,\"serviceChargeAmount\":0.00,"
            + "\"lines\":[],\"payments\":[]}";

    @Test
    void checkoutPostsCartIdAndTendersAndParsesSale() throws Exception {
        try (StubServer stub = new StubServer(201, SALE_JSON, "application/json")) {
            SalesApi api = new SalesApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            CheckoutRequest req = new CheckoutRequest(CART_ID,
                    List.of(new TenderInput("CASH", new BigDecimal("28.75"), new BigDecimal("30.00"))),
                    Map.of(), null, false);
            SaleView s = api.checkout(req);
            assertEquals("S01-T01-9", s.receiptNumber());
            assertEquals("POST", stub.lastMethod);
            assertEquals("/sales", stub.lastPath);
            assertTrue(stub.lastBody.contains("\"cartId\":\"11111111-1111-1111-1111-111111111111\""));
            assertTrue(stub.lastBody.contains("\"method\":\"CASH\""));
            assertTrue(stub.lastBody.contains("tenders"));
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
./mvnw -f pos-terminal/pom.xml test -Dtest=SalesApiTest
```
Expected: FAIL — `CheckoutRequest` and `SalesApi.checkout` do not exist.

- [ ] **Step 3: Create `CheckoutRequest`**

`pos-terminal/src/main/java/com/company/pos/terminal/api/dto/CheckoutRequest.java`:

```java
package com.company.pos.terminal.api.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * POST /sales body. Mirrors the backend CheckoutCommand. Discounts are opaque here
 * (this slice sends {@code Map.of()} / {@code null}); applyServiceCharge is forced false
 * server-side for retail regardless, but we send false for honesty.
 */
public record CheckoutRequest(UUID cartId, List<TenderInput> tenders,
        Map<String, Object> lineDiscounts, Object transactionDiscount, boolean applyServiceCharge) {
}
```

- [ ] **Step 4: Add `checkout` to `SalesApi`**

In `SalesApi.java` add the import `com.company.pos.terminal.api.dto.CheckoutRequest;` and `com.company.pos.terminal.api.dto.SaleView;`, then the method:

```java
    /** POST /sales — retail checkout of a cart; returns the authoritative SaleView. */
    public SaleView checkout(CheckoutRequest req) {
        return client.post("/sales", req, new com.fasterxml.jackson.core.type.TypeReference<SaleView>() {});
    }
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
./mvnw -f pos-terminal/pom.xml test -Dtest=SalesApiTest
```
Expected: PASS.

- [ ] **Step 6: Full build**

```bash
./mvnw -f pos-terminal/pom.xml clean test
```
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/SalesApi.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/CheckoutRequest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/SalesApiTest.java
git commit -m "feat(terminal): SalesApi.checkout for retail POST /sales

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Multi-tender + retail-capable `PaymentViewModel`

Refactor `PaymentViewModel` around a `CheckoutGateway` seam so one VM serves dine-in (`dining.close`) and retail (`sales.checkout`), and support **multiple tenders on one sale** plus the `WALLET` method. Keep the short-cash guard. This rewrites the VM and its test.

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/PaymentViewModel.java`
- Modify: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/PaymentViewModelTest.java`

**Interfaces:**
- Produces:
  - `PaymentViewModel.CheckoutGateway` — functional interface `SaleView checkout(List<TenderInput> tenders)`.
  - `new PaymentViewModel(CheckoutGateway gateway, SalesApi sales, BigDecimal estimatedTotal, Consumer<Runnable> ui)` (+ a 3-arg convenience with `ui = Runnable::run`).
  - `ObservableList<TenderInput> tenders()`; `ReadOnlyStringProperty remainingText()`, `changeText()`, `errorMessage()`; `ReadOnlyObjectProperty<SaleView> sale()`; `ReadOnlyBooleanProperty paid()`.
  - `void addTender(String method, BigDecimal amount, BigDecimal cashTendered)`; `void payFull(String method, BigDecimal cashTendered)`; `void finalizeSale()`; `void reprint()`.
- Consumes: `SalesApi` (Task 5), expanded `SaleView` (Task 4), `TenderInput`.

- [ ] **Step 1: Rewrite the test**

Replace `PaymentViewModelTest.java` with a gateway-based version (keeps the same coverage plus multi-tender):

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.SalesApi;
import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.api.dto.TenderInput;
import com.company.pos.terminal.viewmodel.PaymentViewModel.CheckoutGateway;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentViewModelTest {

    private static SaleView sale28_75() {
        return new SaleView(UUID.randomUUID(), "S01-T01-1", new BigDecimal("25.00"),
                new BigDecimal("3.75"), BigDecimal.ZERO, new BigDecimal("28.75"), "SAR",
                BigDecimal.ZERO, List.of(), List.of());
    }

    /** Records tenders and returns a fixed sale. */
    private static final class RecordingGateway implements CheckoutGateway {
        List<TenderInput> received;
        int calls;
        @Override public SaleView checkout(List<TenderInput> tenders) {
            received = new ArrayList<>(tenders);
            calls++;
            return sale28_75();
        }
    }

    @Test
    void shortCashRejectedBeforeCheckout() {
        RecordingGateway gw = new RecordingGateway();
        PaymentViewModel vm = new PaymentViewModel(gw, null, new BigDecimal("28.75"));
        vm.payFull("CASH", new BigDecimal("20.00"));
        assertEquals(0, gw.calls, "checkout must not run on a short tender");
        assertTrue(vm.errorMessage().get().toLowerCase().contains("insufficient"));
        assertNull(vm.sale().get());
        assertFalse(vm.paid().get());
    }

    @Test
    void fullCashCheckoutComputesChange() {
        RecordingGateway gw = new RecordingGateway();
        PaymentViewModel vm = new PaymentViewModel(gw, null, new BigDecimal("28.75"));
        vm.payFull("CASH", new BigDecimal("30.00"));
        assertTrue(vm.paid().get());
        assertEquals(1, gw.calls);
        assertEquals("CASH", gw.received.get(0).method());
        assertEquals(new BigDecimal("28.75"), gw.received.get(0).amount());
        assertEquals(new BigDecimal("30.00"), gw.received.get(0).tendered());
        assertTrue(vm.changeText().get().contains("1.25"));
    }

    @Test
    void walletFullCheckout() {
        RecordingGateway gw = new RecordingGateway();
        PaymentViewModel vm = new PaymentViewModel(gw, null, new BigDecimal("28.75"));
        vm.payFull("WALLET", null);
        assertTrue(vm.paid().get());
        assertEquals("WALLET", gw.received.get(0).method());
        assertEquals(new BigDecimal("28.75"), gw.received.get(0).amount());
        assertNull(gw.received.get(0).tendered());
    }

    @Test
    void splitCardThenCashFinalizesWhenCovered() {
        RecordingGateway gw = new RecordingGateway();
        PaymentViewModel vm = new PaymentViewModel(gw, null, new BigDecimal("28.75"));
        vm.addTender("CARD", new BigDecimal("20.00"), null);
        assertFalse(vm.paid().get(), "not covered yet");
        assertTrue(vm.remainingText().get().contains("8.75"));
        assertEquals(0, gw.calls);
        vm.addTender("CASH", new BigDecimal("8.75"), new BigDecimal("10.00"));
        vm.finalizeSale();
        assertTrue(vm.paid().get());
        assertEquals(2, gw.received.size());
        assertTrue(vm.changeText().get().contains("1.25"));
    }

    @Test
    void finalizeRejectedWhenUnderTendered() {
        RecordingGateway gw = new RecordingGateway();
        PaymentViewModel vm = new PaymentViewModel(gw, null, new BigDecimal("28.75"));
        vm.addTender("CARD", new BigDecimal("10.00"), null);
        vm.finalizeSale();
        assertFalse(vm.paid().get());
        assertEquals(0, gw.calls);
        assertTrue(vm.errorMessage().get().toLowerCase().contains("remaining"));
    }

    @Test
    void checkoutFailureSurfacesErrorAndDoesNotMarkPaid() {
        CheckoutGateway gw = tenders -> { throw new ApiException(409, null, "cart already closed"); };
        PaymentViewModel vm = new PaymentViewModel(gw, null, new BigDecimal("28.75"));
        vm.payFull("CARD", null);
        assertFalse(vm.paid().get());
        assertNull(vm.sale().get());
        assertEquals("cart already closed", vm.errorMessage().get());
    }

    @Test
    void reprintCallsSalesApiWithSaleId() {
        RecordingGateway gw = new RecordingGateway();
        List<UUID> reprinted = new ArrayList<>();
        SalesApi sales = new SalesApi(null) {
            @Override public void reprint(UUID saleId) { reprinted.add(saleId); }
        };
        PaymentViewModel vm = new PaymentViewModel(gw, sales, new BigDecimal("28.75"));
        vm.payFull("CARD", null);
        vm.reprint();
        assertEquals(1, reprinted.size());
        assertEquals(vm.sale().get().id(), reprinted.get(0));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
./mvnw -f pos-terminal/pom.xml test -Dtest=PaymentViewModelTest
```
Expected: FAIL — the gateway constructor and `payFull/addTender/finalizeSale/remainingText/tenders` do not exist.

- [ ] **Step 3: Rewrite `PaymentViewModel`**

Replace the class body with the multi-tender, gateway-driven version:

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.SalesApi;
import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.api.dto.TenderInput;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * ViewModel that takes payment against an estimated total and exposes the authoritative
 * {@link SaleView}. It is checkout-mechanism agnostic: a {@link CheckoutGateway} performs
 * the actual server call (dine-in close or retail /sales), so this class holds only tender
 * accumulation, the short-cash guard, and change/remaining math. Unit-testable without FX;
 * every method runs synchronously on the caller (the controller runs it off the FX thread).
 *
 * <p>Multiple tenders per sale are supported: {@link #addTender} appends a tender and updates
 * {@link #remainingText()}; {@link #finalizeSale()} refuses while any amount remains due.
 * {@link #payFull} is the one-tap path (tender the whole remaining amount, then finalize).
 */
public class PaymentViewModel {

    /** Performs the actual checkout for the collected tenders and returns the authoritative sale. */
    @FunctionalInterface
    public interface CheckoutGateway {
        SaleView checkout(List<TenderInput> tenders);
    }

    private final CheckoutGateway gateway;
    private final SalesApi sales;
    private final BigDecimal estimatedTotal;
    private final Consumer<Runnable> ui;

    private final ObservableList<TenderInput> tenders = FXCollections.observableArrayList();
    private final ReadOnlyStringWrapper remainingText = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper changeText = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");
    private final ReadOnlyObjectWrapper<SaleView> sale = new ReadOnlyObjectWrapper<>(null);
    private final ReadOnlyBooleanWrapper paid = new ReadOnlyBooleanWrapper(false);

    public PaymentViewModel(CheckoutGateway gateway, SalesApi sales, BigDecimal estimatedTotal) {
        this(gateway, sales, estimatedTotal, Runnable::run);
    }

    public PaymentViewModel(CheckoutGateway gateway, SalesApi sales, BigDecimal estimatedTotal,
            Consumer<Runnable> ui) {
        this.gateway = gateway;
        this.sales = sales;
        this.estimatedTotal = estimatedTotal.setScale(2, RoundingMode.HALF_UP);
        this.ui = ui;
        this.remainingText.set(this.estimatedTotal.toPlainString());
    }

    public ObservableList<TenderInput> tenders() { return tenders; }
    public ReadOnlyStringProperty remainingText() { return remainingText.getReadOnlyProperty(); }
    public ReadOnlyStringProperty changeText() { return changeText.getReadOnlyProperty(); }
    public ReadOnlyStringProperty errorMessage() { return errorMessage.getReadOnlyProperty(); }
    public ReadOnlyObjectProperty<SaleView> sale() { return sale.getReadOnlyProperty(); }
    public ReadOnlyBooleanProperty paid() { return paid.getReadOnlyProperty(); }

    public BigDecimal remaining() {
        BigDecimal covered = BigDecimal.ZERO;
        for (TenderInput t : tenders) {
            covered = covered.add(t.amount());
        }
        BigDecimal rem = estimatedTotal.subtract(covered);
        return rem.signum() < 0 ? BigDecimal.ZERO : rem;
    }

    /**
     * Appends one tender toward the total. {@code amount} is what this tender covers;
     * for CASH, {@code cashTendered} is the money handed over and must be ≥ amount.
     * Rejects (no append) on non-positive amount or short cash.
     */
    public void addTender(String method, BigDecimal amount, BigDecimal cashTendered) {
        BigDecimal amt = (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
        if (amt.signum() <= 0) {
            ui.accept(() -> errorMessage.set("Enter a tender amount"));
            return;
        }
        BigDecimal tendered = null;
        if ("CASH".equals(method)) {
            tendered = (cashTendered == null ? BigDecimal.ZERO : cashTendered).setScale(2, RoundingMode.HALF_UP);
            if (tendered.compareTo(amt) < 0) {
                ui.accept(() -> errorMessage.set("Insufficient cash tendered"));
                return;
            }
        }
        TenderInput t = new TenderInput(method, amt, tendered);
        String rem = estimatedTotal.subtract(coveredIncluding(amt)).max(BigDecimal.ZERO).toPlainString();
        ui.accept(() -> {
            tenders.add(t);
            remainingText.set(rem);
            errorMessage.set("");
        });
    }

    private BigDecimal coveredIncluding(BigDecimal extra) {
        BigDecimal covered = extra;
        for (TenderInput t : tenders) {
            covered = covered.add(t.amount());
        }
        return covered;
    }

    /** One-tap path: tender the whole remaining amount with {@code method}, then finalize. */
    public void payFull(String method, BigDecimal cashTendered) {
        BigDecimal due = remaining();
        if (due.signum() <= 0) {
            finalizeSale();
            return;
        }
        int before = tenders.size();
        addTender(method, due, cashTendered);
        if (tenders.size() == before) {
            return; // addTender rejected (e.g. short cash); error already set
        }
        finalizeSale();
    }

    /** Runs the gateway checkout once the full amount is tendered; else surfaces remaining due. */
    public void finalizeSale() {
        BigDecimal due = remaining();
        if (due.signum() > 0) {
            ui.accept(() -> errorMessage.set("Remaining due: " + due.toPlainString()));
            return;
        }
        if (tenders.isEmpty()) {
            ui.accept(() -> errorMessage.set("Add a tender first"));
            return;
        }
        ui.accept(() -> errorMessage.set(""));
        try {
            SaleView closed = gateway.checkout(new ArrayList<>(tenders));
            String change = totalChange().toPlainString();
            ui.accept(() -> {
                sale.set(closed);
                paid.set(true);
                changeText.set(change);
            });
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
        }
    }

    /** Overall change = Σ over cash tenders of (tendered − amount), never negative. */
    private BigDecimal totalChange() {
        BigDecimal change = BigDecimal.ZERO;
        for (TenderInput t : tenders) {
            if (t.tendered() != null) {
                change = change.add(t.tendered().subtract(t.amount()));
            }
        }
        return change.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    /** Reprints the closed sale's receipt. No-op if nothing has been paid yet. */
    public void reprint() {
        SaleView current = sale.get();
        if (current == null) {
            return;
        }
        try {
            sales.reprint(current.id());
            ui.accept(() -> errorMessage.set(""));
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
        }
    }

    private String messageOf(ApiException e) {
        if (e.problem() != null) {
            if (e.problem().detail() != null && !e.problem().detail().isBlank()) {
                return e.problem().detail();
            }
            if (e.problem().title() != null && !e.problem().title().isBlank()) {
                return e.problem().title();
            }
        }
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        return "Request failed";
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./mvnw -f pos-terminal/pom.xml test -Dtest=PaymentViewModelTest
```
Expected: PASS. (Note: `PaymentController` will not compile yet — it still uses the old constructor. That is fixed in Task 7. Do NOT run the full `clean test` here; run only this test class.)

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/PaymentViewModel.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/PaymentViewModelTest.java
git commit -m "feat(terminal): multi-tender, gateway-driven PaymentViewModel

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: `PaymentController` + `payment.fxml` — multi-tender UI, full receipt, retail mode

Rebuild the payment screen around the new VM: a tender chip list with per-method buttons (Cash keypad/change, Card masked PAN, Wallet), a running "remaining due", and a receipt that renders `SaleView` lines/discount/payments. A `Mode` enum drives the checkout gateway and the cancel/done navigation targets. Add `Navigator.toRetailPayment`.

**Files:**
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/PaymentController.java` (rewrite)
- Modify: `pos-terminal/src/main/resources/fxml/payment.fxml` (rewrite)
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java` (add `toRetailPayment`; change dine-in `toPayment` to build a dine-in gateway via the new controller ctor)

**Interfaces:**
- Consumes: `PaymentViewModel` (Task 6), `SalesApi.checkout`/`CheckoutRequest` (Task 5), `DiningApi.close`, `Services.cartApi` not needed here.
- Produces: `Navigator.toRetailPayment(UUID cartId, BigDecimal estimatedTotal)`; `PaymentController(Services, Navigator, PaymentController.Mode, UUID id, BigDecimal estimatedTotal)`; enum `PaymentController.Mode { DINE_IN, RETAIL }`.

- [ ] **Step 1: Rewrite `payment.fxml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.geometry.Insets?>
<?import javafx.scene.control.Button?>
<?import javafx.scene.control.Label?>
<?import javafx.scene.control.Separator?>
<?import javafx.scene.control.TextField?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.layout.Pane?>
<?import javafx.scene.layout.StackPane?>
<?import javafx.scene.layout.VBox?>

<!-- Payment: estimated total + tender entry (Cash/Card/Wallet, multi-tender) until covered,
     then the authoritative SaleView receipt. Style classes come from app.css; no inline styling. -->
<StackPane styleClass="screen" xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml">
  <VBox alignment="TOP_CENTER" spacing="20" styleClass="card" maxWidth="520" maxHeight="-Infinity">
    <padding><Insets top="32" right="32" bottom="32" left="32"/></padding>

    <Label text="Payment" styleClass="title"/>
    <Label fx:id="totalLabel" styleClass="pay-total,money"/>
    <Label fx:id="remainingLabel" styleClass="pay-total,money"/>

    <Label fx:id="errorLabel" styleClass="error-banner" wrapText="true" maxWidth="Infinity"/>

    <!-- Tender entry: shown until paid. -->
    <VBox fx:id="tenderBox" spacing="14" alignment="TOP_CENTER" maxWidth="Infinity">
      <VBox fx:id="tenderChips" spacing="6" maxWidth="Infinity"/>

      <VBox spacing="6" styleClass="field" maxWidth="Infinity">
        <Label text="Amount for this tender (blank = full remaining)" styleClass="field-label"/>
        <TextField fx:id="amountField" promptText="0.00" styleClass="money" maxWidth="Infinity"/>
      </VBox>

      <VBox spacing="6" styleClass="field" maxWidth="Infinity">
        <Label text="Cash tendered (cash only)" styleClass="field-label"/>
        <TextField fx:id="tenderedField" promptText="0.00" styleClass="money" maxWidth="Infinity"/>
      </VBox>

      <VBox spacing="6" styleClass="field" maxWidth="Infinity">
        <Label text="Card number (card only)" styleClass="field-label"/>
        <TextField fx:id="panField" promptText="•••• •••• •••• ••••" maxWidth="Infinity"/>
      </VBox>

      <Label fx:id="changePreviewLabel" styleClass="pay-change-preview,money"/>

      <HBox spacing="12" alignment="CENTER" maxWidth="Infinity">
        <Button fx:id="payCashButton" text="Cash" styleClass="tender-cash" maxWidth="Infinity" HBox.hgrow="ALWAYS"/>
        <Button fx:id="payCardButton" text="Card" styleClass="tender-card" maxWidth="Infinity" HBox.hgrow="ALWAYS"/>
        <Button fx:id="payWalletButton" text="Wallet" styleClass="tender-wallet" maxWidth="Infinity" HBox.hgrow="ALWAYS"/>
      </HBox>

      <HBox spacing="12" alignment="CENTER" maxWidth="Infinity">
        <Button fx:id="addTenderButton" text="Add partial tender" styleClass="btn-secondary" maxWidth="Infinity" HBox.hgrow="ALWAYS"/>
        <Button fx:id="cancelButton" text="Cancel" styleClass="btn-secondary" maxWidth="Infinity" HBox.hgrow="ALWAYS"/>
      </HBox>
    </VBox>

    <!-- Receipt: hidden until a checkout succeeds. -->
    <VBox fx:id="resultBox" spacing="8" alignment="TOP_LEFT" visible="false" managed="false" maxWidth="Infinity">
      <Label fx:id="receiptLabel" styleClass="subtitle"/>
      <Separator/>
      <VBox fx:id="receiptLines" spacing="4" maxWidth="Infinity"/>
      <Separator/>
      <HBox maxWidth="Infinity">
        <Label text="Subtotal" styleClass="pay-line-label"/><Pane HBox.hgrow="ALWAYS"/>
        <Label fx:id="subtotalValue" styleClass="pay-line-value,money"/>
      </HBox>
      <HBox fx:id="discountRow" visible="false" managed="false" maxWidth="Infinity">
        <Label text="Discount" styleClass="pay-line-label"/><Pane HBox.hgrow="ALWAYS"/>
        <Label fx:id="discountValue" styleClass="pay-line-value,money"/>
      </HBox>
      <HBox maxWidth="Infinity">
        <Label text="Tax" styleClass="pay-line-label"/><Pane HBox.hgrow="ALWAYS"/>
        <Label fx:id="taxValue" styleClass="pay-line-value,money"/>
      </HBox>
      <HBox fx:id="serviceChargeRow" visible="false" managed="false" maxWidth="Infinity">
        <Label text="Service charge" styleClass="pay-line-label"/><Pane HBox.hgrow="ALWAYS"/>
        <Label fx:id="serviceChargeValue" styleClass="pay-line-value,money"/>
      </HBox>
      <Separator/>
      <HBox maxWidth="Infinity">
        <Label text="Grand total" styleClass="pay-grand-label"/><Pane HBox.hgrow="ALWAYS"/>
        <Label fx:id="grandTotalValue" styleClass="pay-grand-value,money"/>
      </HBox>
      <VBox fx:id="paymentsBox" spacing="4" maxWidth="Infinity"/>
      <Label fx:id="changeLabel" styleClass="pay-change,money" visible="false" managed="false"/>

      <HBox spacing="12" alignment="CENTER" maxWidth="Infinity">
        <Button fx:id="reprintButton" text="Reprint receipt" styleClass="btn-secondary" maxWidth="Infinity" HBox.hgrow="ALWAYS"/>
        <Button fx:id="doneButton" text="Done" styleClass="btn-primary" maxWidth="Infinity" HBox.hgrow="ALWAYS"/>
      </HBox>
    </VBox>
  </VBox>
</StackPane>
```

- [ ] **Step 2: Rewrite `PaymentController`**

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.CheckoutRequest;
import com.company.pos.terminal.api.dto.CloseOrderRequest;
import com.company.pos.terminal.api.dto.SaleLineModifierView;
import com.company.pos.terminal.api.dto.SaleLineView;
import com.company.pos.terminal.api.dto.SalePaymentView;
import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.PaymentViewModel;
import com.company.pos.terminal.viewmodel.PaymentViewModel.CheckoutGateway;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Thin controller for the payment screen, in DINE_IN or RETAIL mode. Builds the
 * {@link PaymentViewModel} with a {@link CheckoutGateway} bound to the right server call
 * ({@code dining.close} vs {@code sales.checkout}) and runs the VM off the FX thread via
 * {@link FxTasks}. Cash/Card/Wallet buttons take the whole remaining amount (one-tap);
 * "Add partial tender" appends a split tender. The receipt renders only from the authoritative
 * {@link SaleView}. Error text is bound to {@code vm.errorMessage()}.
 */
public class PaymentController {

    /** Which backend closes the sale, and where cancel/done navigate. */
    public enum Mode { DINE_IN, RETAIL }

    private static final System.Logger LOG = System.getLogger(PaymentController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final Mode mode;
    private final UUID id; // orderId (DINE_IN) or cartId (RETAIL)
    private final BigDecimal estimatedTotal;
    private final PaymentViewModel vm;

    @FXML private Label totalLabel;
    @FXML private Label remainingLabel;
    @FXML private Label errorLabel;
    @FXML private VBox tenderBox;
    @FXML private VBox tenderChips;
    @FXML private TextField amountField;
    @FXML private TextField tenderedField;
    @FXML private TextField panField;
    @FXML private Label changePreviewLabel;
    @FXML private Button payCashButton;
    @FXML private Button payCardButton;
    @FXML private Button payWalletButton;
    @FXML private Button addTenderButton;
    @FXML private Button cancelButton;
    @FXML private VBox resultBox;
    @FXML private Label receiptLabel;
    @FXML private VBox receiptLines;
    @FXML private Label subtotalValue;
    @FXML private HBox discountRow;
    @FXML private Label discountValue;
    @FXML private Label taxValue;
    @FXML private HBox serviceChargeRow;
    @FXML private Label serviceChargeValue;
    @FXML private Label grandTotalValue;
    @FXML private VBox paymentsBox;
    @FXML private Label changeLabel;
    @FXML private Button reprintButton;
    @FXML private Button doneButton;

    public PaymentController(Services services, Navigator navigator, Mode mode, UUID id,
            BigDecimal estimatedTotal) {
        this.services = services;
        this.navigator = navigator;
        this.mode = mode;
        this.id = id;
        this.estimatedTotal = estimatedTotal.setScale(2, RoundingMode.HALF_UP);
        this.vm = new PaymentViewModel(gatewayFor(mode, id), services.salesApi, estimatedTotal,
                Platform::runLater);
    }

    private CheckoutGateway gatewayFor(Mode m, UUID id) {
        if (m == Mode.RETAIL) {
            return tenders -> services.salesApi.checkout(
                    new CheckoutRequest(id, tenders, Map.of(), null, false));
        }
        return tenders -> services.diningApi.close(id,
                new CloseOrderRequest(tenders, Map.of(), null, false));
    }

    @FXML
    public void initialize() {
        totalLabel.setText("Total due (est.): " + estimatedTotal.toPlainString());
        remainingLabel.textProperty().bind(
                javafx.beans.binding.Bindings.concat("Remaining: ", vm.remainingText()));

        errorLabel.textProperty().bind(vm.errorMessage());
        errorLabel.visibleProperty().bind(vm.errorMessage().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        tenderedField.textProperty().addListener((o, was, now) -> updateChangePreview(now));

        payCashButton.setOnAction(e -> pay(() -> vm.payFull("CASH", parse(tenderedField))));
        payCardButton.setOnAction(e -> pay(() -> vm.payFull("CARD", null)));
        payWalletButton.setOnAction(e -> pay(() -> vm.payFull("WALLET", null)));
        addTenderButton.setOnAction(e -> addPartial());
        cancelButton.setOnAction(e -> cancel());
        reprintButton.setOnAction(e -> reprint());
        doneButton.setOnAction(e -> done());

        vm.tenders().addListener((javafx.collections.ListChangeListener<Object>) c -> renderChips());
        vm.sale().addListener((o, was, now) -> { if (now != null) showResult(now); });
    }

    /** "Add partial tender": tender the typed amount with the chosen method, without finalizing. */
    private void addPartial() {
        BigDecimal amount = parse(amountField);
        String method = panField.getText() != null && !panField.getText().isBlank() ? "CARD" : "CASH";
        // Cash partial uses the tendered field; card/wallet partials pass null tendered.
        BigDecimal cash = "CASH".equals(method) ? parse(tenderedField) : null;
        pay(() -> vm.addTender(method, amount, cash));
    }

    private void renderChips() {
        tenderChips.getChildren().clear();
        vm.tenders().forEach(t -> {
            Label chip = new Label(t.toString());
            chip.getStyleClass().addAll("tender-chip", "money");
            tenderChips.getChildren().add(chip);
        });
    }

    private void cancel() {
        if (mode == Mode.RETAIL) {
            navigator.toRetail();
        } else {
            navigator.toOrder(id);
        }
    }

    private void done() {
        if (mode == Mode.RETAIL) {
            navigator.toHome();
        } else {
            navigator.toTableMap();
        }
    }

    private BigDecimal parse(TextField f) {
        String raw = f.getText();
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private void pay(Runnable action) {
        setBusy(true);
        FxTasks.run(action, () -> setBusy(false), err -> {
            setBusy(false);
            LOG.log(System.Logger.Level.ERROR, "Unexpected error taking payment", err);
        });
    }

    private void reprint() {
        FxTasks.run(vm::reprint, () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Unexpected error reprinting receipt", err));
    }

    private void setBusy(boolean busy) {
        payCashButton.setDisable(busy);
        payCardButton.setDisable(busy);
        payWalletButton.setDisable(busy);
        addTenderButton.setDisable(busy);
    }

    private void updateChangePreview(String raw) {
        if (raw == null || raw.isBlank()) {
            changePreviewLabel.setText("");
            return;
        }
        try {
            BigDecimal cash = new BigDecimal(raw.trim()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal due = estimatedTotal; // preview against the full estimate
            if (cash.compareTo(due) >= 0) {
                changePreviewLabel.setText("Change: " + cash.subtract(due).toPlainString());
            } else {
                changePreviewLabel.setText("");
            }
        } catch (NumberFormatException ex) {
            changePreviewLabel.setText("");
        }
    }

    private void showResult(SaleView sale) {
        String cur = sale.currencyCode();
        receiptLabel.setText("Receipt " + sale.receiptNumber());

        receiptLines.getChildren().clear();
        if (sale.lines() != null) {
            for (SaleLineView line : sale.lines()) {
                receiptLines.getChildren().add(lineRow(line, cur));
            }
        }

        subtotalValue.setText(money(sale.subtotal(), cur));

        BigDecimal discount = sale.discountTotal();
        boolean hasDiscount = discount != null && discount.compareTo(BigDecimal.ZERO) > 0;
        discountRow.setVisible(hasDiscount);
        discountRow.setManaged(hasDiscount);
        if (hasDiscount) {
            discountValue.setText("-" + money(discount, cur));
        }

        taxValue.setText(money(sale.taxTotal(), cur));

        BigDecimal sc = sale.serviceChargeAmount();
        boolean hasSc = sc != null && sc.compareTo(BigDecimal.ZERO) > 0;
        serviceChargeRow.setVisible(hasSc);
        serviceChargeRow.setManaged(hasSc);
        if (hasSc) {
            serviceChargeValue.setText(money(sc, cur));
        }

        grandTotalValue.setText(money(sale.grandTotal(), cur));

        paymentsBox.getChildren().clear();
        if (sale.payments() != null) {
            for (SalePaymentView p : sale.payments()) {
                Label row = new Label(p.method() + "  " + money(p.amount(), cur)
                        + (p.changeGiven() != null && p.changeGiven().signum() > 0
                            ? "  (change " + p.changeGiven().toPlainString() + ")" : ""));
                row.getStyleClass().addAll("pay-line-value", "money");
                paymentsBox.getChildren().add(row);
            }
        }

        String change = vm.changeText().get();
        boolean showChange = change != null && !change.isBlank() && new BigDecimal(change).signum() > 0;
        changeLabel.setVisible(showChange);
        changeLabel.setManaged(showChange);
        if (showChange) {
            changeLabel.setText("Change due: " + change + " " + cur);
        }

        doneButton.setDefaultButton(true);
        tenderBox.setVisible(false);
        tenderBox.setManaged(false);
        resultBox.setVisible(true);
        resultBox.setManaged(true);
    }

    private HBox lineRow(SaleLineView line, String cur) {
        String mods = "";
        if (line.modifiers() != null && !line.modifiers().isEmpty()) {
            mods = " (" + line.modifiers().stream()
                    .map(SaleLineModifierView::name).collect(Collectors.joining(", ")) + ")";
        }
        Label name = new Label(qty(line.quantity()) + " × " + line.name() + mods);
        name.getStyleClass().add("pay-line-label");
        Label total = new Label(money(line.lineTotal(), cur));
        total.getStyleClass().addAll("pay-line-value", "money");
        HBox row = new HBox(name, new javafx.scene.layout.Region(), total);
        HBox.setHgrow(row.getChildren().get(1), javafx.scene.layout.Priority.ALWAYS);
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private static String qty(BigDecimal q) {
        return (q == null ? BigDecimal.ZERO : q).stripTrailingZeros().toPlainString();
    }

    private static String money(BigDecimal amount, String currency) {
        BigDecimal value = (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
        return value.toPlainString() + " " + currency;
    }
}
```

- [ ] **Step 3: Update `Navigator`**

In `Navigator.java`, replace the `toPayment` method and add `toRetailPayment` (and `toRetail`/`toHome` are added in Task 9 — reference them only after Task 9; here add the payment overloads and update dine-in). Change the dine-in `toPayment`:

```java
    public void toPayment(UUID orderId, BigDecimal estimatedTotal) {
        com.company.pos.terminal.view.PaymentController controller =
                new com.company.pos.terminal.view.PaymentController(services, this,
                        com.company.pos.terminal.view.PaymentController.Mode.DINE_IN, orderId, estimatedTotal);
        setScene("/fxml/payment.fxml", controller);
    }

    public void toRetailPayment(UUID cartId, BigDecimal estimatedTotal) {
        com.company.pos.terminal.view.PaymentController controller =
                new com.company.pos.terminal.view.PaymentController(services, this,
                        com.company.pos.terminal.view.PaymentController.Mode.RETAIL, cartId, estimatedTotal);
        setScene("/fxml/payment.fxml", controller);
    }
```

Note: `PaymentController.cancel()`/`done()` call `navigator.toRetail()`/`toHome()` which are added in Task 9. To keep this task compiling on its own, add temporary stubs in `Navigator` now (they will be fleshed out in Task 9):

```java
    public void toHome() { toTableMap(); }   // TEMP: replaced by real Home in Task 9
    public void toRetail() { toTableMap(); }  // TEMP: replaced in Task 9
```

- [ ] **Step 4: Full build**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -f pos-terminal/pom.xml clean test
```
Expected: BUILD SUCCESS, all tests pass (the payment VM tests from Task 6 now compile alongside the controller).

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/PaymentController.java \
        pos-terminal/src/main/resources/fxml/payment.fxml \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java
git commit -m "feat(terminal): multi-tender payment screen + retail checkout mode

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: `CartSubtotalCalculator` + `RetailViewModel`

The observable cart state for the retail screen: create the cart, add/update/remove lines, compute the client-side **est.** subtotal directly from `CartLineView.unitPrice`, expose an item-added pulse signal, and resolve barcodes/search against a `MenuCache`.

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/order/CartSubtotalCalculator.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/RetailViewModel.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/order/CartSubtotalCalculatorTest.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/RetailViewModelTest.java`

**Interfaces:**
- Produces:
  - `CartSubtotalCalculator.estimate(CartView)` → `BigDecimal` (scale 2).
  - `RetailViewModel(CartApi cart, MenuCache cache, Consumer<Runnable> ui)` (+ 2-arg convenience with `Runnable::run`).
  - `void start()` (creates the cart); `void addBySku(String sku, BigDecimal qty, List<UUID> optionIds)`; `String addByBarcode(String code)` (returns resolved sku or null); `void updateQty(CartLineView, BigDecimal)`; `void removeLine(CartLineView)`; `UUID cartId()`; `BigDecimal estimatedTotal()`.
  - Properties: `ObservableList<CartLineView> lines()`, `ReadOnlyStringProperty subtotalText()`, `ReadOnlyStringProperty errorMessage()`, `ReadOnlyIntegerProperty itemAddedCount()`.
- Consumes: `CartApi` (Task 3), `MenuCache.skuForBarcode` (Task 2), `CartView`/`CartLineView` (Task 3).

- [ ] **Step 1: Write the failing tests**

`CartSubtotalCalculatorTest.java`:

```java
package com.company.pos.terminal.order;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.CartLineView;
import com.company.pos.terminal.api.dto.CartView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CartSubtotalCalculatorTest {

    private static CartLineView line(String sku, String qty, String unit) {
        return new CartLineView(UUID.randomUUID(), sku, sku, new BigDecimal(qty), new BigDecimal(unit),
                new BigDecimal(unit), "SAR", List.of());
    }

    @Test
    void sumsUnitPriceTimesQuantityAtScale2() {
        CartView cart = new CartView(UUID.randomUUID(), "OPEN", "SAR", null,
                List.of(line("A", "2", "14.00"), line("B", "1", "8.50")));
        assertEquals(0, new BigDecimal("36.50").compareTo(CartSubtotalCalculator.estimate(cart)));
    }

    @Test
    void emptyOrNullCartIsZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(CartSubtotalCalculator.estimate(null)));
        CartView empty = new CartView(UUID.randomUUID(), "OPEN", "SAR", null, List.of());
        assertEquals(0, BigDecimal.ZERO.compareTo(CartSubtotalCalculator.estimate(empty)));
    }
}
```

`RetailViewModelTest.java`:

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.CartApi;
import com.company.pos.terminal.api.dto.CartLineView;
import com.company.pos.terminal.api.dto.CartView;
import com.company.pos.terminal.api.dto.ProductView;
import com.company.pos.terminal.order.MenuCache;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetailViewModelTest {

    private static final UUID CART_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static MenuCache cache() {
        return new MenuCache(List.of(
                new ProductView("LATTE", "Latte", "Drinks", "6291041500213", new BigDecimal("14.00"))));
    }

    private static CartView cartWith(String sku, String qty, String unit) {
        return new CartView(CART_ID, "OPEN", "SAR", null, List.of(
                new CartLineView(UUID.randomUUID(), sku, sku, new BigDecimal(qty),
                        new BigDecimal(unit), new BigDecimal(unit), "SAR", List.of())));
    }

    /** Stub CartApi that records calls and returns a canned cart. */
    private static final class StubCartApi extends CartApi {
        CartView next;
        String addedSku;
        StubCartApi() { super(null); }
        @Override public UUID createCart() { return CART_ID; }
        @Override public CartView addLine(UUID cartId, String sku, BigDecimal qty, List<UUID> ids) {
            addedSku = sku; return next;
        }
    }

    @Test
    void startCreatesCartAndStartsEmpty() {
        StubCartApi api = new StubCartApi();
        RetailViewModel vm = new RetailViewModel(api, cache());
        vm.start();
        assertEquals(CART_ID, vm.cartId());
        assertTrue(vm.lines().isEmpty());
        assertEquals("0.00", vm.subtotalText().get());
    }

    @Test
    void addBySkuUpdatesLinesSubtotalAndPulse() {
        StubCartApi api = new StubCartApi();
        api.next = cartWith("LATTE", "2", "14.00");
        RetailViewModel vm = new RetailViewModel(api, cache());
        vm.start();
        int before = vm.itemAddedCount().get();
        vm.addBySku("LATTE", BigDecimal.ONE, List.of());
        assertEquals(1, vm.lines().size());
        assertEquals("28.00", vm.subtotalText().get());
        assertEquals(before + 1, vm.itemAddedCount().get(), "item-added pulse signal fires");
    }

    @Test
    void addByBarcodeResolvesToSku() {
        StubCartApi api = new StubCartApi();
        api.next = cartWith("LATTE", "1", "14.00");
        RetailViewModel vm = new RetailViewModel(api, cache());
        vm.start();
        String sku = vm.addByBarcode("6291041500213");
        assertEquals("LATTE", sku);
        assertEquals("LATTE", api.addedSku);
    }

    @Test
    void addByUnknownBarcodeSetsErrorAndDoesNotAdd() {
        StubCartApi api = new StubCartApi();
        RetailViewModel vm = new RetailViewModel(api, cache());
        vm.start();
        String sku = vm.addByBarcode("0000000000000");
        assertNull(sku);
        assertNull(api.addedSku);
        assertTrue(vm.errorMessage().get().toLowerCase().contains("barcode"));
    }
}
```

- [ ] **Step 2: Run to verify they fail**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -f pos-terminal/pom.xml test -Dtest=CartSubtotalCalculatorTest,RetailViewModelTest
```
Expected: FAIL — classes/methods do not exist.

- [ ] **Step 3: Create `CartSubtotalCalculator`**

```java
package com.company.pos.terminal.order;

import com.company.pos.terminal.api.dto.CartLineView;
import com.company.pos.terminal.api.dto.CartView;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure client-side estimator for the retail cart subtotal. PRE-tax only: the server owns the
 * authoritative total at checkout. Unlike the dining estimate, {@link CartLineView#unitPrice()}
 * already includes modifier deltas, so the estimate is {@code Σ unitPrice × quantity}, scale 2.
 */
public final class CartSubtotalCalculator {

    private CartSubtotalCalculator() {
    }

    public static BigDecimal estimate(CartView cart) {
        BigDecimal total = BigDecimal.ZERO;
        if (cart != null && cart.lines() != null) {
            for (CartLineView line : cart.lines()) {
                if (line == null) {
                    continue;
                }
                BigDecimal unit = line.unitPrice() == null ? BigDecimal.ZERO : line.unitPrice();
                BigDecimal qty = line.quantity() == null ? BigDecimal.ZERO : line.quantity();
                total = total.add(unit.multiply(qty));
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 4: Create `RetailViewModel`**

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.CartApi;
import com.company.pos.terminal.api.dto.CartLineView;
import com.company.pos.terminal.api.dto.CartView;
import com.company.pos.terminal.order.CartSubtotalCalculator;
import com.company.pos.terminal.order.MenuCache;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * ViewModel for the retail cart. Creates a server cart, mutates its lines, and exposes the lines,
 * a client-side pre-tax {@code est.} subtotal ({@link CartSubtotalCalculator}), an error message,
 * and an item-added counter the screen watches to fire the total-bar pulse. Unit-testable without
 * FX; every mutating call runs synchronously (the controller runs it off the FX thread).
 *
 * <p>Barcode entry resolves client-side against the {@link MenuCache} (the backend {@code ?q=}
 * searches name+SKU only), then adds the resolved sku with quantity 1 and no modifiers.
 */
public class RetailViewModel {

    private final CartApi cart;
    private final MenuCache cache;
    private final Consumer<Runnable> ui;

    private final ObservableList<CartLineView> lines = FXCollections.observableArrayList();
    private final ReadOnlyStringWrapper subtotalText = new ReadOnlyStringWrapper("0.00");
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");
    private final ReadOnlyIntegerWrapper itemAddedCount = new ReadOnlyIntegerWrapper(0);

    private UUID cartId;
    private BigDecimal estimatedTotal = BigDecimal.ZERO.setScale(2);

    public RetailViewModel(CartApi cart, MenuCache cache) {
        this(cart, cache, Runnable::run);
    }

    public RetailViewModel(CartApi cart, MenuCache cache, Consumer<Runnable> ui) {
        this.cart = cart;
        this.cache = cache;
        this.ui = ui;
    }

    public ObservableList<CartLineView> lines() { return lines; }
    public ReadOnlyStringProperty subtotalText() { return subtotalText.getReadOnlyProperty(); }
    public ReadOnlyStringProperty errorMessage() { return errorMessage.getReadOnlyProperty(); }
    public ReadOnlyIntegerProperty itemAddedCount() { return itemAddedCount.getReadOnlyProperty(); }
    public UUID cartId() { return cartId; }
    public BigDecimal estimatedTotal() { return estimatedTotal; }

    /** Creates a fresh server cart. Call once when the screen opens. */
    public void start() {
        try {
            UUID id = cart.createCart();
            ui.accept(() -> {
                cartId = id;
                lines.clear();
                subtotalText.set("0.00");
                errorMessage.set("");
            });
            this.cartId = id; // also set synchronously so cartId() is usable in tests/next calls
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
        }
    }

    public void addBySku(String sku, BigDecimal qty, List<UUID> optionIds) {
        boolean ok = apply(() -> cart.addLine(cartId, sku, qty, optionIds));
        if (ok) {
            ui.accept(() -> itemAddedCount.set(itemAddedCount.get() + 1));
        }
    }

    /**
     * Resolves a barcode to a sku via the cache and adds it (qty 1, no modifiers).
     * @return the resolved sku, or {@code null} if the barcode is unknown (error surfaced).
     */
    public String addByBarcode(String code) {
        String sku = cache.skuForBarcode(code);
        if (sku == null) {
            ui.accept(() -> errorMessage.set("No product for barcode: " + code));
            return null;
        }
        addBySku(sku, BigDecimal.ONE, List.of());
        return sku;
    }

    public void updateQty(CartLineView line, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            removeLine(line);
            return;
        }
        apply(() -> cart.updateLine(cartId, line.lineId(), qty));
    }

    public void removeLine(CartLineView line) {
        apply(() -> cart.removeLine(cartId, line.lineId()));
    }

    /** Runs a cart call, swaps in the returned cart, rebuilds lines + subtotal. @return success. */
    private boolean apply(Supplier<CartView> call) {
        try {
            CartView v = call.get();
            List<CartLineView> next = v.lines() == null ? List.of() : v.lines();
            BigDecimal sub = CartSubtotalCalculator.estimate(v);
            this.estimatedTotal = sub;
            ui.accept(() -> {
                lines.setAll(next);
                subtotalText.set(sub.toPlainString());
                errorMessage.set("");
            });
            return true;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return false;
        }
    }

    private String messageOf(ApiException e) {
        if (e.problem() != null) {
            if (e.problem().detail() != null && !e.problem().detail().isBlank()) {
                return e.problem().detail();
            }
            if (e.problem().title() != null && !e.problem().title().isBlank()) {
                return e.problem().title();
            }
        }
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        return "Request failed";
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./mvnw -f pos-terminal/pom.xml test -Dtest=CartSubtotalCalculatorTest,RetailViewModelTest
```
Expected: PASS.

- [ ] **Step 6: Full build**

```bash
./mvnw -f pos-terminal/pom.xml clean test
```
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/order/CartSubtotalCalculator.java \
        pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/RetailViewModel.java \
        pos-terminal/src/test/java/com/company/pos/terminal/order/CartSubtotalCalculatorTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/RetailViewModelTest.java
git commit -m "feat(terminal): RetailViewModel + cart subtotal estimator

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: `retail.fxml` + `RetailController` (two-zone screen, total bar, fast entry)

The retail sales screen: category-tabbed menu grid (left), live cart with quantity steppers + pinned amber-pulsing total bar (right), search + barcode fast entry, Charge → retail payment. Adds a `ui.reduced-motion` config flag for the pulse.

**Files:**
- Create: `pos-terminal/src/main/resources/fxml/retail.fxml`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/RetailController.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/config/TerminalConfig.java` (add `ui.reduced-motion`)
- Modify: `pos-terminal/src/main/resources/pos-terminal.properties` (document the key)
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java` (real `toRetail()`)
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/config/TerminalConfigTest.java` (extend — assert default false + override true)

**Interfaces:**
- Consumes: `RetailViewModel`/`CartSubtotalCalculator` (Task 8), `MenuCache`/`ProductView` (Task 2), `ModifierPickerDialog`, `MenuApi`, `Navigator.toRetailPayment` (Task 7), `Services.cartApi` (Task 3).
- Produces: `TerminalConfig.reducedMotion()` → `boolean`; `Navigator.toRetail()` builds `RetailController`.

- [ ] **Step 1: Write the failing config test**

Add to `TerminalConfigTest.java` (append these two tests; match the existing test's `Properties`/`from` style):

```java
    @Test
    void reducedMotionDefaultsFalse() {
        java.util.Properties p = new java.util.Properties();
        assertFalse(com.company.pos.terminal.config.TerminalConfig.from(p).reducedMotion());
    }

    @Test
    void reducedMotionReadsTrue() {
        java.util.Properties p = new java.util.Properties();
        p.setProperty("ui.reduced-motion", "true");
        assertTrue(com.company.pos.terminal.config.TerminalConfig.from(p).reducedMotion());
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -f pos-terminal/pom.xml test -Dtest=TerminalConfigTest
```
Expected: FAIL — `reducedMotion()` does not exist.

- [ ] **Step 3: Add the config flag**

In `TerminalConfig.java` add a field `private final boolean reducedMotion;`, parse it in the constructor:

```java
        this.reducedMotion = Boolean.parseBoolean(p.getProperty("ui.reduced-motion", "false"));
```

add the accessor `public boolean reducedMotion() { return reducedMotion; }`, and add `"ui.reduced-motion"` to the override key array in `load()`.

- [ ] **Step 4: Document the key in `pos-terminal.properties`**

Append:

```properties
# When true, the total-bar pulse degrades to a static highlight (no animation).
ui.reduced-motion=false
```

- [ ] **Step 5: Create `retail.fxml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.geometry.Insets?>
<?import javafx.scene.control.Button?>
<?import javafx.scene.control.Label?>
<?import javafx.scene.control.TabPane?>
<?import javafx.scene.control.TextField?>
<?import javafx.scene.layout.BorderPane?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.layout.Pane?>
<?import javafx.scene.layout.StackPane?>
<?import javafx.scene.layout.VBox?>

<!-- Two-zone retail sale: CENTER ~65% = category-tabbed menu grid; RIGHT ~35% = live cart
     with quantity steppers and the pinned ink total bar. Fast entry (search + barcode) sits
     above the grid. Style classes come from app.css. -->
<BorderPane styleClass="screen" xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml">
  <top>
    <HBox spacing="16" alignment="CENTER_LEFT" styleClass="top-nav">
      <padding><Insets top="12" right="16" bottom="12" left="16"/></padding>
      <Label text="Retail sale" styleClass="title"/>
      <Pane HBox.hgrow="ALWAYS"/>
      <TextField fx:id="searchField" promptText="Search products…" styleClass="search-field" prefWidth="240"/>
      <TextField fx:id="barcodeField" promptText="Scan barcode" styleClass="search-field" prefWidth="200"/>
      <Button fx:id="homeButton" text="Home" styleClass="btn-secondary"/>
    </HBox>
  </top>

  <center>
    <VBox spacing="12">
      <padding><Insets top="12" right="16" bottom="0" left="0"/></padding>
      <Label fx:id="errorLabel" styleClass="error-banner" wrapText="true" maxWidth="Infinity"/>
      <TabPane fx:id="categoryTabs" VBox.vgrow="ALWAYS" styleClass="menu-tabs"/>
    </VBox>
  </center>

  <right>
    <VBox spacing="12" prefWidth="420" styleClass="cart-pane">
      <padding><Insets top="16" right="16" bottom="16" left="16"/></padding>
      <Label text="Cart" styleClass="subtitle"/>

      <StackPane VBox.vgrow="ALWAYS">
        <VBox fx:id="cartLines" spacing="0" maxWidth="Infinity"/>
        <Label fx:id="emptyCartLabel" text="Scan or tap an item to start"
               styleClass="empty-cart" maxWidth="Infinity"/>
      </StackPane>

      <!-- Pinned signature total bar. -->
      <HBox fx:id="totalBar" alignment="CENTER_LEFT" styleClass="total-bar" maxWidth="Infinity">
        <Label text="Total (est.)" styleClass="field-label"/>
        <Pane HBox.hgrow="ALWAYS"/>
        <Label fx:id="totalValue" styleClass="total-bar-grand,money"/>
      </HBox>

      <Button fx:id="chargeButton" text="Charge / Pay" defaultButton="true"
              styleClass="btn-primary" maxWidth="Infinity"/>
    </VBox>
  </right>
</BorderPane>
```

- [ ] **Step 6: Create `RetailController`**

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.CartLineModifierView;
import com.company.pos.terminal.api.dto.CartLineView;
import com.company.pos.terminal.api.dto.ModifierGroupView;
import com.company.pos.terminal.api.dto.ProductView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.order.MenuCache;
import com.company.pos.terminal.viewmodel.RetailViewModel;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Thin controller for the retail sales screen. Binds FXML to a {@link RetailViewModel}
 * (cart on {@code Services.cartApi}) and a {@link MenuCache} (from {@code Services.productApi}).
 * Left/centre: a category-tabbed touch grid; right: the live cart with quantity steppers and the
 * pinned ink total bar that pulses amber on each item-add (static highlight when
 * {@code ui.reduced-motion} is set). Fast entry: a search box (server {@code ?q=}) and a barcode
 * box (client-side match). All VM calls run off the FX thread via {@link FxTasks}.
 */
public class RetailController implements Navigator.Screen {

    private static final System.Logger LOG = System.getLogger(RetailController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private RetailViewModel vm;
    private MenuCache cache;

    @FXML private TextField searchField;
    @FXML private TextField barcodeField;
    @FXML private Button homeButton;
    @FXML private Label errorLabel;
    @FXML private TabPane categoryTabs;
    @FXML private VBox cartLines;
    @FXML private Label emptyCartLabel;
    @FXML private HBox totalBar;
    @FXML private Label totalValue;
    @FXML private Button chargeButton;

    public RetailController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
    }

    @FXML
    public void initialize() {
        errorLabel.visibleProperty().bind(errorLabel.textProperty().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

        homeButton.setOnAction(e -> navigator.toHome());
        chargeButton.setOnAction(e -> charge());
        chargeButton.setDisable(true);

        barcodeField.setOnAction(e -> onBarcode());
        searchField.setOnAction(e -> onSearch());

        FxTasks.run(
                () -> cache = new MenuCache(services.productApi.list()),
                this::afterCatalogLoaded,
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to load catalog", err));
    }

    private void afterCatalogLoaded() {
        vm = new RetailViewModel(services.cartApi, cache, Platform::runLater);

        errorLabel.textProperty().bind(vm.errorMessage());
        totalValue.textProperty().bind(vm.subtotalText());
        vm.lines().addListener((ListChangeListener<CartLineView>) c -> renderCart());
        vm.itemAddedCount().addListener((o, was, now) -> pulseTotalBar());

        buildMenu(cache.categories());
        renderCart();

        FxTasks.run(
                () -> vm.start(),
                () -> chargeButton.setDisable(false),
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to create cart", err));
    }

    private void buildMenu(List<String> categories) {
        categoryTabs.getTabs().clear();
        for (String category : categories) {
            FlowPane grid = new FlowPane(12, 12);
            grid.getStyleClass().add("menu-grid");
            for (ProductView p : cache.productsInCategory(category)) {
                Button b = new Button(p.name() + "\n" + priceText(p));
                b.getStyleClass().addAll("menu-button", categoryClass(category));
                b.setWrapText(true);
                b.setOnAction(e -> addProduct(p));
                grid.getChildren().add(b);
            }
            ScrollPane scroll = new ScrollPane(grid);
            scroll.setFitToWidth(true);
            scroll.getStyleClass().add("menu-scroll");
            Tab tab = new Tab(category, scroll);
            tab.setClosable(false);
            categoryTabs.getTabs().add(tab);
        }
    }

    /** Map a category name to one of the four semantic edge classes (else neutral). */
    private static String categoryClass(String category) {
        String c = category == null ? "" : category.toLowerCase();
        if (c.contains("drink")) return "cat-drinks";
        if (c.contains("food")) return "cat-food";
        if (c.contains("merch") || c.contains("retail")) return "cat-merch";
        if (c.contains("side")) return "cat-sides";
        return "cat-other";
    }

    private void addProduct(ProductView p) {
        FxTasks.run(
                () -> {
                    List<ModifierGroupView> groups = services.menuApi.modifierGroupsForSku(p.sku());
                    Platform.runLater(() -> addWithGroups(p, groups));
                },
                () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to load modifiers for " + p.sku(), err));
    }

    private void addWithGroups(ProductView p, List<ModifierGroupView> groups) {
        List<UUID> optionIds = List.of();
        if (groups != null && !groups.isEmpty()) {
            Optional<List<UUID>> chosen = ModifierPickerDialog.pickFor(p.name(), groups);
            if (chosen.isEmpty()) {
                return;
            }
            optionIds = chosen.get();
        }
        final List<UUID> ids = optionIds;
        FxTasks.run(
                () -> vm.addBySku(p.sku(), BigDecimal.ONE, ids),
                () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to add " + p.sku(), err));
    }

    private void onBarcode() {
        String code = barcodeField.getText();
        barcodeField.clear();
        FxTasks.run(
                () -> vm.addByBarcode(code),
                () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to add by barcode", err));
    }

    /** Server search: rebuild the grid from the query hits (one "Results" tab). */
    private void onSearch() {
        String q = searchField.getText();
        if (q == null || q.isBlank()) {
            buildMenu(cache.categories());
            return;
        }
        FxTasks.run(
                () -> {
                    List<ProductView> hits = services.productApi.search(q);
                    MenuCache resultCache = new MenuCache(hits);
                    Platform.runLater(() -> {
                        this.cache = resultCache;
                        buildMenu(resultCache.categories());
                    });
                },
                () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Search failed", err));
    }

    private void renderCart() {
        cartLines.getChildren().clear();
        boolean empty = vm == null || vm.lines().isEmpty();
        emptyCartLabel.setVisible(empty);
        emptyCartLabel.setManaged(empty);
        if (empty) {
            return;
        }
        for (CartLineView line : vm.lines()) {
            cartLines.getChildren().add(cartRow(line));
        }
    }

    private HBox cartRow(CartLineView line) {
        Button minus = new Button("−");
        minus.getStyleClass().add("qty-stepper");
        minus.setOnAction(e -> step(line, -1));
        Label qty = new Label(qtyText(line.quantity()));
        qty.getStyleClass().add("money");
        Button plus = new Button("+");
        plus.getStyleClass().add("qty-stepper");
        plus.setOnAction(e -> step(line, +1));

        Label name = new Label(line.name() + modifierSuffix(line));
        name.setWrapText(true);
        Label lineTotal = new Label(lineTotalText(line));
        lineTotal.getStyleClass().add("money");

        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, minus, qty, plus, name, spacer, lineTotal);
        row.getStyleClass().add("cart-line");
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private void step(CartLineView line, int delta) {
        BigDecimal current = line.quantity() == null ? BigDecimal.ZERO : line.quantity();
        BigDecimal next = current.add(BigDecimal.valueOf(delta));
        FxTasks.run(
                () -> vm.updateQty(line, next),
                () -> {},
                err -> LOG.log(System.Logger.Level.ERROR, "Failed to change quantity", err));
    }

    private void charge() {
        UUID cartId = vm.cartId();
        if (cartId == null || vm.lines().isEmpty()) {
            return;
        }
        navigator.toRetailPayment(cartId, vm.estimatedTotal());
    }

    /** Amber pulse on item-add; static highlight when reduced-motion is configured. */
    private void pulseTotalBar() {
        if (services.config.reducedMotion()) {
            totalBar.getStyleClass().add("total-bar-pulse");
            return;
        }
        totalBar.getStyleClass().add("total-bar-pulse");
        javafx.animation.PauseTransition hold = new javafx.animation.PauseTransition(Duration.millis(220));
        hold.setOnFinished(e -> totalBar.getStyleClass().remove("total-bar-pulse"));
        hold.play();
    }

    @Override
    public void onLeave() {
        // No polling timers on this screen; nothing to release. Present for symmetry/future use.
    }

    private static String priceText(ProductView p) {
        return (p.unitPrice() == null ? BigDecimal.ZERO : p.unitPrice()).toPlainString();
    }

    private static String qtyText(BigDecimal qty) {
        return (qty == null ? BigDecimal.ZERO : qty).stripTrailingZeros().toPlainString();
    }

    private static String lineTotalText(CartLineView line) {
        BigDecimal unit = line.unitPrice() == null ? BigDecimal.ZERO : line.unitPrice();
        BigDecimal qty = line.quantity() == null ? BigDecimal.ZERO : line.quantity();
        return unit.multiply(qty).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String modifierSuffix(CartLineView line) {
        if (line.modifiers() == null || line.modifiers().isEmpty()) {
            return "";
        }
        return "  (" + line.modifiers().stream()
                .map(CartLineModifierView::name).collect(Collectors.joining(", ")) + ")";
    }
}
```

Required companion edit (`RetailController.onSearch` above depends on it): **`ProductApi` has only `list()` today — add `search(String)`.** In `pos-terminal/src/main/java/com/company/pos/terminal/api/ProductApi.java`, add (mirroring `list()`):

```java
    public List<ProductView> search(String q) {
        return client.get("/products?q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8),
                new TypeReference<List<ProductView>>() {});
    }
```

- [ ] **Step 7: Add the real `toRetail()` to `Navigator`**

Replace the TEMP `toRetail()` stub from Task 7 with:

```java
    public void toRetail() {
        com.company.pos.terminal.view.RetailController controller =
                new com.company.pos.terminal.view.RetailController(services, this);
        setScene("/fxml/retail.fxml", controller);
    }
```

- [ ] **Step 8: Run config test + full build**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -f pos-terminal/pom.xml test -Dtest=TerminalConfigTest
./mvnw -f pos-terminal/pom.xml clean test
```
Expected: PASS, then BUILD SUCCESS.

- [ ] **Step 9: Commit**

```bash
git add pos-terminal/src/main/resources/fxml/retail.fxml \
        pos-terminal/src/main/java/com/company/pos/terminal/view/RetailController.java \
        pos-terminal/src/main/java/com/company/pos/terminal/config/TerminalConfig.java \
        pos-terminal/src/main/resources/pos-terminal.properties \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/ProductApi.java \
        pos-terminal/src/test/java/com/company/pos/terminal/config/TerminalConfigTest.java
git commit -m "feat(terminal): retail sales screen — menu grid, live cart, pulsing total bar

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Home mode picker + wire login → Home + README

Insert the post-login Home screen (Dine-in / Retail), replace the TEMP `toHome()` stub with the real screen, land login on Home instead of the table map, and document the retail flow.

**Files:**
- Create: `pos-terminal/src/main/resources/fxml/home.fxml`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/HomeController.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java` (real `toHome()`)
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/LoginController.java` (navigate to Home on success)
- Modify: `pos-terminal/README.md` (document Home + retail path)

**Interfaces:**
- Consumes: `Navigator.toTableMap()`, `Navigator.toRetail()` (Task 9), `Services.session`.
- Produces: `Navigator.toHome()` builds `HomeController`.

- [ ] **Step 1: Create `home.fxml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.geometry.Insets?>
<?import javafx.scene.control.Button?>
<?import javafx.scene.control.Label?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.layout.Pane?>
<?import javafx.scene.layout.StackPane?>
<?import javafx.scene.layout.VBox?>

<!-- Post-login mode picker. Two large tiles route to the dine-in floor or a new retail sale. -->
<StackPane styleClass="screen" xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml">
  <VBox alignment="CENTER" spacing="32" maxWidth="-Infinity">
    <HBox spacing="16" alignment="CENTER" maxWidth="Infinity">
      <Label text="Choose a mode" styleClass="title"/>
      <Pane HBox.hgrow="ALWAYS"/>
      <Label fx:id="userLabel" styleClass="subtitle"/>
      <Button fx:id="signOutButton" text="Sign out" styleClass="btn-secondary"/>
    </HBox>

    <HBox spacing="32" alignment="CENTER">
      <Button fx:id="dineInButton" text="Dine-in" styleClass="home-tile"/>
      <Button fx:id="retailButton" text="Retail sale" styleClass="home-tile"/>
    </HBox>
  </VBox>
</StackPane>
```

- [ ] **Step 2: Create `HomeController`**

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

/**
 * Thin controller for the post-login mode picker. Two tiles route to the dine-in table map or a
 * fresh retail sale. Shows the signed-in user and a sign-out affordance. No business logic.
 */
public class HomeController {

    private final Services services;
    private final Navigator navigator;

    @FXML private Label userLabel;
    @FXML private Button signOutButton;
    @FXML private Button dineInButton;
    @FXML private Button retailButton;

    public HomeController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
    }

    @FXML
    public void initialize() {
        String user = services.session.username();
        userLabel.setText(user == null ? "" : "Signed in: " + user);
        dineInButton.setOnAction(e -> navigator.toTableMap());
        retailButton.setOnAction(e -> navigator.toRetail());
        signOutButton.setOnAction(e -> {
            services.session.clear();
            navigator.toLogin();
        });
    }
}
```

- [ ] **Step 3: Replace the TEMP `toHome()` in `Navigator`**

Replace the Task-7 TEMP `toHome()` stub with:

```java
    public void toHome() {
        com.company.pos.terminal.view.HomeController controller =
                new com.company.pos.terminal.view.HomeController(services, this);
        setScene("/fxml/home.fxml", controller);
    }
```

- [ ] **Step 4: Land login on Home**

In `LoginController.java`, find `navigator.toTableMap()` in the login-success callback and change it to `navigator.toHome()`.

- [ ] **Step 5: Update the README**

In `pos-terminal/README.md`, update the flow description from "login → table map → order → payment" to note the new Home picker and retail path, e.g. add a short section:

```markdown
## Screens

After login the terminal shows a **Home** mode picker:

- **Dine-in** → table map → order → payment (seat-to-payment).
- **Retail sale** → a two-zone quick-service screen (category menu grid + live cart
  with a pinned estimated-total bar) → multi-tender payment → receipt.

The cart shows a client-side **estimated** pre-tax subtotal; tax, service charge, and
the grand total are authoritative only from the server's `SaleView` at checkout.
Barcode entry matches the cached catalogue client-side (the server search covers
name + SKU only).
```

- [ ] **Step 6: Full build**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -f pos-terminal/pom.xml clean test
```
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 7: Manual smoke run (optional but recommended)**

With a dev backend running (`embedded,dev` profile, seeded products across ≥2 categories, one with a modifier group), launch and walk the retail path:

```bash
./mvnw -f pos-terminal/pom.xml javafx:run
```
Verify: login → Home → Retail → tap/search/scan an item (total bar pulses amber) → Charge → Cash/Card/Wallet (multi-tender covers the due) → receipt with lines, tax, grand total, change → Done returns to Home.

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/resources/fxml/home.fxml \
        pos-terminal/src/main/java/com/company/pos/terminal/view/HomeController.java \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/LoginController.java \
        pos-terminal/README.md
git commit -m "feat(terminal): Home mode picker + retail entry; login lands on Home

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage** (against `2026-07-11-terminal-retail-slice-design.md`):

| Spec item | Task |
| --- | --- |
| Emerald semantic `app.css` (tokens, category edges, tender colours, type scale, total bar) | 1 |
| System fonts + `.ttf` drop-in hook | 1 |
| `ProductView.barcode` + client-side barcode lookup | 2 |
| `CartApi` + cart DTOs | 3 |
| `SaleView` receipt fields (lines, payments, discount) | 4 |
| Retail checkout `POST /sales` | 5 |
| Multi-tender + Wallet payment, short-cash rejection | 6, 7 |
| Payment screen tender colours + authoritative receipt | 7 |
| `RetailViewModel` (est. subtotal, item-added pulse, barcode/search) | 8 |
| Retail two-zone screen, quantity steppers, pinned pulsing total bar, empty-cart state, fast entry | 9 |
| `ui.reduced-motion` degrade | 9 |
| Home mode picker + navigation; login → Home | 10 |
| Dine-in path preserved | 7 (Mode.DINE_IN), 10 (Home routes to it) |
| Deferred: discounts, hold/resume, void, split | Out of scope — not built |

**Placeholder scan:** No "TBD"/"add error handling"/"similar to Task N" — every step shows the code or exact command.

**Type consistency:** `PaymentViewModel.CheckoutGateway` used identically in Tasks 6 & 7. `TenderInput(String method, BigDecimal amount, BigDecimal tendered)` consistent (Tasks 5–7). `CartView`/`CartLineView` field names consistent (Tasks 3, 8, 9). `SaleView` 10-arg constructor consistent (Tasks 4, 6). `RetailViewModel.itemAddedCount()`/`estimatedTotal()`/`cartId()` used consistently (Tasks 8, 9). `Navigator.toRetailPayment/toHome/toRetail` — TEMP stubs in Task 7 are replaced with real screens in Tasks 9 & 10, and the plan flags this explicitly at each site so no dangling reference remains after Task 10.

**Ordering note for the executor:** Task 6 intentionally leaves `PaymentController` non-compiling until Task 7 (it runs only its own VM test). Every other task ends on a green full `clean test`. Do the tasks strictly in order.
