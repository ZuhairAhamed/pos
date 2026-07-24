package com.company.pos.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.util.Identifiers;
import com.company.pos.kitchen.api.KitchenTicketService;
import com.company.pos.kitchen.api.KitchenTicketState;
import com.company.pos.kitchen.domain.KitchenTicket;
import com.company.pos.kitchen.infrastructure.KitchenTicketRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
class RealtimeKitchenEndToEndTest {

    @LocalServerPort
    int port;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    KitchenTicketService kitchenTicketService;
    @Autowired
    KitchenTicketRepository kitchenTicketRepository;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void seed() {
        users.findByUsername("wspush").ifPresent(users::delete);
        users.save(new User(Identifiers.newId(), "wspush", "WS Push",
                encoder.encode("pw"), Set.of(Role.MANAGER)));
    }

    @Test
    void authenticatedTerminalReceivesAFrameWhenAKitchenTicketChanges() throws Exception {
        String token = login();
        CountDownLatch frame = new CountDownLatch(1);

        WebSocket ws = http.newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/floor"),
                        new WebSocket.Listener() {
                            @Override
                            public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                                if (data.toString().contains("\"topic\":\"KITCHEN\"")) {
                                    frame.countDown();
                                }
                                socket.request(1);
                                return null;
                            }
                        })
                .get(5, TimeUnit.SECONDS);

        // Seed a FIRED ticket directly (no dining chain needed — the trigger is KitchenTicketService.advance).
        KitchenTicket ticket = new KitchenTicket(
                Identifiers.newId(), UUID.randomUUID(), "T1", "Grill",
                Instant.now());
        ticket.addLine("BURGER", "Beef Burger", new BigDecimal("1"), null, "MAIN", List.of());
        kitchenTicketRepository.save(ticket);

        // advance publishes KitchenTicketChanged → KitchenTicketChangedListener → broadcast.
        kitchenTicketService.advance(ticket.getId(), KitchenTicketState.FIRED);

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
        assertThat(resp.statusCode())
                .as("login must succeed before parsing token (got body: %s)", resp.body())
                .isEqualTo(200);
        return mapper.readTree(resp.body()).get("token").asText();
    }
}
