# Terminal UI — Restaurant Seat-to-Payment Slice — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first JavaFX desktop POS terminal — a REST thin client to the store server — delivering one restaurant vertical slice: login → shared table map → dine-in order with modifiers → fire to kitchen → close one bill → pay cash/card → receipt.

**Architecture:** A new standalone Maven module `pos-terminal/` (its own `pom.xml`, not part of the server's Spring Modulith monolith, imports no server code — HTTP only). Internal design is FXML + MVVM + a typed API client: thin FXML controllers bind to ViewModels (plain classes with JavaFX properties, holding all logic), which call typed API clients over a shared `ApiClient` (`java.net.http.HttpClient` + Jackson). All logic-bearing units are built test-first; FXML/controller wiring tasks build on already-green ViewModels.

**Tech Stack:** Java 21, JavaFX 21.0.2 (openjfx: controls + fxml), Jackson 2.17.1, JUnit 5.10.2. HTTP stubbing in tests uses the JDK's built-in `com.sun.net.httpserver.HttpServer` (no extra dependency). Build/run via the existing root Maven wrapper: `./mvnw -f pos-terminal/pom.xml ...`.

## Global Constraints

- **Module is standalone.** `pos-terminal/` has its own `pom.xml`; it is NOT added to the server's build reactor, so the server's `./mvnw verify` (what CI runs) is unaffected. Build the terminal explicitly with `./mvnw -f pos-terminal/pom.xml <goal>`.
- **No server imports.** The terminal never imports any `com.company.pos.<module>` server class. It talks HTTP only. Terminal-side DTOs are its own records that mirror the server's JSON.
- **Money is `BigDecimal`, never `double`** — matches the server convention; all amounts and price math use `BigDecimal` with `setScale(2, RoundingMode.HALF_UP)` where a scale is needed.
- **Jackson ignores unknown JSON fields** — the shared `ObjectMapper` sets `FAIL_ON_UNKNOWN_PROPERTIES=false` so terminal DTOs may carry a subset of server fields.
- **No blocking the JavaFX Application Thread** — every network call runs in a `javafx.concurrent.Task`; results return to the FX thread via `Platform.runLater` (or the Task's `setOnSucceeded`). ViewModels expose `javafx.beans.property` types.
- **Java package root:** `com.company.pos.terminal`.
- **Base URL / IDs come from config**, never hardcoded — `TerminalConfig` reads `pos-terminal.properties` (overridable by JVM system properties).

---

### Task 1: Module scaffold + build + `TerminalConfig`

**Files:**
- Create: `pos-terminal/pom.xml`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/config/TerminalConfig.java`
- Create: `pos-terminal/src/main/resources/pos-terminal.properties`
- Create: `pos-terminal/src/main/java/module-info.java` — omit (use classpath, not modulepath, to avoid JPMS friction with Jackson). Do NOT create a `module-info.java`.
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/config/TerminalConfigTest.java`

**Interfaces:**
- Produces: `TerminalConfig` with `static TerminalConfig load()` (reads classpath `pos-terminal.properties`, then overlays any matching JVM system properties) and getters `String serverBaseUrl()`, `String terminalId()`, `String storeId()`, `int pollIntervalSeconds()`. Also `static TerminalConfig from(Properties p)` for tests.

- [ ] **Step 1: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.company.pos</groupId>
  <artifactId>pos-terminal</artifactId>
  <version>0.1.0</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <javafx.version>21.0.2</javafx.version>
    <jackson.version>2.17.1</jackson.version>
    <junit.version>5.10.2</junit.version>
    <mainClass>com.company.pos.terminal.app.PosTerminalApp</mainClass>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.openjfx</groupId><artifactId>javafx-controls</artifactId><version>${javafx.version}</version>
    </dependency>
    <dependency>
      <groupId>org.openjfx</groupId><artifactId>javafx-fxml</artifactId><version>${javafx.version}</version>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId><version>${jackson.version}</version>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.datatype</groupId><artifactId>jackson-datatype-jsr310</artifactId><version>${jackson.version}</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>${junit.version}</version><scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version>
      </plugin>
      <plugin>
        <groupId>org.openjfx</groupId><artifactId>javafx-maven-plugin</artifactId><version>0.0.8</version>
        <configuration><mainClass>${mainClass}</mainClass></configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Create `pos-terminal.properties`**

```properties
server.base-url=http://localhost:8080
terminal.id=T01
store.id=S01
poll.interval.seconds=5
```

- [ ] **Step 3: Write the failing test**

```java
package com.company.pos.terminal.config;

import org.junit.jupiter.api.Test;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class TerminalConfigTest {

    @Test
    void readsValuesFromProperties() {
        Properties p = new Properties();
        p.setProperty("server.base-url", "http://store:9000");
        p.setProperty("terminal.id", "T07");
        p.setProperty("store.id", "S02");
        p.setProperty("poll.interval.seconds", "3");

        TerminalConfig cfg = TerminalConfig.from(p);

        assertEquals("http://store:9000", cfg.serverBaseUrl());
        assertEquals("T07", cfg.terminalId());
        assertEquals("S02", cfg.storeId());
        assertEquals(3, cfg.pollIntervalSeconds());
    }

    @Test
    void pollIntervalFallsBackToFiveWhenMissing() {
        TerminalConfig cfg = TerminalConfig.from(new Properties());
        assertEquals(5, cfg.pollIntervalSeconds());
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=TerminalConfigTest`
Expected: FAIL — `TerminalConfig` does not compile/exist.

- [ ] **Step 5: Implement `TerminalConfig`**

```java
package com.company.pos.terminal.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class TerminalConfig {
    private final String serverBaseUrl;
    private final String terminalId;
    private final String storeId;
    private final int pollIntervalSeconds;

    private TerminalConfig(Properties p) {
        this.serverBaseUrl = p.getProperty("server.base-url", "http://localhost:8080");
        this.terminalId = p.getProperty("terminal.id", "T01");
        this.storeId = p.getProperty("store.id", "S01");
        this.pollIntervalSeconds = Integer.parseInt(p.getProperty("poll.interval.seconds", "5"));
    }

    public static TerminalConfig from(Properties p) {
        return new TerminalConfig(p);
    }

    /** Load bundled defaults, then overlay any matching JVM system properties. */
    public static TerminalConfig load() {
        Properties p = new Properties();
        try (InputStream in = TerminalConfig.class.getResourceAsStream("/pos-terminal.properties")) {
            if (in != null) p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read pos-terminal.properties", e);
        }
        for (String key : new String[]{"server.base-url", "terminal.id", "store.id", "poll.interval.seconds"}) {
            String override = System.getProperty(key);
            if (override != null) p.setProperty(key, override);
        }
        return new TerminalConfig(p);
    }

    public String serverBaseUrl() { return serverBaseUrl; }
    public String terminalId() { return terminalId; }
    public String storeId() { return storeId; }
    public int pollIntervalSeconds() { return pollIntervalSeconds; }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=TerminalConfigTest`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add pos-terminal/pom.xml pos-terminal/src/main/resources/pos-terminal.properties \
        pos-terminal/src/main/java/com/company/pos/terminal/config/TerminalConfig.java \
        pos-terminal/src/test/java/com/company/pos/terminal/config/TerminalConfigTest.java
git commit -m "feat(terminal): scaffold pos-terminal module + TerminalConfig"
```

---

### Task 2: `ApiClient` + `ApiException` + `ProblemDetail` + `SessionManager`

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/SessionManager.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/ProblemDetail.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/ApiException.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/ApiClient.java`
- Create: `pos-terminal/src/test/java/com/company/pos/terminal/api/StubServer.java` (test helper)
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/ApiClientTest.java`

**Interfaces:**
- Produces:
  - `SessionManager` — `void setToken(String)`, `String token()`, `boolean isAuthenticated()`, `void setUser(String username, java.util.Set<String> roles)`, `String username()`, `java.util.Set<String> roles()`, `boolean isManager()` (true if roles contains `"MANAGER"` or `"ADMIN"`), `void clear()`.
  - `ProblemDetail` — `record ProblemDetail(String title, int status, String detail)`.
  - `ApiException extends RuntimeException` — `int status()`, `ProblemDetail problem()` (nullable), constructor `ApiException(int status, ProblemDetail problem, String message)`.
  - `ApiClient` — constructor `ApiClient(String baseUrl, SessionManager session, com.fasterxml.jackson.databind.ObjectMapper mapper, java.net.http.HttpClient http)` and a convenience `ApiClient(String baseUrl, SessionManager session)`. Methods: `<T> T get(String path, com.fasterxml.jackson.core.type.TypeReference<T> type)`, `<T> T post(String path, Object body, TypeReference<T> type)`, `<T> T put(String path, Object body, TypeReference<T> type)`, `void delete(String path)`. Also `static ObjectMapper defaultMapper()`. Each method sets `Authorization: Bearer <token>` when the session has a token, sends/receives `application/json`, and throws `ApiException` on any non-2xx (parsing a problem+json body into `ProblemDetail` when present).

- [ ] **Step 1: Write the test helper `StubServer`**

```java
package com.company.pos.terminal.api;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal in-process HTTP server for API tests. Records the last request. */
final class StubServer implements AutoCloseable {
    private final HttpServer server;
    String lastMethod, lastPath, lastBody, lastAuth;
    private final List<String> pathsHit = new ArrayList<>();

    StubServer(int status, String responseBody, String contentType) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            lastMethod = ex.getRequestMethod();
            lastPath = ex.getRequestURI().getPath();
            pathsHit.add(lastPath);
            lastAuth = ex.getRequestHeaders().getFirst("Authorization");
            lastBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out = responseBody == null ? new byte[0] : responseBody.getBytes(StandardCharsets.UTF_8);
            if (contentType != null) ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
    }

    String baseUrl() { return "http://localhost:" + server.getAddress().getPort(); }
    List<String> pathsHit() { return pathsHit; }

    @Override public void close() { server.stop(0); }
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.company.pos.terminal.api;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiClientTest {

    record Echo(String token) {}

    @Test
    void getDeserializesJsonAndSendsBearerToken() throws Exception {
        try (StubServer stub = new StubServer(200, "{\"token\":\"abc\"}", "application/json")) {
            SessionManager session = new SessionManager();
            session.setToken("jwt-123");
            ApiClient client = new ApiClient(stub.baseUrl(), session);

            Echo body = client.get("/auth/me", new TypeReference<Echo>() {});

            assertEquals("abc", body.token());
            assertEquals("GET", stub.lastMethod);
            assertEquals("/auth/me", stub.lastPath);
            assertEquals("Bearer jwt-123", stub.lastAuth);
        }
    }

    @Test
    void postSerializesBody() throws Exception {
        try (StubServer stub = new StubServer(200, "{\"token\":\"t\"}", "application/json")) {
            ApiClient client = new ApiClient(stub.baseUrl(), new SessionManager());
            client.post("/auth/login", new Echo("pw"), new TypeReference<Echo>() {});
            assertEquals("POST", stub.lastMethod);
            assertTrue(stub.lastBody.contains("\"token\":\"pw\""));
        }
    }

    @Test
    void nonSuccessThrowsApiExceptionWithProblemDetail() throws Exception {
        String problem = "{\"title\":\"Bad Request\",\"status\":400,\"detail\":\"reason required\"}";
        try (StubServer stub = new StubServer(400, problem, "application/problem+json")) {
            ApiClient client = new ApiClient(stub.baseUrl(), new SessionManager());
            ApiException ex = assertThrows(ApiException.class,
                    () -> client.get("/x", new TypeReference<Echo>() {}));
            assertEquals(400, ex.status());
            assertNotNull(ex.problem());
            assertEquals("reason required", ex.problem().detail());
        }
    }

    @Test
    void sessionManagerRolesDriveIsManager() {
        SessionManager s = new SessionManager();
        s.setUser("m", java.util.Set.of("MANAGER"));
        assertTrue(s.isManager());
        s.setUser("c", java.util.Set.of("CASHIER"));
        assertFalse(s.isManager());
        s.clear();
        assertFalse(s.isAuthenticated());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=ApiClientTest`
Expected: FAIL — classes do not exist.

- [ ] **Step 4: Implement `SessionManager`**

```java
package com.company.pos.terminal.api;

import java.util.Set;

public final class SessionManager {
    private volatile String token;
    private volatile String username;
    private volatile Set<String> roles = Set.of();

    public void setToken(String token) { this.token = token; }
    public String token() { return token; }
    public boolean isAuthenticated() { return token != null; }

    public void setUser(String username, Set<String> roles) {
        this.username = username;
        this.roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public String username() { return username; }
    public Set<String> roles() { return roles; }
    public boolean isManager() { return roles.contains("MANAGER") || roles.contains("ADMIN"); }

    public void clear() {
        this.token = null;
        this.username = null;
        this.roles = Set.of();
    }
}
```

- [ ] **Step 5: Implement `ProblemDetail` and `ApiException`**

```java
package com.company.pos.terminal.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProblemDetail(String title, int status, String detail) {}
```

```java
package com.company.pos.terminal.api;

public class ApiException extends RuntimeException {
    private final int status;
    private final transient ProblemDetail problem;

    public ApiException(int status, ProblemDetail problem, String message) {
        super(message);
        this.status = status;
        this.problem = problem;
    }

    public int status() { return status; }
    public ProblemDetail problem() { return problem; }
}
```

- [ ] **Step 6: Implement `ApiClient`**

```java
package com.company.pos.terminal.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class ApiClient {
    private final String baseUrl;
    private final SessionManager session;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public ApiClient(String baseUrl, SessionManager session, ObjectMapper mapper, HttpClient http) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.session = session;
        this.mapper = mapper;
        this.http = http;
    }

    public ApiClient(String baseUrl, SessionManager session) {
        this(baseUrl, session, defaultMapper(),
             HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    public static ObjectMapper defaultMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public <T> T get(String path, TypeReference<T> type) { return send("GET", path, null, type); }
    public <T> T post(String path, Object body, TypeReference<T> type) { return send("POST", path, body, type); }
    public <T> T put(String path, Object body, TypeReference<T> type) { return send("PUT", path, body, type); }
    public void delete(String path) { send("DELETE", path, null, null); }

    private <T> T send(String method, String path, Object body, TypeReference<T> type) {
        try {
            HttpRequest.BodyPublisher pub = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8);
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .method(method, pub);
            if (body != null) b.header("Content-Type", "application/json");
            if (session.token() != null) b.header("Authorization", "Bearer " + session.token());

            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            if (sc < 200 || sc >= 300) throw toApiException(sc, resp.body());
            if (type == null || resp.body() == null || resp.body().isBlank()) return null;
            return mapper.readValue(resp.body(), type);
        } catch (ApiException e) {
            throw e;
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ApiException(0, null, "Cannot reach store server: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new ApiException(-1, null, "Client error: " + e.getMessage());
        }
    }

    private ApiException toApiException(int status, String body) {
        ProblemDetail pd = null;
        if (body != null && !body.isBlank()) {
            try { pd = mapper.readValue(body, new TypeReference<ProblemDetail>() {}); }
            catch (Exception ignored) { /* not a problem+json body */ }
        }
        String msg = pd != null && pd.detail() != null ? pd.detail() : "HTTP " + status;
        return new ApiException(status, pd, msg);
    }
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=ApiClientTest`
Expected: PASS (4 tests).

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/ \
        pos-terminal/src/test/java/com/company/pos/terminal/api/
git commit -m "feat(terminal): ApiClient, ApiException, ProblemDetail, SessionManager"
```

---

### Task 3: DTOs + `AuthApi`

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/LoginRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/PinLoginRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/TokenResponse.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/MeResponse.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/AuthApi.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/AuthApiTest.java`

**Interfaces:**
- Consumes: `ApiClient`, `SessionManager` from Task 2.
- Produces:
  - `LoginRequest(String username, String password)`, `PinLoginRequest(String cashierCode, String pin)`, `TokenResponse(String token)`, `MeResponse(String username, java.util.Set<String> roles)`.
  - `AuthApi(ApiClient client, SessionManager session)` with `void login(String username, String password)` and `void pinLogin(String cashierCode, String pin)` — each POSTs, stores the returned token in the session, then GETs `/auth/me` and stores username+roles.

- [ ] **Step 1: Confirm server JSON field names**

Read `src/main/java/com/company/pos/auth/web/` (the auth controller + its request/response records) to confirm the exact JSON field names for login (`username`,`password`), pin-login (`cashierCode`,`pin`), the token field (`token`), and `/auth/me` (`username`, `roles`). Adjust the DTO records below if any field name differs. (run-modes.md §Phase 1 documents these shapes.)

- [ ] **Step 2: Write the failing test**

```java
package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuthApiTest {

    @Test
    void loginStoresTokenAndRoles() throws Exception {
        // Stub returns a token for POST /auth/login AND the same body for GET /auth/me.
        // The MeResponse fields are ignored by the token parse and vice-versa (ignore-unknown).
        String body = "{\"token\":\"jwt-xyz\",\"username\":\"alice\",\"roles\":[\"MANAGER\"]}";
        try (StubServer stub = new StubServer(200, body, "application/json")) {
            SessionManager session = new SessionManager();
            AuthApi auth = new AuthApi(new ApiClient(stub.baseUrl(), session), session);

            auth.login("alice", "pw");

            assertEquals("jwt-xyz", session.token());
            assertEquals("alice", session.username());
            assertTrue(session.isManager());
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=AuthApiTest`
Expected: FAIL — `AuthApi`/DTOs missing.

- [ ] **Step 4: Implement the DTOs**

```java
package com.company.pos.terminal.api.dto;
public record LoginRequest(String username, String password) {}
```
```java
package com.company.pos.terminal.api.dto;
public record PinLoginRequest(String cashierCode, String pin) {}
```
```java
package com.company.pos.terminal.api.dto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
@JsonIgnoreProperties(ignoreUnknown = true)
public record TokenResponse(String token) {}
```
```java
package com.company.pos.terminal.api.dto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Set;
@JsonIgnoreProperties(ignoreUnknown = true)
public record MeResponse(String username, Set<String> roles) {}
```

- [ ] **Step 5: Implement `AuthApi`**

```java
package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.*;
import com.fasterxml.jackson.core.type.TypeReference;

public class AuthApi {
    private final ApiClient client;
    private final SessionManager session;

    public AuthApi(ApiClient client, SessionManager session) {
        this.client = client;
        this.session = session;
    }

    public void login(String username, String password) {
        TokenResponse t = client.post("/auth/login", new LoginRequest(username, password),
                new TypeReference<TokenResponse>() {});
        completeLogin(t);
    }

    public void pinLogin(String cashierCode, String pin) {
        TokenResponse t = client.post("/auth/pin-login", new PinLoginRequest(cashierCode, pin),
                new TypeReference<TokenResponse>() {});
        completeLogin(t);
    }

    private void completeLogin(TokenResponse t) {
        session.setToken(t.token());
        MeResponse me = client.get("/auth/me", new TypeReference<MeResponse>() {});
        session.setUser(me.username(), me.roles());
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=AuthApiTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ \
        pos-terminal/src/main/java/com/company/pos/terminal/api/AuthApi.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/AuthApiTest.java
git commit -m "feat(terminal): AuthApi + auth DTOs (login/pin, stores token+roles)"
```

---

### Task 4: `ProductApi` + `MenuApi` + DTOs

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ProductView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ModifierGroupView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ModifierOptionView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/ProductApi.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/MenuApi.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/CatalogApiTest.java`

**Interfaces:**
- Consumes: `ApiClient`.
- Produces:
  - `ProductView(String sku, String name, String categoryName, java.math.BigDecimal price)`.
  - `ModifierOptionView(java.util.UUID id, String name, java.math.BigDecimal priceDelta)`.
  - `ModifierGroupView(java.util.UUID id, String name, int minSelections, int maxSelections, java.util.List<ModifierOptionView> options)`.
  - `ProductApi(ApiClient client)` — `java.util.List<ProductView> list()` → `GET /products`.
  - `MenuApi(ApiClient client)` — `java.util.List<ModifierGroupView> modifierGroupsForSku(String sku)` → `GET /menu/products/{sku}/modifier-groups`.

- [ ] **Step 1: Confirm server JSON field names**

Read `src/main/java/com/company/pos/product/api/ProductView.java` and `src/main/java/com/company/pos/menu/api/` (the modifier-group + option views) to confirm field names (`sku`,`name`,`categoryName`,`price`; group `id`,`name`,`minSelections`,`maxSelections`,`options`; option `id`,`name`,`priceDelta`). Adjust the records below to match exactly.

- [ ] **Step 2: Write the failing test**

```java
package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CatalogApiTest {

    @Test
    void listProductsMapsFields() throws Exception {
        String json = "[{\"sku\":\"BURGER\",\"name\":\"Burger\",\"categoryName\":\"Mains\",\"price\":25.00}]";
        try (StubServer stub = new StubServer(200, json, "application/json")) {
            ProductApi api = new ProductApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            List<ProductView> products = api.list();
            assertEquals(1, products.size());
            assertEquals("BURGER", products.get(0).sku());
            assertEquals("Mains", products.get(0).categoryName());
            assertEquals(0, new BigDecimal("25.00").compareTo(products.get(0).price()));
            assertEquals("/products", stub.lastPath);
        }
    }

    @Test
    void modifierGroupsForSkuMapsOptions() throws Exception {
        String json = "[{\"id\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"Doneness\","
                + "\"minSelections\":1,\"maxSelections\":1,\"options\":["
                + "{\"id\":\"22222222-2222-2222-2222-222222222222\",\"name\":\"Rare\",\"priceDelta\":0.00}]}]";
        try (StubServer stub = new StubServer(200, json, "application/json")) {
            MenuApi api = new MenuApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            List<ModifierGroupView> groups = api.modifierGroupsForSku("STEAK");
            assertEquals(1, groups.size());
            assertEquals(1, groups.get(0).minSelections());
            assertEquals("Rare", groups.get(0).options().get(0).name());
            assertEquals("/menu/products/STEAK/modifier-groups", stub.lastPath);
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=CatalogApiTest`
Expected: FAIL — classes missing.

- [ ] **Step 4: Implement DTOs**

```java
package com.company.pos.terminal.api.dto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductView(String sku, String name, String categoryName, BigDecimal price) {}
```
```java
package com.company.pos.terminal.api.dto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModifierOptionView(UUID id, String name, BigDecimal priceDelta) {}
```
```java
package com.company.pos.terminal.api.dto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModifierGroupView(UUID id, String name, int minSelections, int maxSelections,
                                List<ModifierOptionView> options) {}
```

- [ ] **Step 5: Implement `ProductApi` and `MenuApi`**

```java
package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.ProductView;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;

public class ProductApi {
    private final ApiClient client;
    public ProductApi(ApiClient client) { this.client = client; }

    public List<ProductView> list() {
        return client.get("/products", new TypeReference<List<ProductView>>() {});
    }
}
```
```java
package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.ModifierGroupView;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;

public class MenuApi {
    private final ApiClient client;
    public MenuApi(ApiClient client) { this.client = client; }

    public List<ModifierGroupView> modifierGroupsForSku(String sku) {
        return client.get("/menu/products/" + sku + "/modifier-groups",
                new TypeReference<List<ModifierGroupView>>() {});
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=CatalogApiTest`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ProductView.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ModifierGroupView.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ModifierOptionView.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/ProductApi.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/MenuApi.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/CatalogApiTest.java
git commit -m "feat(terminal): ProductApi + MenuApi + catalog DTOs"
```

---

### Task 5: `DiningApi` + `SalesApi` + DTOs

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/TableView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/OrderView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/OrderLineView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/OrderLineModifierView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/OpenOrderRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/AddLineRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/UpdateLineRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/CloseOrderRequest.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/TenderInput.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/dto/SaleView.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/SalesApi.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/DiningApiTest.java`

**Interfaces:**
- Consumes: `ApiClient`, `ModifierOptionView` names not needed here.
- Produces:
  - `TableView(java.util.UUID id, String label, int seats, boolean active)`.
  - `OrderLineModifierView(java.util.UUID optionId, String name, java.math.BigDecimal priceDelta)`.
  - `OrderLineView(java.util.UUID id, String sku, java.math.BigDecimal qty, String note, String course, java.time.Instant firedAt, java.util.List<OrderLineModifierView> modifiers)` — `firedAt != null` means fired/locked.
  - `OrderView(java.util.UUID id, java.util.UUID tableId, String serviceType, String status, java.util.UUID saleId, java.util.List<OrderLineView> lines)`.
  - `OpenOrderRequest(java.util.UUID tableId, String serviceType)`.
  - `AddLineRequest(String sku, java.math.BigDecimal qty, String note, String course, java.util.List<java.util.UUID> modifierOptionIds)`.
  - `UpdateLineRequest(java.math.BigDecimal qty)`.
  - `TenderInput(String method, java.math.BigDecimal amount, java.math.BigDecimal tendered)`.
  - `CloseOrderRequest(java.util.List<TenderInput> tenders, java.util.Map<String,Object> lineDiscounts, Object transactionDiscount, boolean waiveServiceCharge)` — for this slice `lineDiscounts` is an empty map and `transactionDiscount` is null.
  - `SaleView(java.util.UUID id, String receiptNumber, java.math.BigDecimal subtotal, java.math.BigDecimal taxTotal, java.math.BigDecimal serviceChargeAmount, java.math.BigDecimal grandTotal, String currencyCode)`.
  - `DiningApi(ApiClient client)`: `List<TableView> tables()`, `List<OrderView> openOrders()`, `OrderView order(UUID id)`, `OrderView openOrder(UUID tableId)` (serviceType `"DINE_IN"`), `OrderView addLine(UUID orderId, AddLineRequest req)`, `OrderView updateLine(UUID orderId, UUID lineId, BigDecimal qty)`, `OrderView removeLine(UUID orderId, UUID lineId)`, `void fire(UUID orderId)`, `SaleView close(UUID orderId, CloseOrderRequest req)`.
  - `SalesApi(ApiClient client)`: `void reprint(UUID saleId)` → `POST /sales/{id}/reprint`.

- [ ] **Step 1: Confirm server JSON field names**

Read these server files and align the terminal records field-for-field:
- `src/main/java/com/company/pos/dining/api/` — `TableView`, `OrderView`, `OrderLineView`, `OrderLineModifierView`, `OpenOrderCommand`, `AddLineCommand`, `CloseOrderCommand`.
- `src/main/java/com/company/pos/sales/api/` — `TenderInput`, `PaymentMethod`, `SaleView`.
Confirm: `OrderView` status enum values (`OPEN`/`CLOSED`), `OrderLineView.firedAt` is the fired marker, `CloseOrderCommand` field order (`tenders`, `lineDiscounts`, `transactionDiscount`, `waiveServiceCharge`), `TenderInput(method, amount, tendered)`, and `PaymentMethod` values (`CASH`,`CARD`,`WALLET`). Adjust records if any differ.

- [ ] **Step 2: Write the failing test**

```java
package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DiningApiTest {

    @Test
    void openOrderPostsDineInAndParsesOrder() throws Exception {
        String order = "{\"id\":\"33333333-3333-3333-3333-333333333333\","
                + "\"tableId\":\"44444444-4444-4444-4444-444444444444\","
                + "\"serviceType\":\"DINE_IN\",\"status\":\"OPEN\",\"saleId\":null,\"lines\":[]}";
        try (StubServer stub = new StubServer(200, order, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            UUID table = UUID.fromString("44444444-4444-4444-4444-444444444444");
            OrderView v = api.openOrder(table);
            assertEquals("OPEN", v.status());
            assertEquals("/dining/orders", stub.lastPath);
            assertTrue(stub.lastBody.contains("DINE_IN"));
        }
    }

    @Test
    void closeSendsTendersAndParsesSale() throws Exception {
        String sale = "{\"id\":\"55555555-5555-5555-5555-555555555555\",\"receiptNumber\":\"S01-T01-1\","
                + "\"subtotal\":25.00,\"taxTotal\":3.75,\"serviceChargeAmount\":0.00,"
                + "\"grandTotal\":28.75,\"currencyCode\":\"SAR\"}";
        try (StubServer stub = new StubServer(200, sale, "application/json")) {
            DiningApi api = new DiningApi(new ApiClient(stub.baseUrl(), new SessionManager()));
            UUID orderId = UUID.fromString("33333333-3333-3333-3333-333333333333");
            CloseOrderRequest req = new CloseOrderRequest(
                    List.of(new TenderInput("CASH", new BigDecimal("28.75"), new BigDecimal("30.00"))),
                    Map.of(), null, false);
            SaleView s = api.close(orderId, req);
            assertEquals("S01-T01-1", s.receiptNumber());
            assertEquals(0, new BigDecimal("28.75").compareTo(s.grandTotal()));
            assertEquals("/dining/orders/33333333-3333-3333-3333-333333333333/close", stub.lastPath);
            assertTrue(stub.lastBody.contains("CASH"));
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=DiningApiTest`
Expected: FAIL — classes missing.

- [ ] **Step 4: Implement the DTOs**

```java
package com.company.pos.terminal.api.dto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;
@JsonIgnoreProperties(ignoreUnknown = true)
public record TableView(UUID id, String label, int seats, boolean active) {}
```
```java
package com.company.pos.terminal.api.dto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderLineModifierView(UUID optionId, String name, BigDecimal priceDelta) {}
```
```java
package com.company.pos.terminal.api.dto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderLineView(UUID id, String sku, BigDecimal qty, String note, String course,
                            Instant firedAt, List<OrderLineModifierView> modifiers) {
    public boolean fired() { return firedAt != null; }
}
```
```java
package com.company.pos.terminal.api.dto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderView(UUID id, UUID tableId, String serviceType, String status, UUID saleId,
                        List<OrderLineView> lines) {}
```
```java
package com.company.pos.terminal.api.dto;
import java.util.UUID;
public record OpenOrderRequest(UUID tableId, String serviceType) {}
```
```java
package com.company.pos.terminal.api.dto;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
public record AddLineRequest(String sku, BigDecimal qty, String note, String course,
                             List<UUID> modifierOptionIds) {}
```
```java
package com.company.pos.terminal.api.dto;
import java.math.BigDecimal;
public record UpdateLineRequest(BigDecimal qty) {}
```
```java
package com.company.pos.terminal.api.dto;
import java.math.BigDecimal;
public record TenderInput(String method, BigDecimal amount, BigDecimal tendered) {}
```
```java
package com.company.pos.terminal.api.dto;
import java.util.List;
import java.util.Map;
public record CloseOrderRequest(List<TenderInput> tenders, Map<String, Object> lineDiscounts,
                                Object transactionDiscount, boolean waiveServiceCharge) {}
```
```java
package com.company.pos.terminal.api.dto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;
@JsonIgnoreProperties(ignoreUnknown = true)
public record SaleView(UUID id, String receiptNumber, BigDecimal subtotal, BigDecimal taxTotal,
                       BigDecimal serviceChargeAmount, BigDecimal grandTotal, String currencyCode) {}
```

- [ ] **Step 5: Implement `DiningApi` and `SalesApi`**

```java
package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.*;
import com.fasterxml.jackson.core.type.TypeReference;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class DiningApi {
    private final ApiClient client;
    public DiningApi(ApiClient client) { this.client = client; }

    public List<TableView> tables() {
        return client.get("/dining/tables", new TypeReference<List<TableView>>() {});
    }
    public List<OrderView> openOrders() {
        return client.get("/dining/orders", new TypeReference<List<OrderView>>() {});
    }
    public OrderView order(UUID id) {
        return client.get("/dining/orders/" + id, new TypeReference<OrderView>() {});
    }
    public OrderView openOrder(UUID tableId) {
        return client.post("/dining/orders", new OpenOrderRequest(tableId, "DINE_IN"),
                new TypeReference<OrderView>() {});
    }
    public OrderView addLine(UUID orderId, AddLineRequest req) {
        return client.post("/dining/orders/" + orderId + "/lines", req, new TypeReference<OrderView>() {});
    }
    public OrderView updateLine(UUID orderId, UUID lineId, BigDecimal qty) {
        return client.put("/dining/orders/" + orderId + "/lines/" + lineId,
                new UpdateLineRequest(qty), new TypeReference<OrderView>() {});
    }
    public OrderView removeLine(UUID orderId, UUID lineId) {
        client.delete("/dining/orders/" + orderId + "/lines/" + lineId);
        return order(orderId);
    }
    public void fire(UUID orderId) {
        client.post("/dining/orders/" + orderId + "/fire", null, new TypeReference<OrderView>() {});
    }
    public SaleView close(UUID orderId, CloseOrderRequest req) {
        return client.post("/dining/orders/" + orderId + "/close", req, new TypeReference<SaleView>() {});
    }
}
```
```java
package com.company.pos.terminal.api;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.UUID;

public class SalesApi {
    private final ApiClient client;
    public SalesApi(ApiClient client) { this.client = client; }

    public void reprint(UUID saleId) {
        client.post("/sales/" + saleId + "/reprint", null, new TypeReference<Void>() {});
    }
}
```

> Note on `removeLine`: the server's `DELETE .../lines/{lineId}` returns the updated `OrderView` (204/200 varies). To stay robust to either, this client issues the DELETE then re-fetches the order. If Step 1 confirms DELETE returns the `OrderView` body, simplify to a single call in a follow-up.

- [ ] **Step 6: Run tests to verify they pass**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=DiningApiTest`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/dto/ \
        pos-terminal/src/main/java/com/company/pos/terminal/api/DiningApi.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/SalesApi.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/DiningApiTest.java
git commit -m "feat(terminal): DiningApi + SalesApi + dining/sale DTOs"
```

---

### Task 6: `MenuCache` + `SubtotalCalculator` (pure logic)

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/order/MenuCache.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/order/SubtotalCalculator.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/order/SubtotalCalculatorTest.java`

**Interfaces:**
- Consumes: `ProductView`, `OrderView`, `OrderLineView`, `OrderLineModifierView`.
- Produces:
  - `MenuCache` — constructed from `List<ProductView>`; `java.math.BigDecimal basePrice(String sku)` (returns `BigDecimal.ZERO` for an unknown sku), `List<String> categories()` (distinct category names in encounter order, with null/blank mapped to `"Other"`), `List<ProductView> productsInCategory(String category)`.
  - `SubtotalCalculator` — `static java.math.BigDecimal estimate(OrderView order, MenuCache cache)` = Σ over lines of `(basePrice(sku) + Σ modifier.priceDelta) × qty`, scaled to 2dp HALF_UP.

- [ ] **Step 1: Write the failing test**

```java
package com.company.pos.terminal.order;

import com.company.pos.terminal.api.dto.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SubtotalCalculatorTest {

    private MenuCache cacheWith(String sku, String cat, String price) {
        return new MenuCache(List.of(new ProductView(sku, sku, cat, new BigDecimal(price))));
    }

    @Test
    void estimatesBasePriceTimesQty() {
        MenuCache cache = cacheWith("BURGER", "Mains", "25.00");
        OrderView order = new OrderView(UUID.randomUUID(), UUID.randomUUID(), "DINE_IN", "OPEN", null,
                List.of(new OrderLineView(UUID.randomUUID(), "BURGER", new BigDecimal("2"),
                        null, "MAIN", null, List.of())));
        assertEquals(0, new BigDecimal("50.00").compareTo(SubtotalCalculator.estimate(order, cache)));
    }

    @Test
    void addsModifierDeltas() {
        MenuCache cache = cacheWith("STEAK", "Mains", "40.00");
        OrderLineModifierView addOn = new OrderLineModifierView(UUID.randomUUID(), "Extra cheese", new BigDecimal("2.00"));
        OrderView order = new OrderView(UUID.randomUUID(), UUID.randomUUID(), "DINE_IN", "OPEN", null,
                List.of(new OrderLineView(UUID.randomUUID(), "STEAK", new BigDecimal("1"),
                        null, "MAIN", null, List.of(addOn))));
        assertEquals(0, new BigDecimal("42.00").compareTo(SubtotalCalculator.estimate(order, cache)));
    }

    @Test
    void unknownSkuContributesZeroBase() {
        MenuCache cache = cacheWith("BURGER", "Mains", "25.00");
        OrderView order = new OrderView(UUID.randomUUID(), UUID.randomUUID(), "DINE_IN", "OPEN", null,
                List.of(new OrderLineView(UUID.randomUUID(), "GHOST", new BigDecimal("3"),
                        null, "MAIN", null, List.of())));
        assertEquals(0, BigDecimal.ZERO.compareTo(SubtotalCalculator.estimate(order, cache)));
    }

    @Test
    void categoriesAreDistinctInOrderWithOtherFallback() {
        MenuCache cache = new MenuCache(List.of(
                new ProductView("A", "A", "Mains", new BigDecimal("1")),
                new ProductView("B", "B", "Mains", new BigDecimal("1")),
                new ProductView("C", "C", null, new BigDecimal("1"))));
        assertEquals(List.of("Mains", "Other"), cache.categories());
        assertEquals(2, cache.productsInCategory("Mains").size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=SubtotalCalculatorTest`
Expected: FAIL — classes missing.

- [ ] **Step 3: Implement `MenuCache`**

```java
package com.company.pos.terminal.order;

import com.company.pos.terminal.api.dto.ProductView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MenuCache {
    static final String OTHER = "Other";
    private final Map<String, ProductView> bySku = new LinkedHashMap<>();
    private final Map<String, List<ProductView>> byCategory = new LinkedHashMap<>();

    public MenuCache(List<ProductView> products) {
        for (ProductView p : products) {
            bySku.put(p.sku(), p);
            String cat = (p.categoryName() == null || p.categoryName().isBlank()) ? OTHER : p.categoryName();
            byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(p);
        }
    }

    public BigDecimal basePrice(String sku) {
        ProductView p = bySku.get(sku);
        return p == null || p.price() == null ? BigDecimal.ZERO : p.price();
    }

    public List<String> categories() { return new ArrayList<>(byCategory.keySet()); }

    public List<ProductView> productsInCategory(String category) {
        return byCategory.getOrDefault(category, List.of());
    }
}
```

- [ ] **Step 4: Implement `SubtotalCalculator`**

```java
package com.company.pos.terminal.order;

import com.company.pos.terminal.api.dto.OrderLineModifierView;
import com.company.pos.terminal.api.dto.OrderLineView;
import com.company.pos.terminal.api.dto.OrderView;
import java.math.BigDecimal;
import java.math.RoundingMode;

public final class SubtotalCalculator {
    private SubtotalCalculator() {}

    public static BigDecimal estimate(OrderView order, MenuCache cache) {
        BigDecimal total = BigDecimal.ZERO;
        if (order == null || order.lines() == null) return total.setScale(2, RoundingMode.HALF_UP);
        for (OrderLineView line : order.lines()) {
            BigDecimal unit = cache.basePrice(line.sku());
            if (line.modifiers() != null) {
                for (OrderLineModifierView m : line.modifiers()) {
                    if (m.priceDelta() != null) unit = unit.add(m.priceDelta());
                }
            }
            BigDecimal qty = line.qty() == null ? BigDecimal.ZERO : line.qty();
            total = total.add(unit.multiply(qty));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=SubtotalCalculatorTest`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/order/ \
        pos-terminal/src/test/java/com/company/pos/terminal/order/
git commit -m "feat(terminal): MenuCache + client-side SubtotalCalculator"
```

---

### Task 7: `LoginViewModel`

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/LoginViewModel.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/LoginViewModelTest.java`

**Interfaces:**
- Consumes: `AuthApi`, `ApiException`.
- Produces: `LoginViewModel(AuthApi auth)` with JavaFX properties `javafx.beans.property.StringProperty username()`, `passwordOrPin()`, and read-only `javafx.beans.property.ReadOnlyBooleanProperty busy()`, `javafx.beans.property.ReadOnlyStringProperty errorMessage()`, `javafx.beans.property.ReadOnlyBooleanProperty loggedIn()`. Method `void login()` (username/password) and `void pinLogin(String cashierCode)` run **synchronously** on the calling thread (the controller wraps them in a `Task`); on success set `loggedIn=true`, clear error; on `ApiException` set `errorMessage` and `loggedIn=false`.

Note: ViewModel methods are synchronous and side-effect the properties; the controller (Task 11) is responsible for running them off the FX thread. This keeps the ViewModel unit-testable without any FX threading.

- [ ] **Step 1: Write the failing test**

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.AuthApi;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoginViewModelTest {

    @Test
    void successSetsLoggedIn() {
        AuthApi auth = new AuthApi(null, null) {
            @Override public void login(String u, String p) { /* success no-op */ }
        };
        LoginViewModel vm = new LoginViewModel(auth);
        vm.username().set("alice");
        vm.passwordOrPin().set("pw");
        vm.login();
        assertTrue(vm.loggedIn().get());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void failureSetsErrorMessage() {
        AuthApi auth = new AuthApi(null, null) {
            @Override public void login(String u, String p) {
                throw new ApiException(401, null, "Invalid credentials");
            }
        };
        LoginViewModel vm = new LoginViewModel(auth);
        vm.username().set("alice");
        vm.passwordOrPin().set("wrong");
        vm.login();
        assertFalse(vm.loggedIn().get());
        assertEquals("Invalid credentials", vm.errorMessage().get());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=LoginViewModelTest`
Expected: FAIL — `LoginViewModel` missing. (Note: `AuthApi` must be non-final with overridable methods — it already is from Task 3.)

- [ ] **Step 3: Implement `LoginViewModel`**

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.AuthApi;
import javafx.beans.property.*;

public class LoginViewModel {
    private final AuthApi auth;
    private final StringProperty username = new SimpleStringProperty("");
    private final StringProperty passwordOrPin = new SimpleStringProperty("");
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final StringProperty errorMessage = new SimpleStringProperty("");
    private final BooleanProperty loggedIn = new SimpleBooleanProperty(false);

    public LoginViewModel(AuthApi auth) { this.auth = auth; }

    public StringProperty username() { return username; }
    public StringProperty passwordOrPin() { return passwordOrPin; }
    public ReadOnlyBooleanProperty busy() { return busy; }
    public ReadOnlyStringProperty errorMessage() { return errorMessage; }
    public ReadOnlyBooleanProperty loggedIn() { return loggedIn; }

    public void login() {
        run(() -> auth.login(username.get(), passwordOrPin.get()));
    }

    public void pinLogin(String cashierCode) {
        run(() -> auth.pinLogin(cashierCode, passwordOrPin.get()));
    }

    private void run(Runnable call) {
        busy.set(true);
        errorMessage.set("");
        try {
            call.run();
            loggedIn.set(true);
        } catch (ApiException e) {
            loggedIn.set(false);
            errorMessage.set(e.getMessage());
        } finally {
            busy.set(false);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=LoginViewModelTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/LoginViewModel.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/LoginViewModelTest.java
git commit -m "feat(terminal): LoginViewModel"
```

---

### Task 8: `TableMapViewModel`

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/TableCell.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/TableMapViewModel.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/TableMapViewModelTest.java`

**Interfaces:**
- Consumes: `DiningApi`, `TableView`, `OrderView`, `ApiException`.
- Produces:
  - `TableCell(java.util.UUID tableId, String label, boolean occupied, java.util.UUID orderId)` — `orderId` is the open order on that table, or null.
  - `TableMapViewModel(DiningApi dining)` with `javafx.collections.ObservableList<TableCell> cells()`, `ReadOnlyStringProperty errorMessage()`, `void refresh()` (loads tables + open orders, rebuilds `cells` marking a table occupied when an OPEN order references it), `UUID openOrResume(TableCell cell)` (if occupied returns its `orderId`; else calls `dining.openOrder(tableId)` and returns the new order id). On `ApiException` set `errorMessage`.

- [ ] **Step 1: Write the failing test**

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.DiningApi;
import com.company.pos.terminal.api.dto.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TableMapViewModelTest {

    private final UUID t1 = UUID.randomUUID();
    private final UUID t2 = UUID.randomUUID();
    private final UUID openOrderId = UUID.randomUUID();

    private DiningApi diningWithOneOccupied() {
        return new DiningApi(null) {
            @Override public List<TableView> tables() {
                return List.of(new TableView(t1, "T1", 4, true), new TableView(t2, "T2", 2, true));
            }
            @Override public List<OrderView> openOrders() {
                return List.of(new OrderView(openOrderId, t1, "DINE_IN", "OPEN", null, List.of()));
            }
        };
    }

    @Test
    void refreshMarksOccupiedTables() {
        TableMapViewModel vm = new TableMapViewModel(diningWithOneOccupied());
        vm.refresh();
        assertEquals(2, vm.cells().size());
        TableCell c1 = vm.cells().stream().filter(c -> c.tableId().equals(t1)).findFirst().orElseThrow();
        TableCell c2 = vm.cells().stream().filter(c -> c.tableId().equals(t2)).findFirst().orElseThrow();
        assertTrue(c1.occupied());
        assertEquals(openOrderId, c1.orderId());
        assertFalse(c2.occupied());
    }

    @Test
    void openOrResumeReturnsExistingOrderIdForOccupied() {
        TableMapViewModel vm = new TableMapViewModel(diningWithOneOccupied());
        vm.refresh();
        TableCell occupied = vm.cells().stream().filter(TableCell::occupied).findFirst().orElseThrow();
        assertEquals(openOrderId, vm.openOrResume(occupied));
    }

    @Test
    void openOrResumeOpensNewOrderForFreeTable() {
        UUID newId = UUID.randomUUID();
        DiningApi dining = new DiningApi(null) {
            @Override public List<TableView> tables() { return List.of(new TableView(t2, "T2", 2, true)); }
            @Override public List<OrderView> openOrders() { return List.of(); }
            @Override public OrderView openOrder(UUID tableId) {
                return new OrderView(newId, tableId, "DINE_IN", "OPEN", null, List.of());
            }
        };
        TableMapViewModel vm = new TableMapViewModel(dining);
        vm.refresh();
        assertEquals(newId, vm.openOrResume(vm.cells().get(0)));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=TableMapViewModelTest`
Expected: FAIL — classes missing.

- [ ] **Step 3: Implement `TableCell`**

```java
package com.company.pos.terminal.viewmodel;

import java.util.UUID;

public record TableCell(UUID tableId, String label, boolean occupied, UUID orderId) {}
```

- [ ] **Step 4: Implement `TableMapViewModel`**

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.DiningApi;
import com.company.pos.terminal.api.dto.OrderView;
import com.company.pos.terminal.api.dto.TableView;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TableMapViewModel {
    private final DiningApi dining;
    private final ObservableList<TableCell> cells = FXCollections.observableArrayList();
    private final StringProperty errorMessage = new SimpleStringProperty("");

    public TableMapViewModel(DiningApi dining) { this.dining = dining; }

    public ObservableList<TableCell> cells() { return cells; }
    public ReadOnlyStringProperty errorMessage() { return errorMessage; }

    public void refresh() {
        try {
            Map<UUID, UUID> orderByTable = new HashMap<>();
            for (OrderView o : dining.openOrders()) {
                if ("OPEN".equals(o.status())) orderByTable.put(o.tableId(), o.id());
            }
            java.util.List<TableCell> next = new java.util.ArrayList<>();
            for (TableView t : dining.tables()) {
                if (!t.active()) continue;
                UUID orderId = orderByTable.get(t.id());
                next.add(new TableCell(t.id(), t.label(), orderId != null, orderId));
            }
            cells.setAll(next);
            errorMessage.set("");
        } catch (ApiException e) {
            errorMessage.set(e.getMessage());
        }
    }

    public UUID openOrResume(TableCell cell) {
        try {
            if (cell.occupied()) return cell.orderId();
            OrderView opened = dining.openOrder(cell.tableId());
            return opened.id();
        } catch (ApiException e) {
            errorMessage.set(e.getMessage());
            return null;
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=TableMapViewModelTest`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/TableCell.java \
        pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/TableMapViewModel.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/TableMapViewModelTest.java
git commit -m "feat(terminal): TableMapViewModel (occupied detection, open/resume)"
```

---

### Task 9: `OrderViewModel` + modifier-selection validation

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/order/ModifierSelectionValidator.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/OrderViewModel.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/order/ModifierSelectionValidatorTest.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/OrderViewModelTest.java`

**Interfaces:**
- Consumes: `DiningApi`, `MenuCache`, `AddLineRequest`, `OrderView`, `OrderLineView`, `ModifierGroupView`.
- Produces:
  - `ModifierSelectionValidator.validate(List<ModifierGroupView> groups, Set<UUID> selectedOptionIds)` → returns `null` if valid, else a human message: forced group (minSelections ≥ 1) must have ≥ min selections from its options; no group may exceed maxSelections.
  - `OrderViewModel(DiningApi dining, MenuCache cache)` with: `void load(UUID orderId)`; `ObservableList<OrderLineView> lines()`; `ReadOnlyStringProperty subtotalText()` (formatted estimate, recomputed after each change); `ReadOnlyStringProperty errorMessage()`; `void addLine(String sku, BigDecimal qty, String note, String course, List<UUID> optionIds)`; `boolean canEdit(OrderLineView line)` (= `!line.fired()`); `void updateQty(OrderLineView line, BigDecimal qty)`; `void removeLine(OrderLineView line)`; `void fire()`; `UUID orderId()`; `OrderView currentOrder()`. Each mutating call refreshes the internal `OrderView`, `lines`, and `subtotalText`. Edit/remove of a fired line is a no-op that sets `errorMessage` ("Fired lines cannot be changed").

- [ ] **Step 1: Write the validator failing test**

```java
package com.company.pos.terminal.order;

import com.company.pos.terminal.api.dto.ModifierGroupView;
import com.company.pos.terminal.api.dto.ModifierOptionView;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ModifierSelectionValidatorTest {

    private final UUID optA = UUID.randomUUID();
    private final UUID optB = UUID.randomUUID();

    private ModifierGroupView forcedOneOf() {
        return new ModifierGroupView(UUID.randomUUID(), "Doneness", 1, 1, List.of(
                new ModifierOptionView(optA, "Rare", BigDecimal.ZERO),
                new ModifierOptionView(optB, "Well", BigDecimal.ZERO)));
    }

    @Test
    void forcedGroupWithNoSelectionIsInvalid() {
        assertNotNull(ModifierSelectionValidator.validate(List.of(forcedOneOf()), Set.of()));
    }

    @Test
    void exceedingMaxIsInvalid() {
        assertNotNull(ModifierSelectionValidator.validate(List.of(forcedOneOf()), Set.of(optA, optB)));
    }

    @Test
    void validSingleSelectionPasses() {
        assertNull(ModifierSelectionValidator.validate(List.of(forcedOneOf()), Set.of(optA)));
    }
}
```

- [ ] **Step 2: Run to verify it fails, then implement `ModifierSelectionValidator`**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=ModifierSelectionValidatorTest`
Expected: FAIL. Then implement:

```java
package com.company.pos.terminal.order;

import com.company.pos.terminal.api.dto.ModifierGroupView;
import com.company.pos.terminal.api.dto.ModifierOptionView;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ModifierSelectionValidator {
    private ModifierSelectionValidator() {}

    /** @return null if valid, else a human-readable reason. */
    public static String validate(List<ModifierGroupView> groups, Set<UUID> selected) {
        for (ModifierGroupView g : groups) {
            long chosen = g.options().stream()
                    .map(ModifierOptionView::id)
                    .filter(selected::contains)
                    .count();
            if (chosen < g.minSelections())
                return "Choose at least " + g.minSelections() + " for " + g.name();
            if (g.maxSelections() > 0 && chosen > g.maxSelections())
                return "Choose at most " + g.maxSelections() + " for " + g.name();
        }
        return null;
    }
}
```

- [ ] **Step 3: Run to verify validator passes**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=ModifierSelectionValidatorTest`
Expected: PASS (3 tests).

- [ ] **Step 4: Write the `OrderViewModel` failing test**

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.DiningApi;
import com.company.pos.terminal.api.dto.*;
import com.company.pos.terminal.order.MenuCache;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OrderViewModelTest {

    private final UUID orderId = UUID.randomUUID();
    private final UUID firedLineId = UUID.randomUUID();

    private MenuCache cache() {
        return new MenuCache(List.of(new ProductView("BURGER", "Burger", "Mains", new BigDecimal("25.00"))));
    }

    private OrderView orderWith(List<OrderLineView> lines) {
        return new OrderView(orderId, UUID.randomUUID(), "DINE_IN", "OPEN", null, lines);
    }

    @Test
    void addLineRefreshesLinesAndSubtotal() {
        OrderLineView added = new OrderLineView(UUID.randomUUID(), "BURGER", new BigDecimal("2"),
                null, "MAIN", null, List.of());
        DiningApi dining = new DiningApi(null) {
            @Override public OrderView order(UUID id) { return orderWith(List.of()); }
            @Override public OrderView addLine(UUID oid, AddLineRequest req) { return orderWith(List.of(added)); }
        };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        vm.addLine("BURGER", new BigDecimal("2"), null, "MAIN", List.of());
        assertEquals(1, vm.lines().size());
        assertTrue(vm.subtotalText().get().contains("50.00"));
    }

    @Test
    void firedLineCannotBeEdited() {
        OrderLineView fired = new OrderLineView(firedLineId, "BURGER", new BigDecimal("1"),
                null, "MAIN", java.time.Instant.now(), List.of());
        DiningApi dining = new DiningApi(null) {
            @Override public OrderView order(UUID id) { return orderWith(List.of(fired)); }
        };
        OrderViewModel vm = new OrderViewModel(dining, cache());
        vm.load(orderId);
        assertFalse(vm.canEdit(fired));
        vm.updateQty(fired, new BigDecimal("5"));
        assertEquals("Fired lines cannot be changed", vm.errorMessage().get());
    }
}
```

- [ ] **Step 5: Run to verify it fails**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=OrderViewModelTest`
Expected: FAIL — `OrderViewModel` missing.

- [ ] **Step 6: Implement `OrderViewModel`**

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.DiningApi;
import com.company.pos.terminal.api.dto.AddLineRequest;
import com.company.pos.terminal.api.dto.OrderLineView;
import com.company.pos.terminal.api.dto.OrderView;
import com.company.pos.terminal.order.MenuCache;
import com.company.pos.terminal.order.SubtotalCalculator;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class OrderViewModel {
    private final DiningApi dining;
    private final MenuCache cache;
    private final ObservableList<OrderLineView> lines = FXCollections.observableArrayList();
    private final StringProperty subtotalText = new SimpleStringProperty("0.00");
    private final StringProperty errorMessage = new SimpleStringProperty("");
    private OrderView order;

    public OrderViewModel(DiningApi dining, MenuCache cache) {
        this.dining = dining;
        this.cache = cache;
    }

    public ObservableList<OrderLineView> lines() { return lines; }
    public ReadOnlyStringProperty subtotalText() { return subtotalText; }
    public ReadOnlyStringProperty errorMessage() { return errorMessage; }
    public UUID orderId() { return order == null ? null : order.id(); }
    public OrderView currentOrder() { return order; }
    public boolean canEdit(OrderLineView line) { return !line.fired(); }

    public void load(UUID orderId) { apply(() -> dining.order(orderId)); }

    public void addLine(String sku, BigDecimal qty, String note, String course, List<UUID> optionIds) {
        apply(() -> dining.addLine(order.id(), new AddLineRequest(sku, qty, note, course, optionIds)));
    }

    public void updateQty(OrderLineView line, BigDecimal qty) {
        if (!canEdit(line)) { errorMessage.set("Fired lines cannot be changed"); return; }
        apply(() -> dining.updateLine(order.id(), line.id(), qty));
    }

    public void removeLine(OrderLineView line) {
        if (!canEdit(line)) { errorMessage.set("Fired lines cannot be changed"); return; }
        apply(() -> dining.removeLine(order.id(), line.id()));
    }

    public void fire() {
        try {
            dining.fire(order.id());
            apply(() -> dining.order(order.id()));
        } catch (ApiException e) {
            errorMessage.set(e.getMessage());
        }
    }

    private void apply(java.util.function.Supplier<OrderView> call) {
        try {
            order = call.get();
            lines.setAll(order.lines() == null ? List.of() : order.lines());
            subtotalText.set(SubtotalCalculator.estimate(order, cache).toPlainString());
            errorMessage.set("");
        } catch (ApiException e) {
            errorMessage.set(e.getMessage());
        }
    }
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=OrderViewModelTest`
Expected: PASS (2 tests).

- [ ] **Step 8: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/order/ModifierSelectionValidator.java \
        pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/OrderViewModel.java \
        pos-terminal/src/test/java/com/company/pos/terminal/order/ModifierSelectionValidatorTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/OrderViewModelTest.java
git commit -m "feat(terminal): OrderViewModel + modifier-selection validation"
```

---

### Task 10: `PaymentViewModel`

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/PaymentViewModel.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/PaymentViewModelTest.java`

**Interfaces:**
- Consumes: `DiningApi`, `CloseOrderRequest`, `TenderInput`, `SaleView`, `ApiException`.
- Produces: `PaymentViewModel(DiningApi dining, UUID orderId, BigDecimal estimatedTotal)` with `ReadOnlyStringProperty changeText()`, `ReadOnlyStringProperty errorMessage()`, `ReadOnlyObjectProperty<SaleView> sale()` (set on success), `void payCash(BigDecimal tendered)` (rejects `tendered < estimatedTotal` with an error, before any API call; else builds a single CASH `TenderInput(amount=estimatedTotal, tendered)`, closes, sets `sale` and `changeText = tendered - estimatedTotal`), `void payCard()` (single CARD `TenderInput(amount=estimatedTotal, tendered=null)`, closes, sets `sale`). Both send an empty `lineDiscounts`, null `transactionDiscount`, `waiveServiceCharge=false`.

Note: the terminal pays the **estimated** total; because the store default has service charge off and this slice never waives, the server's computed grand total equals the estimate for a no-discount close. If Step-1 verification (Task 5) shows service charge could be on for DINE_IN, the plan's follow-up is to first call a quote; for this slice we assume service charge is off (the spec's deferred scope).

- [ ] **Step 1: Write the failing test**

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.DiningApi;
import com.company.pos.terminal.api.dto.CloseOrderRequest;
import com.company.pos.terminal.api.dto.SaleView;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PaymentViewModelTest {

    private final UUID orderId = UUID.randomUUID();

    @Test
    void shortCashTenderRejectedBeforeClose() {
        boolean[] closeCalled = {false};
        DiningApi dining = new DiningApi(null) {
            @Override public SaleView close(UUID id, CloseOrderRequest req) { closeCalled[0] = true; return null; }
        };
        PaymentViewModel vm = new PaymentViewModel(dining, orderId, new BigDecimal("28.75"));
        vm.payCash(new BigDecimal("20.00"));
        assertFalse(closeCalled[0]);
        assertTrue(vm.errorMessage().get().toLowerCase().contains("insufficient"));
        assertNull(vm.sale().get());
    }

    @Test
    void cashPaymentClosesAndComputesChange() {
        SaleView returned = new SaleView(UUID.randomUUID(), "S01-T01-1",
                new BigDecimal("25.00"), new BigDecimal("3.75"), BigDecimal.ZERO,
                new BigDecimal("28.75"), "SAR");
        DiningApi dining = new DiningApi(null) {
            @Override public SaleView close(UUID id, CloseOrderRequest req) { return returned; }
        };
        PaymentViewModel vm = new PaymentViewModel(dining, orderId, new BigDecimal("28.75"));
        vm.payCash(new BigDecimal("30.00"));
        assertNotNull(vm.sale().get());
        assertEquals("S01-T01-1", vm.sale().get().receiptNumber());
        assertTrue(vm.changeText().get().contains("1.25"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=PaymentViewModelTest`
Expected: FAIL — `PaymentViewModel` missing.

- [ ] **Step 3: Implement `PaymentViewModel`**

```java
package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.DiningApi;
import com.company.pos.terminal.api.dto.CloseOrderRequest;
import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.api.dto.TenderInput;
import javafx.beans.property.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PaymentViewModel {
    private final DiningApi dining;
    private final UUID orderId;
    private final BigDecimal estimatedTotal;
    private final StringProperty changeText = new SimpleStringProperty("");
    private final StringProperty errorMessage = new SimpleStringProperty("");
    private final ObjectProperty<SaleView> sale = new SimpleObjectProperty<>(null);

    public PaymentViewModel(DiningApi dining, UUID orderId, BigDecimal estimatedTotal) {
        this.dining = dining;
        this.orderId = orderId;
        this.estimatedTotal = estimatedTotal.setScale(2, RoundingMode.HALF_UP);
    }

    public ReadOnlyStringProperty changeText() { return changeText; }
    public ReadOnlyStringProperty errorMessage() { return errorMessage; }
    public ReadOnlyObjectProperty<SaleView> sale() { return sale; }

    public void payCash(BigDecimal tendered) {
        BigDecimal t = tendered == null ? BigDecimal.ZERO : tendered.setScale(2, RoundingMode.HALF_UP);
        if (t.compareTo(estimatedTotal) < 0) {
            errorMessage.set("Insufficient cash tendered");
            return;
        }
        close(new TenderInput("CASH", estimatedTotal, t));
        if (sale.get() != null) changeText.set(t.subtract(estimatedTotal).toPlainString());
    }

    public void payCard() {
        close(new TenderInput("CARD", estimatedTotal, null));
    }

    private void close(TenderInput tender) {
        errorMessage.set("");
        try {
            CloseOrderRequest req = new CloseOrderRequest(List.of(tender), Map.of(), null, false);
            sale.set(dining.close(orderId, req));
        } catch (ApiException e) {
            errorMessage.set(e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=PaymentViewModelTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/viewmodel/PaymentViewModel.java \
        pos-terminal/src/test/java/com/company/pos/terminal/viewmodel/PaymentViewModelTest.java
git commit -m "feat(terminal): PaymentViewModel (cash change, short-tender guard, card)"
```

---

### Task 11: App shell + `Navigator` + Login screen (FXML + controller)

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/app/PosTerminalApp.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/app/FxTasks.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/LoginController.java`
- Create: `pos-terminal/src/main/resources/fxml/login.fxml`
- Create: `pos-terminal/src/main/resources/css/app.css`

**Interfaces:**
- Consumes: `TerminalConfig`, `ApiClient`, `SessionManager`, all `*Api`, `LoginViewModel`.
- Produces:
  - `Services` — a tiny composition root holding singletons: `config`, `session`, `apiClient`, `authApi`, `productApi`, `menuApi`, `diningApi`, `salesApi`. Constructed once in `PosTerminalApp.init()`.
  - `Navigator` — `Navigator(Stage stage, Services services)`; `void toLogin()`, `void toTableMap()`, `void toOrder(UUID orderId)`, `void toPayment(UUID orderId, BigDecimal estimatedTotal)`. Each loads the FXML, injects the controller's dependencies, sets the scene root.
  - `FxTasks.run(Runnable work, Runnable onDone, java.util.function.Consumer<Throwable> onError)` — runs `work` on a background thread, `onDone`/`onError` on the FX thread.
  - `PosTerminalApp extends Application`.

This task's automated coverage is the already-green ViewModel tests; the deliverable is verified by launching the app and logging in.

- [ ] **Step 1: Implement `Services` (composition root)**

```java
package com.company.pos.terminal.app;

import com.company.pos.terminal.api.*;
import com.company.pos.terminal.config.TerminalConfig;

public final class Services {
    public final TerminalConfig config;
    public final SessionManager session;
    public final ApiClient apiClient;
    public final AuthApi authApi;
    public final ProductApi productApi;
    public final MenuApi menuApi;
    public final DiningApi diningApi;
    public final SalesApi salesApi;

    public Services() {
        this.config = TerminalConfig.load();
        this.session = new SessionManager();
        this.apiClient = new ApiClient(config.serverBaseUrl(), session);
        this.authApi = new AuthApi(apiClient, session);
        this.productApi = new ProductApi(apiClient);
        this.menuApi = new MenuApi(apiClient);
        this.diningApi = new DiningApi(apiClient);
        this.salesApi = new SalesApi(apiClient);
    }
}
```

- [ ] **Step 2: Implement `FxTasks`**

```java
package com.company.pos.terminal.app;

import javafx.application.Platform;
import javafx.concurrent.Task;
import java.util.function.Consumer;

public final class FxTasks {
    private FxTasks() {}

    public static void run(Runnable work, Runnable onDone, Consumer<Throwable> onError) {
        Task<Void> task = new Task<>() {
            @Override protected Void call() { work.run(); return null; }
        };
        task.setOnSucceeded(e -> onDone.run());
        task.setOnFailed(e -> Platform.runLater(() -> onError.accept(task.getException())));
        Thread t = new Thread(task, "api-call");
        t.setDaemon(true);
        t.start();
    }
}
```

- [ ] **Step 3: Implement `Navigator`**

```java
package com.company.pos.terminal.app;

import com.company.pos.terminal.view.LoginController;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;

public class Navigator {
    private final Stage stage;
    private final Services services;

    public Navigator(Stage stage, Services services) {
        this.stage = stage;
        this.services = services;
    }

    public void toLogin() {
        LoginController controller = new LoginController(services, this);
        setScene("/fxml/login.fxml", controller);
    }

    // toTableMap(), toOrder(UUID), toPayment(UUID, BigDecimal) are added in Tasks 12-13.
    public void toTableMap() { throw new UnsupportedOperationException("added in Task 12"); }
    public void toOrder(UUID orderId) { throw new UnsupportedOperationException("added in Task 12"); }
    public void toPayment(UUID orderId, BigDecimal estimatedTotal) { throw new UnsupportedOperationException("added in Task 13"); }

    void setScene(String fxml, Object controller) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxml));
            loader.setController(controller);
            Parent root = loader.load();
            Scene scene = stage.getScene();
            if (scene == null) {
                scene = new Scene(root, 1024, 768);
                scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());
                stage.setScene(scene);
            } else {
                scene.setRoot(root);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load " + fxml, e);
        }
    }
}
```

- [ ] **Step 4: Implement `PosTerminalApp`**

```java
package com.company.pos.terminal.app;

import javafx.application.Application;
import javafx.stage.Stage;

public class PosTerminalApp extends Application {
    private Services services;

    @Override public void init() { services = new Services(); }

    @Override public void start(Stage stage) {
        stage.setTitle("POS Terminal — " + services.config.terminalId());
        Navigator navigator = new Navigator(stage, services);
        navigator.toLogin();
        stage.show();
    }

    public static void main(String[] args) { launch(args); }
}
```

- [ ] **Step 5: Create `login.fxml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?import javafx.scene.layout.*?>
<?import javafx.scene.control.*?>
<?import javafx.geometry.Insets?>
<VBox alignment="CENTER" spacing="12" xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml">
  <padding><Insets top="40" right="40" bottom="40" left="40"/></padding>
  <Label text="POS Terminal" styleClass="title"/>
  <TextField fx:id="usernameField" promptText="Username" maxWidth="280"/>
  <PasswordField fx:id="passwordField" promptText="Password / PIN" maxWidth="280"/>
  <Button fx:id="loginButton" text="Log in" defaultButton="true" maxWidth="280"/>
  <Label fx:id="errorLabel" styleClass="error" wrapText="true" maxWidth="280"/>
</VBox>
```

- [ ] **Step 6: Create `app.css`**

```css
.title { -fx-font-size: 28px; -fx-font-weight: bold; }
.error { -fx-text-fill: #b00020; }
.table-free { -fx-background-color: #e8f5e9; }
.table-occupied { -fx-background-color: #ffebee; }
.menu-button { -fx-min-width: 120px; -fx-min-height: 72px; -fx-font-size: 15px; }
```

- [ ] **Step 7: Implement `LoginController`**

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.LoginViewModel;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

public class LoginController {
    private final Services services;
    private final Navigator navigator;
    private final LoginViewModel vm;

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Button loginButton;
    @FXML private Label errorLabel;

    public LoginController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new LoginViewModel(services.authApi);
    }

    @FXML
    public void initialize() {
        vm.username().bindBidirectional(usernameField.textProperty());
        vm.passwordOrPin().bindBidirectional(passwordField.textProperty());
        errorLabel.textProperty().bind(vm.errorMessage());
        loginButton.disableProperty().bind(vm.busy());
        loginButton.setOnAction(e -> doLogin());
    }

    private void doLogin() {
        FxTasks.run(vm::login, () -> {
            if (vm.loggedIn().get()) navigator.toTableMap();
        }, err -> errorLabel.setText(err.getMessage()));
    }
}
```

- [ ] **Step 8: Build + verify the app launches**

Run: `./mvnw -f pos-terminal/pom.xml -q clean test` (all prior tests still green), then `./mvnw -f pos-terminal/pom.xml javafx:run`.
Expected: a window titled "POS Terminal — T01" showing the login form. (Clicking "Log in" with no backend running shows a "Cannot reach store server" error in `errorLabel` — expected.)

- [ ] **Step 9: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/app/ \
        pos-terminal/src/main/java/com/company/pos/terminal/view/LoginController.java \
        pos-terminal/src/main/resources/fxml/login.fxml \
        pos-terminal/src/main/resources/css/app.css
git commit -m "feat(terminal): app shell, Navigator, FxTasks, login screen"
```

---

### Task 12: Table map screen (FXML + controller + Navigator wiring + polling)

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/TableMapController.java`
- Create: `pos-terminal/src/main/resources/fxml/table-map.fxml`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java` (implement `toTableMap()` and `toOrder(...)` stub → real once Task 13 lands; for now `toOrder` navigates to the order screen created in Task 13, so implement `toTableMap` here and leave `toOrder` throwing until Task 13).

**Interfaces:**
- Consumes: `TableMapViewModel`, `Services`, `Navigator`, `TerminalConfig.pollIntervalSeconds()`.
- Produces: `TableMapController(Services services, Navigator navigator)`; renders `vm.cells()` as a `FlowPane` of buttons (styleClass `table-free`/`table-occupied`); a `javafx.animation.Timeline` polls `vm.refresh()` every `poll.interval` seconds via `FxTasks`; tapping a table calls `vm.openOrResume(cell)` on a background thread, then `navigator.toOrder(orderId)`.

- [ ] **Step 1: Implement `toTableMap()` in `Navigator`**

Replace the `toTableMap()` stub body:

```java
    public void toTableMap() {
        com.company.pos.terminal.view.TableMapController c =
                new com.company.pos.terminal.view.TableMapController(services, this);
        setScene("/fxml/table-map.fxml", c);
    }
```

- [ ] **Step 2: Create `table-map.fxml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?import javafx.scene.layout.*?>
<?import javafx.scene.control.*?>
<?import javafx.geometry.Insets?>
<BorderPane xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml">
  <top>
    <HBox spacing="12" alignment="CENTER_LEFT">
      <padding><Insets top="12" right="12" bottom="12" left="12"/></padding>
      <Label text="Tables" styleClass="title"/>
      <Pane HBox.hgrow="ALWAYS"/>
      <Label fx:id="errorLabel" styleClass="error"/>
      <Button fx:id="refreshButton" text="Refresh"/>
    </HBox>
  </top>
  <center>
    <ScrollPane fitToWidth="true">
      <FlowPane fx:id="tableFlow" hgap="12" vgap="12">
        <padding><Insets top="12" right="12" bottom="12" left="12"/></padding>
      </FlowPane>
    </ScrollPane>
  </center>
</BorderPane>
```

- [ ] **Step 3: Implement `TableMapController`**

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.TableCell;
import com.company.pos.terminal.viewmodel.TableMapViewModel;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.util.Duration;

public class TableMapController {
    private final Services services;
    private final Navigator navigator;
    private final TableMapViewModel vm;
    private Timeline poller;

    @FXML private FlowPane tableFlow;
    @FXML private Label errorLabel;
    @FXML private Button refreshButton;

    public TableMapController(Services services, Navigator navigator) {
        this.services = services;
        this.navigator = navigator;
        this.vm = new TableMapViewModel(services.diningApi);
    }

    @FXML
    public void initialize() {
        errorLabel.textProperty().bind(vm.errorMessage());
        vm.cells().addListener((javafx.collections.ListChangeListener<TableCell>) c -> render());
        refreshButton.setOnAction(e -> refresh());
        refresh();
        int seconds = Math.max(1, services.config.pollIntervalSeconds());
        poller = new Timeline(new KeyFrame(Duration.seconds(seconds), e -> refresh()));
        poller.setCycleCount(Timeline.INDEFINITE);
        poller.play();
    }

    private void refresh() {
        FxTasks.run(vm::refresh, this::render, err -> errorLabel.setText(err.getMessage()));
    }

    private void render() {
        tableFlow.getChildren().clear();
        for (TableCell cell : vm.cells()) {
            Button b = new Button(cell.label() + (cell.occupied() ? "\n(occupied)" : ""));
            b.getStyleClass().add(cell.occupied() ? "table-occupied" : "table-free");
            b.getStyleClass().add("menu-button");
            b.setOnAction(e -> open(cell));
            tableFlow.getChildren().add(b);
        }
    }

    private void open(TableCell cell) {
        java.util.UUID[] holder = new java.util.UUID[1];
        FxTasks.run(() -> holder[0] = vm.openOrResume(cell), () -> {
            if (holder[0] != null) { if (poller != null) poller.stop(); navigator.toOrder(holder[0]); }
        }, err -> errorLabel.setText(err.getMessage()));
    }
}
```

- [ ] **Step 4: Build + verify compilation and existing tests**

Run: `./mvnw -f pos-terminal/pom.xml -q clean test`
Expected: BUILD SUCCESS (all ViewModel/API tests green; new UI classes compile). `navigator.toOrder(...)` still throws `UnsupportedOperationException` — that is wired in Task 13; do not click a table yet in a manual run.

- [ ] **Step 5: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/TableMapController.java \
        pos-terminal/src/main/resources/fxml/table-map.fxml \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java
git commit -m "feat(terminal): table map screen with polling refresh"
```

---

### Task 13: Order screen + modifier picker (FXML + controllers + Navigator wiring)

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/OrderController.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/ModifierPickerDialog.java`
- Create: `pos-terminal/src/main/resources/fxml/order.fxml`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java` (implement `toOrder(UUID)`; keep `toPayment` throwing until Task 14)

**Interfaces:**
- Consumes: `OrderViewModel`, `MenuCache`, `ProductApi`, `MenuApi`, `ModifierSelectionValidator`, `Services`, `Navigator`.
- Produces:
  - `ModifierPickerDialog.pickFor(String sku, List<ModifierGroupView> groups)` → returns `Optional<List<UUID>>` of chosen option ids (empty optional = cancelled); uses a JavaFX `Dialog` with checkboxes/radio per group and validates via `ModifierSelectionValidator` before allowing OK.
  - `OrderController(Services services, Navigator navigator, UUID orderId)`; loads the product catalog once into a `MenuCache`, renders category tabs + product buttons on the right, the order lines + estimated subtotal on the left, "Fire" and "Pay" buttons. Adding a product with modifier groups opens the dialog.

- [ ] **Step 1: Implement `toOrder(UUID)` in `Navigator`**

Replace the `toOrder` stub body:

```java
    public void toOrder(UUID orderId) {
        com.company.pos.terminal.view.OrderController c =
                new com.company.pos.terminal.view.OrderController(services, this, orderId);
        setScene("/fxml/order.fxml", c);
    }
```

- [ ] **Step 2: Create `order.fxml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?import javafx.scene.layout.*?>
<?import javafx.scene.control.*?>
<?import javafx.geometry.Insets?>
<BorderPane xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml">
  <left>
    <VBox spacing="8" prefWidth="360">
      <padding><Insets top="12" right="12" bottom="12" left="12"/></padding>
      <Label text="Order" styleClass="title"/>
      <ListView fx:id="lineList" VBox.vgrow="ALWAYS"/>
      <Label fx:id="subtotalLabel"/>
      <Label fx:id="errorLabel" styleClass="error" wrapText="true"/>
      <HBox spacing="8">
        <Button fx:id="removeButton" text="Remove line"/>
        <Button fx:id="fireButton" text="Fire to kitchen"/>
        <Pane HBox.hgrow="ALWAYS"/>
        <Button fx:id="payButton" text="Pay" defaultButton="true"/>
      </HBox>
      <Button fx:id="backButton" text="Back to tables"/>
    </VBox>
  </left>
  <center>
    <VBox spacing="8">
      <padding><Insets top="12" right="12" bottom="12" left="12"/></padding>
      <TabPane fx:id="categoryTabs" VBox.vgrow="ALWAYS"/>
    </VBox>
  </center>
</BorderPane>
```

- [ ] **Step 3: Implement `ModifierPickerDialog`**

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.ModifierGroupView;
import com.company.pos.terminal.api.dto.ModifierOptionView;
import com.company.pos.terminal.order.ModifierSelectionValidator;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.*;

public final class ModifierPickerDialog {
    private ModifierPickerDialog() {}

    public static Optional<List<UUID>> pickFor(String sku, List<ModifierGroupView> groups) {
        Dialog<List<UUID>> dialog = new Dialog<>();
        dialog.setTitle("Options — " + sku);
        ButtonType ok = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

        Set<UUID> selected = new LinkedHashSet<>();
        VBox box = new VBox(10);
        for (ModifierGroupView g : groups) {
            box.getChildren().add(new Label(g.name()
                    + (g.minSelections() >= 1 ? " (required)" : " (optional)")));
            for (ModifierOptionView opt : g.options()) {
                CheckBox cb = new CheckBox(opt.name()
                        + (opt.priceDelta() != null && opt.priceDelta().signum() != 0 ? " +" + opt.priceDelta() : ""));
                cb.selectedProperty().addListener((o, was, now) -> {
                    if (now) selected.add(opt.id()); else selected.remove(opt.id());
                });
                box.getChildren().add(cb);
            }
        }
        dialog.getDialogPane().setContent(box);

        Button okButton = (Button) dialog.getDialogPane().lookupButton(ok);
        okButton.addEventFilter(javafx.event.ActionEvent.ACTION, evt -> {
            String err = ModifierSelectionValidator.validate(groups, selected);
            if (err != null) { new Alert(Alert.AlertType.WARNING, err).showAndWait(); evt.consume(); }
        });

        dialog.setResultConverter(bt -> bt == ok ? new ArrayList<>(selected) : null);
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }
}
```

- [ ] **Step 4: Implement `OrderController`**

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.*;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.order.MenuCache;
import com.company.pos.terminal.order.SubtotalCalculator;
import com.company.pos.terminal.viewmodel.OrderViewModel;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class OrderController {
    private final Services services;
    private final Navigator navigator;
    private final UUID orderId;
    private OrderViewModel vm;
    private MenuCache cache;

    @FXML private ListView<OrderLineView> lineList;
    @FXML private Label subtotalLabel;
    @FXML private Label errorLabel;
    @FXML private Button removeButton;
    @FXML private Button fireButton;
    @FXML private Button payButton;
    @FXML private Button backButton;
    @FXML private TabPane categoryTabs;

    public OrderController(Services services, Navigator navigator, UUID orderId) {
        this.services = services;
        this.navigator = navigator;
        this.orderId = orderId;
    }

    @FXML
    public void initialize() {
        lineList.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(OrderLineView line, boolean empty) {
                super.updateItem(line, empty);
                setText(empty || line == null ? null
                        : line.qty().stripTrailingZeros().toPlainString() + " × " + line.sku()
                          + (line.fired() ? "  [fired]" : "")
                          + (line.modifiers() == null || line.modifiers().isEmpty() ? ""
                             : "  (" + line.modifiers().size() + " mods)"));
            }
        });
        removeButton.setOnAction(e -> removeSelected());
        fireButton.setOnAction(e -> fire());
        payButton.setOnAction(e -> pay());
        backButton.setOnAction(e -> navigator.toTableMap());

        // Load catalog then the order, off the FX thread.
        FxTasks.run(() -> {
            cache = new MenuCache(services.productApi.list());
        }, this::afterCatalogLoaded, err -> errorLabel.setText(err.getMessage()));
    }

    private void afterCatalogLoaded() {
        vm = new OrderViewModel(services.diningApi, cache);
        subtotalLabel.textProperty().bind(
                javafx.beans.binding.Bindings.concat("Subtotal (est.): ", vm.subtotalText()));
        errorLabel.textProperty().bind(vm.errorMessage());
        vm.lines().addListener((javafx.collections.ListChangeListener<OrderLineView>) c ->
                lineList.getItems().setAll(vm.lines()));
        buildMenu();
        FxTasks.run(() -> vm.load(orderId), () -> lineList.getItems().setAll(vm.lines()),
                err -> errorLabel.setText(err.getMessage()));
    }

    private void buildMenu() {
        categoryTabs.getTabs().clear();
        for (String category : cache.categories()) {
            FlowPane grid = new FlowPane(12, 12);
            for (ProductView p : cache.productsInCategory(category)) {
                Button b = new Button(p.name() + "\n" + p.price().toPlainString());
                b.getStyleClass().add("menu-button");
                b.setOnAction(e -> addProduct(p));
                grid.getChildren().add(b);
            }
            Tab tab = new Tab(category, new ScrollPane(grid));
            tab.setClosable(false);
            categoryTabs.getTabs().add(tab);
        }
    }

    private void addProduct(ProductView p) {
        FxTasks.run(() -> {
            List<ModifierGroupView> groups = services.menuApi.modifierGroupsForSku(p.sku());
            javafx.application.Platform.runLater(() -> addWithGroups(p, groups));
        }, () -> {}, err -> errorLabel.setText(err.getMessage()));
    }

    private void addWithGroups(ProductView p, List<ModifierGroupView> groups) {
        List<UUID> optionIds = List.of();
        if (groups != null && !groups.isEmpty()) {
            Optional<List<UUID>> chosen = ModifierPickerDialog.pickFor(p.sku(), groups);
            if (chosen.isEmpty()) return; // cancelled
            optionIds = chosen.get();
        }
        final List<UUID> ids = optionIds;
        FxTasks.run(() -> vm.addLine(p.sku(), BigDecimal.ONE, null, "MAIN", ids),
                () -> {}, err -> errorLabel.setText(err.getMessage()));
    }

    private void removeSelected() {
        OrderLineView sel = lineList.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        FxTasks.run(() -> vm.removeLine(sel), () -> {}, err -> errorLabel.setText(err.getMessage()));
    }

    private void fire() {
        FxTasks.run(() -> vm.fire(), () -> {}, err -> errorLabel.setText(err.getMessage()));
    }

    private void pay() {
        BigDecimal total = SubtotalCalculator.estimate(vm.currentOrder(), cache);
        navigator.toPayment(orderId, total);
    }
}
```

- [ ] **Step 5: Build + verify compilation and existing tests**

Run: `./mvnw -f pos-terminal/pom.xml -q clean test`
Expected: BUILD SUCCESS. `navigator.toPayment(...)` still throws until Task 14.

- [ ] **Step 6: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/OrderController.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/ModifierPickerDialog.java \
        pos-terminal/src/main/resources/fxml/order.fxml \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java
git commit -m "feat(terminal): order screen (menu grid, lines, modifiers, fire)"
```

---

### Task 14: Payment + confirmation screen + run/seed docs + E2E verification

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/view/PaymentController.java`
- Create: `pos-terminal/src/main/resources/fxml/payment.fxml`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java` (implement `toPayment(UUID, BigDecimal)`)
- Create: `pos-terminal/README.md`

**Interfaces:**
- Consumes: `PaymentViewModel`, `SalesApi`, `Services`, `Navigator`.
- Produces: `PaymentController(Services services, Navigator navigator, UUID orderId, BigDecimal estimatedTotal)`; cash tender field + "Pay cash" and "Pay card" buttons; on success shows the `SaleView` totals + receipt number + change, a "Reprint" button (`salesApi.reprint`), and "Done" → `navigator.toTableMap()`.

- [ ] **Step 1: Implement `toPayment(...)` in `Navigator`**

Replace the `toPayment` stub body:

```java
    public void toPayment(UUID orderId, BigDecimal estimatedTotal) {
        com.company.pos.terminal.view.PaymentController c =
                new com.company.pos.terminal.view.PaymentController(services, this, orderId, estimatedTotal);
        setScene("/fxml/payment.fxml", c);
    }
```

- [ ] **Step 2: Create `payment.fxml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?import javafx.scene.layout.*?>
<?import javafx.scene.control.*?>
<?import javafx.geometry.Insets?>
<VBox spacing="12" alignment="TOP_CENTER" xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml">
  <padding><Insets top="30" right="30" bottom="30" left="30"/></padding>
  <Label text="Payment" styleClass="title"/>
  <Label fx:id="totalLabel"/>
  <HBox spacing="8" alignment="CENTER" maxWidth="360">
    <TextField fx:id="tenderedField" promptText="Cash tendered" HBox.hgrow="ALWAYS"/>
    <Button fx:id="payCashButton" text="Pay cash"/>
    <Button fx:id="payCardButton" text="Pay card"/>
  </HBox>
  <Label fx:id="errorLabel" styleClass="error" wrapText="true"/>
  <Separator/>
  <VBox fx:id="resultBox" spacing="6" alignment="CENTER" visible="false" managed="false">
    <Label fx:id="receiptLabel"/>
    <Label fx:id="changeLabel"/>
    <HBox spacing="8" alignment="CENTER">
      <Button fx:id="reprintButton" text="Reprint"/>
      <Button fx:id="doneButton" text="Done" defaultButton="true"/>
    </HBox>
  </VBox>
  <Button fx:id="cancelButton" text="Back to order"/>
</VBox>
```

- [ ] **Step 3: Implement `PaymentController`**

```java
package com.company.pos.terminal.view;

import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.app.FxTasks;
import com.company.pos.terminal.app.Navigator;
import com.company.pos.terminal.app.Services;
import com.company.pos.terminal.viewmodel.PaymentViewModel;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.UUID;

public class PaymentController {
    private final Services services;
    private final Navigator navigator;
    private final UUID orderId;
    private final PaymentViewModel vm;

    @FXML private Label totalLabel;
    @FXML private TextField tenderedField;
    @FXML private Button payCashButton;
    @FXML private Button payCardButton;
    @FXML private Label errorLabel;
    @FXML private VBox resultBox;
    @FXML private Label receiptLabel;
    @FXML private Label changeLabel;
    @FXML private Button reprintButton;
    @FXML private Button doneButton;
    @FXML private Button cancelButton;

    public PaymentController(Services services, Navigator navigator, UUID orderId, BigDecimal estimatedTotal) {
        this.services = services;
        this.navigator = navigator;
        this.orderId = orderId;
        this.vm = new PaymentViewModel(services.diningApi, orderId, estimatedTotal);
        this.estimatedTotal = estimatedTotal;
    }
    private final BigDecimal estimatedTotal;

    @FXML
    public void initialize() {
        totalLabel.setText("Total (est.): " + estimatedTotal.toPlainString());
        errorLabel.textProperty().bind(vm.errorMessage());
        payCashButton.setOnAction(e -> payCash());
        payCardButton.setOnAction(e -> pay(vm::payCard));
        cancelButton.setOnAction(e -> navigator.toOrder(orderId));
        doneButton.setOnAction(e -> navigator.toTableMap());
        vm.sale().addListener((o, was, now) -> { if (now != null) showResult(now); });
    }

    private void payCash() {
        BigDecimal tendered;
        try { tendered = new BigDecimal(tenderedField.getText().trim()); }
        catch (RuntimeException ex) { errorLabel.setText("Enter a valid cash amount"); return; }
        pay(() -> vm.payCash(tendered));
    }

    private void pay(Runnable action) {
        setBusy(true);
        FxTasks.run(action, () -> setBusy(false), err -> { setBusy(false); errorLabel.setText(err.getMessage()); });
    }

    private void setBusy(boolean b) {
        payCashButton.setDisable(b);
        payCardButton.setDisable(b);
    }

    private void showResult(SaleView sale) {
        receiptLabel.setText("Receipt " + sale.receiptNumber() + "  —  Total " + sale.grandTotal().toPlainString()
                + " " + sale.currencyCode());
        changeLabel.setText(vm.changeText().get().isBlank() ? "" : "Change: " + vm.changeText().get());
        reprintButton.setOnAction(e -> FxTasks.run(() -> services.salesApi.reprint(sale.id()),
                () -> {}, err -> errorLabel.setText(err.getMessage())));
        resultBox.setVisible(true);
        resultBox.setManaged(true);
        payCashButton.setDisable(true);
        payCardButton.setDisable(true);
        tenderedField.setDisable(true);
        cancelButton.setDisable(true);
    }
}
```

- [ ] **Step 4: Create `pos-terminal/README.md` (run + seed recipe)**

````markdown
# POS Terminal (JavaFX)

REST thin client for the restaurant seat-to-payment slice. Talks to a running
store-server backend over HTTP.

## Build & test
```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -f pos-terminal/pom.xml clean test
```

## Run
```bash
./mvnw -f pos-terminal/pom.xml javafx:run \
  -Dserver.base-url=http://localhost:8080 -Dterminal.id=T01 -Dstore.id=S01
```

## End-to-end prerequisites (seed data)
Start the backend (`store-server` or `embedded` profile) and ensure it has:
- at least one **active dining table** (`POST /dining/tables`, MANAGER),
- a few **products across ≥2 categories** (via ERP down-sync `POST /sync/erp`
  against the fake adapter, or existing catalog),
- one product with a **modifier group** (`POST /menu/modifier-groups`,
  `.../options`, `.../assignments`, MANAGER).

Log in as a cashier/server, tap a free table, add items (pick modifiers when
prompted), fire to kitchen, pay cash or card, and confirm the receipt prints
server-side (in-memory `InMemoryPrinter` logs it) and the table returns to free.
````

- [ ] **Step 5: Build + run full test suite**

Run: `./mvnw -f pos-terminal/pom.xml clean test`
Expected: BUILD SUCCESS — all unit tests green (TerminalConfig, ApiClient, Auth/Catalog/Dining API, Subtotal, ModifierSelection, Login/TableMap/Order/Payment ViewModels).

- [ ] **Step 6: Manual E2E against the real backend**

In one shell, start the backend seeded per the README. In another:
`./mvnw -f pos-terminal/pom.xml javafx:run`
Walk the full flow: login → table map → open table → add item (with a modifier) → fire → pay cash → see receipt number + change → Done → table free again.
Expected: each step succeeds; a stopped backend shows "Cannot reach store server".

- [ ] **Step 7: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/view/PaymentController.java \
        pos-terminal/src/main/resources/fxml/payment.fxml \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Navigator.java \
        pos-terminal/README.md
git commit -m "feat(terminal): payment + confirmation screen, run/seed docs, E2E"
```

---

## Self-Review

**1. Spec coverage** — every in-scope spec item maps to a task:
- Separate `pos-terminal` module → Task 1. App shell + navigation → Task 11. Login (password + PIN) → Tasks 3, 7, 11. Table map + open/resume → Tasks 5, 8, 12. Order screen + touch menu grid + add/update/remove + fired-lock → Tasks 4, 6, 9, 13. Modifier picker → Tasks 4, 9, 13. Payment single-bill close (cash + card) + reprint → Tasks 5, 10, 14. Client-side estimated subtotal → Task 6. Errors/session-expiry/unreachable/409 → `ApiException` mapping (Task 2) surfaced in every ViewModel + controller. Live floor via polling → Task 12. Testing (ViewModels + API clients vs stub server) → Tasks 2–10; E2E + seed recipe → Task 14.
- Deferred items (split billing, service-charge waiver UI, shift UI, WebSocket, retail, back-office, packaging, direct hardware) → intentionally absent; noted in `CloseOrderRequest` (empty discounts, `waiveServiceCharge=false`) and Task 10's note.

**2. Placeholder scan** — no "TBD"/"handle errors"/"similar to". The two `UnsupportedOperationException` stubs in `Navigator` are deliberate, called out, and replaced in Tasks 12/13/14 with the exact replacement code shown; nothing else is deferred within a task.

**3. Type consistency** — checked across tasks: `ApiClient.get/post/put(TypeReference)` + `delete` (Task 2) are the signatures every `*Api` calls (Tasks 3–5); `SessionManager.setUser/isManager` (Task 2) used by `AuthApi` (Task 3); `MenuCache(List<ProductView>)`, `basePrice`, `categories`, `productsInCategory` (Task 6) used by `SubtotalCalculator` (Task 6) and `OrderController` (Task 13); `OrderViewModel.load/addLine/updateQty/removeLine/fire/canEdit/currentOrder/subtotalText` (Task 9) used by `OrderController` (Task 13); `PaymentViewModel(dining, orderId, estimatedTotal).payCash/payCard/sale/changeText` (Task 10) used by `PaymentController` (Task 14); `Navigator.toLogin/toTableMap/toOrder/toPayment` (Task 11) implemented incrementally in Tasks 11–14; `DiningApi` method names identical between definition (Task 5) and every consumer. `AddLineRequest` course argument is the string `"MAIN"` consistently.

**Known follow-ups (not blockers, deferred by the spec):** DELETE-line double round-trip (Task 5 note), potential service-charge-on case needing a pre-close quote (Task 10 note), and confirming exact server JSON field names (Step-1 verification steps in Tasks 3–5).
