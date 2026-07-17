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
                .satisfies(ex -> {
                    // The JDK WebSocket client wraps the HTTP 401 response in an ExecutionException.
                    // The message may not contain "401" verbatim — we assert the connection failed.
                    assertThat(ex).isInstanceOf(java.util.concurrent.ExecutionException.class);
                });
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
