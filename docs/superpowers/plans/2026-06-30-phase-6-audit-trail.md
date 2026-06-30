# Phase 6 — Audit Trail Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a tamper-evident, hash-chained audit trail (new Tier-3 `audit` module) that records sales, returns, manager discount overrides, logins, ERP price changes, and runtime setting changes, with an ADMIN-only query + chain-verify HTTP surface.

**Architecture:** A new `audit` module persists an append-only, per-store hash-chained `audit_record` log. Transactional facts arrive as Spring Modulith domain events (`@ApplicationModuleListener`, after-commit, outbox-tracked); non-transactional security events (login success/failure) arrive through a synchronous `AuditService.record(...)` facade that commits in its own transaction. Producers (`sales`, `product`, `configuration`) publish three new domain events; `auth` calls the facade.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Modulith (event registry/outbox + boundary enforcement), Spring Data JPA, Flyway (store-server) / Hibernate ddl-auto (embedded), Jackson (canonical payload JSON), JDK `MessageDigest` SHA-256.

## Global Constraints

- **JDK 21.** `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` before any Maven command.
- **Build with Maven** (`./mvnw`), never Gradle. Most tests are `@SpringBootTest @ActiveProfiles("embedded")` (in-memory SQLite, no Docker).
- **Money is `BigDecimal`** — never `double`.
- **Module boundaries are enforced.** Update the consuming module's `package-info.java` `allowedDependencies` when adding a cross-module dependency, and expose shared types only through a module's `api` (`@NamedInterface`). New cross-module events must implement `com.company.pos.common.events.DomainEvent` and live in the producing module's `api` package.
- **No module cycles.** `audit` may depend on `sales :: api`, `product :: api`, `configuration :: api`; therefore `sales`/`product`/`configuration` must NOT depend on `audit`. Only `auth` depends on `audit :: api`.
- **Flyway versions are globally sequential.** Next free is **V19**. Only `store-server` runs Flyway; register the new path in `application-store-server.yml`. `embedded` builds schema via Hibernate.
- **Re-run `./mvnw test -Dtest=ModularityTests`** after any `package-info.java` change.
- Domain entities are declared `public class` in their `domain` package (consistent with `inventory.domain.StockLevel`); cross-module access is blocked by Modulith boundaries, not Java visibility.

## Deviations from the approved spec (decided during planning)

1. **`SETTING_CHANGED` is event-driven, not a synchronous facade call.** A synchronous `configuration → audit` facade call plus `audit → configuration` (for `STORE_ID`) would be a forbidden module cycle. Instead `configuration` publishes a `SettingChanged` domain event from the new `PUT /config/{key}` controller and `audit` subscribes. A setting change has a committing transaction, so the event path is reliable here.
2. **`audit` also depends on `configuration :: api`** (for `STORE_ID` and the `SettingChanged` event), in addition to the spec's `sales :: api` and `product :: api`.
3. **`POST /audit/verify` verifies the whole chain** (no `from`/`to` range). Verifying a sub-range can't check linkage to records outside the range; full-chain verify is the honest MVP behavior.
4. **Sales/return audit rows record `terminalId` as the actor**, not the cashier/manager — `SaleCompleted`/`ReturnCompleted` do not carry the acting user, and Phase 6 deliberately does not edit those shared event contracts. The high-value actor identities (logins, setting changes, discount overrides) ARE captured precisely. Capturing the sale/return user is a documented follow-up.

---

### Task 1: HashChainer (pure hashing + linkage)

**Files:**
- Create: `src/main/java/com/company/pos/audit/application/HashChainer.java`
- Test: `src/test/java/com/company/pos/audit/application/HashChainerTest.java`

**Interfaces:**
- Produces: `HashChainer.chainHash(long seq, Instant occurredAt, String actor, String action, String entityRef, String payload, String prevHash) -> String` (64-char lowercase hex SHA-256). Package-private final class, static method.

- [ ] **Step 1: Write the failing test**

```java
package com.company.pos.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class HashChainerTest {

    private static final Instant TS = Instant.parse("2026-06-30T10:15:30Z");

    @Test
    void hashIsDeterministicForSameInput() {
        String a = HashChainer.chainHash(1, TS, "alice", "LOGIN_FAILED", "alice", "{}", "GENESIS");
        String b = HashChainer.chainHash(1, TS, "alice", "LOGIN_FAILED", "alice", "{}", "GENESIS");
        assertThat(a).isEqualTo(b).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void hashChangesWhenAnyFieldChanges() {
        String base = HashChainer.chainHash(1, TS, "alice", "LOGIN_FAILED", "alice", "{}", "GENESIS");
        assertThat(HashChainer.chainHash(2, TS, "alice", "LOGIN_FAILED", "alice", "{}", "GENESIS"))
                .isNotEqualTo(base);
        assertThat(HashChainer.chainHash(1, TS, "bob", "LOGIN_FAILED", "alice", "{}", "GENESIS"))
                .isNotEqualTo(base);
        assertThat(HashChainer.chainHash(1, TS, "alice", "LOGIN_FAILED", "alice", "{}", "PREV"))
                .isNotEqualTo(base);
    }

    @Test
    void linkageMeansNextHashDependsOnPrevHash() {
        String first = HashChainer.chainHash(1, TS, "a", "X", "e", "{}", "GENESIS");
        String secondLinked = HashChainer.chainHash(2, TS, "a", "X", "e", "{}", first);
        String secondTampered = HashChainer.chainHash(2, TS, "a", "X", "e", "{}", "GENESIS");
        assertThat(secondLinked).isNotEqualTo(secondTampered);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=HashChainerTest`
Expected: FAIL — `HashChainer` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
package com.company.pos.audit.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** Pure SHA-256 chain hashing for the audit trail. Each record's hash binds its own content to
 *  the previous record's hash, so any edit/deletion/reorder breaks the chain detectably. */
final class HashChainer {

    private HashChainer() {
    }

    static String chainHash(long seq, Instant occurredAt, String actor, String action,
            String entityRef, String payload, String prevHash) {
        String content = String.join("",
                Long.toString(seq),
                occurredAt.toString(),
                nullToEmpty(actor),
                nullToEmpty(action),
                nullToEmpty(entityRef),
                nullToEmpty(payload),
                nullToEmpty(prevHash));
        return sha256Hex(content);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=HashChainerTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/pos/audit/application/HashChainer.java src/test/java/com/company/pos/audit/application/HashChainerTest.java
git commit -m "feat(audit): pure SHA-256 hash chainer"
```

---

### Task 2: Audit module — api surface, persistence, append + verify core

**Files:**
- Create: `src/main/java/com/company/pos/audit/package-info.java`
- Create: `src/main/java/com/company/pos/audit/api/package-info.java`
- Create: `src/main/java/com/company/pos/audit/api/AuditAction.java`
- Create: `src/main/java/com/company/pos/audit/api/AuditService.java`
- Create: `src/main/java/com/company/pos/audit/api/AuditRecordView.java`
- Create: `src/main/java/com/company/pos/audit/api/AuditVerifyResult.java`
- Create: `src/main/java/com/company/pos/audit/domain/AuditRecord.java`
- Create: `src/main/java/com/company/pos/audit/domain/AuditChainHead.java`
- Create: `src/main/java/com/company/pos/audit/infrastructure/AuditRecordRepository.java`
- Create: `src/main/java/com/company/pos/audit/infrastructure/AuditChainHeadRepository.java`
- Create: `src/main/java/com/company/pos/audit/application/DefaultAuditService.java`
- Create: `src/main/resources/db/migration/audit/V19__audit_record.sql`
- Modify: `src/main/resources/application-store-server.yml` (append `,classpath:db/migration/audit` to `spring.flyway.locations`)
- Test: `src/test/java/com/company/pos/audit/AuditServiceIntegrationTest.java`
- Test: `src/test/java/com/company/pos/audit/infrastructure/AuditTamperDetectionTest.java`

**Interfaces:**
- Consumes: `HashChainer.chainHash(...)` (Task 1); `ConfigurationService.getString(SettingKey.STORE_ID)`; `Identifiers.newId()`.
- Produces:
  - `enum AuditAction { SALE_COMPLETED, RETURN_COMPLETED, DISCOUNT_OVERRIDE, LOGIN_SUCCEEDED, LOGIN_FAILED, PIN_LOGIN_SUCCEEDED, PIN_LOGIN_FAILED, PRICE_CHANGED, SETTING_CHANGED }`
  - `interface AuditService { void record(AuditAction action, String actor, String entityRef, java.util.Map<String,String> details); }`
  - `record AuditRecordView(java.util.UUID id, long seq, String storeId, java.time.Instant occurredAt, String actor, String action, String entityRef, String payload, String prevHash, String hash)`
  - `record AuditVerifyResult(boolean intact, long recordsChecked, Long firstBrokenSeq)`
  - `DefaultAuditService` exposes a package-private `void append(AuditAction action, String actor, String entityRef, Map<String,String> details)` (REQUIRED propagation) for in-module listeners, plus public `record(...)` (REQUIRES_NEW) and `AuditVerifyResult verify()` and `java.util.List<AuditRecordView> recent(int limit)`.

- [ ] **Step 1: Create the module + api declarations, enum, and api types**

`src/main/java/com/company/pos/audit/package-info.java`:
```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "sales :: api", "product :: api",
                "configuration :: api" })
package com.company.pos.audit;
```

`src/main/java/com/company/pos/audit/api/package-info.java`:
```java
@org.springframework.modulith.NamedInterface("api")
package com.company.pos.audit.api;
```

`src/main/java/com/company/pos/audit/api/AuditAction.java`:
```java
package com.company.pos.audit.api;

public enum AuditAction {
    SALE_COMPLETED,
    RETURN_COMPLETED,
    DISCOUNT_OVERRIDE,
    LOGIN_SUCCEEDED,
    LOGIN_FAILED,
    PIN_LOGIN_SUCCEEDED,
    PIN_LOGIN_FAILED,
    PRICE_CHANGED,
    SETTING_CHANGED
}
```

`src/main/java/com/company/pos/audit/api/AuditService.java`:
```java
package com.company.pos.audit.api;

import java.util.Map;

/** Records a non-transactional security action into the tamper-evident trail in its own
 *  committed transaction. Used by callers (e.g. {@code auth}) that have no business transaction
 *  to ride — a failed login still leaves a record even though the caller then throws. */
public interface AuditService {

    void record(AuditAction action, String actor, String entityRef, Map<String, String> details);
}
```

`src/main/java/com/company/pos/audit/api/AuditRecordView.java`:
```java
package com.company.pos.audit.api;

import java.time.Instant;
import java.util.UUID;

public record AuditRecordView(UUID id, long seq, String storeId, Instant occurredAt, String actor,
        String action, String entityRef, String payload, String prevHash, String hash) {
}
```

`src/main/java/com/company/pos/audit/api/AuditVerifyResult.java`:
```java
package com.company.pos.audit.api;

public record AuditVerifyResult(boolean intact, long recordsChecked, Long firstBrokenSeq) {
}
```

- [ ] **Step 2: Create the domain entities**

`src/main/java/com/company/pos/audit/domain/AuditRecord.java`:
```java
package com.company.pos.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_record")
public class AuditRecord {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "seq", nullable = false)
    private long seq;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private String action;

    @Column(name = "entity_ref")
    private String entityRef;

    @Column(columnDefinition = "text")
    private String payload;

    @Column(name = "prev_hash", length = 64, nullable = false)
    private String prevHash;

    @Column(length = 64, nullable = false)
    private String hash;

    protected AuditRecord() {
    }

    public AuditRecord(UUID id, long seq, String storeId, Instant occurredAt, String actor,
            String action, String entityRef, String payload, String prevHash, String hash) {
        this.id = id.toString();
        this.seq = seq;
        this.storeId = storeId;
        this.occurredAt = occurredAt;
        this.actor = actor;
        this.action = action;
        this.entityRef = entityRef;
        this.payload = payload;
        this.prevHash = prevHash;
        this.hash = hash;
    }

    public UUID getId() {
        return UUID.fromString(id);
    }

    public long getSeq() {
        return seq;
    }

    public String getStoreId() {
        return storeId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getEntityRef() {
        return entityRef;
    }

    public String getPayload() {
        return payload;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public String getHash() {
        return hash;
    }
}
```

`src/main/java/com/company/pos/audit/domain/AuditChainHead.java`:
```java
package com.company.pos.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The tip of a store's audit chain. A single row per store, locked while appending so concurrent
 *  terminals serialize and never claim the same prev_hash. */
@Entity
@Table(name = "audit_chain_head")
public class AuditChainHead {

    @Id
    @Column(name = "store_id")
    private String storeId;

    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    @Column(name = "last_hash", length = 64, nullable = false)
    private String lastHash;

    protected AuditChainHead() {
    }

    public AuditChainHead(String storeId, long lastSeq, String lastHash) {
        this.storeId = storeId;
        this.lastSeq = lastSeq;
        this.lastHash = lastHash;
    }

    public String getStoreId() {
        return storeId;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public String getLastHash() {
        return lastHash;
    }

    public void advance(long seq, String hash) {
        this.lastSeq = seq;
        this.lastHash = hash;
    }
}
```

- [ ] **Step 3: Create the repositories**

`src/main/java/com/company/pos/audit/infrastructure/AuditRecordRepository.java`:
```java
package com.company.pos.audit.infrastructure;

import com.company.pos.audit.domain.AuditRecord;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditRecordRepository extends JpaRepository<AuditRecord, String> {

    List<AuditRecord> findByStoreIdOrderBySeqAsc(String storeId);

    @Query("""
            select a from AuditRecord a
            where (:actor is null or a.actor = :actor)
              and (:action is null or a.action = :action)
              and a.occurredAt between :from and :to
            order by a.seq desc
            """)
    List<AuditRecord> search(@Param("actor") String actor, @Param("action") String action,
            @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);
}
```

`src/main/java/com/company/pos/audit/infrastructure/AuditChainHeadRepository.java`:
```java
package com.company.pos.audit.infrastructure;

import com.company.pos.audit.domain.AuditChainHead;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AuditChainHeadRepository extends JpaRepository<AuditChainHead, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AuditChainHead> findByStoreId(String storeId);
}
```

- [ ] **Step 4: Create the migration and register the Flyway path**

`src/main/resources/db/migration/audit/V19__audit_record.sql`:
```sql
CREATE TABLE audit_record (
    id           VARCHAR(36)   NOT NULL PRIMARY KEY,
    seq          BIGINT        NOT NULL,
    store_id     VARCHAR(64)   NOT NULL,
    occurred_at  TIMESTAMP     NOT NULL,
    actor        VARCHAR(128)  NOT NULL,
    action       VARCHAR(32)   NOT NULL,
    entity_ref   VARCHAR(128),
    payload      TEXT,
    prev_hash    CHAR(64)      NOT NULL,
    hash         CHAR(64)      NOT NULL
);

CREATE UNIQUE INDEX ux_audit_record_store_seq ON audit_record (store_id, seq);
CREATE INDEX ix_audit_record_occurred_at ON audit_record (occurred_at);
CREATE INDEX ix_audit_record_action ON audit_record (action);

CREATE TABLE audit_chain_head (
    store_id   VARCHAR(64)  NOT NULL PRIMARY KEY,
    last_seq   BIGINT       NOT NULL,
    last_hash  CHAR(64)     NOT NULL
);
```

In `src/main/resources/application-store-server.yml`, append `,classpath:db/migration/audit` to the end of the `spring.flyway.locations` line (it currently ends with `...,classpath:db/migration/events`).

- [ ] **Step 5: Create DefaultAuditService**

`src/main/java/com/company/pos/audit/application/DefaultAuditService.java`:
```java
package com.company.pos.audit.application;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.audit.api.AuditRecordView;
import com.company.pos.audit.api.AuditService;
import com.company.pos.audit.api.AuditVerifyResult;
import com.company.pos.audit.domain.AuditChainHead;
import com.company.pos.audit.domain.AuditRecord;
import com.company.pos.audit.infrastructure.AuditChainHeadRepository;
import com.company.pos.audit.infrastructure.AuditRecordRepository;
import com.company.pos.common.util.Identifiers;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultAuditService implements AuditService {

    private static final String GENESIS = "GENESIS";

    private final AuditRecordRepository records;
    private final AuditChainHeadRepository heads;
    private final ConfigurationService config;
    private final ObjectMapper objectMapper;

    DefaultAuditService(AuditRecordRepository records, AuditChainHeadRepository heads,
            ConfigurationService config, ObjectMapper objectMapper) {
        this.records = records;
        this.heads = heads;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /** Facade for non-transactional callers (auth): commit independently so a caller that then
     *  throws (e.g. a failed login) still leaves the record. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, String actor, String entityRef, Map<String, String> details) {
        doAppend(action, actor, entityRef, details);
    }

    /** Entry point for in-module event listeners: joins the listener's after-commit transaction so
     *  the insert and the listener's outbox completion are atomic. */
    @Transactional
    void append(AuditAction action, String actor, String entityRef, Map<String, String> details) {
        doAppend(action, actor, entityRef, details);
    }

    @Transactional(readOnly = true)
    public AuditVerifyResult verify() {
        String storeId = config.getString(SettingKey.STORE_ID);
        List<AuditRecord> chain = records.findByStoreIdOrderBySeqAsc(storeId);
        String prev = GENESIS;
        long checked = 0;
        for (AuditRecord r : chain) {
            String expected = HashChainer.chainHash(r.getSeq(), r.getOccurredAt(), r.getActor(),
                    r.getAction(), r.getEntityRef(), r.getPayload(), prev);
            if (!expected.equals(r.getHash()) || !prev.equals(r.getPrevHash())) {
                return new AuditVerifyResult(false, checked, r.getSeq());
            }
            prev = r.getHash();
            checked++;
        }
        return new AuditVerifyResult(true, checked, null);
    }

    @Transactional(readOnly = true)
    public List<AuditRecordView> recent(int limit) {
        String storeId = config.getString(SettingKey.STORE_ID);
        List<AuditRecord> chain = records.findByStoreIdOrderBySeqAsc(storeId);
        int from = Math.max(0, chain.size() - limit);
        return chain.subList(from, chain.size()).stream()
                .sorted((a, b) -> Long.compare(b.getSeq(), a.getSeq()))
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AuditRecordView> search(String actor, String action, Instant from, Instant to,
            int page, int size) {
        return records.search(actor, action, from, to, PageRequest.of(page, size)).stream()
                .map(this::toView)
                .toList();
    }

    private void doAppend(AuditAction action, String actor, String entityRef,
            Map<String, String> details) {
        String storeId = config.getString(SettingKey.STORE_ID);
        AuditChainHead head = heads.findByStoreId(storeId)
                .orElseGet(() -> new AuditChainHead(storeId, 0L, GENESIS));
        long seq = head.getLastSeq() + 1;
        String prevHash = head.getLastHash();
        Instant now = Instant.now();
        String payload = canonicalJson(details);
        String hash = HashChainer.chainHash(seq, now, actor, action.name(), entityRef, payload, prevHash);
        records.save(new AuditRecord(Identifiers.newId(), seq, storeId, now, actor, action.name(),
                entityRef, payload, prevHash, hash));
        head.advance(seq, hash);
        heads.save(head);
    }

    private String canonicalJson(Map<String, String> details) {
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(details == null ? Map.of() : details));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize audit payload", e);
        }
    }

    AuditRecordView toView(AuditRecord r) {
        return new AuditRecordView(r.getId(), r.getSeq(), r.getStoreId(), r.getOccurredAt(),
                r.getActor(), r.getAction(), r.getEntityRef(), r.getPayload(), r.getPrevHash(),
                r.getHash());
    }
}
```

- [ ] **Step 6: Write the failing integration test**

`src/test/java/com/company/pos/audit/AuditServiceIntegrationTest.java`:
```java
package com.company.pos.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.audit.api.AuditRecordView;
import com.company.pos.audit.api.AuditService;
import com.company.pos.audit.api.AuditVerifyResult;
import com.company.pos.audit.application.DefaultAuditService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class AuditServiceIntegrationTest {

    @Autowired
    AuditService auditService;

    @Autowired
    DefaultAuditService defaultAuditService;

    @Test
    void recordsChainAndVerifyIntact() {
        auditService.record(AuditAction.LOGIN_FAILED, "alice", "alice", Map.of("reason", "bad"));
        auditService.record(AuditAction.LOGIN_SUCCEEDED, "bob", "bob", Map.of());

        List<AuditRecordView> recent = defaultAuditService.recent(10);
        assertThat(recent).extracting(AuditRecordView::action)
                .contains("LOGIN_FAILED", "LOGIN_SUCCEEDED");
        // newest first, chained
        AuditRecordView newest = recent.get(0);
        AuditRecordView prior = recent.get(1);
        assertThat(newest.seq()).isEqualTo(prior.seq() + 1);
        assertThat(newest.prevHash()).isEqualTo(prior.hash());

        AuditVerifyResult result = defaultAuditService.verify();
        assertThat(result.intact()).isTrue();
        assertThat(result.recordsChecked()).isGreaterThanOrEqualTo(2);
    }
}
```

`src/test/java/com/company/pos/audit/infrastructure/AuditTamperDetectionTest.java`:
```java
package com.company.pos.audit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.audit.api.AuditService;
import com.company.pos.audit.api.AuditVerifyResult;
import com.company.pos.audit.application.DefaultAuditService;
import com.company.pos.audit.domain.AuditRecord;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
class AuditTamperDetectionTest {

    @Autowired
    AuditService auditService;

    @Autowired
    DefaultAuditService defaultAuditService;

    @Autowired
    AuditRecordRepository records;

    @Test
    @Transactional
    void verifyDetectsAMutatedPayload() {
        auditService.record(AuditAction.SETTING_CHANGED, "admin", "tax.rate",
                Map.of("old", "0.15", "new", "0.16"));
        auditService.record(AuditAction.SETTING_CHANGED, "admin", "store.name",
                Map.of("old", "A", "new", "B"));

        List<AuditRecord> all = records.findAll();
        AuditRecord victim = all.get(0);
        // Tamper: overwrite the payload directly, leaving the stored hash stale.
        records.save(new AuditRecord(victim.getId(), victim.getSeq(), victim.getStoreId(),
                victim.getOccurredAt(), victim.getActor(), victim.getAction(), victim.getEntityRef(),
                "{\"old\":\"0.15\",\"new\":\"0.99\"}", victim.getPrevHash(), victim.getHash()));

        AuditVerifyResult result = defaultAuditService.verify();
        assertThat(result.intact()).isFalse();
        assertThat(result.firstBrokenSeq()).isNotNull();
    }
}
```

- [ ] **Step 7: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=AuditServiceIntegrationTest,AuditTamperDetectionTest`
Expected: FAIL until Steps 1–5 compile and wire (entities/repos/service). If they already compile, the tests should drive any remaining gaps.

- [ ] **Step 8: Run the tests to verify they pass, and verify boundaries**

Run: `./mvnw test -Dtest=AuditServiceIntegrationTest,AuditTamperDetectionTest && ./mvnw test -Dtest=ModularityTests`
Expected: PASS. `ModularityTests` confirms the new `audit` module's declared dependencies are legal and acyclic.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/company/pos/audit src/main/resources/db/migration/audit src/main/resources/application-store-server.yml src/test/java/com/company/pos/audit
git commit -m "feat(audit): hash-chained append-only store with verify + ADMIN read service"
```

---

### Task 3: `DiscountOverridden` event + override detection in sales

**Files:**
- Create: `src/main/java/com/company/pos/sales/api/DiscountOverridden.java`
- Create: `src/main/java/com/company/pos/sales/application/DiscountOverride.java`
- Modify: `src/main/java/com/company/pos/sales/application/DiscountResult.java`
- Modify: `src/main/java/com/company/pos/sales/application/DiscountCalculator.java`
- Modify: `src/main/java/com/company/pos/sales/application/DefaultSalesService.java:111-115` (capture overrides) and `:196-197` (publish)
- Test: `src/test/java/com/company/pos/sales/application/DiscountCalculatorTest.java` (extend existing)

**Interfaces:**
- Consumes: existing `DiscountCalculator.apply(...)`, `DefaultSalesService` checkout flow, `DomainEvents.publish(...)`.
- Produces:
  - `record DiscountOverridden(java.util.UUID saleId, String actor, String sku, java.math.BigDecimal discountAmount, String discountType, String reasonCode) implements DomainEvent` (`sku` is `null` for a transaction-level discount).
  - `record DiscountOverride(String sku, java.math.BigDecimal amount, com.company.pos.sales.api.DiscountType type, String reasonCode)` (package-private, `sales.application`).
  - `DiscountResult` gains a `java.util.List<DiscountOverride> overrides` component (last position).

- [ ] **Step 1: Write the failing test (extend DiscountCalculatorTest)**

Add these tests to the existing `DiscountCalculatorTest` (construct the calculator and inputs exactly as the existing tests in that file do; reuse their `PricedLine`/`DiscountInput` builders):

```java
    @Test
    void managerOverCapDiscountIsReportedAsAnOverride() {
        // base 100.00; cashier caps: 10% and 20.00. A 50% manager discount exceeds both.
        java.util.List<com.company.pos.pricing.api.PricedLine> priced = java.util.List.of(
                new com.company.pos.pricing.api.PricedLine("SKU1", "Item", new java.math.BigDecimal("1"),
                        new java.math.BigDecimal("100.00"), new java.math.BigDecimal("100.00"), "SAR"));
        DiscountResult result = new DiscountCalculator().apply(priced,
                java.util.Map.of("SKU1", new com.company.pos.sales.api.DiscountInput(
                        com.company.pos.sales.api.DiscountType.PERCENT, new java.math.BigDecimal("50"), "MANAGER_COMP")),
                null, true, new java.math.BigDecimal("10"), new java.math.BigDecimal("20.00"),
                java.util.Set.of("MANAGER_COMP"));
        assertThat(result.overrides()).hasSize(1);
        assertThat(result.overrides().get(0).sku()).isEqualTo("SKU1");
        assertThat(result.overrides().get(0).amount()).isEqualByComparingTo("50.00");
    }

    @Test
    void cashierWithinCapProducesNoOverride() {
        java.util.List<com.company.pos.pricing.api.PricedLine> priced = java.util.List.of(
                new com.company.pos.pricing.api.PricedLine("SKU1", "Item", new java.math.BigDecimal("1"),
                        new java.math.BigDecimal("100.00"), new java.math.BigDecimal("100.00"), "SAR"));
        DiscountResult result = new DiscountCalculator().apply(priced,
                java.util.Map.of("SKU1", new com.company.pos.sales.api.DiscountInput(
                        com.company.pos.sales.api.DiscountType.PERCENT, new java.math.BigDecimal("5"), "LOYALTY")),
                null, false, new java.math.BigDecimal("10"), new java.math.BigDecimal("20.00"),
                java.util.Set.of("LOYALTY"));
        assertThat(result.overrides()).isEmpty();
    }
```

> Note: confirm the `PricedLine` constructor argument order against `pricing/api/PricedLine.java` before running; adjust the literal args if the record's components differ.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=DiscountCalculatorTest`
Expected: FAIL — `DiscountResult.overrides()` does not exist (compilation error).

- [ ] **Step 3: Add the DiscountOverride record and extend DiscountResult**

`src/main/java/com/company/pos/sales/application/DiscountOverride.java`:
```java
package com.company.pos.sales.application;

import com.company.pos.sales.api.DiscountType;
import java.math.BigDecimal;

/** A discount a manager applied that exceeds the cashier cap. {@code sku} is null for a
 *  transaction-level discount. Surfaced so the orchestrator can publish a DiscountOverridden fact. */
record DiscountOverride(String sku, BigDecimal amount, DiscountType type, String reasonCode) {
}
```

Modify `DiscountResult.java` to add the `overrides` component:
```java
package com.company.pos.sales.application;

import com.company.pos.sales.api.DiscountType;
import java.math.BigDecimal;
import java.util.List;

/** The outcome of applying manual discounts: per-line results plus the transaction-level
 *  discount summary, the grand discount total, and any manager cap overrides. */
record DiscountResult(List<DiscountedLine> lines, BigDecimal txnDiscountAmount,
        DiscountType txnDiscountType, String txnDiscountReason, BigDecimal discountTotal,
        List<DiscountOverride> overrides) {
}
```

- [ ] **Step 4: Detect overrides in DiscountCalculator**

In `DiscountCalculator.java`:

1. Add a field-free helper that tests the cap without throwing, and refactor `enforceCap` to use it. Add:
```java
    private boolean exceedsCashierCap(BigDecimal d, BigDecimal base, BigDecimal maxPercent,
            BigDecimal maxAmount) {
        if (d.compareTo(maxAmount) > 0) {
            return true;
        }
        if (base.signum() > 0) {
            BigDecimal effectivePercent = d.multiply(HUNDRED).divide(base, 2, RoundingMode.HALF_UP);
            return effectivePercent.compareTo(maxPercent) > 0;
        }
        return false;
    }
```
and replace the body of `enforceCap` with:
```java
    private void enforceCap(BigDecimal d, BigDecimal base, boolean callerIsManager,
            BigDecimal maxPercent, BigDecimal maxAmount) {
        if (callerIsManager) {
            return;
        }
        if (exceedsCashierCap(d, base, maxPercent, maxAmount)) {
            throw DomainException.validation("Discount exceeds cashier limit; manager approval required");
        }
    }
```

2. Declare `List<DiscountOverride> overrides = new ArrayList<>();` near the top of `apply(...)`.

3. In the line-discount loop, after `enforceCap(d, g, ...)` (inside the `if (in != null)` block), add:
```java
                if (callerIsManager && exceedsCashierCap(d, g, cashierMaxPercent, cashierMaxAmount)) {
                    overrides.add(new DiscountOverride(p.sku(), d, in.type(), in.reasonCode()));
                }
```

4. In the transaction-discount block, after `enforceCap(txnAmt, cartBase, ...)`, add:
```java
            if (callerIsManager && exceedsCashierCap(txnAmt, cartBase, cashierMaxPercent, cashierMaxAmount)) {
                overrides.add(new DiscountOverride(null, txnAmt, txnDiscount.type(), txnDiscount.reasonCode()));
            }
```

5. Change the final return to include `overrides`:
```java
        return new DiscountResult(lines, txnAmt, txnType, txnReason, discountTotal, overrides);
```

- [ ] **Step 5: Create the DiscountOverridden event and publish it**

`src/main/java/com/company/pos/sales/api/DiscountOverridden.java`:
```java
package com.company.pos.sales.api;

import com.company.pos.common.events.DomainEvent;
import java.math.BigDecimal;
import java.util.UUID;

/** A manager applied a discount exceeding the cashier cap. {@code sku} is null for a
 *  transaction-level discount. */
public record DiscountOverridden(UUID saleId, String actor, String sku, BigDecimal discountAmount,
        String discountType, String reasonCode) implements DomainEvent {
}
```

In `DefaultSalesService.java`, immediately after `events.publish(new SaleCompleted(...))` (currently around line 196-197), add:
```java
        for (DiscountOverride o : disc.overrides()) {
            events.publish(new com.company.pos.sales.api.DiscountOverridden(saleId, cashierUsername,
                    o.sku(), o.amount(), o.type() == null ? null : o.type().name(), o.reasonCode()));
        }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./mvnw test -Dtest=DiscountCalculatorTest`
Expected: PASS (existing tests + 2 new).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/pos/sales/api/DiscountOverridden.java src/main/java/com/company/pos/sales/application/DiscountOverride.java src/main/java/com/company/pos/sales/application/DiscountResult.java src/main/java/com/company/pos/sales/application/DiscountCalculator.java src/main/java/com/company/pos/sales/application/DefaultSalesService.java src/test/java/com/company/pos/sales/application/DiscountCalculatorTest.java
git commit -m "feat(sales): emit DiscountOverridden when a manager exceeds the cashier discount cap"
```

---

### Task 4: `ProductPriceChanged` event on ERP down-sync

**Files:**
- Create: `src/main/java/com/company/pos/product/api/ProductPriceChanged.java`
- Modify: `src/main/java/com/company/pos/product/application/ProductErpSyncService.java`
- Test: `src/test/java/com/company/pos/product/ProductPriceChangedTest.java`

**Interfaces:**
- Consumes: existing `ProductErpSyncService.sync()`, `ErpClient`, `Product.getUnitPrice()`, `DomainEvents.publish(...)`.
- Produces: `record ProductPriceChanged(String sku, java.math.BigDecimal oldPrice, java.math.BigDecimal newPrice, long erpVersion) implements DomainEvent`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/company/pos/product/ProductPriceChangedTest.java`:
```java
package com.company.pos.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.product.api.ProductPriceChanged;
import com.company.pos.product.api.ProductSync;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.modulith.test.AssertablePublishedEvents;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class ProductPriceChangedTest {

    @Autowired
    ProductSync productSync;

    // NOTE: use the project's existing FakeErpClient seam to stage products. Mirror the staging
    // approach already used by ProductErpSyncServiceTest (com.company.pos.product) — autowire the
    // fake, push a product at version 1, sync, then push the same SKU at version 2 with a new price.
    @Test
    void priceChangeOnExistingSkuPublishesEvent(@Autowired AssertablePublishedEvents events) {
        // ARRANGE: stage SKU "P1" @ 5.00 (v1), sync; then re-stage "P1" @ 7.00 (v2).
        // (Staging calls intentionally omitted here — copy them from ProductErpSyncServiceTest.)
        // ACT
        productSync.sync();
        // ASSERT
        events.assertThat()
                .contains(ProductPriceChanged.class)
                .matching(ProductPriceChanged::sku, "P1")
                .matching(ProductPriceChanged::newPrice, new java.math.BigDecimal("7.00"));
    }
}
```

> The agent MUST first open `src/test/java/com/company/pos/product/ProductErpSyncServiceTest.java` and copy its exact `FakeErpClient` staging calls into the ARRANGE block (push version 1, `sync()`, push version 2 with a changed price). Also assert a brand-new SKU does NOT publish the event and an unchanged price does NOT publish it — add two more cases mirroring the staging.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ProductPriceChangedTest`
Expected: FAIL — `ProductPriceChanged` does not exist / no event published.

- [ ] **Step 3: Create the event**

`src/main/java/com/company/pos/product/api/ProductPriceChanged.java`:
```java
package com.company.pos.product.api;

import com.company.pos.common.events.DomainEvent;
import java.math.BigDecimal;

public record ProductPriceChanged(String sku, BigDecimal oldPrice, BigDecimal newPrice,
        long erpVersion) implements DomainEvent {
}
```

- [ ] **Step 4: Emit the event in ProductErpSyncService**

In `ProductErpSyncService.java`:

1. Add a `DomainEvents` dependency. Add the import `import com.company.pos.common.events.DomainEvents;` and `import com.company.pos.product.api.ProductPriceChanged;`, add the field and constructor parameter:
```java
    private final DomainEvents events;
```
Update the constructor signature to accept `DomainEvents events` and assign `this.events = events;`.

2. In the upsert loop, capture the old price and compare. Replace the block from `Product p = existing != null ...` through `products.save(p);` with:
```java
            BigDecimal oldPrice = existing != null ? existing.getUnitPrice() : null;
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
            if (oldPrice != null && oldPrice.compareTo(e.unitPrice()) != 0) {
                events.publish(new ProductPriceChanged(e.sku(), oldPrice, e.unitPrice(), e.version()));
            }
```
Add `import java.math.BigDecimal;` if not already present.

> `product`'s `package-info.java` already allows `common`, so `DomainEvents` needs no boundary change.

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=ProductPriceChangedTest,ProductErpSyncServiceTest`
Expected: PASS (new test + the existing sync test still green).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/product/api/ProductPriceChanged.java src/main/java/com/company/pos/product/application/ProductErpSyncService.java src/test/java/com/company/pos/product/ProductPriceChangedTest.java
git commit -m "feat(product): publish ProductPriceChanged when ERP sync changes an existing price"
```

---

### Task 5: `SettingChanged` event + ADMIN `PUT /config/{key}` endpoint

**Files:**
- Create: `src/main/java/com/company/pos/configuration/api/SettingChanged.java`
- Create: `src/main/java/com/company/pos/configuration/web/ConfigurationController.java`
- Test: `src/test/java/com/company/pos/configuration/ConfigurationControllerTest.java`

**Interfaces:**
- Consumes: `ConfigurationService.getString/put`, `SettingKey.valueOf(...)`, `DomainEvents.publish(...)`, JWT method security (`@PreAuthorize`).
- Produces: `record SettingChanged(String key, String oldValue, String newValue, String actor) implements DomainEvent`; HTTP `PUT /config/{key}` (ADMIN-only) accepting `{ "value": "..." }`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/company/pos/configuration/ConfigurationControllerTest.java`:
```java
package com.company.pos.configuration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class ConfigurationControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ConfigurationService config;

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanUpdateASetting() throws Exception {
        mvc.perform(put("/config/STORE_NAME")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"Downtown Branch\"}"))
                .andExpect(status().isNoContent());
        assertThat(config.getString(SettingKey.STORE_NAME)).isEqualTo("Downtown Branch");
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void managerCannotUpdateASetting() throws Exception {
        mvc.perform(put("/config/STORE_NAME")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"Nope\"}"))
                .andExpect(status().isForbidden());
    }
}
```

> Confirm `spring-security-test` is already a test dependency (the auth web tests use `@WithMockUser`); if a similar MockMvc test exists under `auth`, mirror its setup.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ConfigurationControllerTest`
Expected: FAIL — no `/config/{key}` mapping (404/405).

- [ ] **Step 3: Create the SettingChanged event**

`src/main/java/com/company/pos/configuration/api/SettingChanged.java`:
```java
package com.company.pos.configuration.api;

import com.company.pos.common.events.DomainEvent;

/** A runtime setting was changed through the admin endpoint (not bootstrap/env seeding). */
public record SettingChanged(String key, String oldValue, String newValue, String actor)
        implements DomainEvent {
}
```

- [ ] **Step 4: Create the controller**

`src/main/java/com/company/pos/configuration/web/ConfigurationController.java`:
```java
package com.company.pos.configuration.web;

import com.company.pos.common.events.DomainEvents;
import com.company.pos.common.exception.DomainException;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingChanged;
import com.company.pos.configuration.api.SettingKey;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ConfigurationController {

    private final ConfigurationService config;
    private final DomainEvents events;

    ConfigurationController(ConfigurationService config, DomainEvents events) {
        this.config = config;
        this.events = events;
    }

    @PutMapping("/config/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void update(@PathVariable String key, @RequestBody UpdateRequest request, Principal principal) {
        SettingKey settingKey = resolve(key);
        String oldValue = config.getString(settingKey);
        config.put(settingKey, request.value());
        events.publish(new SettingChanged(settingKey.key(), oldValue, request.value(),
                principal.getName()));
    }

    private SettingKey resolve(String key) {
        try {
            return SettingKey.valueOf(key);
        } catch (IllegalArgumentException ex) {
            throw DomainException.validation("Unknown setting key " + key);
        }
    }

    public record UpdateRequest(String value) {
    }
}
```

> `configuration`'s `package-info.java` already allows `common` (for `DomainEvents` and `DomainException`); no boundary change is needed. `@EnableMethodSecurity` is already on globally (auth `SecurityConfig`), so `@PreAuthorize` is active.

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=ConfigurationControllerTest`
Expected: PASS (admin 204 + value changed; manager 403).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/configuration/api/SettingChanged.java src/main/java/com/company/pos/configuration/web/ConfigurationController.java src/test/java/com/company/pos/configuration/ConfigurationControllerTest.java
git commit -m "feat(configuration): ADMIN PUT /config/{key} publishing SettingChanged"
```

---

### Task 6: Audit event listeners (sales, returns, discount, price, setting)

**Files:**
- Create: `src/main/java/com/company/pos/audit/application/SaleCompletedAuditListener.java`
- Create: `src/main/java/com/company/pos/audit/application/ReturnCompletedAuditListener.java`
- Create: `src/main/java/com/company/pos/audit/application/DiscountOverriddenAuditListener.java`
- Create: `src/main/java/com/company/pos/audit/application/ProductPriceChangedAuditListener.java`
- Create: `src/main/java/com/company/pos/audit/application/SettingChangedAuditListener.java`
- Test: `src/test/java/com/company/pos/audit/AuditEventListenersTest.java`

**Interfaces:**
- Consumes: `DefaultAuditService.append(AuditAction, String, String, Map<String,String>)` (Task 2); events `SaleCompleted`, `ReturnCompleted`, `DiscountOverridden` (`sales :: api`), `ProductPriceChanged` (`product :: api`), `SettingChanged` (`configuration :: api`).
- Produces: audit rows with the actions defined in `AuditAction`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/company/pos/audit/AuditEventListenersTest.java`:
```java
package com.company.pos.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.audit.api.AuditRecordView;
import com.company.pos.audit.application.DefaultAuditService;
import com.company.pos.common.events.DomainEvents;
import com.company.pos.configuration.api.SettingChanged;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class AuditEventListenersTest {

    @Autowired
    DomainEvents events;

    @Autowired
    DefaultAuditService audit;

    @Test
    void settingChangedEventIsAudited() {
        events.publish(new SettingChanged("tax.rate", "0.15", "0.16", "admin"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<AuditRecordView> recent = audit.recent(20);
            assertThat(recent).anySatisfy(r -> {
                assertThat(r.action()).isEqualTo("SETTING_CHANGED");
                assertThat(r.entityRef()).isEqualTo("tax.rate");
                assertThat(r.actor()).isEqualTo("admin");
            });
        });
        assertThat(audit.verify().intact()).isTrue();
    }
}
```

> This test drives the `SettingChanged` listener directly via the event publisher (no HTTP needed). The other four listeners are covered end-to-end in Task 8's capstone; this task's listener wiring is symmetric, so one representative async test plus a green build is sufficient here. `Awaitility` is already used by the project's after-commit tests (see sync/notification tests) — reuse the same import.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AuditEventListenersTest`
Expected: FAIL — no `SETTING_CHANGED` record appears (no listener yet).

- [ ] **Step 3: Implement the five listeners**

`SettingChangedAuditListener.java`:
```java
package com.company.pos.audit.application;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.configuration.api.SettingChanged;
import java.util.Map;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class SettingChangedAuditListener {

    private final DefaultAuditService audit;

    SettingChangedAuditListener(DefaultAuditService audit) {
        this.audit = audit;
    }

    @ApplicationModuleListener
    void on(SettingChanged event) {
        audit.append(AuditAction.SETTING_CHANGED, event.actor(), event.key(),
                Map.of("old", n(event.oldValue()), "new", n(event.newValue())));
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }
}
```

`SaleCompletedAuditListener.java`:
```java
package com.company.pos.audit.application;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.sales.api.SaleCompleted;
import java.util.Map;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class SaleCompletedAuditListener {

    private final DefaultAuditService audit;

    SaleCompletedAuditListener(DefaultAuditService audit) {
        this.audit = audit;
    }

    @ApplicationModuleListener
    void on(SaleCompleted event) {
        audit.append(AuditAction.SALE_COMPLETED, event.terminalId(), event.receiptNumber(),
                Map.of("grandTotal", event.grandTotal().toPlainString(),
                        "currency", event.currencyCode(),
                        "terminalId", event.terminalId()));
    }
}
```

`ReturnCompletedAuditListener.java`:
```java
package com.company.pos.audit.application;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.sales.api.ReturnCompleted;
import java.util.Map;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class ReturnCompletedAuditListener {

    private final DefaultAuditService audit;

    ReturnCompletedAuditListener(DefaultAuditService audit) {
        this.audit = audit;
    }

    @ApplicationModuleListener
    void on(ReturnCompleted event) {
        audit.append(AuditAction.RETURN_COMPLETED, event.terminalId(), event.creditNoteNumber(),
                Map.of("originalSaleId", event.originalSaleId().toString(),
                        "refundGrandTotal", event.refundGrandTotal().toPlainString(),
                        "currency", event.currencyCode()));
    }
}
```

`DiscountOverriddenAuditListener.java`:
```java
package com.company.pos.audit.application;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.sales.api.DiscountOverridden;
import java.util.Map;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class DiscountOverriddenAuditListener {

    private final DefaultAuditService audit;

    DiscountOverriddenAuditListener(DefaultAuditService audit) {
        this.audit = audit;
    }

    @ApplicationModuleListener
    void on(DiscountOverridden event) {
        audit.append(AuditAction.DISCOUNT_OVERRIDE, event.actor(), event.saleId().toString(),
                Map.of("sku", event.sku() == null ? "TRANSACTION" : event.sku(),
                        "discountAmount", event.discountAmount().toPlainString(),
                        "discountType", event.discountType() == null ? "" : event.discountType(),
                        "reasonCode", event.reasonCode() == null ? "" : event.reasonCode()));
    }
}
```

`ProductPriceChangedAuditListener.java`:
```java
package com.company.pos.audit.application;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.product.api.ProductPriceChanged;
import java.util.Map;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class ProductPriceChangedAuditListener {

    private final DefaultAuditService audit;

    ProductPriceChangedAuditListener(DefaultAuditService audit) {
        this.audit = audit;
    }

    @ApplicationModuleListener
    void on(ProductPriceChanged event) {
        audit.append(AuditAction.PRICE_CHANGED, "system", event.sku(),
                Map.of("oldPrice", event.oldPrice().toPlainString(),
                        "newPrice", event.newPrice().toPlainString(),
                        "erpVersion", Long.toString(event.erpVersion())));
    }
}
```

- [ ] **Step 4: Run test to verify it passes + boundaries**

Run: `./mvnw test -Dtest=AuditEventListenersTest && ./mvnw test -Dtest=ModularityTests`
Expected: PASS. `ModularityTests` confirms `audit`'s dependencies on `sales :: api`, `product :: api`, `configuration :: api` are all legal and acyclic.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/company/pos/audit/application/*AuditListener.java src/test/java/com/company/pos/audit/AuditEventListenersTest.java
git commit -m "feat(audit): subscribe to sale, return, discount-override, price-change, setting-change events"
```

---

### Task 7: Synchronous login auditing in `auth`

**Files:**
- Modify: `src/main/java/com/company/pos/auth/package-info.java` (add `audit :: api`)
- Modify: `src/main/java/com/company/pos/auth/application/AuthService.java`
- Test: `src/test/java/com/company/pos/auth/LoginAuditTest.java`

**Interfaces:**
- Consumes: `AuditService.record(AuditAction, String actor, String entityRef, Map<String,String>)` (`audit :: api`).
- Produces: `LOGIN_SUCCEEDED` / `LOGIN_FAILED` / `PIN_LOGIN_SUCCEEDED` / `PIN_LOGIN_FAILED` audit rows.

- [ ] **Step 1: Add the module dependency**

First confirm the current contents of `src/main/java/com/company/pos/auth/package-info.java`, then add `"audit :: api"` to its `allowedDependencies` list. Example result (preserve any existing entries):
```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "audit :: api" })
package com.company.pos.auth;
```

- [ ] **Step 2: Write the failing test**

`src/test/java/com/company/pos/auth/LoginAuditTest.java`:
```java
package com.company.pos.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.audit.api.AuditRecordView;
import com.company.pos.audit.application.DefaultAuditService;
import com.company.pos.auth.application.AuthService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class LoginAuditTest {

    @Autowired
    AuthService authService;

    @Autowired
    DefaultAuditService audit;

    @Test
    void failedLoginIsAuditedEvenThoughLoginThrows() {
        assertThatThrownBy(() -> authService.login("ghost", "wrong"))
                .isInstanceOf(RuntimeException.class);

        List<AuditRecordView> recent = audit.recent(20);
        assertThat(recent).anySatisfy(r -> {
            assertThat(r.action()).isEqualTo("LOGIN_FAILED");
            assertThat(r.actor()).isEqualTo("ghost");
        });
        assertThat(audit.verify().intact()).isTrue();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw test -Dtest=LoginAuditTest`
Expected: FAIL — no `LOGIN_FAILED` record (auth does not audit yet).

- [ ] **Step 4: Wire AuditService into AuthService**

In `AuthService.java`: add imports `import com.company.pos.audit.api.AuditAction;`, `import com.company.pos.audit.api.AuditService;`, `import java.util.Map;`. Add the field and constructor parameter:
```java
    private final AuditService audit;
```
(add `AuditService audit` as the final constructor parameter and `this.audit = audit;`).

Replace the `login` and `pinLogin` method bodies with audited versions:
```java
    public String login(String username, String rawPassword) {
        try {
            User user = users.findByUsername(username)
                    .filter(User::isEnabled)
                    .orElseThrow(() -> DomainException.validation("Invalid credentials"));
            if (!encoder.matches(rawPassword, user.getPasswordHash())) {
                throw DomainException.validation("Invalid credentials");
            }
            String token = jwtService.issue(user);
            audit.record(AuditAction.LOGIN_SUCCEEDED, username, username, Map.of());
            return token;
        } catch (RuntimeException ex) {
            audit.record(AuditAction.LOGIN_FAILED, username, username,
                    Map.of("reason", "invalid_credentials"));
            throw ex;
        }
    }

    public String pinLogin(String cashierCode, String pin) {
        try {
            User user = users.findByCashierCode(cashierCode)
                    .filter(User::isEnabled)
                    .orElseThrow(() -> DomainException.validation("Invalid credentials"));
            if (user.getPinHash() == null || !encoder.matches(pin, user.getPinHash())) {
                throw DomainException.validation("Invalid credentials");
            }
            String token = jwtService.issue(user);
            audit.record(AuditAction.PIN_LOGIN_SUCCEEDED, cashierCode, cashierCode, Map.of());
            return token;
        } catch (RuntimeException ex) {
            audit.record(AuditAction.PIN_LOGIN_FAILED, cashierCode, cashierCode,
                    Map.of("reason", "invalid_credentials"));
            throw ex;
        }
    }
```

> `audit.record(...)` is `REQUIRES_NEW`, so the `LOGIN_FAILED` row commits in its own transaction before the exception propagates — the failed-login record survives even though `login` throws.

- [ ] **Step 5: Run test + boundaries**

Run: `./mvnw test -Dtest=LoginAuditTest && ./mvnw test -Dtest=ModularityTests`
Expected: PASS. `ModularityTests` confirms `auth → audit :: api` is legal and introduces no cycle (audit does not depend on auth).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/auth/package-info.java src/main/java/com/company/pos/auth/application/AuthService.java src/test/java/com/company/pos/auth/LoginAuditTest.java
git commit -m "feat(auth): audit login/pin success and failure via the audit facade"
```

---

### Task 8: ADMIN audit read API (`/audit`, `/audit/{id}`, `/audit/verify`)

**Files:**
- Create: `src/main/java/com/company/pos/audit/web/AuditController.java`
- Modify: `src/main/java/com/company/pos/audit/application/DefaultAuditService.java` (add `findById`)
- Test: `src/test/java/com/company/pos/audit/AuditControllerTest.java`

**Interfaces:**
- Consumes: `DefaultAuditService.search(...)`, `DefaultAuditService.verify()`, new `DefaultAuditService.findById(UUID)`.
- Produces: HTTP `GET /audit`, `GET /audit/{id}`, `POST /audit/verify` (all ADMIN-only).

- [ ] **Step 1: Add findById to DefaultAuditService**

In `DefaultAuditService.java` add:
```java
    @Transactional(readOnly = true)
    public AuditRecordView findById(java.util.UUID id) {
        return records.findById(id.toString())
                .map(this::toView)
                .orElseThrow(() -> com.company.pos.common.exception.DomainException.notFound(
                        "No audit record " + id));
    }
```

- [ ] **Step 2: Write the failing test**

`src/test/java/com/company/pos/audit/AuditControllerTest.java`:
```java
package com.company.pos.audit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.audit.api.AuditService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class AuditControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AuditService auditService;

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanQueryAndVerify() throws Exception {
        auditService.record(AuditAction.LOGIN_FAILED, "mallory", "mallory", Map.of("reason", "x"));

        mvc.perform(get("/audit").param("action", "LOGIN_FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("LOGIN_FAILED"));

        mvc.perform(post("/audit/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(true));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void managerCannotReadAudit() throws Exception {
        mvc.perform(get("/audit")).andExpect(status().isForbidden());
        mvc.perform(post("/audit/verify")).andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw test -Dtest=AuditControllerTest`
Expected: FAIL — no `/audit` mapping.

- [ ] **Step 4: Create the controller**

`src/main/java/com/company/pos/audit/web/AuditController.java`:
```java
package com.company.pos.audit.web;

import com.company.pos.audit.api.AuditRecordView;
import com.company.pos.audit.api.AuditVerifyResult;
import com.company.pos.audit.application.DefaultAuditService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasRole('ADMIN')")
class AuditController {

    private static final Instant MIN = Instant.parse("1970-01-01T00:00:00Z");
    private static final Instant MAX = Instant.parse("9999-12-31T23:59:59Z");

    private final DefaultAuditService audit;

    AuditController(DefaultAuditService audit) {
        this.audit = audit;
    }

    @GetMapping("/audit")
    List<AuditRecordView> search(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return audit.search(actor, action, from == null ? MIN : from, to == null ? MAX : to, page, size);
    }

    @GetMapping("/audit/{id}")
    AuditRecordView get(@PathVariable UUID id) {
        return audit.findById(id);
    }

    @PostMapping("/audit/verify")
    AuditVerifyResult verify() {
        return audit.verify();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=AuditControllerTest`
Expected: PASS (admin 200 + filter + verify intact; manager 403 on both).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/pos/audit/web/AuditController.java src/main/java/com/company/pos/audit/application/DefaultAuditService.java src/test/java/com/company/pos/audit/AuditControllerTest.java
git commit -m "feat(audit): ADMIN-only GET /audit, GET /audit/{id}, POST /audit/verify"
```

---

### Task 9: Capstone end-to-end test + docs + full verify

**Files:**
- Test: `src/test/java/com/company/pos/audit/AuditTrailE2ETest.java`
- Modify: `docs/run-modes.md` (add a Phase 6 — Audit trail section)

**Interfaces:**
- Consumes: the whole stack — checkout with a manager over-cap discount, ERP price-change sync, config PUT, plus the audit query/verify API.

- [ ] **Step 1: Write the capstone end-to-end test**

`src/test/java/com/company/pos/audit/AuditTrailE2ETest.java`:
```java
package com.company.pos.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.company.pos.audit.api.AuditRecordView;
import com.company.pos.audit.application.DefaultAuditService;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class AuditTrailE2ETest {

    @Autowired
    DefaultAuditService audit;

    // Reuse the existing checkout E2E harness: this test should drive a real cash sale with a
    // manager over-cap discount (mirror the setup in the sales capstone test
    // com.company.pos.sales.*E2E*), then assert the chain contains SALE_COMPLETED and
    // DISCOUNT_OVERRIDE and verifies intact.
    @Test
    void completedSaleWithManagerOverrideLandsInTheChain() {
        // ARRANGE+ACT: copy the cart-create + checkout(manager=true, over-cap discount) flow from
        // the sales capstone E2E test. The exact builder calls live in that test; replicate them.

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<String> actions = audit.recent(50).stream().map(AuditRecordView::action).toList();
            assertThat(actions).contains("SALE_COMPLETED", "DISCOUNT_OVERRIDE");
        });
        assertThat(Set.of(true)).contains(audit.verify().intact());
    }
}
```

> The agent MUST open the existing sales capstone E2E test (search `src/test/java/com/company/pos/sales` for the `@SpringBootTest` checkout flow) and copy its exact cart/checkout setup into the ARRANGE+ACT block, adding a manager-level line discount above the cashier cap. Do not invent API shapes — replicate the working test's calls.

- [ ] **Step 2: Run the capstone test**

Run: `./mvnw test -Dtest=AuditTrailE2ETest`
Expected: PASS — both `SALE_COMPLETED` and `DISCOUNT_OVERRIDE` appear and the chain verifies intact.

- [ ] **Step 3: Document the phase in run-modes.md**

Add a new section to `docs/run-modes.md` (after the Phase 5 section) describing:
- New ADMIN endpoints: `GET /audit?from=&to=&actor=&action=&page=&size=`, `GET /audit/{id}`, `POST /audit/verify`, and `PUT /config/{key}` (ADMIN).
- What is captured and how (events vs synchronous facade table from the spec).
- The tamper-evidence model (per-store hash chain; `verify` walks the whole chain).
- Known limits (copy honestly): sales/return audit rows record `terminalId`, not the acting cashier/manager (events don't carry the user); audit listeners are not idempotent so an outbox replay can append a duplicate (valid) row; chain has no archival/pruning (indefinite retention); `verify` covers the whole chain only.

Keep the prose consistent with the existing run-modes style. Write the section to match the documented surface above.

- [ ] **Step 4: Full build + boundary verification**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw verify`
Expected: BUILD SUCCESS — all module tests and `ModularityTests` pass.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/company/pos/audit/AuditTrailE2ETest.java docs/run-modes.md
git commit -m "test(audit): e2e sale+override chained; document phase 6 audit trail"
```

---

## Self-Review

**Spec coverage:**
- Hash-chained tamper-evident store → Tasks 1, 2 (HashChainer, AuditRecord/AuditChainHead, verify).
- Hybrid capture (events + synchronous facade) → Tasks 6 (events) + 7 (auth facade).
- Coverage: sales & returns → Task 6; logins & auth → Task 7; manager overrides → Tasks 3 + 6; config & price changes → Tasks 4 (price) + 5 (config) + 6 (listeners).
- ADMIN read + verify API → Task 8.
- ADMIN `PUT /config/{key}` → Task 5.
- Migration V19 + Flyway registration → Task 2.
- `ModularityTests` re-run after each boundary change → Tasks 2, 6, 7.
- Voids deferred; sales/return actor limitation; no archival → documented in Task 9 Step 3 and the Deviations section.

**Placeholder scan:** The two E2E/sync-staging tests (Task 4 Step 1, Task 9 Step 1) intentionally instruct the implementer to copy exact builder calls from named existing tests rather than inventing API shapes — this is a deliberate "replicate the working harness" directive with the precise source test named, not a vague TODO. All production code is fully specified.

**Type consistency:** `DefaultAuditService.append(...)`/`record(...)` signatures match listener and auth call sites; `DiscountResult` 6th component `overrides` matches the `new DiscountResult(...)` call in `DiscountCalculator`; event record component names (`event.terminalId()`, `event.creditNoteNumber()`, `event.actor()`, `event.sku()`) match the producing records defined here and the existing `SaleCompleted`/`ReturnCompleted` accessors.
