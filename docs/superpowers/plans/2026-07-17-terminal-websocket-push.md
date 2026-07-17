# Terminal WebSocket Push (Live Floor Map) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Push a lightweight "floor changed" ping from the server to every connected JavaFX terminal over a WebSocket so the dining floor map refreshes immediately, with polling retained as a slow fallback.

**Architecture:** `dining` publishes a new `DiningFloorChanged` fact on every mutating write (through the existing Spring Modulith outbox, after-commit/async). A new `realtime` module subscribes with `@ApplicationModuleListener` and broadcasts a tiny JSON ping to all sessions of a single `/ws/floor` WebSocket. The terminal opens one socket (JDK 21 `HttpClient` WebSocket, JWT on the handshake) and, on any frame, calls its existing `TableMapController.refresh()` — the server stays the sole source of truth.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith 1.2.5, Spring WebSocket, JavaFX 21, JDK `java.net.http` WebSocket client, JUnit 5, AssertJ, Mockito (backend, via `spring-boot-starter-test`).

## Global Constraints

- **JDK 21 required.** Before any Maven command: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"` (system default is 17).
- **Backend build/test:** `./mvnw test -Dtest=<Class>` (embedded SQLite, no Docker). Root reactor.
- **Terminal build/test:** `./mvnw -f pos-terminal/pom.xml clean test` (headless; not in the root reactor — `./mvnw verify` does not touch it).
- **Re-run `ModularityTests` after any backend cross-module change:** `./mvnw test -Dtest=ModularityTests`.
- **Module boundaries are enforced.** A new module declares its `allowedDependencies` in `package-info.java`; may import only another module's `:: api`. Never import another module's `domain`/`infrastructure`.
- **Events for facts, calls for queries.** The ping is an invalidation signal only — carries NO floor state. The terminal re-fetches via existing REST.
- **Side-effect listeners are after-commit, async, own transaction** (`@ApplicationModuleListener`) — a dead socket must never roll back or block a dining write.
- **Terminal FX-threading convention.** The push callback runs on a WebSocket/HttpClient thread; it must NOT touch the scene graph directly — it calls the controller's existing `refresh()`, which routes through `FxTasks.run(...)` (background) and `vm.refresh()` marshals UI via `ui.accept(...)` (`Platform.runLater`). No new observable is written off-thread. Any inter-thread time source is an injected `Supplier<Instant>`, never inline `Instant.now()` (terminal only; backend uses `Instant.now()` freely as it does today).
- **No Flyway migration, no `configuration` setting, no `pos-terminal` Maven change** are needed (JDK WebSocket is built in).
- Commit trailer on every commit:
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

---

## Task 1: Backend — `DiningFloorChanged` fact, published on every dining write

**Files:**
- Create: `src/main/java/com/company/pos/dining/api/DiningFloorChanged.java`
- Create: `src/main/java/com/company/pos/dining/api/FloorChangeType.java`
- Modify: `src/main/java/com/company/pos/dining/application/DefaultDiningService.java`
- Test: `src/test/java/com/company/pos/dining/DiningFloorChangedPublicationTest.java`

**Interfaces:**
- Consumes: `com.company.pos.common.events.DomainEvent` (marker), the existing `DomainEvents events` field on `DefaultDiningService`.
- Produces: `DiningFloorChanged(FloorChangeType change, UUID tableId, UUID orderId, Instant at)` implements `DomainEvent`; `enum FloorChangeType { TABLE_REGISTERED, TABLE_DEACTIVATED, ORDER_OPENED, LINE_ADDED, LINE_UPDATED, LINE_REMOVED, ORDER_FIRED, ORDER_CLOSED, ORDER_SPLIT_CLOSED, ORDER_TRANSFERRED, ORDER_MERGED, ORDER_VOIDED }`. Consumed by Task 2's listener.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/company/pos/dining/DiningFloorChangedPublicationTest.java`:

```java
package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.dining.api.DiningFloorChanged;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.FloorChangeType;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OrderView;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.ServiceType;
import com.company.pos.dining.api.TableView;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@RecordApplicationEvents
@Transactional
class DiningFloorChangedPublicationTest {

    @Autowired
    DiningService dining;
    @Autowired
    ApplicationEvents events;

    @Test
    void registerOpenTransferAndVoidEachPublishAFloorChange() {
        TableView t1 = dining.registerTable(new RegisterTableCommand("WS-1", 4));
        TableView t2 = dining.registerTable(new RegisterTableCommand("WS-2", 4));
        OrderView order = dining.openOrder(new OpenOrderCommand(t1.id(), ServiceType.DINE_IN), "tester");
        dining.transferOrder(order.id(), t2.id());
        dining.voidOrder(order.id(), "test");

        assertThat(changesOf(FloorChangeType.TABLE_REGISTERED)).isEqualTo(2);
        assertThat(changesOf(FloorChangeType.ORDER_OPENED)).isEqualTo(1);
        assertThat(changesOf(FloorChangeType.ORDER_TRANSFERRED)).isEqualTo(1);
        assertThat(changesOf(FloorChangeType.ORDER_VOIDED)).isEqualTo(1);

        // The opened order carries both table and order id on its ping.
        DiningFloorChanged opened = events.stream(DiningFloorChanged.class)
                .filter(e -> e.change() == FloorChangeType.ORDER_OPENED).findFirst().orElseThrow();
        assertThat(opened.tableId()).isEqualTo(t1.id());
        assertThat(opened.orderId()).isEqualTo(order.id());
        assertThat(opened.at()).isNotNull();
    }

    private long changesOf(FloorChangeType type) {
        return events.stream(DiningFloorChanged.class).filter(e -> e.change() == type).count();
    }
}
```

> Confirm `RegisterTableCommand`'s constructor is `(String label, Integer seats)` and `OpenOrderCommand`'s is `(UUID tableId, ServiceType serviceType)` by reading `src/main/java/com/company/pos/dining/api/RegisterTableCommand.java` and `OpenOrderCommand.java`; adapt the two `new …` calls to the real signatures if they differ.

- [ ] **Step 2: Run it to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=DiningFloorChangedPublicationTest`
Expected: FAIL — `DiningFloorChanged` / `FloorChangeType` do not exist (compile error).

- [ ] **Step 3: Create `FloorChangeType`**

`src/main/java/com/company/pos/dining/api/FloorChangeType.java`:

```java
package com.company.pos.dining.api;

/** What kind of dining write triggered a {@link DiningFloorChanged} floor-invalidation ping. */
public enum FloorChangeType {
    TABLE_REGISTERED, TABLE_DEACTIVATED,
    ORDER_OPENED, LINE_ADDED, LINE_UPDATED, LINE_REMOVED, ORDER_FIRED,
    ORDER_CLOSED, ORDER_SPLIT_CLOSED, ORDER_TRANSFERRED, ORDER_MERGED, ORDER_VOIDED
}
```

- [ ] **Step 4: Create `DiningFloorChanged`**

`src/main/java/com/company/pos/dining/api/DiningFloorChanged.java`:

```java
package com.company.pos.dining.api;

import com.company.pos.common.events.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@code dining} after any mutating write, so terminals can invalidate their floor
 * map. Deliberately carries NO floor state — only the change kind and the affected table/order ids
 * (either may be null where not applicable). Delivered through the outbox (after-commit, async);
 * the {@code realtime} module fans it out to connected terminals, which re-fetch the authoritative
 * state via REST. Over-refresh is harmless; under-refresh leaves stale tiles — so we publish on
 * every write rather than trying to predict which writes change a tile.
 */
public record DiningFloorChanged(FloorChangeType change, UUID tableId, UUID orderId, Instant at)
        implements DomainEvent {
}
```

- [ ] **Step 5: Add the publish helper + imports to `DefaultDiningService`**

Add the two imports alongside the existing `com.company.pos.dining.api.*` imports:

```java
import com.company.pos.dining.api.DiningFloorChanged;
import com.company.pos.dining.api.FloorChangeType;
```

Add this private helper (place it next to `load(...)`):

```java
private void publishFloorChanged(FloorChangeType change, UUID tableId, UUID orderId) {
    events.publish(new DiningFloorChanged(change, tableId, orderId, Instant.now()));
}
```

- [ ] **Step 6: Publish from every mutating write**

Apply these edits inside `DefaultDiningService` (add the publish call immediately before each method's `return`, or at the end for void methods):

- `registerTable`: replace `return toTableView(tables.save(table));` with
  ```java
  DiningTable saved = tables.save(table);
  publishFloorChanged(FloorChangeType.TABLE_REGISTERED, saved.getId(), null);
  return toTableView(saved);
  ```
- `deactivateTable`: after `table.setActive(false);` add
  `publishFloorChanged(FloorChangeType.TABLE_DEACTIVATED, tableId, null);`
- `openOrder`: replace `return toOrderView(orders.save(order));` with
  ```java
  DiningOrder saved = orders.save(order);
  publishFloorChanged(FloorChangeType.ORDER_OPENED, saved.getTableId(), saved.getId());
  return toOrderView(saved);
  ```
- `addLine`: after `order.addLine(line);` add
  `publishFloorChanged(FloorChangeType.LINE_ADDED, order.getTableId(), order.getId());`
- `updateLine`: before `return toOrderView(order);` add
  `publishFloorChanged(FloorChangeType.LINE_UPDATED, order.getTableId(), order.getId());`
- `removeLine`: after `order.removeLine(line);` add
  `publishFloorChanged(FloorChangeType.LINE_REMOVED, order.getTableId(), order.getId());`
- `fireOrder`: after `events.publish(new KitchenTicketsFired(...));` add
  `publishFloorChanged(FloorChangeType.ORDER_FIRED, order.getTableId(), order.getId());`
- `closeOrder`: after `order.close(sale.id(), Instant.now());` add
  `publishFloorChanged(FloorChangeType.ORDER_CLOSED, order.getTableId(), order.getId());`
- `closeOrderSplit`: after the `for (SaleView sale : results) { ... }` loop, before `return results;`, add
  `publishFloorChanged(FloorChangeType.ORDER_SPLIT_CLOSED, order.getTableId(), order.getId());`
- `transferOrder`: replace `return toOrderView(orders.save(order));` with
  ```java
  DiningOrder saved = orders.save(order);
  publishFloorChanged(FloorChangeType.ORDER_TRANSFERRED, saved.getTableId(), saved.getId());
  return toOrderView(saved);
  ```
- `mergeOrders`: replace `return toOrderView(orders.save(survivor));` with
  ```java
  DiningOrder saved = orders.save(survivor);
  publishFloorChanged(FloorChangeType.ORDER_MERGED, saved.getTableId(), saved.getId());
  return toOrderView(saved);
  ```
- `voidOrder`: after `order.voidOrder();` add
  `publishFloorChanged(FloorChangeType.ORDER_VOIDED, order.getTableId(), order.getId());`

- [ ] **Step 7: Run the publication test — verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=DiningFloorChangedPublicationTest`
Expected: PASS.

- [ ] **Step 8: Run the dining regression + modularity**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest='com.company.pos.dining.*,ModularityTests'`
Expected: PASS (the new `dining.api` type is a published named interface — no boundary change yet).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/company/pos/dining/api/DiningFloorChanged.java \
        src/main/java/com/company/pos/dining/api/FloorChangeType.java \
        src/main/java/com/company/pos/dining/application/DefaultDiningService.java \
        src/test/java/com/company/pos/dining/DiningFloorChangedPublicationTest.java
git commit -m "feat(dining): publish DiningFloorChanged on every mutating write

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Backend — `realtime` module (WebSocket transport + fan-out listener)

**Files:**
- Modify: `pom.xml` (add `spring-boot-starter-websocket`)
- Create: `src/main/java/com/company/pos/realtime/package-info.java`
- Create: `src/main/java/com/company/pos/realtime/infrastructure/FloorWebSocketHandler.java`
- Create: `src/main/java/com/company/pos/realtime/infrastructure/WebSocketConfig.java`
- Create: `src/main/java/com/company/pos/realtime/application/DiningFloorChangedListener.java`
- Test: `src/test/java/com/company/pos/realtime/FloorWebSocketHandlerTest.java`
- Test: `src/test/java/com/company/pos/realtime/DiningFloorChangedListenerTest.java`

**Interfaces:**
- Consumes: `DiningFloorChanged` / `FloorChangeType` (Task 1), `spring-websocket` (`TextWebSocketHandler`, `WebSocketSession`, `TextMessage`, `WebSocketConfigurer`).
- Produces: `FloorWebSocketHandler` with `void broadcast(String json)`; a `@Component` listener that serialises a ping and calls `broadcast`.

- [ ] **Step 1: Add the WebSocket starter to `pom.xml`**

Insert after the `spring-boot-starter-web` dependency block (lines 37–40):

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-websocket</artifactId>
        </dependency>
```

- [ ] **Step 2: Write the failing handler test**

`src/test/java/com/company/pos/realtime/FloorWebSocketHandlerTest.java`:

```java
package com.company.pos.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.pos.realtime.infrastructure.FloorWebSocketHandler;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class FloorWebSocketHandlerTest {

    @Test
    void broadcastReachesEveryOpenSession() throws Exception {
        FloorWebSocketHandler handler = new FloorWebSocketHandler();
        WebSocketSession a = openSession();
        WebSocketSession b = openSession();
        handler.afterConnectionEstablished(a);
        handler.afterConnectionEstablished(b);

        handler.broadcast("{\"type\":\"FLOOR_CHANGED\"}");

        verify(a).sendMessage(any(TextMessage.class));
        verify(b).sendMessage(any(TextMessage.class));
    }

    @Test
    void aFailingSessionDoesNotStopTheOthers() throws Exception {
        FloorWebSocketHandler handler = new FloorWebSocketHandler();
        WebSocketSession bad = openSession();
        WebSocketSession good = openSession();
        doThrow(new IOException("dead")).when(bad).sendMessage(any(TextMessage.class));
        handler.afterConnectionEstablished(bad);
        handler.afterConnectionEstablished(good);

        handler.broadcast("ping");

        verify(good).sendMessage(any(TextMessage.class));
    }

    @Test
    void closedSessionIsRemovedAndNoLongerReceives() throws Exception {
        FloorWebSocketHandler handler = new FloorWebSocketHandler();
        WebSocketSession s = openSession();
        handler.afterConnectionEstablished(s);
        handler.afterConnectionClosed(s, CloseStatus.NORMAL);

        handler.broadcast("ping");

        verify(s, never()).sendMessage(any(TextMessage.class));
    }

    private WebSocketSession openSession() {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.isOpen()).thenReturn(true);
        return s;
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=FloorWebSocketHandlerTest`
Expected: FAIL — `FloorWebSocketHandler` does not exist.

- [ ] **Step 4: Create the module `package-info.java`**

`src/main/java/com/company/pos/realtime/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "dining :: api" })
package com.company.pos.realtime;
```

- [ ] **Step 5: Create `FloorWebSocketHandler`**

`src/main/java/com/company/pos/realtime/infrastructure/FloorWebSocketHandler.java`:

```java
package com.company.pos.realtime.infrastructure;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The single {@code /ws/floor} push channel. Holds every connected terminal's session and
 * fans a floor-invalidation ping out to all of them. Push-only: inbound frames are ignored.
 * A session that is closed or throws on send is dropped, so one dead terminal never blocks the
 * rest.
 */
@Component
public class FloorWebSocketHandler extends TextWebSocketHandler {

    private static final System.Logger LOG = System.getLogger(FloorWebSocketHandler.class.getName());

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    /** Sends {@code json} to every open session; drops any that is closed or fails. */
    public void broadcast(String json) {
        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }
            try {
                session.sendMessage(message);
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "Dropping unreachable floor session", e);
                sessions.remove(session);
            }
        }
    }
}
```

- [ ] **Step 6: Run the handler test — verify it passes**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=FloorWebSocketHandlerTest`
Expected: PASS.

- [ ] **Step 7: Create `WebSocketConfig`**

`src/main/java/com/company/pos/realtime/infrastructure/WebSocketConfig.java`:

```java
package com.company.pos.realtime.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the floor push channel at {@code /ws/floor}. The handshake is a plain HTTP GET, so it
 * is authenticated by the existing OAuth2 resource-server JWT filter (SecurityConfig authenticates
 * every non-/auth request). Origins are unrestricted — terminals are LAN native apps, not browsers.
 */
@Configuration
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {

    private final FloorWebSocketHandler handler;

    WebSocketConfig(FloorWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/floor").setAllowedOriginPatterns("*");
    }
}
```

- [ ] **Step 8: Write the failing listener test**

`src/test/java/com/company/pos/realtime/DiningFloorChangedListenerTest.java`:

```java
package com.company.pos.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.company.pos.dining.api.DiningFloorChanged;
import com.company.pos.dining.api.FloorChangeType;
import com.company.pos.realtime.application.DiningFloorChangedListener;
import com.company.pos.realtime.infrastructure.FloorWebSocketHandler;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DiningFloorChangedListenerTest {

    @Test
    void broadcastsANonEmptyPingCarryingTheChangeType() {
        FloorWebSocketHandler handler = mock(FloorWebSocketHandler.class);
        DiningFloorChangedListener listener = new DiningFloorChangedListener(handler);

        listener.on(new DiningFloorChanged(FloorChangeType.ORDER_OPENED,
                UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-07-17T10:00:00Z")));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(handler).broadcast(json.capture());
        assertThat(json.getValue()).contains("FLOOR_CHANGED").contains("ORDER_OPENED");
    }
}
```

- [ ] **Step 9: Run it to verify it fails**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=DiningFloorChangedListenerTest`
Expected: FAIL — `DiningFloorChangedListener` does not exist.

- [ ] **Step 10: Create `DiningFloorChangedListener`**

`src/main/java/com/company/pos/realtime/application/DiningFloorChangedListener.java`:

```java
package com.company.pos.realtime.application;

import com.company.pos.dining.api.DiningFloorChanged;
import com.company.pos.realtime.infrastructure.FloorWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Fans a {@link DiningFloorChanged} fact out to every connected terminal (after-commit, async, own
 * tx — a dead socket never rolls back or blocks a dining write). The ping is intentionally tiny:
 * the terminal ignores the body and simply re-fetches the authoritative floor state over REST. The
 * fields are for logs and future filtering. At-least-once outbox replay may re-send a ping; a
 * duplicate re-fetch is harmless.
 */
@Component
public class DiningFloorChangedListener {

    private final FloorWebSocketHandler handler;
    private final ObjectMapper mapper = new ObjectMapper();

    public DiningFloorChangedListener(FloorWebSocketHandler handler) {
        this.handler = handler;
    }

    @ApplicationModuleListener
    public void on(DiningFloorChanged event) {
        ObjectNode ping = mapper.createObjectNode();
        ping.put("type", "FLOOR_CHANGED");
        ping.put("change", event.change().name());
        if (event.at() != null) {
            ping.put("at", event.at().toString());
        }
        handler.broadcast(ping.toString());
    }
}
```

- [ ] **Step 11: Run both realtime unit tests — verify they pass**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest='FloorWebSocketHandlerTest,DiningFloorChangedListenerTest'`
Expected: PASS.

- [ ] **Step 12: Run modularity + a broad boot smoke**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest='ModularityTests,DiningFloorChangedPublicationTest'`
Expected: PASS. `ModularityTests` accepts the new `realtime` module (declares `{ common, dining :: api }`, and nothing depends back on it).

> If `ModularityTests` complains the listener/handler reaches outside its allowed deps, re-read the failure: the only cross-module import is `dining.api.DiningFloorChanged` (allowed) and `common` (open). Do NOT widen `allowedDependencies` beyond `{ "common", "dining :: api" }`.

- [ ] **Step 13: Commit**

```bash
git add pom.xml src/main/java/com/company/pos/realtime src/test/java/com/company/pos/realtime
git commit -m "feat(realtime): WebSocket floor channel + DiningFloorChanged fan-out listener

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Backend — WebSocket end-to-end test (handshake auth + live delivery)

**Files:**
- Test: `src/test/java/com/company/pos/realtime/RealtimeFloorEndToEndTest.java`

**Interfaces:**
- Consumes: real HTTP server on a random port, `POST /auth/login`, autowired `DiningService`, JDK `HttpClient` WebSocket client.

- [ ] **Step 1: Write the end-to-end test**

`src/test/java/com/company/pos/realtime/RealtimeFloorEndToEndTest.java`:

```java
package com.company.pos.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.util.Identifiers;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.DiningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("embedded")
class RealtimeFloorEndToEndTest {

    @LocalServerPort
    int port;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    DiningService dining;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void seed() {
        users.findByUsername("wspush").ifPresent(users::delete);
        users.save(new User(Identifiers.newId(), "wspush", "WS Push",
                encoder.encode("pw"), Set.of(Role.MANAGER)));
    }

    @Test
    void handshakeWithoutTokenIsRejected() {
        assertThatThrownBy(() ->
                http.newWebSocketBuilder()
                        .buildAsync(URI.create("ws://localhost:" + port + "/ws/floor"),
                                new WebSocket.Listener() {})
                        .get(5, TimeUnit.SECONDS))
                .hasMessageContaining("401");
    }

    @Test
    void authenticatedTerminalReceivesAFrameWhenTheFloorChanges() throws Exception {
        String token = login();
        CountDownLatch frame = new CountDownLatch(1);

        WebSocket ws = http.newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/floor"),
                        new WebSocket.Listener() {
                            @Override
                            public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                                if (data.toString().contains("FLOOR_CHANGED")) {
                                    frame.countDown();
                                }
                                socket.request(1);
                                return null;
                            }
                        })
                .get(5, TimeUnit.SECONDS);

        // A dining write → DiningFloorChanged → after-commit fan-out → the socket receives a ping.
        dining.registerTable(new RegisterTableCommand("WS-E2E-1", 4));

        assertThat(frame.await(10, TimeUnit.SECONDS)).isTrue();
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    private String login() throws Exception {
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"username\":\"wspush\",\"password\":\"pw\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(resp.body()).get("token").asText();
    }
}
```

> Two anchors to confirm by reading the code, adapting if they differ (do NOT guess):
> - `UserRepository.findByUsername(String)` and `User(UUID, String, String, String, Set<Role>)` — mirror the exact usage in `src/test/java/com/company/pos/CashSaleEndToEndTest.java` (it seeds users the same way).
> - The login JSON field for the token is `token` (per `CashSaleEndToEndTest.login`).
> If the unauthenticated handshake surfaces the rejection as a different message than `"401"`, assert on `WebSocket` handshake failure instead (e.g. `.isInstanceOf(java.util.concurrent.ExecutionException.class)`), keeping the intent: no token ⇒ no connection.

- [ ] **Step 2: Run the end-to-end test**

Run: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test -Dtest=RealtimeFloorEndToEndTest`
Expected: PASS — unauthenticated handshake rejected; authenticated socket receives a `FLOOR_CHANGED` frame after the write.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/company/pos/realtime/RealtimeFloorEndToEndTest.java
git commit -m "test(realtime): end-to-end WebSocket handshake auth + live floor push

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Terminal — consume the push (RealtimeClient + config + floor-map wiring)

**Files:**
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/RealtimeClient.java`
- Create: `pos-terminal/src/main/java/com/company/pos/terminal/api/WebSocketRealtimeClient.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/config/TerminalConfig.java`
- Modify: `pos-terminal/src/main/resources/pos-terminal.properties`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`
- Modify: `pos-terminal/src/main/java/com/company/pos/terminal/view/TableMapController.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/api/WebSocketRealtimeClientTest.java`
- Test: `pos-terminal/src/test/java/com/company/pos/terminal/config/TerminalConfigTest.java`

**Interfaces:**
- Consumes: `SessionManager.token()`, `TerminalConfig.serverBaseUrl()`.
- Produces: `RealtimeClient { void connect(Runnable onMessage); void close(); }`; `WebSocketRealtimeClient` (real impl) with static `wsUri(String httpBaseUrl, String path)` and package-visible `FrameListener`; `Services.realtimeClient`; `TerminalConfig.realtimeEnabled()` / `realtimePath()`.

- [ ] **Step 1: Write the failing `WebSocketRealtimeClient` unit test**

`pos-terminal/src/test/java/com/company/pos/terminal/api/WebSocketRealtimeClientTest.java`:

```java
package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WebSocketRealtimeClientTest {

    @Test
    void wsUriMapsHttpToWsAndAppendsPath() {
        assertEquals("ws://localhost:8080/ws/floor",
                WebSocketRealtimeClient.wsUri("http://localhost:8080", "/ws/floor"));
    }

    @Test
    void wsUriMapsHttpsToWssAndTrimsTrailingSlash() {
        assertEquals("wss://store.example.com/ws/floor",
                WebSocketRealtimeClient.wsUri("https://store.example.com/", "/ws/floor"));
    }

    @Test
    void onTextRunsCallbackOnceAndRequestsAnotherFrame() {
        AtomicInteger fired = new AtomicInteger();
        WebSocketRealtimeClient.FrameListener listener =
                new WebSocketRealtimeClient.FrameListener(fired::incrementAndGet, null);
        CountingWebSocket socket = new CountingWebSocket();

        listener.onText(socket, "{\"type\":\"FLOOR_CHANGED\"}", true);

        assertEquals(1, fired.get());
        assertEquals(1, socket.requested);
    }

    /** Minimal no-op {@link WebSocket} that counts request(n) calls. */
    private static final class CountingWebSocket implements WebSocket {
        int requested;
        @Override public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) { return null; }
        @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) { return null; }
        @Override public CompletableFuture<WebSocket> sendPing(ByteBuffer message) { return null; }
        @Override public CompletableFuture<WebSocket> sendPong(ByteBuffer message) { return null; }
        @Override public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) { return null; }
        @Override public void request(long n) { requested += (int) n; }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isOutputClosed() { return false; }
        @Override public boolean isInputClosed() { return false; }
        @Override public void abort() { }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=WebSocketRealtimeClientTest`
Expected: FAIL — `WebSocketRealtimeClient` does not exist.
(Set `JAVA_HOME` first as in Global Constraints.)

- [ ] **Step 3: Create the `RealtimeClient` interface**

`pos-terminal/src/main/java/com/company/pos/terminal/api/RealtimeClient.java`:

```java
package com.company.pos.terminal.api;

/**
 * A server→terminal push channel. {@link #connect} opens it and invokes {@code onMessage} on every
 * frame (on a background/transport thread — callers must marshal any UI work themselves). An
 * abstraction so the floor screen can depend on it and tests can inject a fake (a real socket
 * cannot be stood up in the terminal's headless test scope).
 */
public interface RealtimeClient {
    void connect(Runnable onMessage);
    void close();
}
```

- [ ] **Step 4: Create `WebSocketRealtimeClient`**

`pos-terminal/src/main/java/com/company/pos/terminal/api/WebSocketRealtimeClient.java`:

```java
package com.company.pos.terminal.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link RealtimeClient} over a JDK 21 {@link WebSocket}. Attaches the cashier's bearer token to
 * the handshake (re-read on every reconnect), routes each text frame to the {@code onMessage}
 * callback, and reconnects with a fixed backoff after a drop/error so a backend restart re-arms
 * push. The floor screen's fallback poller bridges any gap while disconnected.
 *
 * <p>Deliberately thin: only {@link #wsUri} and {@link FrameListener} carry logic and are unit
 * tested; the socket round-trip is exercised by the backend end-to-end test and manual E2E (no
 * WebSocket server exists in the terminal's headless test scope).
 */
public final class WebSocketRealtimeClient implements RealtimeClient {

    private static final System.Logger LOG = System.getLogger(WebSocketRealtimeClient.class.getName());
    private static final int RECONNECT_SECONDS = 5;

    private final String url;
    private final SessionManager session;
    private final HttpClient http;
    private final ScheduledExecutorService reconnect =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-reconnect");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile WebSocket socket;
    private volatile Runnable onMessage;

    public WebSocketRealtimeClient(String httpBaseUrl, String path, SessionManager session) {
        this(wsUri(httpBaseUrl, path), session,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    WebSocketRealtimeClient(String wsUrl, SessionManager session, HttpClient http) {
        this.url = wsUrl;
        this.session = session;
        this.http = http;
    }

    /** Maps an http(s) base URL to a ws(s) URL and appends {@code path} (trailing slash safe). */
    static String wsUri(String httpBaseUrl, String path) {
        String base = httpBaseUrl.endsWith("/")
                ? httpBaseUrl.substring(0, httpBaseUrl.length() - 1) : httpBaseUrl;
        if (base.startsWith("https://")) {
            base = "wss://" + base.substring("https://".length());
        } else if (base.startsWith("http://")) {
            base = "ws://" + base.substring("http://".length());
        }
        return base + path;
    }

    @Override
    public void connect(Runnable onMessage) {
        this.onMessage = onMessage;
        openSocket();
    }

    private void openSocket() {
        if (closed.get()) {
            return;
        }
        WebSocket.Builder builder = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5));
        String token = session.token();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        builder.buildAsync(URI.create(url), new FrameListener(onMessage, this))
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        LOG.log(System.Logger.Level.DEBUG, "Floor socket connect failed; retrying", err);
                        scheduleReconnect();
                    } else {
                        this.socket = ws;
                    }
                });
    }

    void scheduleReconnect() {
        if (closed.get()) {
            return;
        }
        try {
            reconnect.schedule(this::openSocket, RECONNECT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // close() shut the scheduler down between the guard and the schedule — nothing to do.
        }
    }

    @Override
    public void close() {
        closed.set(true);
        WebSocket s = socket;
        if (s != null) {
            s.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
        reconnect.shutdownNow();
    }

    /** Package-visible for unit testing: routes each text frame to the callback, then asks for one
     *  more. Reconnects via {@code owner} on close/error ({@code owner} may be null in tests). */
    static final class FrameListener implements WebSocket.Listener {
        private final Runnable onMessage;
        private final WebSocketRealtimeClient owner;

        FrameListener(Runnable onMessage, WebSocketRealtimeClient owner) {
            this.onMessage = onMessage;
            this.owner = owner;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            onMessage.run();
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (owner != null) {
                owner.scheduleReconnect();
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (owner != null) {
                owner.scheduleReconnect();
            }
            return null;
        }
    }
}
```

- [ ] **Step 5: Run the client test — verify it passes**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=WebSocketRealtimeClientTest`
Expected: PASS.

- [ ] **Step 6: Write the failing `TerminalConfig` test**

`pos-terminal/src/test/java/com/company/pos/terminal/config/TerminalConfigTest.java`:

```java
package com.company.pos.terminal.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class TerminalConfigTest {

    @Test
    void realtimeDefaultsOnWithFloorPathAndForty5sFallbackPoll() {
        TerminalConfig c = TerminalConfig.from(new Properties());
        assertTrue(c.realtimeEnabled());
        assertEquals("/ws/floor", c.realtimePath());
        assertEquals(45, c.pollIntervalSeconds());
    }

    @Test
    void realtimeCanBeDisabledAndPathOverridden() {
        Properties p = new Properties();
        p.setProperty("realtime.enabled", "false");
        p.setProperty("realtime.path", "/ws/custom");
        TerminalConfig c = TerminalConfig.from(p);
        assertFalse(c.realtimeEnabled());
        assertEquals("/ws/custom", c.realtimePath());
    }
}
```

- [ ] **Step 7: Run it to verify it fails**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=TerminalConfigTest`
Expected: FAIL — `realtimeEnabled()` / `realtimePath()` do not exist, and the default poll is 5.

- [ ] **Step 8: Extend `TerminalConfig`**

In `pos-terminal/src/main/java/com/company/pos/terminal/config/TerminalConfig.java`:

Add fields:

```java
    private final boolean realtimeEnabled;
    private final String realtimePath;
```

In the constructor, change the poll default and parse the two new keys:

```java
        String rawInterval = p.getProperty("poll.interval.seconds", "45");
```

```java
        this.realtimeEnabled = Boolean.parseBoolean(p.getProperty("realtime.enabled", "true"));
        this.realtimePath = p.getProperty("realtime.path", "/ws/floor");
```

Add the two keys to the system-property overlay array in `load()`:

```java
        for (String key : new String[]{"server.base-url", "terminal.id", "store.id", "poll.interval.seconds", "ui.reduced-motion", "dining.takeaway.label-prefix", "dining.dwell.attention.minutes", "realtime.enabled", "realtime.path"}) {
```

Add the accessors:

```java
    public boolean realtimeEnabled() { return realtimeEnabled; }
    public String realtimePath() { return realtimePath; }
```

- [ ] **Step 9: Update `pos-terminal.properties`**

In `pos-terminal/src/main/resources/pos-terminal.properties`, change the poll line and add the two keys:

```properties
# Fallback refresh cadence for the floor map. Live updates arrive via the /ws/floor WebSocket;
# this slow poll only self-heals a dropped socket and keeps the dwell timer ticking.
poll.interval.seconds=45
# Server→terminal live push. When false, the floor map relies on the fallback poll alone.
realtime.enabled=true
realtime.path=/ws/floor
```

- [ ] **Step 10: Run the config test — verify it passes**

Run: `./mvnw -f pos-terminal/pom.xml test -Dtest=TerminalConfigTest`
Expected: PASS.

- [ ] **Step 11: Wire the client into `Services`**

In `pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java`, add the import, the field, and its construction (after `diningApi` is built):

```java
import com.company.pos.terminal.api.RealtimeClient;
import com.company.pos.terminal.api.WebSocketRealtimeClient;
```

```java
    public final RealtimeClient realtimeClient;
```

```java
        this.realtimeClient = new WebSocketRealtimeClient(
                config.serverBaseUrl(), config.realtimePath(), session);
```

- [ ] **Step 12: Wire the floor map to connect on enter, close on leave**

In `pos-terminal/src/main/java/com/company/pos/terminal/view/TableMapController.java`:

At the end of `initialize()` (after the poller is started), connect push when enabled:

```java
        if (services.config.realtimeEnabled()) {
            services.realtimeClient.connect(this::refresh);
        }
```

In `onLeave()`, close the client alongside stopping the poller:

```java
    @Override
    public void onLeave() {
        stopPolling();
        services.realtimeClient.close();
    }
```

> `refresh()` already runs `vm.refresh()` off the FX thread via `FxTasks` and marshals UI mutations through `Platform.runLater` — so invoking it from the WebSocket callback thread is safe and needs no extra guarding.

- [ ] **Step 13: Run the full terminal suite**

Run: `./mvnw -f pos-terminal/pom.xml clean test`
Expected: PASS — new `WebSocketRealtimeClientTest` + `TerminalConfigTest` green; existing `TableMapViewModelTest` (incl. the deferred-dispatcher regression) still green.

- [ ] **Step 14: Commit**

```bash
git add pos-terminal/src/main/java/com/company/pos/terminal/api/RealtimeClient.java \
        pos-terminal/src/main/java/com/company/pos/terminal/api/WebSocketRealtimeClient.java \
        pos-terminal/src/main/java/com/company/pos/terminal/config/TerminalConfig.java \
        pos-terminal/src/main/resources/pos-terminal.properties \
        pos-terminal/src/main/java/com/company/pos/terminal/app/Services.java \
        pos-terminal/src/main/java/com/company/pos/terminal/view/TableMapController.java \
        pos-terminal/src/test/java/com/company/pos/terminal/api/WebSocketRealtimeClientTest.java \
        pos-terminal/src/test/java/com/company/pos/terminal/config/TerminalConfigTest.java
git commit -m "feat(terminal): live floor map via /ws/floor WebSocket, poll as fallback

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Final verification (after all tasks)

- [ ] Backend: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)" && ./mvnw test` (full suite incl. `ModularityTests`).
- [ ] Terminal: `./mvnw -f pos-terminal/pom.xml clean test`.
- [ ] Manual E2E (documented, not automated): start backend `--spring.profiles.active=embedded,dev`; launch two terminals (`./mvnw -f pos-terminal/pom.xml javafx:run`), log in `manager`/`manager`, both on the floor map. Open/transfer/void a table on terminal A → terminal B's tile updates within ~1s (no 45s wait). Kill + restart the backend → terminals reconnect within a few seconds and the 45s poll bridges the gap meanwhile.

## Self-review notes (author)

- **Spec coverage:** transport = raw WebSocket (Task 2/4); payload = invalidation ping, no floor state (Task 1 event has no DTOs; Task 2 ping is `{type,change,at}`); polling retained at 45s fallback (Task 4); scope = floor map only (Task 4 touches only `TableMapController`). Security = handshake through existing JWT filter (Task 2 config comment; Task 3 proves it). Module boundary = `realtime {common, dining::api}` (Task 2).
- **Type consistency:** `DiningFloorChanged(FloorChangeType, UUID, UUID, Instant)` and `FloorChangeType` enum names are used identically in Tasks 1–3; `wsUri`/`FrameListener` signatures match between the impl and its test in Task 4; `realtimeEnabled()`/`realtimePath()` match between `TerminalConfig`, `Services`, `TableMapController`, and the config test.
- **Confirm-before-coding anchors (flagged in-task, not placeholders):** `RegisterTableCommand`/`OpenOrderCommand` constructor shapes (Task 1); `UserRepository.findByUsername` + `User(...)` ctor + login token field (Task 3, mirror `CashSaleEndToEndTest`); the exact rejection message for an unauthenticated handshake (Task 3).
