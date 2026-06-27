# Phase 1 — Identity & Master Data Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A POS terminal can authenticate (username/password or cashier PIN), and shows a live product catalogue with stock that was pulled from the ERP via a delta down-sync.

**Architecture:** Builds on the Phase 0 modular monolith. Adds four modules under `com.company.pos`: `auth` (Spring Security, stateless JWT, RBAC, cashier PIN), `product` and `inventory` (ERP-mastered domain capability modules), and `integration` (a vendor-neutral `ErpClient` port with an in-memory fake adapter + per-stream sync cursors). ERP down-sync is **pull-based and versioned**: each domain module owns its data and upserts ERP records (ERP authoritative by version), driven by a cross-module coordinator in the application root. The concrete ERP vendor adapter is deliberately deferred — everything is built and tested against the fake.

**Tech Stack:** Java 21 · Spring Boot 3.3.5 · Spring Modulith 1.2.5 · Spring Security 6 (oauth2-resource-server, Nimbus JWT, HMAC) · Spring Data JPA · Flyway · PostgreSQL + SQLite · Maven · JUnit 5 · Testcontainers · spring-security-test.

## Global Constraints

These apply to **every** task; each task's requirements implicitly include this section.

- **Root package:** `com.company.pos`. Source root `src/main/java/com/company/pos/`, test root `src/test/java/com/company/pos/`.
- **Build:** Maven via `./mvnw` (Maven Wrapper). Java 21 toolchain. Run focused tests with `./mvnw test -Dtest=ClassName`; full build with `./mvnw -B verify`.
- **Module boundaries (Spring Modulith):** a module references another only through its public API. `common` and `database` are OPEN shared modules. New module allowed-dependencies: `auth → {common, database}`, `product → {common, database, integration}`, `inventory → {common, database, integration}`, `integration → {common, database}`. The `ModularityTests.verify()` test must stay green. Cross-module orchestration that must reach two capability modules (the ERP sync coordinator) lives in the **application root package** `com.company.pos` (not a module, so not boundary-constrained) — this is a temporary home until the formal `sync` module arrives in Phase 3.
- **Identifiers:** transactional/aggregate ids are client-generated UUIDs via `com.company.pos.common.util.Identifiers.newId()`. Persist UUID fields as strings for cross-engine portability: type the field `java.util.UUID`, annotate `@JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)` and `@Column(length = 36)`, and use `VARCHAR(36)` in migrations.
- **Money:** never `double`/`float`. Use `BigDecimal` columns and `javax.money.MonetaryAmount` via `com.company.pos.common.util.Monies`. Store the currency code (`VARCHAR(3)`) alongside every amount.
- **Errors:** throw `com.company.pos.common.exception.DomainException` (factories `notFound`/`validation`/`conflict`); the Phase 0 `ApiExceptionHandler` renders RFC-7807.
- **Persistence — single physical schema, per-module migration ownership:** On `store-server` (PostgreSQL) all tables live in one schema `pos`; each module still owns its DDL via a per-module Flyway location (`classpath:db/migration/<module>`). On `embedded` (SQLite) Hibernate `ddl-auto=update` creates tables and Flyway is disabled. Entities are **schema-agnostic** (`@Table(name = "...")`, no `schema` attribute) so the same mapping works on both engines. Flyway migration versions are globally unique and ordered: `V1` configuration (Phase 0), `V2` auth, `V3` product, `V4` inventory, `V5` integration.
- **JWT:** HS256, symmetric secret from property `pos.auth.jwt.secret` (≥ 32 bytes). Token TTL from `pos.auth.jwt.ttl-minutes` (default 720). Claims: `sub`=username, `uid`=user id, `roles`=list of role names. Authorities map with prefix `ROLE_` so `@PreAuthorize("hasRole('MANAGER')")` works.
- **Roles:** `CASHIER`, `MANAGER`, `ADMIN` (enum `com.company.pos.auth.api.Role`).
- **Test scheduling:** ERP sync must NOT run on a timer during tests. The scheduler bean is gated by `@ConditionalOnProperty("pos.sync.erp.scheduled"=true)`; this property defaults to **`false`** in `application.yml` and is set `true` only in `application-store-server.yml`. Tests run the `embedded` profile (→ scheduler off) and invoke sync explicitly. Do **not** add a competing `src/test/resources/application.yml` — a second `application.yml` on the test classpath shadows the main one and would drop the JWT secret.
- **Commit discipline:** every task ends with ONE commit using the message shown, committing only that task's files. Conventional Commits prefixes (`feat:`/`build:`/`test:`/`fix:`).

---

## File Structure

```
src/main/java/com/company/pos/
├── PosApplication.java                  # MODIFY (Task 12: add @EnableScheduling)
├── ErpSyncCoordinator.java              # Task 12 (root package — cross-module orchestration)
├── ErpSyncScheduler.java                # Task 12
├── SyncController.java                  # Task 12
├── auth/
│   ├── package-info.java                # Task 1  @ApplicationModule {common, database}
│   ├── api/Role.java                    # Task 1
│   ├── api/AuthenticatedUser.java       # Task 5 (principal projection DTO)
│   ├── domain/User.java                 # Task 1
│   ├── domain/RoleSetConverter.java     # Task 1
│   ├── infrastructure/UserRepository.java          # Task 1
│   ├── infrastructure/SecurityConfig.java          # Task 2
│   ├── infrastructure/JwtSupportConfig.java        # Task 2 (encoder/decoder/authorities)
│   ├── application/JwtService.java                 # Task 2
│   ├── application/AuthService.java                # Task 3 (+ PIN in Task 4)
│   └── web/AuthController.java                      # Task 3 (+ Task 4, Task 5)
├── product/
│   ├── package-info.java                # Task 6 (MODIFY Task 10: add integration)
│   ├── api/ProductCatalog.java          # Task 6
│   ├── api/ProductView.java             # Task 6
│   ├── api/ProductSync.java             # Task 10
│   ├── domain/Product.java              # Task 6
│   ├── domain/Category.java             # Task 6
│   ├── infrastructure/ProductRepository.java       # Task 6
│   ├── infrastructure/CategoryRepository.java      # Task 6
│   ├── application/DefaultProductCatalog.java      # Task 6
│   ├── application/ProductErpSyncService.java      # Task 10
│   └── web/ProductController.java                  # Task 7
├── inventory/
│   ├── package-info.java                # Task 8 (MODIFY Task 11: add integration)
│   ├── api/InventoryService.java        # Task 8
│   ├── api/StockView.java               # Task 8
│   ├── api/InventorySync.java           # Task 11
│   ├── domain/StockLevel.java           # Task 8
│   ├── infrastructure/StockLevelRepository.java    # Task 8
│   ├── application/DefaultInventoryService.java     # Task 8
│   ├── application/InventoryErpSyncService.java     # Task 11
│   └── web/InventoryController.java                 # Task 8
└── integration/
    ├── package-info.java                # Task 9  @ApplicationModule {common, database}
    ├── api/ErpClient.java               # Task 9
    ├── api/ErpProduct.java              # Task 9
    ├── api/ErpStockLevel.java           # Task 9
    ├── api/SyncCursorStore.java         # Task 9
    ├── erp/FakeErpClient.java           # Task 9
    ├── erp/SyncCursor.java              # Task 9 (entity)
    ├── erp/SyncCursorRepository.java    # Task 9
    └── erp/JpaSyncCursorStore.java      # Task 9

src/main/resources/
├── application.yml                      # MODIFY (Task 2 jwt defaults; Task 12 sync defaults)
├── application-store-server.yml         # MODIFY (Tasks 1,6,8,9: schema=pos, flyway locations; Task 12: enable scheduler)
└── db/migration/{auth,product,inventory,integration}/V*.sql   # Tasks 1,6,8,9

src/test/java/com/company/pos/
├── auth/UserRepositoryTest.java                 # Task 1
├── auth/JwtServiceTest.java                     # Task 2
├── auth/PasswordLoginTest.java                  # Task 3
├── auth/PinLoginTest.java                       # Task 4
├── auth/RbacTest.java                           # Task 5
├── product/ProductCatalogTest.java             # Task 6
├── product/ProductControllerTest.java          # Task 7
├── inventory/InventoryServiceTest.java         # Task 8
├── integration/ErpIntegrationTest.java         # Task 9
├── product/ProductErpSyncServiceTest.java      # Task 10
├── inventory/InventoryErpSyncServiceTest.java  # Task 11
├── SyncControllerTest.java                      # Task 12 (root package)
└── ErpDownSyncEndToEndTest.java                 # Task 13 (root package)
```

**Design note — pull-based versioned down-sync:** `integration` exposes only the `ErpClient` port (+ DTOs) and a `SyncCursorStore`; it never references `product`/`inventory`. Each domain module's sync service pulls its own stream (`erpClient.fetchProductsSince(cursor)`), upserts records whose ERP `version` is newer than the stored one (ERP authoritative), then advances the cursor to the max version seen. A root-package coordinator invokes both; a timer (gated for tests) and a `MANAGER`-only endpoint trigger it. This keeps integration vendor-neutral and swappable, domain modules owners of their data, and all module dependencies pointing downward.

---

### Task 1: Auth module foundation — User, roles, repository, migration, password encoder

**Files:**
- Modify: `pom.xml` (add `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`, test `spring-security-test`)
- Modify: `src/main/resources/application-store-server.yml` (schema `pos`; add auth Flyway location)
- Create: `src/main/java/com/company/pos/auth/package-info.java`
- Create: `src/main/java/com/company/pos/auth/api/Role.java`
- Create: `src/main/java/com/company/pos/auth/domain/User.java`
- Create: `src/main/java/com/company/pos/auth/domain/RoleSetConverter.java`
- Create: `src/main/java/com/company/pos/auth/infrastructure/UserRepository.java`
- Create: `src/main/java/com/company/pos/auth/infrastructure/PasswordConfig.java`
- Create: `src/main/resources/db/migration/auth/V2__auth_users.sql`
- Test: `src/test/java/com/company/pos/auth/UserRepositoryTest.java`

**Interfaces:**
- Consumes: Phase 0 `common.util.Identifiers`, the `database` dual-profile setup.
- Produces:
  - `Role` enum `{ CASHIER, MANAGER, ADMIN }` in `auth.api`.
  - `User` JPA entity (`auth.domain`): `UUID id`, `String username`, `String cashierCode` (nullable), `String passwordHash`, `String pinHash` (nullable), `String displayName`, `Set<Role> roles`, `boolean enabled`; constructor `User(UUID id, String username, String displayName, String passwordHash, Set<Role> roles)`; getters; `setCashierCode`, `setPinHash`.
  - `UserRepository extends JpaRepository<User, UUID>` with `Optional<User> findByUsername(String)` and `Optional<User> findByCashierCode(String)`.
  - `PasswordConfig` exposing a `PasswordEncoder` bean (`BCryptPasswordEncoder`).

- [ ] **Step 1: Add dependencies to `pom.xml`**

Inside `<dependencies>`, add:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Switch the store-server schema to `pos` and add the auth migration location**

Edit `src/main/resources/application-store-server.yml` — replace the `jpa.properties.hibernate.default_schema` value and the entire `flyway` block so it reads:

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
        default_schema: pos
  flyway:
    enabled: true
    schemas: pos
    default-schema: pos
    create-schemas: true
    locations: classpath:db/migration/configuration,classpath:db/migration/auth
```

- [ ] **Step 3: Write the failing test** `src/test/java/com/company/pos/auth/UserRepositoryTest.java`

```java
package com.company.pos.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.util.Identifiers;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class UserRepositoryTest {

    @Autowired
    UserRepository users;

    @Test
    void persistsAndLooksUpUserWithRoles() {
        User u = new User(Identifiers.newId(), "alice", "Alice Manager",
                "hash", Set.of(Role.MANAGER, Role.CASHIER));
        u.setCashierCode("1001");
        users.save(u);

        User found = users.findByUsername("alice").orElseThrow();
        assertThat(found.getRoles()).containsExactlyInAnyOrder(Role.MANAGER, Role.CASHIER);
        assertThat(users.findByCashierCode("1001")).isPresent();
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./mvnw test -Dtest=UserRepositoryTest`
Expected: FAIL — compilation error, `Role`/`User`/`UserRepository` do not exist.

- [ ] **Step 5: Write the module package** `src/main/java/com/company/pos/auth/package-info.java`

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database" })
package com.company.pos.auth;
```

- [ ] **Step 6: Write `Role`** `src/main/java/com/company/pos/auth/api/Role.java`

```java
package com.company.pos.auth.api;

public enum Role {
    CASHIER,
    MANAGER,
    ADMIN
}
```

- [ ] **Step 7: Write `RoleSetConverter`** `src/main/java/com/company/pos/auth/domain/RoleSetConverter.java`

```java
package com.company.pos.auth.domain;

import com.company.pos.auth.api.Role;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Converter
public class RoleSetConverter implements AttributeConverter<Set<Role>, String> {

    @Override
    public String convertToDatabaseColumn(Set<Role> roles) {
        if (roles == null || roles.isEmpty()) {
            return "";
        }
        return roles.stream().map(Role::name).collect(Collectors.joining(","));
    }

    @Override
    public Set<Role> convertToEntityAttribute(String dbValue) {
        if (dbValue == null || dbValue.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(dbValue.split(","))
                .map(String::trim)
                .map(Role::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
```

- [ ] **Step 8: Write `User`** `src/main/java/com/company/pos/auth/domain/User.java`

```java
package com.company.pos.auth.domain;

import com.company.pos.auth.api.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "app_user")
public class User {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(name = "cashier_code", unique = true, length = 20)
    private String cashierCode;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "pin_hash", length = 100)
    private String pinHash;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Convert(converter = RoleSetConverter.class)
    @Column(name = "roles", nullable = false, length = 200)
    private Set<Role> roles = new LinkedHashSet<>();

    @Column(nullable = false)
    private boolean enabled = true;

    protected User() {
        // JPA
    }

    public User(UUID id, String username, String displayName, String passwordHash, Set<Role> roles) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.roles = new LinkedHashSet<>(roles);
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getCashierCode() {
        return cashierCode;
    }

    public void setCashierCode(String cashierCode) {
        this.cashierCode = cashierCode;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getPinHash() {
        return pinHash;
    }

    public void setPinHash(String pinHash) {
        this.pinHash = pinHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
```

- [ ] **Step 9: Write `UserRepository`** `src/main/java/com/company/pos/auth/infrastructure/UserRepository.java`

```java
package com.company.pos.auth.infrastructure;

import com.company.pos.auth.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    Optional<User> findByCashierCode(String cashierCode);
}
```

- [ ] **Step 10: Write `PasswordConfig`** `src/main/java/com/company/pos/auth/infrastructure/PasswordConfig.java`

```java
package com.company.pos.auth.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
class PasswordConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

- [ ] **Step 11: Write the migration** `src/main/resources/db/migration/auth/V2__auth_users.sql`

```sql
CREATE TABLE app_user (
    id            VARCHAR(36) PRIMARY KEY,
    username      VARCHAR(100) NOT NULL UNIQUE,
    cashier_code  VARCHAR(20) UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    pin_hash      VARCHAR(100),
    display_name  VARCHAR(200) NOT NULL,
    roles         VARCHAR(200) NOT NULL,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE
);
```

- [ ] **Step 12: Run the test to verify it passes**

Run: `./mvnw test -Dtest=UserRepositoryTest`
Expected: PASS — user persists, roles round-trip through the converter, both lookups work (embedded SQLite via ddl-auto).

- [ ] **Step 13: Verify boundaries still hold**

Run: `./mvnw test -Dtest=ModularityTests`
Expected: PASS — `auth` is a valid module depending only on `common`/`database`. Adding `spring-boot-starter-security` activates a default filter chain, but no HTTP calls are made by existing tests, so they still pass (the full suite is re-checked in later tasks).

- [ ] **Step 14: Commit**

```bash
git add pom.xml src/main/resources/application-store-server.yml \
        src/main/java/com/company/pos/auth/ \
        src/main/resources/db/migration/auth/ \
        src/test/java/com/company/pos/auth/UserRepositoryTest.java
git commit -m "feat: add auth module foundation (user, roles, repository, migration)"
```

---

### Task 2: JWT service + stateless security configuration

**Files:**
- Modify: `src/main/resources/application.yml` (JWT secret/ttl defaults)
- Create: `src/main/java/com/company/pos/auth/application/JwtService.java`
- Create: `src/main/java/com/company/pos/auth/infrastructure/JwtSupportConfig.java`
- Create: `src/main/java/com/company/pos/auth/infrastructure/SecurityConfig.java`
- Test: `src/test/java/com/company/pos/auth/JwtServiceTest.java`

**Interfaces:**
- Consumes: `User` (Task 1), Spring Security 6, `spring-boot-starter-oauth2-resource-server`.
- Produces:
  - `JwtService` (`auth.application`, public): `String issue(User user)` — returns a signed HS256 JWT with `sub`=username, `uid`=id, `roles`=role names, expiry from config.
  - `JwtSupportConfig` (`auth.infrastructure`): beans `JwtEncoder`, `JwtDecoder` (both HS256 over the shared secret) and `JwtAuthenticationConverter` (maps the `roles` claim to `ROLE_`-prefixed authorities).
  - `SecurityConfig` (`auth.infrastructure`): a `SecurityFilterChain` — CSRF off, stateless sessions, `permitAll` for `/auth/login` and `/auth/pin-login`, everything else authenticated, oauth2 resource server using the JWT beans. `@EnableMethodSecurity` for later `@PreAuthorize`.

- [ ] **Step 1: Add JWT defaults to `application.yml`**

Append under the existing `spring:` document (top-level keys), so the file becomes:

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

pos:
  auth:
    jwt:
      secret: ${POS_JWT_SECRET:dev-only-secret-change-me-0123456789abcdef}
      ttl-minutes: ${POS_JWT_TTL:720}
```

- [ ] **Step 2: Write the failing test** `src/test/java/com/company/pos/auth/JwtServiceTest.java`

```java
package com.company.pos.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.application.JwtService;
import com.company.pos.auth.domain.User;
import com.company.pos.common.util.Identifiers;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class JwtServiceTest {

    @Autowired
    JwtService jwtService;

    @Autowired
    JwtDecoder jwtDecoder;

    @Test
    void issuesTokenWithSubjectUidAndRoles() {
        UUID id = Identifiers.newId();
        User u = new User(id, "alice", "Alice", "hash", Set.of(Role.MANAGER));

        String token = jwtService.issue(u);
        Jwt decoded = jwtDecoder.decode(token);

        assertThat(decoded.getSubject()).isEqualTo("alice");
        assertThat(decoded.getClaimAsString("uid")).isEqualTo(id.toString());
        assertThat(decoded.getClaimAsStringList("roles")).containsExactly("MANAGER");
        assertThat(decoded.getExpiresAt()).isNotNull();
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=JwtServiceTest`
Expected: FAIL — compilation error, `JwtService` does not exist (and no `JwtDecoder` bean yet).

- [ ] **Step 4: Write `JwtSupportConfig`** `src/main/java/com/company/pos/auth/infrastructure/JwtSupportConfig.java`

```java
package com.company.pos.auth.infrastructure;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtAuthenticationConverter;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

@Configuration
class JwtSupportConfig {

    private final SecretKeySpec secretKey;

    JwtSupportConfig(@Value("${pos.auth.jwt.secret}") String secret) {
        this.secretKey = new SecretKeySpec(secret.getBytes(), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
```

- [ ] **Step 5: Write `JwtService`** `src/main/java/com/company/pos/auth/application/JwtService.java`

```java
package com.company.pos.auth.application;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final long ttlMinutes;

    public JwtService(JwtEncoder encoder, @Value("${pos.auth.jwt.ttl-minutes}") long ttlMinutes) {
        this.encoder = encoder;
        this.ttlMinutes = ttlMinutes;
    }

    public String issue(User user) {
        Instant now = Instant.now();
        List<String> roles = user.getRoles().stream().map(Role::name).toList();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getUsername())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(ttlMinutes)))
                .claim("uid", user.getId().toString())
                .claim("roles", roles)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
```

- [ ] **Step 6: Write `SecurityConfig`** `src/main/java/com/company/pos/auth/infrastructure/SecurityConfig.java`

```java
package com.company.pos.auth.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder jwtDecoder,
            JwtAuthenticationConverter authConverter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/login", "/auth/pin-login").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(authConverter)));
        return http.build();
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./mvnw test -Dtest=JwtServiceTest`
Expected: PASS — token issued and decoded; `sub`/`uid`/`roles`/expiry all present.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/application.yml \
        src/main/java/com/company/pos/auth/application/JwtService.java \
        src/main/java/com/company/pos/auth/infrastructure/JwtSupportConfig.java \
        src/main/java/com/company/pos/auth/infrastructure/SecurityConfig.java \
        src/test/java/com/company/pos/auth/JwtServiceTest.java
git commit -m "feat: add JWT service and stateless resource-server security config"
```

---

### Task 3: Username/password login endpoint

**Files:**
- Create: `src/main/java/com/company/pos/auth/application/AuthService.java`
- Create: `src/main/java/com/company/pos/auth/web/AuthController.java`
- Test: `src/test/java/com/company/pos/auth/PasswordLoginTest.java`

**Interfaces:**
- Consumes: `UserRepository` (Task 1), `PasswordEncoder` (Task 1), `JwtService` (Task 2).
- Produces:
  - `AuthService` (`auth.application`, public): `String login(String username, String rawPassword)` — loads the user, verifies the password with `PasswordEncoder.matches`, throws `DomainException.validation("Invalid credentials")` on failure or disabled user, returns a JWT on success.
  - `AuthController` (`auth.web`): `POST /auth/login` accepting `LoginRequest(String username, String password)`, returning `TokenResponse(String token)` (HTTP 200). These two records are nested public records in `AuthController`.

- [ ] **Step 1: Write the failing test** `src/test/java/com/company/pos/auth/PasswordLoginTest.java`

```java
package com.company.pos.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.util.Identifiers;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Transactional
class PasswordLoginTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void seed() {
        users.save(new User(Identifiers.newId(), "alice", "Alice",
                encoder.encode("s3cret"), Set.of(Role.MANAGER)));
    }

    @Test
    void loginWithValidCredentialsReturnsToken() throws Exception {
        mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"s3cret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void loginWithWrongPasswordIsRejected() throws Exception {
        mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"nope\"}"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=PasswordLoginTest`
Expected: FAIL — compilation error / 404, `AuthController` and `AuthService` do not exist.

- [ ] **Step 3: Write `AuthService`** `src/main/java/com/company/pos/auth/application/AuthService.java`

```java
package com.company.pos.auth.application;

import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.exception.DomainException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwtService) {
        this.users = users;
        this.encoder = encoder;
        this.jwtService = jwtService;
    }

    public String login(String username, String rawPassword) {
        User user = users.findByUsername(username)
                .filter(User::isEnabled)
                .orElseThrow(() -> DomainException.validation("Invalid credentials"));
        if (!encoder.matches(rawPassword, user.getPasswordHash())) {
            throw DomainException.validation("Invalid credentials");
        }
        return jwtService.issue(user);
    }
}
```

- [ ] **Step 4: Write `AuthController`** `src/main/java/com/company/pos/auth/web/AuthController.java`

```java
package com.company.pos.auth.web;

import com.company.pos.auth.application.AuthService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
class AuthController {

    private final AuthService authService;

    AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    TokenResponse login(@RequestBody LoginRequest request) {
        return new TokenResponse(authService.login(request.username(), request.password()));
    }

    public record LoginRequest(String username, String password) {
    }

    public record TokenResponse(String token) {
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=PasswordLoginTest`
Expected: PASS — valid login returns a non-empty token (200); wrong password maps through `DomainException.validation` → RFC-7807 400.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/auth/application/AuthService.java \
        src/main/java/com/company/pos/auth/web/AuthController.java \
        src/test/java/com/company/pos/auth/PasswordLoginTest.java
git commit -m "feat: add username/password login endpoint issuing JWT"
```

---

### Task 4: Cashier PIN login endpoint

**Files:**
- Modify: `src/main/java/com/company/pos/auth/application/AuthService.java` (add `pinLogin`)
- Modify: `src/main/java/com/company/pos/auth/web/AuthController.java` (add `POST /auth/pin-login`)
- Test: `src/test/java/com/company/pos/auth/PinLoginTest.java`

**Interfaces:**
- Consumes: `AuthService` (Task 3), `UserRepository.findByCashierCode` (Task 1), `PasswordEncoder`, `JwtService`.
- Produces:
  - `AuthService.pinLogin(String cashierCode, String pin)` — loads by cashier code, verifies `pin` against `pinHash` (reject if no PIN set / disabled), returns a JWT.
  - `AuthController` `POST /auth/pin-login` accepting `PinRequest(String cashierCode, String pin)`, returning the same `TokenResponse`.

- [ ] **Step 1: Write the failing test** `src/test/java/com/company/pos/auth/PinLoginTest.java`

```java
package com.company.pos.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.util.Identifiers;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Transactional
class PinLoginTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void seed() {
        User u = new User(Identifiers.newId(), "bob", "Bob", encoder.encode("pw"), Set.of(Role.CASHIER));
        u.setCashierCode("2002");
        u.setPinHash(encoder.encode("4321"));
        users.save(u);
    }

    @Test
    void pinLoginWithValidPinReturnsToken() throws Exception {
        mvc.perform(post("/auth/pin-login").contentType("application/json")
                        .content("{\"cashierCode\":\"2002\",\"pin\":\"4321\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void pinLoginWithWrongPinIsRejected() throws Exception {
        mvc.perform(post("/auth/pin-login").contentType("application/json")
                        .content("{\"cashierCode\":\"2002\",\"pin\":\"0000\"}"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=PinLoginTest`
Expected: FAIL — 404/compile error, `pinLogin` / `/auth/pin-login` do not exist.

- [ ] **Step 3: Add `pinLogin` to `AuthService`**

Add this method to `AuthService` (below `login`):

```java
    public String pinLogin(String cashierCode, String pin) {
        User user = users.findByCashierCode(cashierCode)
                .filter(User::isEnabled)
                .orElseThrow(() -> DomainException.validation("Invalid credentials"));
        if (user.getPinHash() == null || !encoder.matches(pin, user.getPinHash())) {
            throw DomainException.validation("Invalid credentials");
        }
        return jwtService.issue(user);
    }
```

- [ ] **Step 4: Add the endpoint and request record to `AuthController`**

Add the mapping method (below `login`) and the record (next to the other records):

```java
    @PostMapping("/pin-login")
    TokenResponse pinLogin(@RequestBody PinRequest request) {
        return new TokenResponse(authService.pinLogin(request.cashierCode(), request.pin()));
    }
```

```java
    public record PinRequest(String cashierCode, String pin) {
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=PinLoginTest`
Expected: PASS — valid PIN returns a token; wrong PIN → 400.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/auth/application/AuthService.java \
        src/main/java/com/company/pos/auth/web/AuthController.java \
        src/test/java/com/company/pos/auth/PinLoginTest.java
git commit -m "feat: add cashier PIN login endpoint"
```

---

### Task 5: RBAC enforcement — `/auth/me` and a MANAGER-only endpoint

**Files:**
- Create: `src/main/java/com/company/pos/auth/api/AuthenticatedUser.java`
- Modify: `src/main/java/com/company/pos/auth/web/AuthController.java` (add `GET /auth/me`, `GET /auth/manager-check`)
- Test: `src/test/java/com/company/pos/auth/RbacTest.java`

**Interfaces:**
- Consumes: the security filter chain + `JwtAuthenticationConverter` (Task 2), `@EnableMethodSecurity` (Task 2).
- Produces:
  - `AuthenticatedUser` (`auth.api`, public record): `(String username, List<String> roles)` — a projection of the authenticated principal that other layers can consume without touching Spring Security types.
  - `AuthController` `GET /auth/me` — authenticated; returns `AuthenticatedUser` from the `JwtAuthenticationToken`.
  - `AuthController` `GET /auth/manager-check` — `@PreAuthorize("hasRole('MANAGER')")`; returns 200 `"ok"` only for managers (proves method security + authority mapping work end-to-end).

- [ ] **Step 1: Write the failing test** `src/test/java/com/company/pos/auth/RbacTest.java`

```java
package com.company.pos.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class RbacTest {

    @Autowired
    MockMvc mvc;

    @Test
    void meIsUnauthorizedWithoutToken() throws Exception {
        mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsPrincipalForAuthenticatedUser() throws Exception {
        mvc.perform(get("/auth/me").with(jwt().jwt(j -> j.subject("alice").claim("roles", java.util.List.of("CASHIER")))
                        .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CASHIER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.roles[0]").value("CASHIER"));
    }

    @Test
    void managerCheckForbiddenForCashier() throws Exception {
        mvc.perform(get("/auth/manager-check").with(jwt()
                        .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CASHIER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCheckAllowedForManager() throws Exception {
        mvc.perform(get("/auth/manager-check").with(jwt()
                        .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=RbacTest`
Expected: FAIL — `/auth/me` and `/auth/manager-check` return 404 (endpoints not defined yet); `AuthenticatedUser` does not exist.

- [ ] **Step 3: Write `AuthenticatedUser`** `src/main/java/com/company/pos/auth/api/AuthenticatedUser.java`

```java
package com.company.pos.auth.api;

import java.util.List;

public record AuthenticatedUser(String username, List<String> roles) {
}
```

- [ ] **Step 4: Add the endpoints to `AuthController`**

Add these imports to `AuthController`:

```java
import com.company.pos.auth.api.AuthenticatedUser;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
```

Add these two mapping methods (below `pinLogin`):

```java
    @GetMapping("/me")
    AuthenticatedUser me(JwtAuthenticationToken authentication) {
        Jwt jwt = authentication.getToken();
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new AuthenticatedUser(jwt.getSubject(), roles == null ? List.of() : roles);
    }

    @GetMapping("/manager-check")
    @PreAuthorize("hasRole('MANAGER')")
    String managerCheck() {
        return "ok";
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=RbacTest`
Expected: PASS — no token → 401; cashier token → `/auth/me` 200 with principal, `/auth/manager-check` 403; manager token → 200.

- [ ] **Step 6: Run the full auth suite and boundaries**

Run: `./mvnw test -Dtest='com.company.pos.auth.*,ModularityTests'`
Expected: PASS — all five auth tests plus `ModularityTests` green.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/pos/auth/api/AuthenticatedUser.java \
        src/main/java/com/company/pos/auth/web/AuthController.java \
        src/test/java/com/company/pos/auth/RbacTest.java
git commit -m "feat: enforce RBAC with /auth/me and a manager-only endpoint"
```

---

### Task 6: Product module — catalogue domain + read facade

**Files:**
- Modify: `src/main/resources/application-store-server.yml` (add product Flyway location)
- Create: `src/main/java/com/company/pos/product/package-info.java`
- Create: `src/main/java/com/company/pos/product/domain/Category.java`
- Create: `src/main/java/com/company/pos/product/domain/Product.java`
- Create: `src/main/java/com/company/pos/product/infrastructure/CategoryRepository.java`
- Create: `src/main/java/com/company/pos/product/infrastructure/ProductRepository.java`
- Create: `src/main/java/com/company/pos/product/api/ProductView.java`
- Create: `src/main/java/com/company/pos/product/api/ProductCatalog.java`
- Create: `src/main/java/com/company/pos/product/application/DefaultProductCatalog.java`
- Create: `src/main/resources/db/migration/product/V3__product_catalog.sql`
- Test: `src/test/java/com/company/pos/product/ProductCatalogTest.java`

**Interfaces:**
- Consumes: `common.util.Identifiers`; `database` persistence.
- Produces:
  - `Category` entity (`product.domain`): `UUID id`, `String code` (unique), `String name`, `long erpVersion`; ctor `Category(UUID, String code, String name)`; `setName`, `setErpVersion`.
  - `Product` entity (`product.domain`): `UUID id`, `String sku` (unique), `String name`, `UUID categoryId` (nullable), `String categoryName` (denormalized), `String barcode`, `String unitOfMeasure`, `BigDecimal unitPrice`, `String currencyCode`, `long erpVersion`, `boolean active`; ctor `Product(UUID, String sku, String name)`; setters for the mutable fields.
  - `ProductRepository extends JpaRepository<Product, UUID>`: `Optional<Product> findBySku(String)`, `List<Product> findByNameContainingIgnoreCaseOrSkuContainingIgnoreCase(String, String)`.
  - `CategoryRepository extends JpaRepository<Category, UUID>`: `Optional<Category> findByCode(String)`.
  - `ProductView` record (`product.api`): `(String sku, String name, String categoryName, String barcode, String unitOfMeasure, BigDecimal unitPrice, String currencyCode, boolean active)`.
  - `ProductCatalog` interface (`product.api`): `Optional<ProductView> findBySku(String sku)`, `List<ProductView> search(String query)`, `List<ProductView> findAll()`.

- [ ] **Step 1: Add the product Flyway location to `application-store-server.yml`**

Change the `spring.flyway.locations` line to:

```yaml
    locations: classpath:db/migration/configuration,classpath:db/migration/auth,classpath:db/migration/product
```

- [ ] **Step 2: Write the failing test** `src/test/java/com/company/pos/product/ProductCatalogTest.java`

```java
package com.company.pos.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.common.util.Identifiers;
import com.company.pos.product.api.ProductCatalog;
import com.company.pos.product.api.ProductView;
import com.company.pos.product.domain.Product;
import com.company.pos.product.infrastructure.ProductRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class ProductCatalogTest {

    @Autowired
    ProductRepository products;
    @Autowired
    ProductCatalog catalog;

    private Product newProduct(String sku, String name) {
        Product p = new Product(Identifiers.newId(), sku, name);
        p.setCategoryName("Beverages");
        p.setBarcode("100000" + sku);
        p.setUnitOfMeasure("EA");
        p.setUnitPrice(new BigDecimal("4.50"));
        p.setCurrencyCode("SAR");
        p.setActive(true);
        return p;
    }

    @Test
    void findBySkuReturnsView() {
        products.save(newProduct("COLA", "Cola Can"));

        ProductView view = catalog.findBySku("COLA").orElseThrow();
        assertThat(view.name()).isEqualTo("Cola Can");
        assertThat(view.categoryName()).isEqualTo("Beverages");
        assertThat(view.unitPrice()).isEqualByComparingTo("4.50");
        assertThat(view.currencyCode()).isEqualTo("SAR");
    }

    @Test
    void searchMatchesPartialName() {
        products.save(newProduct("COLA", "Cola Can"));
        products.save(newProduct("WATER", "Spring Water"));

        assertThat(catalog.search("cola")).extracting(ProductView::sku).containsExactly("COLA");
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=ProductCatalogTest`
Expected: FAIL — compilation error, product types do not exist.

- [ ] **Step 4: Write the module package** `src/main/java/com/company/pos/product/package-info.java`

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database" })
package com.company.pos.product;
```

- [ ] **Step 5: Write `Category`** `src/main/java/com/company/pos/product/domain/Category.java`

```java
package com.company.pos.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "category")
public class Category {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "erp_version", nullable = false)
    private long erpVersion;

    protected Category() {
        // JPA
    }

    public Category(UUID id, String code, String name) {
        this.id = id;
        this.code = code;
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getErpVersion() {
        return erpVersion;
    }

    public void setErpVersion(long erpVersion) {
        this.erpVersion = erpVersion;
    }
}
```

- [ ] **Step 6: Write `Product`** `src/main/java/com/company/pos/product/domain/Product.java`

```java
package com.company.pos.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "product")
public class Product {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String sku;

    @Column(nullable = false, length = 300)
    private String name;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "category_id", length = 36)
    private UUID categoryId;

    @Column(name = "category_name", length = 200)
    private String categoryName;

    @Column(length = 64)
    private String barcode;

    @Column(name = "unit_of_measure", length = 16)
    private String unitOfMeasure;

    @Column(name = "unit_price", precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(name = "erp_version", nullable = false)
    private long erpVersion;

    @Column(nullable = false)
    private boolean active = true;

    protected Product() {
        // JPA
    }

    public Product(UUID id, String sku, String name) {
        this.id = id;
        this.sku = sku;
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public String getBarcode() {
        return barcode;
    }

    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    public String getUnitOfMeasure() {
        return unitOfMeasure;
    }

    public void setUnitOfMeasure(String unitOfMeasure) {
        this.unitOfMeasure = unitOfMeasure;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public long getErpVersion() {
        return erpVersion;
    }

    public void setErpVersion(long erpVersion) {
        this.erpVersion = erpVersion;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
```

- [ ] **Step 7: Write the repositories**

`src/main/java/com/company/pos/product/infrastructure/CategoryRepository.java`:

```java
package com.company.pos.product.infrastructure;

import com.company.pos.product.domain.Category;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    Optional<Category> findByCode(String code);
}
```

`src/main/java/com/company/pos/product/infrastructure/ProductRepository.java`:

```java
package com.company.pos.product.infrastructure;

import com.company.pos.product.domain.Product;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    Optional<Product> findBySku(String sku);

    List<Product> findByNameContainingIgnoreCaseOrSkuContainingIgnoreCase(String name, String sku);
}
```

- [ ] **Step 8: Write `ProductView`** `src/main/java/com/company/pos/product/api/ProductView.java`

```java
package com.company.pos.product.api;

import java.math.BigDecimal;

public record ProductView(
        String sku,
        String name,
        String categoryName,
        String barcode,
        String unitOfMeasure,
        BigDecimal unitPrice,
        String currencyCode,
        boolean active) {
}
```

- [ ] **Step 9: Write `ProductCatalog`** `src/main/java/com/company/pos/product/api/ProductCatalog.java`

```java
package com.company.pos.product.api;

import java.util.List;
import java.util.Optional;

public interface ProductCatalog {

    Optional<ProductView> findBySku(String sku);

    List<ProductView> search(String query);

    List<ProductView> findAll();
}
```

- [ ] **Step 10: Write `DefaultProductCatalog`** `src/main/java/com/company/pos/product/application/DefaultProductCatalog.java`

```java
package com.company.pos.product.application;

import com.company.pos.product.api.ProductCatalog;
import com.company.pos.product.api.ProductView;
import com.company.pos.product.domain.Product;
import com.company.pos.product.infrastructure.ProductRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultProductCatalog implements ProductCatalog {

    private final ProductRepository products;

    DefaultProductCatalog(ProductRepository products) {
        this.products = products;
    }

    @Override
    public Optional<ProductView> findBySku(String sku) {
        return products.findBySku(sku).map(this::toView);
    }

    @Override
    public List<ProductView> search(String query) {
        return products.findByNameContainingIgnoreCaseOrSkuContainingIgnoreCase(query, query)
                .stream().map(this::toView).toList();
    }

    @Override
    public List<ProductView> findAll() {
        return products.findAll().stream().map(this::toView).toList();
    }

    private ProductView toView(Product p) {
        return new ProductView(p.getSku(), p.getName(), p.getCategoryName(), p.getBarcode(),
                p.getUnitOfMeasure(), p.getUnitPrice(), p.getCurrencyCode(), p.isActive());
    }
}
```

- [ ] **Step 11: Write the migration** `src/main/resources/db/migration/product/V3__product_catalog.sql`

```sql
CREATE TABLE category (
    id          VARCHAR(36) PRIMARY KEY,
    code        VARCHAR(50) NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    erp_version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE product (
    id              VARCHAR(36) PRIMARY KEY,
    sku             VARCHAR(64) NOT NULL UNIQUE,
    name            VARCHAR(300) NOT NULL,
    category_id     VARCHAR(36),
    category_name   VARCHAR(200),
    barcode         VARCHAR(64),
    unit_of_measure VARCHAR(16),
    unit_price      NUMERIC(19, 4),
    currency_code   VARCHAR(3),
    erp_version     BIGINT NOT NULL DEFAULT 0,
    active          BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_product_name ON product (name);
```

- [ ] **Step 12: Run the test to verify it passes**

Run: `./mvnw test -Dtest=ProductCatalogTest`
Expected: PASS — `findBySku` returns the mapped view; `search` matches by partial name.

- [ ] **Step 13: Verify boundaries**

Run: `./mvnw test -Dtest=ModularityTests`
Expected: PASS — `product` depends only on `common`/`database`.

- [ ] **Step 14: Commit**

```bash
git add src/main/resources/application-store-server.yml \
        src/main/java/com/company/pos/product/ \
        src/main/resources/db/migration/product/ \
        src/test/java/com/company/pos/product/ProductCatalogTest.java
git commit -m "feat: add product catalogue domain and read facade"
```

---

### Task 7: Product REST endpoints (secured)

**Files:**
- Create: `src/main/java/com/company/pos/product/web/ProductController.java`
- Test: `src/test/java/com/company/pos/product/ProductControllerTest.java`

**Interfaces:**
- Consumes: `ProductCatalog` (Task 6); the security filter chain (Task 2) — these endpoints fall under `anyRequest().authenticated()`.
- Produces:
  - `ProductController` (`product.web`): `GET /products` (optional `?q=` → `search`, else `findAll`) returning `List<ProductView>`; `GET /products/{sku}` returning `ProductView` or 404 via `DomainException.notFound`. Both require an authenticated caller (any role).

- [ ] **Step 1: Write the failing test** `src/test/java/com/company/pos/product/ProductControllerTest.java`

```java
package com.company.pos.product;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.common.util.Identifiers;
import com.company.pos.product.domain.Product;
import com.company.pos.product.infrastructure.ProductRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Transactional
class ProductControllerTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ProductRepository products;

    @BeforeEach
    void seed() {
        Product p = new Product(Identifiers.newId(), "COLA", "Cola Can");
        p.setUnitPrice(new BigDecimal("4.50"));
        p.setCurrencyCode("SAR");
        products.save(p);
    }

    @Test
    void listRequiresAuthentication() throws Exception {
        mvc.perform(get("/products")).andExpect(status().isUnauthorized());
    }

    @Test
    void listReturnsProductsForAuthenticatedCaller() throws Exception {
        mvc.perform(get("/products").with(jwt()
                        .authorities(new SimpleGrantedAuthority("ROLE_CASHIER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sku").value("COLA"));
    }

    @Test
    void getBySkuReturnsProduct() throws Exception {
        mvc.perform(get("/products/COLA").with(jwt()
                        .authorities(new SimpleGrantedAuthority("ROLE_CASHIER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Cola Can"));
    }

    @Test
    void getByUnknownSkuReturns404() throws Exception {
        mvc.perform(get("/products/NOPE").with(jwt()
                        .authorities(new SimpleGrantedAuthority("ROLE_CASHIER"))))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=ProductControllerTest`
Expected: FAIL — `/products` returns 404 (controller not defined).

- [ ] **Step 3: Write `ProductController`** `src/main/java/com/company/pos/product/web/ProductController.java`

```java
package com.company.pos.product.web;

import com.company.pos.common.exception.DomainException;
import com.company.pos.product.api.ProductCatalog;
import com.company.pos.product.api.ProductView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ProductController {

    private final ProductCatalog catalog;

    ProductController(ProductCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/products")
    List<ProductView> list(@RequestParam(name = "q", required = false) String query) {
        if (query == null || query.isBlank()) {
            return catalog.findAll();
        }
        return catalog.search(query);
    }

    @GetMapping("/products/{sku}")
    ProductView bySku(@PathVariable String sku) {
        return catalog.findBySku(sku)
                .orElseThrow(() -> DomainException.notFound("No product with sku " + sku));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=ProductControllerTest`
Expected: PASS — unauthenticated → 401; authenticated list/get → 200; unknown sku → 404 (RFC-7807).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/pos/product/web/ProductController.java \
        src/test/java/com/company/pos/product/ProductControllerTest.java
git commit -m "feat: add secured product catalogue REST endpoints"
```

---

### Task 8: Inventory module — stock levels + read facade/endpoint

**Files:**
- Modify: `src/main/resources/application-store-server.yml` (add inventory Flyway location)
- Create: `src/main/java/com/company/pos/inventory/package-info.java`
- Create: `src/main/java/com/company/pos/inventory/domain/StockLevel.java`
- Create: `src/main/java/com/company/pos/inventory/infrastructure/StockLevelRepository.java`
- Create: `src/main/java/com/company/pos/inventory/api/StockView.java`
- Create: `src/main/java/com/company/pos/inventory/api/InventoryService.java`
- Create: `src/main/java/com/company/pos/inventory/application/DefaultInventoryService.java`
- Create: `src/main/java/com/company/pos/inventory/web/InventoryController.java`
- Create: `src/main/resources/db/migration/inventory/V4__inventory_stock_level.sql`
- Test: `src/test/java/com/company/pos/inventory/InventoryServiceTest.java`

**Interfaces:**
- Consumes: `common.util.Identifiers`; `database`; `common.exception.DomainException`; the security chain (Task 2).
- Produces:
  - `StockLevel` entity (`inventory.domain`): `UUID id`, `String sku`, `String locationCode`, `BigDecimal quantityOnHand`, `long erpVersion`; ctor `StockLevel(UUID, String sku, String locationCode)`; `setQuantityOnHand`, `setErpVersion`. Unique on `(sku, location_code)`.
  - `StockLevelRepository extends JpaRepository<StockLevel, UUID>`: `Optional<StockLevel> findBySkuAndLocationCode(String, String)`, `List<StockLevel> findBySku(String)`.
  - `StockView` record (`inventory.api`): `(String sku, BigDecimal quantityOnHand)`.
  - `InventoryService` interface (`inventory.api`): `Optional<StockView> onHand(String sku)` — total quantity across locations for the sku, empty if none.
  - `InventoryController` (`inventory.web`): `GET /inventory/{sku}` (authenticated) → `StockView` or 404.

- [ ] **Step 1: Add the inventory Flyway location to `application-store-server.yml`**

Change the `spring.flyway.locations` line to:

```yaml
    locations: classpath:db/migration/configuration,classpath:db/migration/auth,classpath:db/migration/product,classpath:db/migration/inventory
```

- [ ] **Step 2: Write the failing test** `src/test/java/com/company/pos/inventory/InventoryServiceTest.java`

```java
package com.company.pos.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.common.util.Identifiers;
import com.company.pos.inventory.api.InventoryService;
import com.company.pos.inventory.api.StockView;
import com.company.pos.inventory.domain.StockLevel;
import com.company.pos.inventory.infrastructure.StockLevelRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class InventoryServiceTest {

    @Autowired
    StockLevelRepository stock;
    @Autowired
    InventoryService inventory;

    private StockLevel level(String sku, String location, String qty) {
        StockLevel s = new StockLevel(Identifiers.newId(), sku, location);
        s.setQuantityOnHand(new BigDecimal(qty));
        return s;
    }

    @Test
    void onHandSumsAcrossLocations() {
        stock.save(level("COLA", "MAIN", "10"));
        stock.save(level("COLA", "BACK", "5"));

        StockView view = inventory.onHand("COLA").orElseThrow();
        assertThat(view.quantityOnHand()).isEqualByComparingTo("15");
    }

    @Test
    void onHandEmptyForUnknownSku() {
        assertThat(inventory.onHand("NOPE")).isEmpty();
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=InventoryServiceTest`
Expected: FAIL — compilation error, inventory types do not exist.

- [ ] **Step 4: Write the module package** `src/main/java/com/company/pos/inventory/package-info.java`

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database" })
package com.company.pos.inventory;
```

- [ ] **Step 5: Write `StockLevel`** `src/main/java/com/company/pos/inventory/domain/StockLevel.java`

```java
package com.company.pos.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "stock_level",
        uniqueConstraints = @UniqueConstraint(name = "uq_stock_sku_location",
                columnNames = { "sku", "location_code" }))
public class StockLevel {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(name = "location_code", nullable = false, length = 32)
    private String locationCode;

    @Column(name = "quantity_on_hand", nullable = false, precision = 19, scale = 3)
    private BigDecimal quantityOnHand = BigDecimal.ZERO;

    @Column(name = "erp_version", nullable = false)
    private long erpVersion;

    protected StockLevel() {
        // JPA
    }

    public StockLevel(UUID id, String sku, String locationCode) {
        this.id = id;
        this.sku = sku;
        this.locationCode = locationCode;
    }

    public UUID getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getLocationCode() {
        return locationCode;
    }

    public BigDecimal getQuantityOnHand() {
        return quantityOnHand;
    }

    public void setQuantityOnHand(BigDecimal quantityOnHand) {
        this.quantityOnHand = quantityOnHand;
    }

    public long getErpVersion() {
        return erpVersion;
    }

    public void setErpVersion(long erpVersion) {
        this.erpVersion = erpVersion;
    }
}
```

- [ ] **Step 6: Write `StockLevelRepository`** `src/main/java/com/company/pos/inventory/infrastructure/StockLevelRepository.java`

```java
package com.company.pos.inventory.infrastructure;

import com.company.pos.inventory.domain.StockLevel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockLevelRepository extends JpaRepository<StockLevel, UUID> {

    Optional<StockLevel> findBySkuAndLocationCode(String sku, String locationCode);

    List<StockLevel> findBySku(String sku);
}
```

- [ ] **Step 7: Write `StockView`** `src/main/java/com/company/pos/inventory/api/StockView.java`

```java
package com.company.pos.inventory.api;

import java.math.BigDecimal;

public record StockView(String sku, BigDecimal quantityOnHand) {
}
```

- [ ] **Step 8: Write `InventoryService`** `src/main/java/com/company/pos/inventory/api/InventoryService.java`

```java
package com.company.pos.inventory.api;

import java.util.Optional;

public interface InventoryService {

    Optional<StockView> onHand(String sku);
}
```

- [ ] **Step 9: Write `DefaultInventoryService`** `src/main/java/com/company/pos/inventory/application/DefaultInventoryService.java`

```java
package com.company.pos.inventory.application;

import com.company.pos.inventory.api.InventoryService;
import com.company.pos.inventory.api.StockView;
import com.company.pos.inventory.domain.StockLevel;
import com.company.pos.inventory.infrastructure.StockLevelRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultInventoryService implements InventoryService {

    private final StockLevelRepository stock;

    DefaultInventoryService(StockLevelRepository stock) {
        this.stock = stock;
    }

    @Override
    public Optional<StockView> onHand(String sku) {
        List<StockLevel> levels = stock.findBySku(sku);
        if (levels.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal total = levels.stream()
                .map(StockLevel::getQuantityOnHand)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Optional.of(new StockView(sku, total));
    }
}
```

- [ ] **Step 10: Write `InventoryController`** `src/main/java/com/company/pos/inventory/web/InventoryController.java`

```java
package com.company.pos.inventory.web;

import com.company.pos.common.exception.DomainException;
import com.company.pos.inventory.api.InventoryService;
import com.company.pos.inventory.api.StockView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class InventoryController {

    private final InventoryService inventory;

    InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping("/inventory/{sku}")
    StockView onHand(@PathVariable String sku) {
        return inventory.onHand(sku)
                .orElseThrow(() -> DomainException.notFound("No stock for sku " + sku));
    }
}
```

- [ ] **Step 11: Write the migration** `src/main/resources/db/migration/inventory/V4__inventory_stock_level.sql`

```sql
CREATE TABLE stock_level (
    id               VARCHAR(36) PRIMARY KEY,
    sku              VARCHAR(64) NOT NULL,
    location_code    VARCHAR(32) NOT NULL,
    quantity_on_hand NUMERIC(19, 3) NOT NULL DEFAULT 0,
    erp_version      BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_stock_sku_location UNIQUE (sku, location_code)
);

CREATE INDEX idx_stock_sku ON stock_level (sku);
```

- [ ] **Step 12: Run the test to verify it passes**

Run: `./mvnw test -Dtest=InventoryServiceTest`
Expected: PASS — `onHand` sums across locations; unknown sku → empty.

- [ ] **Step 13: Verify boundaries**

Run: `./mvnw test -Dtest=ModularityTests`
Expected: PASS — `inventory` depends only on `common`/`database`.

- [ ] **Step 14: Commit**

```bash
git add src/main/resources/application-store-server.yml \
        src/main/java/com/company/pos/inventory/ \
        src/main/resources/db/migration/inventory/ \
        src/test/java/com/company/pos/inventory/InventoryServiceTest.java
git commit -m "feat: add inventory stock-level domain, facade, and endpoint"
```

---

### Task 9: Integration module — ErpClient port, fake adapter, sync cursors

**Files:**
- Modify: `src/main/resources/application-store-server.yml` (add integration Flyway location)
- Create: `src/main/java/com/company/pos/integration/package-info.java`
- Create: `src/main/java/com/company/pos/integration/api/ErpProduct.java`
- Create: `src/main/java/com/company/pos/integration/api/ErpStockLevel.java`
- Create: `src/main/java/com/company/pos/integration/api/ErpClient.java`
- Create: `src/main/java/com/company/pos/integration/api/SyncCursorStore.java`
- Create: `src/main/java/com/company/pos/integration/erp/FakeErpClient.java`
- Create: `src/main/java/com/company/pos/integration/erp/SyncCursor.java`
- Create: `src/main/java/com/company/pos/integration/erp/SyncCursorRepository.java`
- Create: `src/main/java/com/company/pos/integration/erp/JpaSyncCursorStore.java`
- Create: `src/main/resources/db/migration/integration/V5__integration_sync_cursor.sql`
- Test: `src/test/java/com/company/pos/integration/ErpIntegrationTest.java`

**Interfaces:**
- Consumes: `common`, `database`.
- Produces (public, `integration.api`):
  - `ErpProduct` record: `(String sku, String name, String categoryCode, String categoryName, String barcode, String unitOfMeasure, BigDecimal unitPrice, String currencyCode, long version, boolean active)`.
  - `ErpStockLevel` record: `(String sku, String locationCode, BigDecimal quantityOnHand, long version)`.
  - `ErpClient` interface: `List<ErpProduct> fetchProductsSince(long version)`, `List<ErpStockLevel> fetchStockLevelsSince(long version)` — each returns records with `version` strictly greater than the argument, ascending by version.
  - `SyncCursorStore` interface: `long get(String stream)` (0 when absent), `void set(String stream, long value)`.
- Internal (`integration.erp`):
  - `FakeErpClient implements ErpClient` (`@Component`): in-memory, seedable via `addProduct(ErpProduct)`, `addStockLevel(ErpStockLevel)`, `clear()`. The only `ErpClient` bean in Phase 1; the concrete vendor adapter replaces it later.
  - `SyncCursor` entity + `SyncCursorRepository` + `JpaSyncCursorStore implements SyncCursorStore`.

- [ ] **Step 1: Add the integration Flyway location to `application-store-server.yml`**

Change the `spring.flyway.locations` line to:

```yaml
    locations: classpath:db/migration/configuration,classpath:db/migration/auth,classpath:db/migration/product,classpath:db/migration/inventory,classpath:db/migration/integration
```

- [ ] **Step 2: Write the failing test** `src/test/java/com/company/pos/integration/ErpIntegrationTest.java`

```java
package com.company.pos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.integration.api.ErpClient;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.SyncCursorStore;
import com.company.pos.integration.erp.FakeErpClient;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class ErpIntegrationTest {

    @Autowired
    ErpClient erpClient;
    @Autowired
    FakeErpClient fake;
    @Autowired
    SyncCursorStore cursors;

    @BeforeEach
    void reset() {
        fake.clear();
    }

    private ErpProduct product(String sku, long version) {
        return new ErpProduct(sku, "Name " + sku, "BEV", "Beverages", "bc" + sku,
                "EA", new BigDecimal("1.00"), "SAR", version, true);
    }

    @Test
    void fetchProductsSinceReturnsOnlyNewerVersionsAscending() {
        fake.addProduct(product("A", 1));
        fake.addProduct(product("B", 3));
        fake.addProduct(product("C", 2));

        assertThat(erpClient.fetchProductsSince(0)).extracting(ErpProduct::sku)
                .containsExactly("A", "C", "B");
        assertThat(erpClient.fetchProductsSince(2)).extracting(ErpProduct::sku)
                .containsExactly("B");
    }

    @Test
    void cursorDefaultsToZeroThenPersists() {
        assertThat(cursors.get("products")).isZero();
        cursors.set("products", 7);
        assertThat(cursors.get("products")).isEqualTo(7);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=ErpIntegrationTest`
Expected: FAIL — compilation error, integration types do not exist.

- [ ] **Step 4: Write the module package** `src/main/java/com/company/pos/integration/package-info.java`

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database" })
package com.company.pos.integration;
```

- [ ] **Step 5: Write the API DTOs**

`src/main/java/com/company/pos/integration/api/ErpProduct.java`:

```java
package com.company.pos.integration.api;

import java.math.BigDecimal;

public record ErpProduct(
        String sku,
        String name,
        String categoryCode,
        String categoryName,
        String barcode,
        String unitOfMeasure,
        BigDecimal unitPrice,
        String currencyCode,
        long version,
        boolean active) {
}
```

`src/main/java/com/company/pos/integration/api/ErpStockLevel.java`:

```java
package com.company.pos.integration.api;

import java.math.BigDecimal;

public record ErpStockLevel(String sku, String locationCode, BigDecimal quantityOnHand, long version) {
}
```

- [ ] **Step 6: Write the API ports**

`src/main/java/com/company/pos/integration/api/ErpClient.java`:

```java
package com.company.pos.integration.api;

import java.util.List;

public interface ErpClient {

    List<ErpProduct> fetchProductsSince(long version);

    List<ErpStockLevel> fetchStockLevelsSince(long version);
}
```

`src/main/java/com/company/pos/integration/api/SyncCursorStore.java`:

```java
package com.company.pos.integration.api;

public interface SyncCursorStore {

    long get(String stream);

    void set(String stream, long value);
}
```

- [ ] **Step 7: Write `FakeErpClient`** `src/main/java/com/company/pos/integration/erp/FakeErpClient.java`

```java
package com.company.pos.integration.erp;

import com.company.pos.integration.api.ErpClient;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/** In-memory ERP stand-in for Phase 1. Replaced by a concrete vendor adapter later. */
@Component
public class FakeErpClient implements ErpClient {

    private final List<ErpProduct> products = new ArrayList<>();
    private final List<ErpStockLevel> stockLevels = new ArrayList<>();

    public void addProduct(ErpProduct product) {
        products.add(product);
    }

    public void addStockLevel(ErpStockLevel level) {
        stockLevels.add(level);
    }

    public void clear() {
        products.clear();
        stockLevels.clear();
    }

    @Override
    public List<ErpProduct> fetchProductsSince(long version) {
        return products.stream()
                .filter(p -> p.version() > version)
                .sorted(Comparator.comparingLong(ErpProduct::version))
                .toList();
    }

    @Override
    public List<ErpStockLevel> fetchStockLevelsSince(long version) {
        return stockLevels.stream()
                .filter(s -> s.version() > version)
                .sorted(Comparator.comparingLong(ErpStockLevel::version))
                .toList();
    }
}
```

- [ ] **Step 8: Write `SyncCursor`** `src/main/java/com/company/pos/integration/erp/SyncCursor.java`

```java
package com.company.pos.integration.erp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sync_cursor")
public class SyncCursor {

    @Id
    @Column(name = "stream", length = 64)
    private String stream;

    @Column(name = "cursor_value", nullable = false)
    private long value;

    protected SyncCursor() {
        // JPA
    }

    public SyncCursor(String stream, long value) {
        this.stream = stream;
        this.value = value;
    }

    public String getStream() {
        return stream;
    }

    public long getValue() {
        return value;
    }

    public void setValue(long value) {
        this.value = value;
    }
}
```

- [ ] **Step 9: Write `SyncCursorRepository`** `src/main/java/com/company/pos/integration/erp/SyncCursorRepository.java`

```java
package com.company.pos.integration.erp;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncCursorRepository extends JpaRepository<SyncCursor, String> {
}
```

- [ ] **Step 10: Write `JpaSyncCursorStore`** `src/main/java/com/company/pos/integration/erp/JpaSyncCursorStore.java`

```java
package com.company.pos.integration.erp;

import com.company.pos.integration.api.SyncCursorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class JpaSyncCursorStore implements SyncCursorStore {

    private final SyncCursorRepository repository;

    JpaSyncCursorStore(SyncCursorRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public long get(String stream) {
        return repository.findById(stream).map(SyncCursor::getValue).orElse(0L);
    }

    @Override
    public void set(String stream, long value) {
        SyncCursor cursor = repository.findById(stream).orElseGet(() -> new SyncCursor(stream, value));
        cursor.setValue(value);
        repository.save(cursor);
    }
}
```

- [ ] **Step 11: Write the migration** `src/main/resources/db/migration/integration/V5__integration_sync_cursor.sql`

```sql
CREATE TABLE sync_cursor (
    stream       VARCHAR(64) PRIMARY KEY,
    cursor_value BIGINT NOT NULL
);
```

- [ ] **Step 12: Run the test to verify it passes**

Run: `./mvnw test -Dtest=ErpIntegrationTest`
Expected: PASS — fake returns only newer versions ascending; cursor defaults to 0 then persists.

- [ ] **Step 13: Verify boundaries**

Run: `./mvnw test -Dtest=ModularityTests`
Expected: PASS — `integration` depends only on `common`/`database`; its `api` is the only thing other modules will reference.

- [ ] **Step 14: Commit**

```bash
git add src/main/resources/application-store-server.yml \
        src/main/java/com/company/pos/integration/ \
        src/main/resources/db/migration/integration/ \
        src/test/java/com/company/pos/integration/ErpIntegrationTest.java
git commit -m "feat: add integration module with ErpClient port, fake adapter, sync cursors"
```

---

### Task 10: Product ERP down-sync (pull, versioned upsert, cursor)

**Files:**
- Create: `src/main/java/com/company/pos/integration/api/package-info.java` (expose `api` as a named interface)
- Modify: `src/main/java/com/company/pos/product/package-info.java` (allow dependency on `integration :: api`)
- Create: `src/main/java/com/company/pos/product/api/ProductSync.java`
- Create: `src/main/java/com/company/pos/product/application/ProductErpSyncService.java`
- Test: `src/test/java/com/company/pos/product/ProductErpSyncServiceTest.java`

**Interfaces:**
- Consumes: `integration.api.ErpClient`, `integration.api.ErpProduct`, `integration.api.SyncCursorStore` (cross-module via the `integration :: api` named interface); `ProductRepository`, `CategoryRepository`, `ProductCatalog` (Task 6); `common.util.Identifiers`.
- Produces:
  - `ProductSync` interface (`product.api`): `int sync()` — pulls the `"products"` stream since the stored cursor, upserts each record into `product`/`category` when the ERP `version` is newer (ERP authoritative), advances the cursor to the highest version seen, returns the number of products upserted.

- [ ] **Step 1: Expose the integration API as a named interface** `src/main/java/com/company/pos/integration/api/package-info.java`

```java
@org.springframework.modulith.NamedInterface("api")
package com.company.pos.integration.api;
```

- [ ] **Step 2: Allow `product` to depend on the integration API** — replace `src/main/java/com/company/pos/product/package-info.java` with:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "integration :: api" })
package com.company.pos.product;
```

- [ ] **Step 3: Write the failing test** `src/test/java/com/company/pos/product/ProductErpSyncServiceTest.java`

```java
package com.company.pos.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.SyncCursorStore;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductCatalog;
import com.company.pos.product.api.ProductSync;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class ProductErpSyncServiceTest {

    @Autowired
    FakeErpClient fake;
    @Autowired
    ProductSync productSync;
    @Autowired
    ProductCatalog catalog;
    @Autowired
    SyncCursorStore cursors;

    @BeforeEach
    void reset() {
        fake.clear();
    }

    private ErpProduct erpProduct(String sku, String name, long version) {
        return new ErpProduct(sku, name, "BEV", "Beverages", "bc" + sku, "EA",
                new BigDecimal("2.00"), "SAR", version, true);
    }

    @Test
    void syncUpsertsNewProductsAndAdvancesCursor() {
        fake.addProduct(erpProduct("COLA", "Cola Can", 1));
        fake.addProduct(erpProduct("WATER", "Spring Water", 2));

        int upserted = productSync.sync();

        assertThat(upserted).isEqualTo(2);
        assertThat(catalog.findBySku("COLA")).isPresent();
        assertThat(cursors.get("products")).isEqualTo(2);
    }

    @Test
    void resyncIsIdempotentWhenNothingNew() {
        fake.addProduct(erpProduct("COLA", "Cola Can", 1));
        productSync.sync();

        assertThat(productSync.sync()).isZero();
    }

    @Test
    void newerVersionUpdatesExistingProduct() {
        fake.addProduct(erpProduct("COLA", "Cola Can", 1));
        productSync.sync();

        fake.addProduct(erpProduct("COLA", "Cola Can 330ml", 3));
        int upserted = productSync.sync();

        assertThat(upserted).isEqualTo(1);
        assertThat(catalog.findBySku("COLA").orElseThrow().name()).isEqualTo("Cola Can 330ml");
        assertThat(cursors.get("products")).isEqualTo(3);
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./mvnw test -Dtest=ProductErpSyncServiceTest`
Expected: FAIL — compilation error, `ProductSync` / `ProductErpSyncService` do not exist.

- [ ] **Step 5: Write `ProductSync`** `src/main/java/com/company/pos/product/api/ProductSync.java`

```java
package com.company.pos.product.api;

public interface ProductSync {

    int sync();
}
```

- [ ] **Step 6: Write `ProductErpSyncService`** `src/main/java/com/company/pos/product/application/ProductErpSyncService.java`

```java
package com.company.pos.product.application;

import com.company.pos.common.util.Identifiers;
import com.company.pos.integration.api.ErpClient;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.SyncCursorStore;
import com.company.pos.product.api.ProductSync;
import com.company.pos.product.domain.Category;
import com.company.pos.product.domain.Product;
import com.company.pos.product.infrastructure.CategoryRepository;
import com.company.pos.product.infrastructure.ProductRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class ProductErpSyncService implements ProductSync {

    private static final String STREAM = "products";

    private final ErpClient erpClient;
    private final SyncCursorStore cursors;
    private final ProductRepository products;
    private final CategoryRepository categories;

    ProductErpSyncService(ErpClient erpClient, SyncCursorStore cursors,
            ProductRepository products, CategoryRepository categories) {
        this.erpClient = erpClient;
        this.cursors = cursors;
        this.products = products;
        this.categories = categories;
    }

    @Override
    public int sync() {
        long cursor = cursors.get(STREAM);
        List<ErpProduct> batch = erpClient.fetchProductsSince(cursor);
        long maxVersion = cursor;
        int upserted = 0;

        for (ErpProduct e : batch) {
            if (e.version() > maxVersion) {
                maxVersion = e.version();
            }
            UUID categoryId = upsertCategory(e);

            Product existing = products.findBySku(e.sku()).orElse(null);
            if (existing != null && existing.getErpVersion() >= e.version()) {
                continue;
            }
            Product p = existing != null ? existing : new Product(Identifiers.newId(), e.sku(), e.name());
            p.setName(e.name());
            p.setCategoryId(categoryId);
            p.setCategoryName(e.categoryName());
            p.setBarcode(e.barcode());
            p.setUnitOfMeasure(e.unitOfMeasure());
            p.setUnitPrice(e.unitPrice());
            p.setCurrencyCode(e.currencyCode());
            p.setErpVersion(e.version());
            p.setActive(e.active());
            products.save(p);
            upserted++;
        }

        if (maxVersion > cursor) {
            cursors.set(STREAM, maxVersion);
        }
        return upserted;
    }

    private UUID upsertCategory(ErpProduct e) {
        Category category = categories.findByCode(e.categoryCode())
                .orElseGet(() -> new Category(Identifiers.newId(), e.categoryCode(), e.categoryName()));
        category.setName(e.categoryName());
        if (e.version() > category.getErpVersion()) {
            category.setErpVersion(e.version());
        }
        categories.save(category);
        return category.getId();
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./mvnw test -Dtest=ProductErpSyncServiceTest`
Expected: PASS — first sync upserts 2 and advances cursor to 2; re-sync with nothing new returns 0; a newer version updates the product and advances the cursor to 3.

- [ ] **Step 8: Verify boundaries**

Run: `./mvnw test -Dtest=ModularityTests`
Expected: PASS — `product` now legally depends on `integration :: api`; `integration` still exposes only its `api` named interface.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/company/pos/integration/api/package-info.java \
        src/main/java/com/company/pos/product/package-info.java \
        src/main/java/com/company/pos/product/api/ProductSync.java \
        src/main/java/com/company/pos/product/application/ProductErpSyncService.java \
        src/test/java/com/company/pos/product/ProductErpSyncServiceTest.java
git commit -m "feat: add versioned product ERP down-sync via ErpClient port"
```

---

### Task 11: Inventory ERP down-sync (pull, versioned upsert, cursor)

**Files:**
- Modify: `src/main/java/com/company/pos/inventory/package-info.java` (allow dependency on `integration :: api`)
- Create: `src/main/java/com/company/pos/inventory/api/InventorySync.java`
- Create: `src/main/java/com/company/pos/inventory/application/InventoryErpSyncService.java`
- Test: `src/test/java/com/company/pos/inventory/InventoryErpSyncServiceTest.java`

**Interfaces:**
- Consumes: `integration.api.ErpClient`, `integration.api.ErpStockLevel`, `integration.api.SyncCursorStore`; `StockLevelRepository` (Task 8); `InventoryService` (Task 8); `common.util.Identifiers`.
- Produces:
  - `InventorySync` interface (`inventory.api`): `int sync()` — pulls the `"stock"` stream since the stored cursor, upserts `StockLevel` rows by `(sku, locationCode)` when the ERP `version` is newer, advances the cursor, returns the number of rows upserted.

- [ ] **Step 1: Allow `inventory` to depend on the integration API** — replace `src/main/java/com/company/pos/inventory/package-info.java` with:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "integration :: api" })
package com.company.pos.inventory;
```

- [ ] **Step 2: Write the failing test** `src/test/java/com/company/pos/inventory/InventoryErpSyncServiceTest.java`

```java
package com.company.pos.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.api.SyncCursorStore;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.inventory.api.InventoryService;
import com.company.pos.inventory.api.InventorySync;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class InventoryErpSyncServiceTest {

    @Autowired
    FakeErpClient fake;
    @Autowired
    InventorySync inventorySync;
    @Autowired
    InventoryService inventory;
    @Autowired
    SyncCursorStore cursors;

    @BeforeEach
    void reset() {
        fake.clear();
    }

    @Test
    void syncUpsertsStockAndAdvancesCursor() {
        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("12"), 1));

        int upserted = inventorySync.sync();

        assertThat(upserted).isEqualTo(1);
        assertThat(inventory.onHand("COLA").orElseThrow().quantityOnHand()).isEqualByComparingTo("12");
        assertThat(cursors.get("stock")).isEqualTo(1);
    }

    @Test
    void newerVersionUpdatesQuantity() {
        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("12"), 1));
        inventorySync.sync();

        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("7"), 4));
        inventorySync.sync();

        assertThat(inventory.onHand("COLA").orElseThrow().quantityOnHand()).isEqualByComparingTo("7");
        assertThat(cursors.get("stock")).isEqualTo(4);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=InventoryErpSyncServiceTest`
Expected: FAIL — compilation error, `InventorySync` / `InventoryErpSyncService` do not exist.

- [ ] **Step 4: Write `InventorySync`** `src/main/java/com/company/pos/inventory/api/InventorySync.java`

```java
package com.company.pos.inventory.api;

public interface InventorySync {

    int sync();
}
```

- [ ] **Step 5: Write `InventoryErpSyncService`** `src/main/java/com/company/pos/inventory/application/InventoryErpSyncService.java`

```java
package com.company.pos.inventory.application;

import com.company.pos.common.util.Identifiers;
import com.company.pos.integration.api.ErpClient;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.api.SyncCursorStore;
import com.company.pos.inventory.api.InventorySync;
import com.company.pos.inventory.domain.StockLevel;
import com.company.pos.inventory.infrastructure.StockLevelRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class InventoryErpSyncService implements InventorySync {

    private static final String STREAM = "stock";

    private final ErpClient erpClient;
    private final SyncCursorStore cursors;
    private final StockLevelRepository stock;

    InventoryErpSyncService(ErpClient erpClient, SyncCursorStore cursors, StockLevelRepository stock) {
        this.erpClient = erpClient;
        this.cursors = cursors;
        this.stock = stock;
    }

    @Override
    public int sync() {
        long cursor = cursors.get(STREAM);
        List<ErpStockLevel> batch = erpClient.fetchStockLevelsSince(cursor);
        long maxVersion = cursor;
        int upserted = 0;

        for (ErpStockLevel e : batch) {
            if (e.version() > maxVersion) {
                maxVersion = e.version();
            }
            StockLevel existing = stock.findBySkuAndLocationCode(e.sku(), e.locationCode()).orElse(null);
            if (existing != null && existing.getErpVersion() >= e.version()) {
                continue;
            }
            StockLevel level = existing != null
                    ? existing
                    : new StockLevel(Identifiers.newId(), e.sku(), e.locationCode());
            level.setQuantityOnHand(e.quantityOnHand());
            level.setErpVersion(e.version());
            stock.save(level);
            upserted++;
        }

        if (maxVersion > cursor) {
            cursors.set(STREAM, maxVersion);
        }
        return upserted;
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw test -Dtest=InventoryErpSyncServiceTest`
Expected: PASS — stock upserted and cursor advanced; a newer version replaces the quantity and advances the cursor to 4.

- [ ] **Step 7: Verify boundaries**

Run: `./mvnw test -Dtest=ModularityTests`
Expected: PASS — `inventory` now legally depends on `integration :: api`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/company/pos/inventory/package-info.java \
        src/main/java/com/company/pos/inventory/api/InventorySync.java \
        src/main/java/com/company/pos/inventory/application/InventoryErpSyncService.java \
        src/test/java/com/company/pos/inventory/InventoryErpSyncServiceTest.java
git commit -m "feat: add versioned inventory ERP down-sync via ErpClient port"
```

---

### Task 12: ERP sync orchestration — coordinator, scheduler, manager-only trigger

The cross-module coordinator lives in the **application root package** `com.company.pos` (not a Modulith module, so it may wire both capability modules). Scheduling defaults OFF (so it never fires during tests, which run the `embedded` profile) and is enabled only on `store-server`; a `MANAGER`-only endpoint triggers sync on demand.

**Files:**
- Modify: `src/main/java/com/company/pos/PosApplication.java` (add `@EnableScheduling`)
- Modify: `src/main/resources/application.yml` (sync defaults; `scheduled` defaults false)
- Modify: `src/main/resources/application-store-server.yml` (enable scheduled sync)
- Create: `src/main/java/com/company/pos/ErpSyncCoordinator.java`
- Create: `src/main/java/com/company/pos/ErpSyncScheduler.java`
- Create: `src/main/java/com/company/pos/SyncController.java`
- Test: `src/test/java/com/company/pos/SyncControllerTest.java`

**Interfaces:**
- Consumes: `product.api.ProductSync`, `inventory.api.InventorySync` (referenced from the root package, which Modulith does not constrain).
- Produces:
  - `ErpSyncCoordinator` (`@Component`, root): `SyncSummary syncAll()` calling both syncs; nested public record `SyncSummary(int products, int stock)`.
  - `ErpSyncScheduler` (`@Component`, root, `@ConditionalOnProperty("pos.sync.erp.scheduled"=true)`): `@Scheduled(fixedDelayString=...)` calls `coordinator.syncAll()`.
  - `SyncController` (`@RestController`, root): `POST /sync/erp` `@PreAuthorize("hasRole('MANAGER')")` returning `SyncSummary`.

- [ ] **Step 1: Write the failing test** `src/test/java/com/company/pos/SyncControllerTest.java`

```java
package com.company.pos;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Transactional
class SyncControllerTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    FakeErpClient fake;

    @BeforeEach
    void seed() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("20"), 1));
    }

    @Test
    void triggerRequiresAuthentication() throws Exception {
        mvc.perform(post("/sync/erp")).andExpect(status().isUnauthorized());
    }

    @Test
    void triggerForbiddenForCashier() throws Exception {
        mvc.perform(post("/sync/erp").with(jwt()
                        .authorities(new SimpleGrantedAuthority("ROLE_CASHIER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerTriggersSyncAndGetsSummary() throws Exception {
        mvc.perform(post("/sync/erp").with(jwt()
                        .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products").value(1))
                .andExpect(jsonPath("$.stock").value(1));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=SyncControllerTest`
Expected: FAIL — `/sync/erp` returns 404 (controller not defined).

- [ ] **Step 3: Write `ErpSyncCoordinator`** `src/main/java/com/company/pos/ErpSyncCoordinator.java`

```java
package com.company.pos;

import com.company.pos.inventory.api.InventorySync;
import com.company.pos.product.api.ProductSync;
import org.springframework.stereotype.Component;

@Component
class ErpSyncCoordinator {

    private final ProductSync productSync;
    private final InventorySync inventorySync;

    ErpSyncCoordinator(ProductSync productSync, InventorySync inventorySync) {
        this.productSync = productSync;
        this.inventorySync = inventorySync;
    }

    SyncSummary syncAll() {
        int products = productSync.sync();
        int stock = inventorySync.sync();
        return new SyncSummary(products, stock);
    }

    public record SyncSummary(int products, int stock) {
    }
}
```

- [ ] **Step 4: Write `ErpSyncScheduler`** `src/main/java/com/company/pos/ErpSyncScheduler.java`

```java
package com.company.pos;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "pos.sync.erp", name = "scheduled", havingValue = "true")
class ErpSyncScheduler {

    private final ErpSyncCoordinator coordinator;

    ErpSyncScheduler(ErpSyncCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Scheduled(fixedDelayString = "${pos.sync.erp.fixed-delay-ms:60000}",
            initialDelayString = "${pos.sync.erp.fixed-delay-ms:60000}")
    void runScheduledSync() {
        coordinator.syncAll();
    }
}
```

- [ ] **Step 5: Write `SyncController`** `src/main/java/com/company/pos/SyncController.java`

```java
package com.company.pos;

import com.company.pos.ErpSyncCoordinator.SyncSummary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SyncController {

    private final ErpSyncCoordinator coordinator;

    SyncController(ErpSyncCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping("/sync/erp")
    @PreAuthorize("hasRole('MANAGER')")
    SyncSummary trigger() {
        return coordinator.syncAll();
    }
}
```

- [ ] **Step 6: Add `@EnableScheduling` to `PosApplication`**

Edit `src/main/java/com/company/pos/PosApplication.java` — add the import and the annotation:

```java
package com.company.pos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PosApplication {

    public static void main(String[] args) {
        SpringApplication.run(PosApplication.class, args);
    }
}
```

- [ ] **Step 7: Add sync defaults to `application.yml`** (scheduling defaults OFF; env-overridable)

Add this block under the top-level `pos:` key (sibling of `auth:`):

```yaml
  sync:
    erp:
      scheduled: ${POS_SYNC_ERP_SCHEDULED:false}
      fixed-delay-ms: ${POS_SYNC_ERP_DELAY_MS:60000}
```

- [ ] **Step 8: Enable scheduled sync on `store-server`** — add to `src/main/resources/application-store-server.yml` (top-level, a new `pos:` document section):

```yaml
pos:
  sync:
    erp:
      scheduled: true
```

- [ ] **Step 9: Run the test to verify it passes**

Run: `./mvnw test -Dtest=SyncControllerTest`
Expected: PASS — unauthenticated → 401; cashier → 403; manager → 200 with `{products:1, stock:1}`. (The scheduler bean is absent under the `embedded` test profile because `pos.sync.erp.scheduled` is false, so no timer fires during the test.)

- [ ] **Step 10: Verify boundaries**

Run: `./mvnw test -Dtest=ModularityTests`
Expected: PASS — the coordinator/scheduler/controller live in the application root package and are not subject to module-dependency rules.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/company/pos/PosApplication.java \
        src/main/java/com/company/pos/ErpSyncCoordinator.java \
        src/main/java/com/company/pos/ErpSyncScheduler.java \
        src/main/java/com/company/pos/SyncController.java \
        src/main/resources/application.yml \
        src/main/resources/application-store-server.yml \
        src/test/java/com/company/pos/SyncControllerTest.java
git commit -m "feat: add ERP sync coordinator, scheduler, and manager-only trigger"
```

---

### Task 13: End-to-end — authenticate, sync, see catalogue + stock; docs; full verify

The capstone. All production code exists by Task 12; this task adds the end-to-end test that proves the phase goal through real HTTP + a real JWT, documents the new surface, and runs the whole build.

**Files:**
- Modify: `docs/run-modes.md` (add Phase 1 auth + sync surface)
- Test: `src/test/java/com/company/pos/ErpDownSyncEndToEndTest.java`

**Interfaces:**
- Consumes: every Phase 1 component end-to-end (auth login, JWT, RBAC, ERP sync, product/inventory read endpoints).
- Produces: no new production types — an integration test and documentation.

- [ ] **Step 1: Write the end-to-end test** `src/test/java/com/company/pos/ErpDownSyncEndToEndTest.java`

```java
package com.company.pos;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.util.Identifiers;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.api.ErpStockLevel;
import com.company.pos.integration.erp.FakeErpClient;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Transactional
class ErpDownSyncEndToEndTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    FakeErpClient fake;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void setUp() {
        fake.clear();
        fake.addProduct(new ErpProduct("COLA", "Cola Can", "BEV", "Beverages", "bcCOLA",
                "EA", new BigDecimal("4.50"), "SAR", 1, true));
        fake.addStockLevel(new ErpStockLevel("COLA", "MAIN", new BigDecimal("20"), 1));
        users.save(new User(Identifiers.newId(), "manager", "Store Manager",
                encoder.encode("pw"), Set.of(Role.MANAGER)));
    }

    @Test
    void authenticateThenSyncThenSeeCatalogueAndStock() throws Exception {
        // 1. Authenticate
        String body = mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"username\":\"manager\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bearer = "Bearer " + JsonPath.read(body, "$.token");

        // 2. Trigger ERP down-sync
        mvc.perform(post("/sync/erp").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products").value(1))
                .andExpect(jsonPath("$.stock").value(1));

        // 3. See the synced catalogue
        mvc.perform(get("/products/COLA").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Cola Can"))
                .andExpect(jsonPath("$.categoryName").value("Beverages"))
                .andExpect(jsonPath("$.unitPrice").value(4.50));

        // 4. See the synced stock
        mvc.perform(get("/inventory/COLA").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(20));
    }
}
```

- [ ] **Step 2: Run the end-to-end test**

Run: `./mvnw test -Dtest=ErpDownSyncEndToEndTest`
Expected: PASS — login returns a real JWT; the manager triggers sync; the catalogue and stock endpoints return the ERP-sourced data. (All production code already exists; this test validates the assembled flow. If it fails, fix the integration gap it reveals before continuing.)

- [ ] **Step 3: Document the Phase 1 surface** — append to `docs/run-modes.md`:

````markdown

## Phase 1 — Identity & catalogue (API surface)

Authentication is JWT bearer (HS256). Obtain a token, then send it as `Authorization: Bearer <token>`.

```
POST /auth/login        {"username","password"}      -> {"token"}
POST /auth/pin-login    {"cashierCode","pin"}        -> {"token"}
GET  /auth/me           (any authenticated)          -> {"username","roles"}
GET  /products[?q=]     (any authenticated)          -> [ProductView...]
GET  /products/{sku}    (any authenticated)          -> ProductView
GET  /inventory/{sku}   (any authenticated)          -> {"sku","quantityOnHand"}
POST /sync/erp          (ROLE_MANAGER)               -> {"products","stock"}  (manual ERP down-sync)
```

Config (env overridable):
- `POS_JWT_SECRET` (≥ 32 bytes; a dev default is baked in — override in any real deployment), `POS_JWT_TTL` (minutes).
- `POS_SYNC_ERP_SCHEDULED` — background ERP down-sync timer. Default **off**; enabled automatically on the `store-server` profile. `POS_SYNC_ERP_DELAY_MS` sets the interval.

> The ERP integration runs against an in-memory **fake** adapter in Phase 1. A concrete vendor adapter implements `com.company.pos.integration.api.ErpClient` later with no change to the sync engine.
````

- [ ] **Step 4: Run the full build exactly as CI will**

Run: `./mvnw -B verify`
Expected: PASS — `BUILD SUCCESS`; all Phase 0 + Phase 1 tests green (including the Testcontainers PostgreSQL test, which now applies migrations `V1`–`V5`), `ModularityTests.verify()` green for all modules, `target/pos.jar` produced. Ensure Docker is running.

- [ ] **Step 5: Commit**

```bash
git add docs/run-modes.md \
        src/test/java/com/company/pos/ErpDownSyncEndToEndTest.java
git commit -m "test: add ERP down-sync end-to-end flow and document Phase 1 API"
```

---

## Phase 1 Done — Definition of Complete

- `./mvnw -B verify` is green: all Phase 0 + Phase 1 tests pass, `ModularityTests.verify()` passes for `common`, `database`, `configuration`, `auth`, `product`, `inventory`, `integration`, and `target/pos.jar` builds.
- A user authenticates via `POST /auth/login` or `POST /auth/pin-login` and receives a JWT; RBAC is enforced (`/sync/erp` is `MANAGER`-only; `/auth/me` reflects roles).
- `POST /sync/erp` (or, on `store-server`, the background timer) pulls products + stock from the ERP (fake) via versioned delta down-sync, and `GET /products`, `GET /products/{sku}`, `GET /inventory/{sku}` return the synced data.
- The store-server PostgreSQL path migrates `V1`–`V5`; the embedded SQLite path creates the same schema via Hibernate.

**Deferred (Phase 2+ / backlog):** product variants, price lists, product images, multi-currency display formatting; password reset; full shift lifecycle (Phase 2 `shift` module — Phase 1 covers PIN/credential login only); inventory movement ledger and ERP **up**-sync (Phase 3 `sync` outbox); the concrete ERP vendor adapter (drops into `integration.api.ErpClient`); moving the root-package sync coordinator into a formal `sync` module in Phase 3. Carry forward the Phase 0 backlog items (e.g. `serialVersionUID`, byte-buddy test-warning suppression) as well.

## Self-Review

**1. Spec coverage (architecture Phase 1 = auth + product + inventory baseline + ERP down-sync):**
- auth login/RBAC/PIN/JWT → Tasks 1–5. product baseline + read → Tasks 6–7. inventory baseline + read → Task 8. integration ErpClient/fake/cursors → Task 9. ERP down-sync (products, prices, stock) → Tasks 10–12. Goal ("authenticate and show the live catalogue from ERP") → Task 13 end-to-end. Covered.
- Password reset and full shift lifecycle are explicitly deferred above (Phase 1 covers credential/PIN login, not a shift record — the `shift` module is Phase 2).

**2. Placeholder scan:** every step contains complete code or an exact command + expected result. No TBD/“handle errors”/“similar to”.

**3. Type consistency:** `ProductSync.sync()`/`InventorySync.sync()` return `int`; `ErpClient.fetchProductsSince(long)`/`fetchStockLevelsSince(long)`; `SyncCursorStore.get/set`; `ErpProduct`/`ErpStockLevel` field names match their consumers in Tasks 10–11; `ProductView`/`StockView` shapes match the controllers and the end-to-end assertions; `Role` enum and `ROLE_`-prefixed authorities are consistent across auth config, tests, and `@PreAuthorize`. `integration.api` is exposed as a named interface (Task 10) before `product`/`inventory` depend on `integration :: api`.
