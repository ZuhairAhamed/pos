# Phase 0 — Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the modular-monolith skeleton for the POS — a Spring Boot 3 application with Spring Modulith boundary enforcement, the `common` shared kernel, the `database` persistence kernel (dual run-mode: PostgreSQL store-server / SQLite embedded), a typed `configuration` store, `device` peripheral port interfaces, and CI — so every later phase has a verified base to build on.

**Architecture:** A single Spring Boot application (one deployable) whose logical modules are top-level Java packages under `com.company.pos`, with boundaries enforced at build time by Spring Modulith's `verify()` test. `common` and `database` are *open* shared-kernel modules usable by anyone; every other module may depend only on another module's `api` package. Persistence is profile-switched: the `store-server` profile uses PostgreSQL with Flyway migrations (the production source of truth); the `embedded` profile uses SQLite with Hibernate schema generation for single-register deployments.

**Tech Stack:** Java 21 · Spring Boot 3.3.x · Spring Modulith 1.2.x · Gradle (Kotlin DSL) · Spring Data JPA · Flyway · PostgreSQL + SQLite (xerial `sqlite-jdbc` + Hibernate community dialect) · JavaMoney (Moneta) · JUnit 5 · Testcontainers · GitHub Actions.

## Global Constraints

These apply to **every** task; each task's requirements implicitly include this section.

- **Root package:** `com.company.pos`. Source root `src/main/java/com/company/pos/`, test root `src/test/java/com/company/pos/`.
- **Java toolchain:** Java **21** (LTS). Configure via Gradle toolchain, not the ambient JDK.
- **Pinned versions** (verify the latest patch at setup; keep the minor lines fixed): Spring Boot `3.3.5`, Spring Modulith `1.2.5`, Gradle `8.10`, Moneta `moneta-core 1.4.4`, xerial `sqlite-jdbc 3.46.1.3`.
- **Money:** never `double`/`float` for money. Use `BigDecimal` at rest and `javax.money.MonetaryAmount` (Moneta) in the domain, via the `common` `Monies` helper. Store the currency code alongside every amount.
- **Module boundaries:** a module may reference another module only through its `api` package (or named interface). `common` and `database` are open shared modules. The Spring Modulith `verify()` test (Task 2) must stay green — adding a cross-internals dependency is a plan/build failure, not a thing to suppress.
- **Identifiers:** transactional aggregates use client-generated UUIDs (`common` `Identifiers.newId()`), never DB auto-increment.
- **Commit discipline:** every task ends with a single commit using the message shown. Commit only the files that task created/modified.
- **Conventional Commits:** commit messages use `feat:` / `chore:` / `test:` / `build:` prefixes as shown.

---

## File Structure

Single Gradle project, single Spring Boot application. Logical modules = packages.

```
POS/
├── build.gradle.kts                         # Task 1 (deps grow in Tasks 4, 6)
├── settings.gradle.kts                      # Task 1
├── gradle/wrapper/…                         # Task 1
├── .github/workflows/ci.yml                 # Task 9
├── src/main/java/com/company/pos/
│   ├── PosApplication.java                  # Task 1
│   ├── common/                              # OPEN shared kernel
│   │   ├── package-info.java                # Task 2
│   │   ├── exception/                       # Task 3  (DomainException, ErrorCode, ProblemDetailFactory, ApiExceptionHandler)
│   │   ├── util/                            # Task 4  (Identifiers, Monies)
│   │   └── events/                          # Task 5  (DomainEvent, DomainEvents)
│   ├── database/                            # OPEN persistence kernel
│   │   └── package-info.java                # Task 2 (config lives in resources / profiles)
│   ├── configuration/                       # CLOSED module (api + domain + application + infrastructure)
│   │   ├── package-info.java                # Task 2
│   │   ├── api/ConfigurationService.java    # Task 7
│   │   ├── api/SettingKey.java              # Task 7
│   │   ├── domain/Setting.java              # Task 7
│   │   ├── application/DefaultConfigurationService.java  # Task 7
│   │   └── infrastructure/SettingRepository.java         # Task 7
│   └── device/                              # CLOSED module — port interfaces only (adapters are Phase 2)
│       ├── package-info.java                # Task 2
│       └── api/                             # Task 8 (Printer, BarcodeScanner, CashDrawer, LineDisplay, Scale, PaymentTerminal + DTOs)
├── src/main/resources/
│   ├── application.yml                      # Task 1 (profiles added Task 6)
│   ├── application-store-server.yml         # Task 6
│   ├── application-embedded.yml             # Task 6
│   └── db/migration/configuration/
│       └── V1__configuration_setting.sql    # Task 7
└── src/test/java/com/company/pos/
    ├── PosApplicationTests.java             # Task 1
    ├── ModularityTests.java                 # Task 2
    ├── common/exception/ApiExceptionHandlerTest.java   # Task 3
    ├── common/util/IdentifiersTest.java                # Task 4
    ├── common/util/MoniesTest.java                     # Task 4
    ├── common/events/DomainEventsTest.java             # Task 5
    ├── database/DatabaseStoreServerTest.java           # Task 6
    ├── database/EmbeddedProfileTest.java               # Task 6 + Task 9
    ├── configuration/ConfigurationServiceTest.java     # Task 7
    └── device/DevicePortContractTest.java              # Task 8
```

**Design note — dual persistence:** Flyway runs on the `store-server` (PostgreSQL) profile, which is the production source of truth. The `embedded` (SQLite) profile uses Hibernate `ddl-auto` and disables Flyway, because Flyway 10 does not ship first-class SQLite support. This is a deliberate Phase-0 tradeoff, documented here so it is not mistaken for full coverage; the production store-server path keeps the "Flyway per-module" model from the architecture.

---

### Task 1: Project bootstrap (Gradle + Spring Boot + Modulith + Java 21)

Stand up the build, the application entry point, and a context-load smoke test.

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `src/main/java/com/company/pos/PosApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `.gitignore`
- Test: `src/test/java/com/company/pos/PosApplicationTests.java`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `com.company.pos.PosApplication` — the `@SpringBootApplication` main class; the root of all Modulith analysis. Gradle tasks `build`, `test`, `bootJar`.

- [ ] **Step 1: Initialize git and the Gradle wrapper**

```bash
cd "/Users/zuhairahamed/Desktop/Research & Development/POS"
git init
# Generate the wrapper at the pinned version (requires a local Gradle, or copy a wrapper jar in).
gradle wrapper --gradle-version 8.10 || echo "If 'gradle' is not installed, copy a gradle-wrapper.jar + scripts from another 8.10 project."
```

Expected: `gradlew`, `gradlew.bat`, and `gradle/wrapper/` are created.

- [ ] **Step 2: Write `gradle/wrapper/gradle-wrapper.properties`**

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.10-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 3: Write `settings.gradle.kts`**

```kotlin
rootProject.name = "pos"
```

- [ ] **Step 4: Write `build.gradle.kts`** (dependencies grow in later tasks)

```kotlin
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.company"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["springModulithVersion"] = "1.2.5"

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.modulith:spring-modulith-starter-core")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<BootJar>("bootJar") {
    archiveFileName.set("pos.jar")
}
```

- [ ] **Step 5: Write the `.gitignore`**

```gitignore
.gradle/
build/
*.log
*.db
*.sqlite
.idea/
!gradle/wrapper/gradle-wrapper.jar
```

- [ ] **Step 6: Write the failing test** `src/test/java/com/company/pos/PosApplicationTests.java`

```java
package com.company.pos;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PosApplicationTests {

    @Test
    void contextLoads() {
        // Fails to compile until PosApplication exists; then verifies the Spring context starts.
    }
}
```

- [ ] **Step 7: Run the test to verify it fails**

Run: `./gradlew test --tests com.company.pos.PosApplicationTests`
Expected: FAIL — compilation error, `PosApplication` (referenced by `@SpringBootTest` component scan root) does not exist / no `@SpringBootConfiguration` found.

- [ ] **Step 8: Write `src/main/resources/application.yml`**

```yaml
spring:
  application:
    name: pos
  main:
    banner-mode: "off"
```

- [ ] **Step 9: Write the minimal implementation** `src/main/java/com/company/pos/PosApplication.java`

```java
package com.company.pos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PosApplication {

    public static void main(String[] args) {
        SpringApplication.run(PosApplication.class, args);
    }
}
```

- [ ] **Step 10: Run the test to verify it passes**

Run: `./gradlew test --tests com.company.pos.PosApplicationTests`
Expected: PASS — `contextLoads` green.

- [ ] **Step 11: Commit**

```bash
git add settings.gradle.kts build.gradle.kts .gitignore gradlew gradlew.bat gradle/ \
        src/main/java/com/company/pos/PosApplication.java \
        src/main/resources/application.yml \
        src/test/java/com/company/pos/PosApplicationTests.java
git commit -m "build: bootstrap Spring Boot 3 + Modulith project on Java 21"
```

---

### Task 2: Module skeleton + Spring Modulith boundary verification

Declare the Phase-0 logical modules as packages and lock their boundaries with a `verify()` test.

**Files:**
- Create: `src/main/java/com/company/pos/common/package-info.java`
- Create: `src/main/java/com/company/pos/database/package-info.java`
- Create: `src/main/java/com/company/pos/configuration/package-info.java`
- Create: `src/main/java/com/company/pos/device/package-info.java`
- Create: `src/main/java/com/company/pos/common/util/KernelMarker.java` (placeholder type so `common` is a non-empty module)
- Create: `src/main/java/com/company/pos/database/DatabaseMarker.java`
- Create: `src/main/java/com/company/pos/configuration/ConfigurationMarker.java`
- Create: `src/main/java/com/company/pos/device/DeviceMarker.java`
- Test: `src/test/java/com/company/pos/ModularityTests.java`

**Interfaces:**
- Consumes: `com.company.pos.PosApplication` (Modulith analysis root) from Task 1.
- Produces: four detectable application modules — `common` (OPEN), `database` (OPEN), `configuration` (allowed deps: `common`, `database`), `device` (allowed deps: `common`). `ModularityTests` enforces them on every build. Later tasks fill these packages.

- [ ] **Step 1: Write the failing test** `src/test/java/com/company/pos/ModularityTests.java`

```java
package com.company.pos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(PosApplication.class);

    @Test
    void verifiesModuleBoundaries() {
        modules.verify();
    }

    @Test
    void detectsTheExpectedPhaseZeroModules() {
        Set<String> names = modules.stream()
                .map(ApplicationModule::getName)
                .collect(Collectors.toSet());
        assertThat(names).contains("common", "database", "configuration", "device");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests com.company.pos.ModularityTests`
Expected: FAIL — `detectsTheExpectedPhaseZeroModules` fails because no module packages exist yet (`names` is empty).

- [ ] **Step 3: Create the `common` open shared-kernel package** `src/main/java/com/company/pos/common/package-info.java`

```java
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.company.pos.common;
```

- [ ] **Step 4: Create the `database` open shared-kernel package** `src/main/java/com/company/pos/database/package-info.java`

```java
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.company.pos.database;
```

- [ ] **Step 5: Create the `configuration` closed module** `src/main/java/com/company/pos/configuration/package-info.java`

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database" })
package com.company.pos.configuration;
```

- [ ] **Step 6: Create the `device` closed module** `src/main/java/com/company/pos/device/package-info.java`

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common" })
package com.company.pos.device;
```

- [ ] **Step 7: Create marker types so each module is detected as a non-empty package**

`src/main/java/com/company/pos/common/util/KernelMarker.java`:

```java
package com.company.pos.common.util;

/** Placeholder so the {@code common} module package is detected. Real types arrive in Tasks 3-5. */
public final class KernelMarker {
    private KernelMarker() {
    }
}
```

`src/main/java/com/company/pos/database/DatabaseMarker.java`:

```java
package com.company.pos.database;

/** Placeholder so the {@code database} module is detected. Persistence config is profile-driven (Task 6). */
public final class DatabaseMarker {
    private DatabaseMarker() {
    }
}
```

`src/main/java/com/company/pos/configuration/ConfigurationMarker.java`:

```java
package com.company.pos.configuration;

/** Placeholder so the {@code configuration} module is detected. Real types arrive in Task 7. */
public final class ConfigurationMarker {
    private ConfigurationMarker() {
    }
}
```

`src/main/java/com/company/pos/device/DeviceMarker.java`:

```java
package com.company.pos.device;

/** Placeholder so the {@code device} module is detected. Port interfaces arrive in Task 8. */
public final class DeviceMarker {
    private DeviceMarker() {
    }
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `./gradlew test --tests com.company.pos.ModularityTests`
Expected: PASS — both tests green; `verifiesModuleBoundaries` confirms no illegal cross-module dependencies.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/company/pos/common/ \
        src/main/java/com/company/pos/database/ \
        src/main/java/com/company/pos/configuration/ \
        src/main/java/com/company/pos/device/ \
        src/test/java/com/company/pos/ModularityTests.java
git commit -m "feat: declare Phase 0 modules with enforced Modulith boundaries"
```

---

### Task 3: `common/exception` — error model + RFC-7807 handler

A small exception taxonomy mapped to HTTP status, plus a handler that renders RFC-7807 `ProblemDetail` for terminals.

**Files:**
- Create: `src/main/java/com/company/pos/common/exception/ErrorCode.java`
- Create: `src/main/java/com/company/pos/common/exception/DomainException.java`
- Create: `src/main/java/com/company/pos/common/exception/ApiExceptionHandler.java`
- Test: `src/test/java/com/company/pos/common/exception/ApiExceptionHandlerTest.java`

**Interfaces:**
- Consumes: nothing from other modules.
- Produces:
  - `ErrorCode` — enum `{ NOT_FOUND(404), VALIDATION(400), CONFLICT(409), INTERNAL(500) }` with `HttpStatus status()`.
  - `DomainException extends RuntimeException` — constructor `DomainException(ErrorCode code, String message)`; getter `ErrorCode errorCode()`. Static factories `notFound(String)`, `validation(String)`, `conflict(String)`.
  - `ApiExceptionHandler` — `@RestControllerAdvice`; method `ProblemDetail handle(DomainException ex)` returning a `ProblemDetail` whose `status` matches the code and whose `properties["code"]` is the enum name. Other modules throw `DomainException`; the handler is global.

- [ ] **Step 1: Write the failing test** `src/test/java/com/company/pos/common/exception/ApiExceptionHandlerTest.java`

```java
package com.company.pos.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void mapsNotFoundToProblemDetail404() {
        ProblemDetail pd = handler.handle(DomainException.notFound("Sale 42 not found"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getDetail()).isEqualTo("Sale 42 not found");
        assertThat(pd.getProperties()).containsEntry("code", "NOT_FOUND");
    }

    @Test
    void mapsValidationToProblemDetail400() {
        ProblemDetail pd = handler.handle(DomainException.validation("Quantity must be positive"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getProperties()).containsEntry("code", "VALIDATION");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests com.company.pos.common.exception.ApiExceptionHandlerTest`
Expected: FAIL — compilation error, `DomainException` / `ApiExceptionHandler` do not exist.

- [ ] **Step 3: Write `ErrorCode`** `src/main/java/com/company/pos/common/exception/ErrorCode.java`

```java
package com.company.pos.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND),
    VALIDATION(HttpStatus.BAD_REQUEST),
    CONFLICT(HttpStatus.CONFLICT),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
```

- [ ] **Step 4: Write `DomainException`** `src/main/java/com/company/pos/common/exception/DomainException.java`

```java
package com.company.pos.common.exception;

public class DomainException extends RuntimeException {

    private final ErrorCode errorCode;

    public DomainException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public static DomainException notFound(String message) {
        return new DomainException(ErrorCode.NOT_FOUND, message);
    }

    public static DomainException validation(String message) {
        return new DomainException(ErrorCode.VALIDATION, message);
    }

    public static DomainException conflict(String message) {
        return new DomainException(ErrorCode.CONFLICT, message);
    }
}
```

- [ ] **Step 5: Write `ApiExceptionHandler`** `src/main/java/com/company/pos/common/exception/ApiExceptionHandler.java`

```java
package com.company.pos.common.exception;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ProblemDetail handle(DomainException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.errorCode().status(), ex.getMessage());
        problem.setProperty("code", ex.errorCode().name());
        return problem;
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew test --tests com.company.pos.common.exception.ApiExceptionHandlerTest`
Expected: PASS — both mappings green.

- [ ] **Step 7: Verify boundaries still hold**

Run: `./gradlew test --tests com.company.pos.ModularityTests`
Expected: PASS — `common` gained types but remains an open module; no boundary violations.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/company/pos/common/exception/ \
        src/test/java/com/company/pos/common/exception/ApiExceptionHandlerTest.java
git commit -m "feat: add common error model with RFC-7807 problem-detail handler"
```

---

### Task 4: `common/util` — identifiers + Money helper

Client-generated UUIDs and a JavaMoney facade so the codebase never touches `double` for money.

**Files:**
- Modify: `build.gradle.kts` (add Moneta dependency)
- Create: `src/main/java/com/company/pos/common/util/Identifiers.java`
- Create: `src/main/java/com/company/pos/common/util/Monies.java`
- Delete: `src/main/java/com/company/pos/common/util/KernelMarker.java` (real types now occupy the package)
- Test: `src/test/java/com/company/pos/common/util/IdentifiersTest.java`
- Test: `src/test/java/com/company/pos/common/util/MoniesTest.java`

**Interfaces:**
- Consumes: nothing from other modules.
- Produces:
  - `Identifiers` — static `UUID newId()` returning a random (version-4) UUID.
  - `Monies` — static factories over `javax.money.MonetaryAmount`: `MonetaryAmount of(BigDecimal amount, String currencyCode)`, `MonetaryAmount zero(String currencyCode)`, `String format(MonetaryAmount amount, Locale locale)`. Adding amounts of different currencies throws `javax.money.MonetaryException` (Moneta's built-in behavior). All later modules use `Monies` for money construction.

- [ ] **Step 1: Add the Moneta dependency to `build.gradle.kts`**

In the `dependencies { … }` block, add (after the `spring-modulith-starter-core` line):

```kotlin
    implementation("org.javamoney.moneta:moneta-core:1.4.4")
```

- [ ] **Step 2: Write the failing test** `src/test/java/com/company/pos/common/util/IdentifiersTest.java`

```java
package com.company.pos.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentifiersTest {

    @Test
    void generatesUniqueVersion4Ids() {
        UUID a = Identifiers.newId();
        UUID b = Identifiers.newId();

        assertThat(a).isNotEqualTo(b);
        assertThat(a.version()).isEqualTo(4);
    }
}
```

- [ ] **Step 3: Write the failing test** `src/test/java/com/company/pos/common/util/MoniesTest.java`

```java
package com.company.pos.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import javax.money.MonetaryAmount;
import javax.money.MonetaryException;
import org.junit.jupiter.api.Test;

class MoniesTest {

    @Test
    void createsAndAddsSameCurrency() {
        MonetaryAmount ten = Monies.of(new BigDecimal("10.00"), "SAR");
        MonetaryAmount five = Monies.of(new BigDecimal("5.00"), "SAR");

        MonetaryAmount sum = ten.add(five);

        assertThat(sum.getNumber().numberValue(BigDecimal.class)).isEqualByComparingTo("15.00");
        assertThat(sum.getCurrency().getCurrencyCode()).isEqualTo("SAR");
    }

    @Test
    void zeroHasGivenCurrency() {
        MonetaryAmount zero = Monies.zero("SAR");

        assertThat(zero.isZero()).isTrue();
        assertThat(zero.getCurrency().getCurrencyCode()).isEqualTo("SAR");
    }

    @Test
    void rejectsAddingDifferentCurrencies() {
        MonetaryAmount sar = Monies.of(new BigDecimal("10.00"), "SAR");
        MonetaryAmount usd = Monies.of(new BigDecimal("10.00"), "USD");

        assertThatThrownBy(() -> sar.add(usd)).isInstanceOf(MonetaryException.class);
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./gradlew test --tests com.company.pos.common.util.IdentifiersTest --tests com.company.pos.common.util.MoniesTest`
Expected: FAIL — compilation error, `Identifiers` / `Monies` do not exist.

- [ ] **Step 5: Write `Identifiers`** `src/main/java/com/company/pos/common/util/Identifiers.java`

```java
package com.company.pos.common.util;

import java.util.UUID;

public final class Identifiers {

    private Identifiers() {
    }

    public static UUID newId() {
        return UUID.randomUUID();
    }
}
```

- [ ] **Step 6: Write `Monies`** `src/main/java/com/company/pos/common/util/Monies.java`

```java
package com.company.pos.common.util;

import java.math.BigDecimal;
import java.util.Locale;
import javax.money.MonetaryAmount;
import javax.money.format.AmountFormatQueryBuilder;
import javax.money.format.MonetaryAmountFormat;
import javax.money.format.MonetaryFormats;
import org.javamoney.moneta.Money;

public final class Monies {

    private Monies() {
    }

    public static MonetaryAmount of(BigDecimal amount, String currencyCode) {
        return Money.of(amount, currencyCode);
    }

    public static MonetaryAmount zero(String currencyCode) {
        return Money.of(BigDecimal.ZERO, currencyCode);
    }

    public static String format(MonetaryAmount amount, Locale locale) {
        MonetaryAmountFormat format = MonetaryFormats.getAmountFormat(
                AmountFormatQueryBuilder.of(locale).build());
        return format.format(amount);
    }
}
```

- [ ] **Step 7: Delete the placeholder marker**

```bash
git rm src/main/java/com/company/pos/common/util/KernelMarker.java
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew test --tests com.company.pos.common.util.IdentifiersTest --tests com.company.pos.common.util.MoniesTest`
Expected: PASS — all three Monies cases and the Identifiers case green.

- [ ] **Step 9: Commit**

```bash
git add build.gradle.kts \
        src/main/java/com/company/pos/common/util/Identifiers.java \
        src/main/java/com/company/pos/common/util/Monies.java \
        src/test/java/com/company/pos/common/util/
git commit -m "feat: add common identifiers and JavaMoney-backed Monies helper"
```

---

### Task 5: `common/events` — domain-event infrastructure

A marker interface for domain facts and a thin publisher over Spring's `ApplicationEventPublisher`. (The *persistent* event/outbox registry is wired in Phase 3; Phase 0 establishes only the in-process publishing convention.)

**Files:**
- Create: `src/main/java/com/company/pos/common/events/DomainEvent.java`
- Create: `src/main/java/com/company/pos/common/events/DomainEvents.java`
- Test: `src/test/java/com/company/pos/common/events/DomainEventsTest.java`

**Interfaces:**
- Consumes: nothing from other modules.
- Produces:
  - `DomainEvent` — empty marker interface that every published domain fact implements.
  - `DomainEvents` — Spring `@Component`; method `void publish(DomainEvent event)` delegating to `ApplicationEventPublisher`. Later modules inject `DomainEvents` to announce state changes (`SaleCompleted`, etc.); `audit`/`sync`/`notification` subscribe.

- [ ] **Step 1: Write the failing test** `src/test/java/com/company/pos/common/events/DomainEventsTest.java`

```java
package com.company.pos.common.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@SpringBootTest
@RecordApplicationEvents
class DomainEventsTest {

    record SampleEvent(String payload) implements DomainEvent {
    }

    @Autowired
    DomainEvents domainEvents;

    @Autowired
    ApplicationEvents recorded;

    @Test
    void publishesDomainEventToListeners() {
        domainEvents.publish(new SampleEvent("hello"));

        assertThat(recorded.stream(SampleEvent.class).count()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests com.company.pos.common.events.DomainEventsTest`
Expected: FAIL — compilation error, `DomainEvent` / `DomainEvents` do not exist.

- [ ] **Step 3: Write `DomainEvent`** `src/main/java/com/company/pos/common/events/DomainEvent.java`

```java
package com.company.pos.common.events;

/** Marker for domain facts published in-process and (from Phase 3) persisted to the outbox. */
public interface DomainEvent {
}
```

- [ ] **Step 4: Write `DomainEvents`** `src/main/java/com/company/pos/common/events/DomainEvents.java`

```java
package com.company.pos.common.events;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class DomainEvents {

    private final ApplicationEventPublisher publisher;

    public DomainEvents(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publish(DomainEvent event) {
        publisher.publishEvent(event);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests com.company.pos.common.events.DomainEventsTest`
Expected: PASS — the recorded event count is 1.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/common/events/ \
        src/test/java/com/company/pos/common/events/DomainEventsTest.java
git commit -m "feat: add in-process domain-event publisher to common kernel"
```

---

### Task 6: `database` — dual-profile datasource + Flyway (store-server) / SQLite (embedded)

Wire the persistence stack for both run modes and prove the store-server profile migrates cleanly against a real PostgreSQL.

**Files:**
- Modify: `build.gradle.kts` (add JPA, drivers, Flyway, Testcontainers)
- Modify: `src/main/resources/application.yml` (default JPA settings)
- Create: `src/main/resources/application-store-server.yml`
- Create: `src/main/resources/application-embedded.yml`
- Test: `src/test/java/com/company/pos/database/DatabaseStoreServerTest.java`
- Test: `src/test/java/com/company/pos/database/EmbeddedProfileTest.java`

**Interfaces:**
- Consumes: `PosApplication` context from Task 1.
- Produces: two working Spring profiles — `store-server` (PostgreSQL + Flyway, schema `configuration` auto-created) and `embedded` (SQLite, single-connection pool, Hibernate `ddl-auto=update`, Flyway disabled). Task 7's `configuration` schema/migration plugs into the store-server Flyway location; its entities run under both profiles.

- [ ] **Step 1: Add persistence dependencies to `build.gradle.kts`**

In `dependencies { … }`, add:

```kotlin
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.xerial:sqlite-jdbc:3.46.1.3")
    runtimeOnly("org.hibernate.orm:hibernate-community-dialects")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
```

- [ ] **Step 2: Set safe JPA defaults in `application.yml`**

Replace the file contents of `src/main/resources/application.yml` with:

```yaml
spring:
  application:
    name: pos
  main:
    banner-mode: "off"
  profiles:
    default: embedded
  jpa:
    open-in-view: false
    properties:
      hibernate:
        format_sql: false
```

- [ ] **Step 3: Write `application-store-server.yml`** (PostgreSQL + Flyway; production source of truth)

```yaml
spring:
  datasource:
    url: ${POS_DB_URL:jdbc:postgresql://localhost:5432/pos}
    username: ${POS_DB_USER:pos}
    password: ${POS_DB_PASSWORD:pos}
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        default_schema: configuration
  flyway:
    enabled: true
    schemas: configuration
    default-schema: configuration
    create-schemas: true
    locations: classpath:db/migration/configuration
```

- [ ] **Step 4: Write `application-embedded.yml`** (SQLite; single register, Flyway disabled — see Design note)

```yaml
spring:
  datasource:
    url: ${POS_DB_URL:jdbc:sqlite:file:pos?mode=memory&cache=shared}
    driver-class-name: org.sqlite.JDBC
    hikari:
      maximum-pool-size: 1
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        dialect: org.hibernate.community.dialect.SQLiteDialect
  flyway:
    enabled: false
```

- [ ] **Step 5: Write the failing store-server test** `src/test/java/com/company/pos/database/DatabaseStoreServerTest.java`

```java
package com.company.pos.database;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("store-server")
@Testcontainers
class DatabaseStoreServerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    DataSource dataSource;

    @Test
    void bootsAndConnectsToPostgres() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.isValid(2)).isTrue();
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
    }
}
```

> Note: `@ServiceConnection` overrides the `spring.datasource.*` URL with the container's, so the test does not need a local PostgreSQL. Flyway runs against the container on context start; with no migrations yet it simply creates the `configuration` schema and an empty history table. Task 7 adds the first migration, which this test will then also exercise.

- [ ] **Step 6: Write the failing embedded test** `src/test/java/com/company/pos/database/EmbeddedProfileTest.java`

```java
package com.company.pos.database;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class EmbeddedProfileTest {

    @Autowired
    DataSource dataSource;

    @Test
    void bootsOnSqlite() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.isValid(2)).isTrue();
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualToIgnoringCase("SQLite");
        }
    }
}
```

- [ ] **Step 7: Run the tests to verify they fail**

Run: `./gradlew test --tests com.company.pos.database.*`
Expected: FAIL — compilation error (test classes reference no new prod types, so the first failure is the missing profile YAML wiring / driver). If dependencies were not yet downloaded the build fails at resolution. Ensure Docker is running for the Testcontainers test.

- [ ] **Step 8: Run the tests to verify they pass**

After Steps 1–4 are in place (the production change for this task is configuration, not Java code), run:
Run: `./gradlew test --tests com.company.pos.database.*`
Expected: PASS — `DatabaseStoreServerTest` connects to the PostgreSQL container; `EmbeddedProfileTest` connects to the in-memory SQLite database.

- [ ] **Step 9: Verify the whole suite and boundaries**

Run: `./gradlew test`
Expected: PASS — all prior tests plus the two new database tests; `ModularityTests` still green (`database` remains an open module).

- [ ] **Step 10: Commit**

```bash
git add build.gradle.kts \
        src/main/resources/application.yml \
        src/main/resources/application-store-server.yml \
        src/main/resources/application-embedded.yml \
        src/test/java/com/company/pos/database/
git commit -m "feat: wire dual-profile persistence (PostgreSQL+Flyway / SQLite embedded)"
```

---

### Task 7: `configuration` module — typed settings store

A typed key/value settings facade backed by JPA, with sensible defaults — the home for store/tax/printer/currency/locale settings used across the app.

**Files:**
- Create: `src/main/resources/db/migration/configuration/V1__configuration_setting.sql`
- Create: `src/main/java/com/company/pos/configuration/api/SettingKey.java`
- Create: `src/main/java/com/company/pos/configuration/api/ConfigurationService.java`
- Create: `src/main/java/com/company/pos/configuration/domain/Setting.java`
- Create: `src/main/java/com/company/pos/configuration/infrastructure/SettingRepository.java`
- Create: `src/main/java/com/company/pos/configuration/application/DefaultConfigurationService.java`
- Delete: `src/main/java/com/company/pos/configuration/ConfigurationMarker.java`
- Test: `src/test/java/com/company/pos/configuration/ConfigurationServiceTest.java`

**Interfaces:**
- Consumes: `database` persistence (Task 6); no other module's API.
- Produces:
  - `SettingKey` (enum, in `api`) — each constant has a `String key()` and a `String defaultValue()`. Constants: `STORE_NAME("store.name","My Store")`, `CURRENCY_CODE("currency.code","SAR")`, `LOCALE("locale","en")`, `TAX_INCLUSIVE("tax.inclusive","false")`, `RECEIPT_PRINTER_PORT("printer.port","COM1")`.
  - `ConfigurationService` (interface, in `api`) — `String getString(SettingKey)`, `int getInt(SettingKey)`, `boolean getBoolean(SettingKey)`, `void put(SettingKey, String value)`. Reads return the stored value or the key's default. Other modules depend only on this interface.

- [ ] **Step 1: Write the Flyway migration** `src/main/resources/db/migration/configuration/V1__configuration_setting.sql`

```sql
CREATE TABLE setting (
    setting_key   VARCHAR(100) PRIMARY KEY,
    setting_value VARCHAR(1000) NOT NULL,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

- [ ] **Step 2: Write the failing test** `src/test/java/com/company/pos/configuration/ConfigurationServiceTest.java`

```java
package com.company.pos.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class ConfigurationServiceTest {

    @Autowired
    ConfigurationService configuration;

    @Test
    void returnsDefaultWhenUnset() {
        assertThat(configuration.getString(SettingKey.CURRENCY_CODE)).isEqualTo("SAR");
    }

    @Test
    void storesAndRetrievesOverride() {
        configuration.put(SettingKey.STORE_NAME, "Riyadh Branch");

        assertThat(configuration.getString(SettingKey.STORE_NAME)).isEqualTo("Riyadh Branch");
    }

    @Test
    void coercesTypedValues() {
        configuration.put(SettingKey.TAX_INCLUSIVE, "true");

        assertThat(configuration.getBoolean(SettingKey.TAX_INCLUSIVE)).isTrue();
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew test --tests com.company.pos.configuration.ConfigurationServiceTest`
Expected: FAIL — compilation error, `ConfigurationService` / `SettingKey` do not exist.

- [ ] **Step 4: Write `SettingKey`** `src/main/java/com/company/pos/configuration/api/SettingKey.java`

```java
package com.company.pos.configuration.api;

public enum SettingKey {
    STORE_NAME("store.name", "My Store"),
    CURRENCY_CODE("currency.code", "SAR"),
    LOCALE("locale", "en"),
    TAX_INCLUSIVE("tax.inclusive", "false"),
    RECEIPT_PRINTER_PORT("printer.port", "COM1");

    private final String key;
    private final String defaultValue;

    SettingKey(String key, String defaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
    }

    public String key() {
        return key;
    }

    public String defaultValue() {
        return defaultValue;
    }
}
```

- [ ] **Step 5: Write the `ConfigurationService` facade** `src/main/java/com/company/pos/configuration/api/ConfigurationService.java`

```java
package com.company.pos.configuration.api;

public interface ConfigurationService {

    String getString(SettingKey key);

    int getInt(SettingKey key);

    boolean getBoolean(SettingKey key);

    void put(SettingKey key, String value);
}
```

- [ ] **Step 6: Write the `Setting` entity** `src/main/java/com/company/pos/configuration/domain/Setting.java`

```java
package com.company.pos.configuration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "setting")
public class Setting {

    @Id
    @Column(name = "setting_key", length = 100, nullable = false)
    private String key;

    @Column(name = "setting_value", length = 1000, nullable = false)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Setting() {
        // JPA
    }

    public Setting(String key, String value) {
        this.key = key;
        this.value = value;
        this.updatedAt = Instant.now();
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
        this.updatedAt = Instant.now();
    }
}
```

- [ ] **Step 7: Write the repository** `src/main/java/com/company/pos/configuration/infrastructure/SettingRepository.java`

```java
package com.company.pos.configuration.infrastructure;

import com.company.pos.configuration.domain.Setting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingRepository extends JpaRepository<Setting, String> {
}
```

- [ ] **Step 8: Write the service implementation** `src/main/java/com/company/pos/configuration/application/DefaultConfigurationService.java`

```java
package com.company.pos.configuration.application;

import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.configuration.domain.Setting;
import com.company.pos.configuration.infrastructure.SettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultConfigurationService implements ConfigurationService {

    private final SettingRepository repository;

    DefaultConfigurationService(SettingRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public String getString(SettingKey key) {
        return repository.findById(key.key())
                .map(Setting::getValue)
                .orElseGet(key::defaultValue);
    }

    @Override
    @Transactional(readOnly = true)
    public int getInt(SettingKey key) {
        return Integer.parseInt(getString(key));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean getBoolean(SettingKey key) {
        return Boolean.parseBoolean(getString(key));
    }

    @Override
    public void put(SettingKey key, String value) {
        Setting setting = repository.findById(key.key())
                .orElseGet(() -> new Setting(key.key(), value));
        setting.setValue(value);
        repository.save(setting);
    }
}
```

- [ ] **Step 9: Delete the placeholder marker**

```bash
git rm src/main/java/com/company/pos/configuration/ConfigurationMarker.java
```

- [ ] **Step 10: Run the test to verify it passes**

Run: `./gradlew test --tests com.company.pos.configuration.ConfigurationServiceTest`
Expected: PASS — default fallback, override persistence, and boolean coercion all green (Hibernate creates the `setting` table under the embedded profile).

- [ ] **Step 11: Verify the full suite and boundaries**

Run: `./gradlew test`
Expected: PASS — including `DatabaseStoreServerTest`, which now also applies the `V1__configuration_setting.sql` migration against PostgreSQL; `ModularityTests` confirms `configuration` depends only on `common`/`database`.

- [ ] **Step 12: Commit**

```bash
git add src/main/resources/db/migration/configuration/ \
        src/main/java/com/company/pos/configuration/ \
        src/test/java/com/company/pos/configuration/ConfigurationServiceTest.java
git commit -m "feat: add typed configuration settings store with JPA backing"
```

---

### Task 8: `device` ports — peripheral port interfaces + fakes

Define vendor-neutral hardware ports so the rest of the app is hardware-agnostic and testable with fakes. (JavaPOS/ESC-POS adapters arrive in Phase 2 — Phase 0 ships interfaces and DTOs only.)

**Files:**
- Create: `src/main/java/com/company/pos/device/api/PrintLine.java`
- Create: `src/main/java/com/company/pos/device/api/Printer.java`
- Create: `src/main/java/com/company/pos/device/api/BarcodeScanner.java`
- Create: `src/main/java/com/company/pos/device/api/CashDrawer.java`
- Create: `src/main/java/com/company/pos/device/api/LineDisplay.java`
- Create: `src/main/java/com/company/pos/device/api/Weight.java`
- Create: `src/main/java/com/company/pos/device/api/Scale.java`
- Create: `src/main/java/com/company/pos/device/api/PaymentRequest.java`
- Create: `src/main/java/com/company/pos/device/api/PaymentResult.java`
- Create: `src/main/java/com/company/pos/device/api/PaymentTerminal.java`
- Delete: `src/main/java/com/company/pos/device/DeviceMarker.java`
- Test: `src/test/java/com/company/pos/device/DevicePortContractTest.java` (includes in-test fakes)

**Interfaces:**
- Consumes: `common` (uses `javax.money.MonetaryAmount` via `PaymentRequest`); no other module.
- Produces (all in `device.api`):
  - `PrintLine(String text, boolean bold)` record; `Printer` — `void print(List<PrintLine> lines)`, `void cut()`.
  - `BarcodeScanner` — `void onScan(java.util.function.Consumer<String> handler)`.
  - `CashDrawer` — `void open()`, `boolean isOpen()`.
  - `LineDisplay` — `void show(String line1, String line2)`, `void clear()`.
  - `Weight(BigDecimal kilograms)` record; `Scale` — `Weight read()`.
  - `PaymentRequest(MonetaryAmount amount, String reference)` record; `PaymentResult(boolean approved, String maskedPan, String token)` record; `PaymentTerminal` — `PaymentResult requestPayment(PaymentRequest request)`. Phase 2 device adapters and the `payment` module implement these.

- [ ] **Step 1: Write the failing contract test** `src/test/java/com/company/pos/device/DevicePortContractTest.java`

```java
package com.company.pos.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.device.api.CashDrawer;
import com.company.pos.device.api.PrintLine;
import com.company.pos.device.api.Printer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DevicePortContractTest {

    /** A fake fulfilling the Printer port — proves the interface is implementable/usable. */
    static final class FakePrinter implements Printer {
        final List<PrintLine> printed = new ArrayList<>();
        int cuts = 0;

        @Override
        public void print(List<PrintLine> lines) {
            printed.addAll(lines);
        }

        @Override
        public void cut() {
            cuts++;
        }
    }

    /** A fake fulfilling the CashDrawer port. */
    static final class FakeCashDrawer implements CashDrawer {
        private boolean open = false;

        @Override
        public void open() {
            this.open = true;
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }

    @Test
    void printerAcceptsLinesAndCuts() {
        FakePrinter printer = new FakePrinter();

        printer.print(List.of(new PrintLine("Total: 15.00 SAR", true)));
        printer.cut();

        assertThat(printer.printed).hasSize(1);
        assertThat(printer.printed.get(0).bold()).isTrue();
        assertThat(printer.cuts).isEqualTo(1);
    }

    @Test
    void cashDrawerOpensFromClosed() {
        FakeCashDrawer drawer = new FakeCashDrawer();

        assertThat(drawer.isOpen()).isFalse();
        drawer.open();
        assertThat(drawer.isOpen()).isTrue();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests com.company.pos.device.DevicePortContractTest`
Expected: FAIL — compilation error, `Printer` / `PrintLine` / `CashDrawer` do not exist.

- [ ] **Step 3: Write the printer port** `src/main/java/com/company/pos/device/api/PrintLine.java` and `Printer.java`

`PrintLine.java`:

```java
package com.company.pos.device.api;

public record PrintLine(String text, boolean bold) {
}
```

`Printer.java`:

```java
package com.company.pos.device.api;

import java.util.List;

public interface Printer {

    void print(List<PrintLine> lines);

    void cut();
}
```

- [ ] **Step 4: Write the cash-drawer and scanner ports**

`CashDrawer.java`:

```java
package com.company.pos.device.api;

public interface CashDrawer {

    void open();

    boolean isOpen();
}
```

`BarcodeScanner.java`:

```java
package com.company.pos.device.api;

import java.util.function.Consumer;

public interface BarcodeScanner {

    void onScan(Consumer<String> handler);
}
```

- [ ] **Step 5: Write the display and scale ports**

`LineDisplay.java`:

```java
package com.company.pos.device.api;

public interface LineDisplay {

    void show(String line1, String line2);

    void clear();
}
```

`Weight.java`:

```java
package com.company.pos.device.api;

import java.math.BigDecimal;

public record Weight(BigDecimal kilograms) {
}
```

`Scale.java`:

```java
package com.company.pos.device.api;

public interface Scale {

    Weight read();
}
```

- [ ] **Step 6: Write the payment-terminal port**

`PaymentRequest.java`:

```java
package com.company.pos.device.api;

import javax.money.MonetaryAmount;

public record PaymentRequest(MonetaryAmount amount, String reference) {
}
```

`PaymentResult.java`:

```java
package com.company.pos.device.api;

public record PaymentResult(boolean approved, String maskedPan, String token) {
}
```

`PaymentTerminal.java`:

```java
package com.company.pos.device.api;

public interface PaymentTerminal {

    PaymentResult requestPayment(PaymentRequest request);
}
```

- [ ] **Step 7: Delete the placeholder marker**

```bash
git rm src/main/java/com/company/pos/device/DeviceMarker.java
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `./gradlew test --tests com.company.pos.device.DevicePortContractTest`
Expected: PASS — both fakes compile against the ports and behave as asserted.

- [ ] **Step 9: Verify the full suite and boundaries**

Run: `./gradlew test`
Expected: PASS — `ModularityTests` confirms `device` depends only on `common` (the `MonetaryAmount` import is JDK/JavaMoney, not another POS module).

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/company/pos/device/api/ \
        src/test/java/com/company/pos/device/DevicePortContractTest.java
git commit -m "feat: add vendor-neutral device port interfaces"
```

---

### Task 9: CI + dual run-mode packaging

Add a CI pipeline that builds and tests on every push, and confirm the application packages and boots in both run modes.

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `docs/run-modes.md`
- Test: re-use `EmbeddedProfileTest` (embedded boot) and `DatabaseStoreServerTest` (store-server boot) — both already assert profile startup.

**Interfaces:**
- Consumes: the full Gradle build and test suite (Tasks 1–8).
- Produces: a green CI workflow and an operator-facing run-mode doc. No new production types.

- [ ] **Step 1: Write the CI workflow** `.github/workflows/ci.yml`

```yaml
name: CI

on:
  push:
    branches: [ main ]
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Build, test, and verify module boundaries
        run: ./gradlew build
        # GitHub-hosted ubuntu runners provide Docker, so the Testcontainers PostgreSQL test runs here.
```

- [ ] **Step 2: Write the run-mode documentation** `docs/run-modes.md`

```markdown
# POS Run Modes

The same `pos.jar` runs in two persistence modes, selected by Spring profile.

## Store-server (recommended, ≥ 2 registers)

PostgreSQL is the local source of truth; Flyway owns the schema.

```bash
java -jar build/libs/pos.jar \
  --spring.profiles.active=store-server \
  --POS_DB_URL=jdbc:postgresql://localhost:5432/pos \
  --POS_DB_USER=pos \
  --POS_DB_PASSWORD=•••
```

## Embedded (single register)

SQLite in-process; Hibernate manages the schema. Flyway is disabled (Flyway 10
has no first-class SQLite support — see the Phase 0 plan's Design note). Set a
file path for durable storage:

```bash
java -jar build/libs/pos.jar \
  --spring.profiles.active=embedded \
  --POS_DB_URL=jdbc:sqlite:file:/var/lib/pos/pos.db
```

> Encryption at rest (SQLCipher for SQLite, TDE for PostgreSQL) is added in the
> security-hardening pass; it is not part of Phase 0.
```

- [ ] **Step 3: Run the full build locally exactly as CI will**

Run: `./gradlew build`
Expected: PASS — compiles, runs every test (including the Testcontainers PostgreSQL test and both profile-boot tests), runs `ModularityTests.verify()`, and produces `build/libs/pos.jar`. Ensure Docker is running locally.

- [ ] **Step 4: Smoke-test the packaged jar in embedded mode**

Run:
```bash
./gradlew bootJar
java -jar build/libs/pos.jar --spring.profiles.active=embedded "--POS_DB_URL=jdbc:sqlite:file:smoke?mode=memory&cache=shared" &
APP_PID=$!
sleep 15
kill $APP_PID
```
Expected: the log shows `Started PosApplication` with the `embedded` profile active and no datasource/Flyway errors, then the process exits on `kill`.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/ci.yml docs/run-modes.md
git commit -m "build: add CI pipeline and document dual run-mode packaging"
```

- [ ] **Step 6: Push and confirm CI is green**

```bash
git push -u origin main   # or the working branch
```
Expected: the **CI** workflow runs on the push/PR and finishes green (build + tests + Modulith verify).

---

## Phase 0 Done — Definition of Complete

- `./gradlew build` is green: every module's tests pass, `ModularityTests.verify()` passes, `pos.jar` builds.
- Both profiles boot: `store-server` (PostgreSQL + Flyway, `configuration` schema migrated) and `embedded` (SQLite).
- The four Phase-0 modules (`common`, `database`, `configuration`, `device`) exist with enforced boundaries.
- CI runs on every push and is green.

This is the verified base. **Phase 1 (Identity & master data — `auth`, `product`, `inventory` baseline, ERP down-sync)** builds directly on it: new modules declare their `@ApplicationModule(allowedDependencies = …)`, add their Flyway migration location to `application-store-server.yml`, throw `DomainException`, construct money via `Monies`, generate ids via `Identifiers`, and publish facts via `DomainEvents`.
