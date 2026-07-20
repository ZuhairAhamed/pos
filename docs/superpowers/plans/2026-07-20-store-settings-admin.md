# Store Settings Admin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add typed settings + write-time validation + an ADMIN `GET /config` to the backend, and an ADMIN terminal console to view/edit the store-operational settings.

**Architecture:** `configuration` gains a `SettingType` enum, a `SettingType type()` on each `SettingKey`, value validation in `ConfigurationService.put`, a `List<SettingView> list()`, and a `GET /config` endpoint (ADMIN). The terminal adds a `ConfigApi`, pure `SettingValidation` + `SettingsCatalog` helpers, a synchronous `SettingsViewModel`, and a programmatically-built grouped **Settings** screen with an ADMIN-only tile. No new audit path (`PUT /config/{key}` already publishes `SettingChanged` → the audit listener already records it).

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Modulith; JavaFX terminal (separate Maven build).

## Global Constraints

- **No migration** (the `setting` table exists; `SettingType` is enum code, not a stored column). **No new audit path.** **No new module dependency.**
- **Validation is defense-in-depth:** the server `SettingType.validate` (throws `DomainException.validation`) is the authority; the terminal `SettingValidation` mirrors it to give a friendly message before the PUT.
- **Terminal FX-threading convention.** VM methods are synchronous, return plain values; the controller runs them off the FX thread via `FxTasks.run(work, onDone, onError)`, reading results in the FX-thread `onDone` via a `holder[]`. The only observable a VM writes off-thread is `errorMessage`, and only inside `ui.accept(...)`. Never call a blocking VM/HTTP method inside `onDone` — each mutation's `onDone` re-kicks `reload()`. Every VM needs an async-dispatcher regression test (a deferred, undrained `ui` dispatcher — assert `errorMessage` "" pre-drain, then the error post-drain).
- **Access:** `GET /config` and the Settings tile are ADMIN (matching the existing `PUT /config` gate and the Staff/Products tiles). `SecurityConfig` needs NO change.
- **Curation is terminal-side.** `GET /config` returns ALL 18 keys typed; the terminal `SettingsCatalog` filters to the curated editable set, groups them, and owns labels + the live-critical flag.
- **`VAT_RATE` stays a fraction** (0.15 = 15%); do not change its semantics.
- **Builds:** backend `./mvnw` (JDK 21 — `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`); terminal `./mvnw -f pos-terminal/pom.xml` (headless).

---

### Task 1: Backend — SettingType, validation, GET /config

**Files:**
- Create: `src/main/java/com/company/pos/configuration/api/SettingType.java`
- Modify: `src/main/java/com/company/pos/configuration/api/SettingKey.java`
- Create: `src/main/java/com/company/pos/configuration/api/SettingView.java`
- Modify: `src/main/java/com/company/pos/configuration/api/ConfigurationService.java`
- Modify: `src/main/java/com/company/pos/configuration/application/DefaultConfigurationService.java`
- Modify: `src/main/java/com/company/pos/configuration/web/ConfigurationController.java`
- Test: `src/test/java/com/company/pos/configuration/SettingTypeTest.java`
- Test: `src/test/java/com/company/pos/configuration/ConfigurationServiceTest.java` (add cases)
- Test: `src/test/java/com/company/pos/configuration/ConfigurationControllerTest.java` (add cases)

**Interfaces:**
- Produces (for terminal Tasks 2–4): `GET /config` (ADMIN) → `List<SettingView>` where `SettingView(String name, String key, String value, String defaultValue, String type)` — `name` is the enum constant (the PUT path segment), `type` is `SettingType.name()`. `PUT /config/{name}` unchanged but now 400s on an invalid value.

- [ ] **Step 1: Write the SettingType validation test (fails first)**

Create `src/test/java/com/company/pos/configuration/SettingTypeTest.java`:

```java
package com.company.pos.configuration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.configuration.api.SettingType;
import org.junit.jupiter.api.Test;

class SettingTypeTest {

    @Test
    void stringRejectsBlank() {
        assertThatThrownBy(() -> SettingType.STRING.validate("  ")).isInstanceOf(DomainException.class);
        assertThatCode(() -> SettingType.STRING.validate("x")).doesNotThrowAnyException();
    }

    @Test
    void booleanAcceptsTrueFalseCaseInsensitive() {
        assertThatCode(() -> SettingType.BOOLEAN.validate("TRUE")).doesNotThrowAnyException();
        assertThatCode(() -> SettingType.BOOLEAN.validate("false")).doesNotThrowAnyException();
        assertThatThrownBy(() -> SettingType.BOOLEAN.validate("yes")).isInstanceOf(DomainException.class);
    }

    @Test
    void intRejectsNonNumberAndNegative() {
        assertThatCode(() -> SettingType.INT.validate("7")).doesNotThrowAnyException();
        assertThatThrownBy(() -> SettingType.INT.validate("x")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> SettingType.INT.validate("-1")).isInstanceOf(DomainException.class);
    }

    @Test
    void decimalRejectsNonNumberAndNegative() {
        assertThatCode(() -> SettingType.DECIMAL.validate("0.15")).doesNotThrowAnyException();
        assertThatThrownBy(() -> SettingType.DECIMAL.validate("abc")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> SettingType.DECIMAL.validate("-1")).isInstanceOf(DomainException.class);
    }

    @Test
    void percentRejectsOutOfRange() {
        assertThatCode(() -> SettingType.PERCENT.validate("10")).doesNotThrowAnyException();
        assertThatThrownBy(() -> SettingType.PERCENT.validate("150")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> SettingType.PERCENT.validate("-5")).isInstanceOf(DomainException.class);
    }

    @Test
    void csvRejectsEmpty() {
        assertThatCode(() -> SettingType.CSV.validate("A,B")).doesNotThrowAnyException();
        assertThatThrownBy(() -> SettingType.CSV.validate("  ")).isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Step 2: Create SettingType**

Create `src/main/java/com/company/pos/configuration/api/SettingType.java`:

```java
package com.company.pos.configuration.api;

import com.company.pos.common.exception.DomainException;
import java.math.BigDecimal;

/** The value type of a {@link SettingKey}, used to render the right editor and to validate writes. */
public enum SettingType {
    STRING, BOOLEAN, INT, DECIMAL, PERCENT, CSV;

    /** Rejects a value that does not fit this type. Callers pass the raw string a client submitted. */
    public void validate(String value) {
        switch (this) {
            case STRING -> {
                if (value == null || value.isBlank()) {
                    throw DomainException.validation("Value is required");
                }
            }
            case BOOLEAN -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw DomainException.validation("Must be true or false");
                }
            }
            case INT -> {
                int n;
                try {
                    n = Integer.parseInt(value == null ? "" : value.trim());
                } catch (NumberFormatException e) {
                    throw DomainException.validation("Must be a whole number");
                }
                if (n < 0) {
                    throw DomainException.validation("Must be zero or greater");
                }
            }
            case DECIMAL -> {
                BigDecimal d = parseDecimal(value);
                if (d.signum() < 0) {
                    throw DomainException.validation("Must be zero or greater");
                }
            }
            case PERCENT -> {
                BigDecimal p = parseDecimal(value);
                if (p.signum() < 0 || p.compareTo(new BigDecimal("100")) > 0) {
                    throw DomainException.validation("Must be a percentage between 0 and 100");
                }
            }
            case CSV -> {
                boolean hasToken = value != null && value.lines()
                        .flatMap(l -> java.util.Arrays.stream(l.split(",")))
                        .anyMatch(t -> !t.isBlank());
                if (!hasToken) {
                    throw DomainException.validation("Enter at least one value");
                }
            }
        }
    }

    private static BigDecimal parseDecimal(String value) {
        try {
            return new BigDecimal(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            throw DomainException.validation("Must be a number");
        }
    }
}
```

- [ ] **Step 3: Run the SettingType test**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=SettingTypeTest`
Expected: PASS (6 tests).

- [ ] **Step 4: Add the type to SettingKey**

Replace `src/main/java/com/company/pos/configuration/api/SettingKey.java` with (adds the `SettingType` arg to every constant + a `type()` accessor):

```java
package com.company.pos.configuration.api;

public enum SettingKey {
    STORE_NAME("store.name", "My Store", SettingType.STRING),
    CURRENCY_CODE("currency.code", "SAR", SettingType.STRING),
    LOCALE("locale", "en", SettingType.STRING),
    TAX_INCLUSIVE("tax.inclusive", "false", SettingType.BOOLEAN),
    RECEIPT_PRINTER_PORT("printer.port", "COM1", SettingType.STRING),
    VAT_RATE("tax.rate", "0.15", SettingType.DECIMAL),
    STORE_ID("store.id", "S01", SettingType.STRING),
    TERMINAL_ID("terminal.id", "T01", SettingType.STRING),
    INVENTORY_LOCATION("inventory.location", "MAIN", SettingType.STRING),
    DISCOUNT_REASON_CODES("discount.reason.codes", "DAMAGED,PRICE_MATCH,LOYALTY,MANAGER_COMP", SettingType.CSV),
    DISCOUNT_CASHIER_MAX_PERCENT("discount.cashier.max.percent", "10", SettingType.PERCENT),
    DISCOUNT_CASHIER_MAX_AMOUNT("discount.cashier.max.amount", "20.00", SettingType.DECIMAL),
    DASHBOARD_REVENUE_WINDOW_DAYS("dashboard.revenue.window.days", "7", SettingType.INT),
    DINING_TABLE_DEFAULT_SEATS("dining.table.default.seats", "4", SettingType.INT),
    KITCHEN_DEFAULT_STATION("kitchen.default.station", "Kitchen", SettingType.STRING),
    SERVICE_CHARGE_ENABLED("service.charge.enabled", "false", SettingType.BOOLEAN),
    SERVICE_CHARGE_PERCENT("service.charge.percent", "0", SettingType.PERCENT),
    SERVICE_CHARGE_LABEL("service.charge.label", "Service Charge", SettingType.STRING);

    private final String key;
    private final String defaultValue;
    private final SettingType type;

    SettingKey(String key, String defaultValue, SettingType type) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.type = type;
    }

    public String key() {
        return key;
    }

    public String defaultValue() {
        return defaultValue;
    }

    public SettingType type() {
        return type;
    }
}
```

- [ ] **Step 5: Create SettingView**

Create `src/main/java/com/company/pos/configuration/api/SettingView.java`:

```java
package com.company.pos.configuration.api;

/** A read projection of one setting. {@code name} is the enum constant (the {@code PUT /config/{name}}
 *  path segment); {@code type} is {@link SettingType#name()}. */
public record SettingView(String name, String key, String value, String defaultValue, String type) {
}
```

- [ ] **Step 6: Extend the service interface + impl**

In `configuration/api/ConfigurationService.java`, add the import `import java.util.List;` and the method (after `put`):

```java
    List<SettingView> list();
```

In `configuration/application/DefaultConfigurationService.java`: add imports `import com.company.pos.configuration.api.SettingView;` and `import java.util.List;`. Change `put` to validate first, and add `list()`:

```java
    @Override
    public void put(SettingKey key, String value) {
        key.type().validate(value);
        Setting setting = repository.findById(key.key())
                .orElseGet(() -> new Setting(key.key(), value));
        setting.setValue(value);
        repository.save(setting);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SettingView> list() {
        return java.util.Arrays.stream(SettingKey.values())
                .map(k -> new SettingView(k.name(), k.key(), getString(k), k.defaultValue(), k.type().name()))
                .toList();
    }
```

- [ ] **Step 7: Add the GET endpoint**

In `configuration/web/ConfigurationController.java`, add imports:

```java
import com.company.pos.configuration.api.SettingView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
```

Add this handler (before the existing `update` method):

```java
    @GetMapping("/config")
    @PreAuthorize("hasRole('ADMIN')")
    List<SettingView> list() {
        return config.list();
    }
```

- [ ] **Step 8: Add service + controller test cases**

In `src/test/java/com/company/pos/configuration/ConfigurationServiceTest.java`, add (imports `assertThatThrownBy` and `DomainException` as needed):

```java
    @Test
    void rejectsInvalidValueOnPut() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> configuration.put(SettingKey.VAT_RATE, "abc"))
                .isInstanceOf(com.company.pos.common.exception.DomainException.class);
    }

    @Test
    void listReturnsAllKeysTyped() {
        java.util.List<com.company.pos.configuration.api.SettingView> all = configuration.list();
        assertThat(all).hasSize(SettingKey.values().length);
        assertThat(all).anySatisfy(v -> {
            assertThat(v.name()).isEqualTo("VAT_RATE");
            assertThat(v.type()).isEqualTo("DECIMAL");
            assertThat(v.defaultValue()).isEqualTo("0.15");
        });
    }
```

In `src/test/java/com/company/pos/configuration/ConfigurationControllerTest.java`, add:

```java
    @Test
    void adminCanListSettings() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/config")
                        .with(jwt().jwt(j -> j.subject("admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[?(@.name=='VAT_RATE')].type").value("DECIMAL"));
    }

    @Test
    void nonAdminCannotListSettings() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/config")
                        .with(jwt().jwt(j -> j.subject("mgr"))
                                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidValueIsRejected() throws Exception {
        mvc.perform(put("/config/VAT_RATE")
                        .with(jwt().jwt(j -> j.subject("admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"abc\"}"))
                .andExpect(status().isBadRequest());
    }
```

- [ ] **Step 9: Run the configuration + boundary tests**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=SettingTypeTest,SettingKeyTest,ConfigurationServiceTest,ConfigurationControllerTest,ModularityTests`
Expected: PASS (all classes; `ModularityTests` confirms no boundary regression — no new module dependency was introduced).

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/company/pos/configuration src/test/java/com/company/pos/configuration
git commit -m "feat(configuration): SettingType + write validation + GET /config"
```

---

### Task 2: Terminal — API client + pure validation & catalog helpers

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/SettingView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/SettingUpdateRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/ConfigApi.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/SettingValidation.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/SettingsCatalog.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/SettingValidationTest.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/SettingsCatalogTest.java`

**Interfaces:**
- Consumes: `ApiClient` (`get(path, TypeReference)`, `put(path, body, TypeReference)` — pass `null` for a 204 void PUT, mirroring `UsersApi`/`ProductAdminApi`).
- Produces (for Tasks 3/4): `ConfigApi.list()` / `update(name, value)`; `SettingValidation.validate(String type, String value)` → message or null; `SettingsCatalog.group(List<SettingView>)` → `List<Group>`, `Group(String category, List<Row> rows)`, `Row(SettingView view, String label, boolean liveCritical)`, and `SettingsCatalog.isLiveCritical(String name)`.

- [ ] **Step 1: DTO + request**

Create `terminal/api/dto/SettingView.java`:

```java
package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SettingView(String name, String key, String value, String defaultValue, String type) {
}
```

Create `terminal/api/SettingUpdateRequest.java`:

```java
package com.company.pos.terminal.api;

/** Outbound body for PUT /config/{name}. */
public record SettingUpdateRequest(String value) {
}
```

- [ ] **Step 2: API client**

Create `terminal/api/ConfigApi.java`:

```java
package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.SettingView;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;

/** Typed client for the store server's configuration endpoints (ADMIN-gated).
 *  Non-final so view-model tests can subclass with fakes. */
public class ConfigApi {

    private final ApiClient client;

    public ConfigApi(ApiClient client) {
        this.client = client;
    }

    /** GET /config — all settings, typed, with current + default values. */
    public List<SettingView> list() {
        return client.get("/config", new TypeReference<List<SettingView>>() {});
    }

    /** PUT /config/{name} — update one setting (204, no body). */
    public void update(String name, String value) {
        client.put("/config/" + name, new SettingUpdateRequest(value), null);
    }
}
```

- [ ] **Step 3: Pure validation helper + test**

Create `terminal/viewmodel/SettingValidation.java`:

```java
package com.company.pos.terminal.viewmodel;

import java.math.BigDecimal;
import java.util.Arrays;

/** Client-side mirror of the server's SettingType validation. Returns a friendly message, or null
 *  when the value is acceptable. The server re-validates authoritatively. */
public final class SettingValidation {

    private SettingValidation() {
    }

    public static String validate(String type, String value) {
        if (type == null) {
            return null;
        }
        switch (type) {
            case "STRING":
                return (value == null || value.isBlank()) ? "Value is required" : null;
            case "BOOLEAN":
                return ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))
                        ? null : "Must be true or false";
            case "INT": {
                int n;
                try {
                    n = Integer.parseInt(value == null ? "" : value.trim());
                } catch (NumberFormatException e) {
                    return "Must be a whole number";
                }
                return n < 0 ? "Must be zero or greater" : null;
            }
            case "DECIMAL": {
                BigDecimal d = parse(value);
                if (d == null) {
                    return "Must be a number";
                }
                return d.signum() < 0 ? "Must be zero or greater" : null;
            }
            case "PERCENT": {
                BigDecimal p = parse(value);
                if (p == null) {
                    return "Must be a number";
                }
                return (p.signum() < 0 || p.compareTo(new BigDecimal("100")) > 0)
                        ? "Must be a percentage between 0 and 100" : null;
            }
            case "CSV": {
                boolean hasToken = value != null
                        && Arrays.stream(value.split(",")).anyMatch(t -> !t.isBlank());
                return hasToken ? null : "Enter at least one value";
            }
            default:
                return null;
        }
    }

    private static BigDecimal parse(String value) {
        try {
            return new BigDecimal(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
```

Create `terminal/src/test/java/com/company/pos/terminal/viewmodel/SettingValidationTest.java`:

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SettingValidationTest {

    @Test
    void stringBlankRejected() {
        assertNotNull(SettingValidation.validate("STRING", "  "));
        assertNull(SettingValidation.validate("STRING", "x"));
    }

    @Test
    void booleanChecked() {
        assertNull(SettingValidation.validate("BOOLEAN", "TRUE"));
        assertNotNull(SettingValidation.validate("BOOLEAN", "yes"));
    }

    @Test
    void intChecked() {
        assertNull(SettingValidation.validate("INT", "7"));
        assertNotNull(SettingValidation.validate("INT", "x"));
        assertNotNull(SettingValidation.validate("INT", "-1"));
    }

    @Test
    void decimalChecked() {
        assertNull(SettingValidation.validate("DECIMAL", "0.15"));
        assertNotNull(SettingValidation.validate("DECIMAL", "abc"));
        assertNotNull(SettingValidation.validate("DECIMAL", "-1"));
    }

    @Test
    void percentChecked() {
        assertNull(SettingValidation.validate("PERCENT", "10"));
        assertNotNull(SettingValidation.validate("PERCENT", "150"));
    }

    @Test
    void csvChecked() {
        assertNull(SettingValidation.validate("CSV", "A,B"));
        assertNotNull(SettingValidation.validate("CSV", "  "));
    }

    @Test
    void unknownTypeAccepts() {
        assertNull(SettingValidation.validate("MYSTERY", "anything"));
    }
}
```

- [ ] **Step 4: Pure catalog helper + test**

Create `terminal/viewmodel/SettingsCatalog.java`:

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.dto.SettingView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Terminal-side presentation of the server's settings: which keys to show, their friendly labels,
 *  their category grouping, and the live-critical flag. Server keys not in this catalog are hidden. */
public final class SettingsCatalog {

    private SettingsCatalog() {
    }

    public record Entry(String category, String label, boolean liveCritical) {
    }

    public record Row(SettingView view, String label, boolean liveCritical) {
    }

    public record Group(String category, List<Row> rows) {
    }

    private static final Map<String, Entry> CATALOG = catalog();

    /** Curated keys in display order (insertion order = category order = within-category order). */
    private static Map<String, Entry> catalog() {
        LinkedHashMap<String, Entry> m = new LinkedHashMap<>();
        m.put("STORE_NAME", new Entry("Store info", "Store name", false));
        m.put("CURRENCY_CODE", new Entry("Store info", "Currency code", true));
        m.put("LOCALE", new Entry("Store info", "Locale", false));
        m.put("TAX_INCLUSIVE", new Entry("Tax", "Prices include tax", true));
        m.put("VAT_RATE", new Entry("Tax", "VAT rate (fraction, e.g. 0.15)", true));
        m.put("SERVICE_CHARGE_ENABLED", new Entry("Service charge", "Service charge enabled", true));
        m.put("SERVICE_CHARGE_PERCENT", new Entry("Service charge", "Service charge %", true));
        m.put("SERVICE_CHARGE_LABEL", new Entry("Service charge", "Service charge label", false));
        m.put("DISCOUNT_CASHIER_MAX_PERCENT", new Entry("Discounts", "Cashier max discount %", true));
        m.put("DISCOUNT_CASHIER_MAX_AMOUNT", new Entry("Discounts", "Cashier max discount amount", true));
        m.put("DISCOUNT_REASON_CODES", new Entry("Discounts", "Discount reason codes (CSV)", false));
        m.put("DINING_TABLE_DEFAULT_SEATS", new Entry("Operations", "Default table seats", false));
        m.put("KITCHEN_DEFAULT_STATION", new Entry("Operations", "Default kitchen station", false));
        m.put("DASHBOARD_REVENUE_WINDOW_DAYS", new Entry("Operations", "Dashboard revenue window (days)", false));
        return m;
    }

    public static boolean isLiveCritical(String name) {
        Entry e = CATALOG.get(name);
        return e != null && e.liveCritical();
    }

    /** Group the server settings into ordered category sections, dropping any key not in the catalog. */
    public static List<Group> group(List<SettingView> serverList) {
        Map<String, SettingView> byName = new LinkedHashMap<>();
        if (serverList != null) {
            for (SettingView v : serverList) {
                byName.put(v.name(), v);
            }
        }
        LinkedHashMap<String, List<Row>> buckets = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> e : CATALOG.entrySet()) {
            SettingView v = byName.get(e.getKey());
            if (v == null) {
                continue;
            }
            buckets.computeIfAbsent(e.getValue().category(), k -> new ArrayList<>())
                    .add(new Row(v, e.getValue().label(), e.getValue().liveCritical()));
        }
        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<Row>> b : buckets.entrySet()) {
            groups.add(new Group(b.getKey(), b.getValue()));
        }
        return groups;
    }
}
```

Create `terminal/src/test/java/com/company/pos/terminal/viewmodel/SettingsCatalogTest.java`:

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.dto.SettingView;
import com.company.pos.terminal.viewmodel.SettingsCatalog.Group;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettingsCatalogTest {

    private SettingView v(String name) {
        return new SettingView(name, name.toLowerCase(), "x", "x", "STRING");
    }

    private List<SettingView> fullServerList() {
        return List.of(
                v("STORE_NAME"), v("CURRENCY_CODE"), v("LOCALE"),
                v("TAX_INCLUSIVE"), v("VAT_RATE"),
                v("SERVICE_CHARGE_ENABLED"), v("SERVICE_CHARGE_PERCENT"), v("SERVICE_CHARGE_LABEL"),
                v("DISCOUNT_CASHIER_MAX_PERCENT"), v("DISCOUNT_CASHIER_MAX_AMOUNT"), v("DISCOUNT_REASON_CODES"),
                v("DINING_TABLE_DEFAULT_SEATS"), v("KITCHEN_DEFAULT_STATION"), v("DASHBOARD_REVENUE_WINDOW_DAYS"),
                // hidden keys the server also returns:
                v("STORE_ID"), v("TERMINAL_ID"), v("RECEIPT_PRINTER_PORT"), v("INVENTORY_LOCATION"));
    }

    @Test
    void groupsInFixedCategoryOrder() {
        List<Group> groups = SettingsCatalog.group(fullServerList());
        assertEquals(List.of("Store info", "Tax", "Service charge", "Discounts", "Operations"),
                groups.stream().map(Group::category).toList());
        assertEquals(3, groups.get(0).rows().size());   // Store info
    }

    @Test
    void hiddenKeysAreDropped() {
        boolean anyHidden = SettingsCatalog.group(fullServerList()).stream()
                .flatMap(g -> g.rows().stream())
                .anyMatch(r -> r.view().name().equals("STORE_ID")
                        || r.view().name().equals("TERMINAL_ID")
                        || r.view().name().equals("RECEIPT_PRINTER_PORT")
                        || r.view().name().equals("INVENTORY_LOCATION"));
        assertFalse(anyHidden);
    }

    @Test
    void liveCriticalFlags() {
        assertTrue(SettingsCatalog.isLiveCritical("VAT_RATE"));
        assertFalse(SettingsCatalog.isLiveCritical("STORE_NAME"));
        assertFalse(SettingsCatalog.isLiveCritical("STORE_ID"));   // hidden → not critical
    }
}
```

- [ ] **Step 5: Run the pure helper tests**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=SettingValidationTest,SettingsCatalogTest`
Expected: PASS (7 + 3 tests).

- [ ] **Step 6: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/dto/SettingView.java pos-terminal/src/main/java/com/company/pos/terminal/api/SettingUpdateRequest.java pos-terminal/src/main/java/com/company/pos/terminal/api/ConfigApi.java pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/SettingValidation.java pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/SettingsCatalog.java pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/SettingValidationTest.java pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/SettingsCatalogTest.java
git commit -m "feat(terminal): ConfigApi + SettingValidation + SettingsCatalog"
```

---

### Task 3: Terminal — SettingsViewModel

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/SettingsViewModel.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/SettingsViewModelTest.java`

**Interfaces:**
- Consumes: `ConfigApi` (Task 2), `SettingValidation` (Task 2), `ApiException`/`ProblemDetail`.
- Produces (for Task 4): `SettingsViewModel(ConfigApi, Consumer<Runnable>)` with `loadSettings()` and `update(String name, String value, String type)` + `errorMessage()`.

- [ ] **Step 1: The view-model**

Create `terminal/viewmodel/SettingsViewModel.java`:

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.ConfigApi;
import com.company.pos.terminal.api.dto.SettingView;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the store-settings screen. Synchronous like the other VMs — the controller runs it
 * off the FX thread via FxTasks and reads the return value; the only observable written off-thread is
 * {@code errorMessage}, inside the {@code ui} dispatcher.
 */
public class SettingsViewModel {

    private final ConfigApi api;
    private final Consumer<Runnable> ui;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public SettingsViewModel(ConfigApi api, Consumer<Runnable> ui) {
        this.api = api;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    public List<SettingView> loadSettings() {
        try {
            List<SettingView> list = api.list();
            ui.accept(() -> errorMessage.set(""));
            return list;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public boolean update(String name, String value, String type) {
        String err = SettingValidation.validate(type, value);
        if (err != null) {
            ui.accept(() -> errorMessage.set(err));
            return false;
        }
        try {
            api.update(name, value);
            ui.accept(() -> errorMessage.set(""));
            return true;
        } catch (ApiException e) {
            fail(e);
            return false;
        }
    }

    private void fail(ApiException e) {
        String msg = messageOf(e);
        ui.accept(() -> errorMessage.set(msg));
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

- [ ] **Step 2: The test**

Create `terminal/src/test/java/com/company/pos/terminal/viewmodel/SettingsViewModelTest.java`:

```java
package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.ConfigApi;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.dto.SettingView;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettingsViewModelTest {

    private SettingsViewModel vm(ConfigApi api) {
        return new SettingsViewModel(api, Runnable::run);
    }

    @Test
    void loadSettingsReturnsListAndClearsError() {
        ConfigApi api = new ConfigApi(null) {
            @Override public List<SettingView> list() {
                return List.of(new SettingView("STORE_NAME", "store.name", "My Store", "My Store", "STRING"));
            }
        };
        SettingsViewModel vm = vm(api);
        assertEquals(1, vm.loadSettings().size());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void updateRejectsInvalidValueWithoutCallingApi() {
        boolean[] called = {false};
        ConfigApi api = new ConfigApi(null) {
            @Override public void update(String name, String value) {
                called[0] = true;
            }
        };
        SettingsViewModel vm = vm(api);
        assertFalse(vm.update("VAT_RATE", "abc", "DECIMAL"));
        assertFalse(called[0]);
        assertEquals("Must be a number", vm.errorMessage().get());
    }

    @Test
    void updateValidCallsApiAndReturnsTrue() {
        boolean[] called = {false};
        ConfigApi api = new ConfigApi(null) {
            @Override public void update(String name, String value) {
                called[0] = true;
            }
        };
        SettingsViewModel vm = vm(api);
        assertTrue(vm.update("VAT_RATE", "0.15", "DECIMAL"));
        assertTrue(called[0]);
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void deferredDispatcherHoldsErrorUntilDrained() {
        ConfigApi api = new ConfigApi(null) {
            @Override public List<SettingView> list() {
                throw new ApiException(500, new ProblemDetail("Error", 500, "boom"), "HTTP 500");
            }
        };
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        SettingsViewModel vm = new SettingsViewModel(api, queue::add);
        assertNull(vm.loadSettings());
        assertEquals("", vm.errorMessage().get());   // deferred: not applied yet
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
        assertEquals("boom", vm.errorMessage().get());
    }
}
```

- [ ] **Step 3: Run the test**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=SettingsViewModelTest`
Expected: PASS (4 tests).

- [ ] **Step 4: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/SettingsViewModel.java pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/SettingsViewModelTest.java
git commit -m "feat(terminal): SettingsViewModel"
```

---

### Task 4: Terminal — settings screen, wiring, admin tile

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/SettingsController.java`
- Create: `pos-terminal/src/main/resources/fxml/settings.fxml`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/AdminController.java`
- Modify: `pos-terminal/src/main/resources/fxml/admin.fxml`

**Interfaces:**
- Consumes: `SettingsViewModel` (Task 3), `SettingsCatalog`/`Group`/`Row` (Task 2), `ConfigApi` (Task 2), existing `Services`/`Navigator`/`FxTasks`.

- [ ] **Step 1: Register ConfigApi in Services**

In `terminal/app/Services.java`: add `import com.company.pos.terminal.api.ConfigApi;`, add the field `public final ConfigApi configApi;` next to `kitchenApi`, and initialize it in the constructor `this.configApi = new ConfigApi(apiClient);` (after `this.tableAdminApi = new TableAdminApi(apiClient);`).

- [ ] **Step 2: The controller**

Create `terminal/view/SettingsController.java`:

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.SettingView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.SettingsCatalog;
import com.company.pos.terminal.viewmodel.SettingsCatalog.Group;
import com.company.pos.terminal.viewmodel.SettingsCatalog.Row;
import com.company.pos.terminal.viewmodel.SettingsViewModel;
import java.util.List;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * ADMIN store-settings screen. Loads the typed settings off the FX thread via FxTasks, then builds
 * the grouped form programmatically (mixed editor types per setting). Save validates client-side,
 * confirms live-critical edits, then PUTs via the VM; each save's onDone re-kicks reload().
 */
public class SettingsController {

    private static final System.Logger LOG = System.getLogger(SettingsController.class.getName());

    private final Services services;
    private final Navigator navigator;
    private final SettingsViewModel vm;

    @FXML private Label errorLabel;
    @FXML private Button backButton;
    @FXML private VBox settingsBox;

    public SettingsController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new SettingsViewModel(services.configApi, Platform::runLater);
    }

    @FXML
    public void initialize() {
        errorLabel.textProperty().bind(vm.errorMessage());
        backButton.setOnAction(e -> navigator.toAdmin());
        reload();
    }

    @SuppressWarnings("unchecked")
    private void reload() {
        final List<SettingView>[] h = new List[1];
        FxTasks.run(
                () -> h[0] = vm.loadSettings(),
                () -> { if (h[0] != null) buildSections(h[0]); },
                err -> LOG.log(System.Logger.Level.ERROR, "Load settings failed", err));
    }

    private void buildSections(List<SettingView> settings) {
        settingsBox.getChildren().clear();
        for (Group group : SettingsCatalog.group(settings)) {
            Label header = new Label(group.category());
            header.getStyleClass().add("field-label");
            settingsBox.getChildren().add(header);
            for (Row row : group.rows()) {
                settingsBox.getChildren().add(buildRow(row));
            }
        }
    }

    private Node buildRow(Row row) {
        SettingView v = row.view();
        Label label = new Label(row.label());
        label.setMinWidth(280);

        Control editor;
        Supplier<String> read;
        if ("BOOLEAN".equals(v.type())) {
            CheckBox cb = new CheckBox();
            cb.setSelected(Boolean.parseBoolean(v.value()));
            editor = cb;
            read = () -> cb.isSelected() ? "true" : "false";
        } else {
            TextField tf = new TextField(v.value());
            editor = tf;
            read = tf::getText;
        }
        HBox.setHgrow(editor, Priority.ALWAYS);

        Button save = new Button("Save");
        save.getStyleClass().add("btn-secondary");
        save.setOnAction(e -> saveSetting(v.name(), v.type(), row.label(), row.liveCritical(), read.get()));

        HBox box = new HBox(12, label, editor, save);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void saveSetting(String name, String type, String label, boolean liveCritical, String value) {
        if (liveCritical && !confirmLiveCritical(label)) {
            return;
        }
        final boolean[] holder = {false};
        FxTasks.run(
                () -> holder[0] = vm.update(name, value, type),
                () -> { if (holder[0]) reload(); },
                err -> LOG.log(System.Logger.Level.ERROR, "Save setting failed", err));
    }

    private boolean confirmLiveCritical(String label) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Changing \"" + label + "\" affects how orders are priced. Apply now?",
                ButtonType.OK, ButtonType.CANCEL);
        alert.setHeaderText("Live setting change");
        alert.getDialogPane().getStyleClass().add("drawer-modal");
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }
}
```

- [ ] **Step 3: The FXML**

Create `terminal/src/main/resources/fxml/settings.fxml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.scene.control.Button?>
<?import javafx.scene.control.Label?>
<?import javafx.scene.control.ScrollPane?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.layout.Pane?>
<?import javafx.scene.layout.VBox?>

<VBox styleClass="screen" spacing="16" xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml">
  <HBox spacing="16" alignment="CENTER_LEFT">
    <Label text="Store settings" styleClass="title"/>
    <Pane HBox.hgrow="ALWAYS"/>
    <Label fx:id="errorLabel" styleClass="error-text"/>
    <Button fx:id="backButton" text="Back" styleClass="btn-secondary"/>
  </HBox>

  <ScrollPane fitToWidth="true" VBox.vgrow="ALWAYS">
    <VBox fx:id="settingsBox" spacing="16"/>
  </ScrollPane>
</VBox>
```

- [ ] **Step 4: Navigator route**

In `terminal/app/Navigator.java`, after the `toTables()` method, add:

```java
    public void toSettings() {
        com.company.pos.terminal.view.SettingsController controller =
                new com.company.pos.terminal.view.SettingsController(services, this);
        setScene("/fxml/settings.fxml", controller);
    }
```

- [ ] **Step 5: Admin tile — controller**

In `terminal/view/AdminController.java`, add the field (next to `tablesButton`):

```java
    @FXML private Button settingsButton;
```

and in `initialize()`, after the `tablesButton` block, add (ADMIN-only gate, reusing the existing `admin` boolean):

```java
        settingsButton.setVisible(admin);
        settingsButton.setManaged(admin);
        settingsButton.setOnAction(e -> navigator.toSettings());
```

- [ ] **Step 6: Admin tile — FXML**

In `terminal/src/main/resources/fxml/admin.fxml`, add the tile after the `tablesButton` button, inside the tiles `HBox`:

```xml
      <Button fx:id="settingsButton" text="Settings" styleClass="home-tile"/>
```

- [ ] **Step 7: Run the full terminal suite**

Run: `./mvnw -f pos-terminal/pom.xml clean test`
Expected: PASS (full suite, including the new `SettingValidationTest`, `SettingsCatalogTest`, `SettingsViewModelTest`; the FXML loads with `errorLabel`/`backButton`/`settingsBox` matching the controller's three `@FXML` fields).

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/SettingsController.java pos-terminal/src/main/resources/fxml/settings.fxml pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java pos-terminal/src/main/java/com/company/pos/terminal/view/AdminController.java pos-terminal/src/main/resources/fxml/admin.fxml
git commit -m "feat(terminal): store settings admin screen + Settings tile (ADMIN)"
```

---

## Self-Review

**Spec coverage:** SettingType + validation (Task 1) ✓; SettingKey.type() all 18 keys (Task 1) ✓; write-time validation in put (Task 1) ✓; ADMIN GET /config returning typed SettingView (Task 1) ✓; ConfigApi + SettingUpdateRequest + SettingView DTO (Task 2) ✓; pure SettingValidation mirror + SettingsCatalog curation/grouping/live-critical/hidden (Task 2) ✓; SettingsViewModel sync + validate-before-PUT + async regression (Task 3) ✓; programmatic grouped screen with type editors + live-critical confirm (Task 4) ✓; ADMIN-only Settings tile + wiring (Task 4) ✓; no migration / no new audit path / no new module dep (Global Constraints) ✓.

**Type consistency:** `SettingView(name, key, value, defaultValue, type)` identical on server (Task 1) and terminal DTO (Task 2). `SettingType.name()` strings ("STRING"/"BOOLEAN"/"INT"/"DECIMAL"/"PERCENT"/"CSV") are what `SettingValidation.validate` and the controller's `"BOOLEAN".equals(...)` switch on. `SettingsViewModel.update(name, value, type)` signature matches the controller call. `SettingsCatalog.group`/`Row`/`Group`/`isLiveCritical` produced in Task 2, consumed in Task 4.

**Placeholder scan:** none — every step carries full code or an exact edit target.

**Note:** `ConfigApi.update` passes a `null` `TypeReference` to `ApiClient.put` for the 204 void response, mirroring the `UsersApi`/`ProductAdminApi` void-endpoint convention. The GET/PUT web tests use the lightweight `.with(jwt()…ROLE_ADMIN)` post-processor (not the login+Awaitility flow) because audit is unchanged and no async listener is asserted here.
