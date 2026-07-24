package com.company.pos.kitchen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.kitchen.api.KitchenTicketState;
import com.company.pos.kitchen.api.KitchenTicketView;
import com.company.pos.kitchen.api.KitchenTicketService;
import com.company.pos.kitchen.domain.KitchenTicket;
import com.company.pos.kitchen.infrastructure.KitchenTicketRepository;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class KitchenTicketServiceTest {

    @Autowired KitchenTicketService service;
    @Autowired KitchenTicketRepository repo;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    private KitchenTicket seedFired(String station) {
        KitchenTicket t = new KitchenTicket(Identifiers.newId(), UUID.randomUUID(), "5", station,
                Instant.parse("2026-07-24T10:00:00Z"));
        t.addLine("BURGER", "Beef Burger", new BigDecimal("2"), "no onion", "MAIN", List.of("Cheese"));
        return repo.save(t);
    }

    @Test
    void listReturnsActiveTicketsFifo() {
        seedFired("Grill");
        List<KitchenTicketView> active = service.listActiveTickets(Optional.empty());
        assertThat(active).hasSize(1);
        assertThat(active.get(0).state()).isEqualTo(KitchenTicketState.FIRED);
        assertThat(active.get(0).lines().get(0).modifiers()).containsExactly("Cheese");
    }

    @Test
    void listFiltersByStation() {
        seedFired("Grill");
        seedFired("Fryer");
        assertThat(service.listActiveTickets(Optional.of("Grill"))).hasSize(1);
        assertThat(service.listActiveTickets(Optional.of("Grill")).get(0).station()).isEqualTo("Grill");
    }

    @Test
    void advanceMovesToPreparing() {
        KitchenTicket t = seedFired("Grill");
        KitchenTicketView view = service.advance(t.getId(), KitchenTicketState.FIRED);
        assertThat(view.state()).isEqualTo(KitchenTicketState.PREPARING);
    }

    @Test
    void advanceWithStaleExpectedStateConflicts() {
        KitchenTicket t = seedFired("Grill");
        service.advance(t.getId(), KitchenTicketState.FIRED);        // now PREPARING
        assertThatThrownBy(() -> service.advance(t.getId(), KitchenTicketState.FIRED))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).errorCode().name()).isEqualTo("CONFLICT"));
    }

    @Test
    void bumpedTicketDropsOutOfActiveList() {
        KitchenTicket t = seedFired("Grill");
        service.advance(t.getId(), KitchenTicketState.FIRED);   // PREPARING
        service.advance(t.getId(), KitchenTicketState.PREPARING); // READY
        service.advance(t.getId(), KitchenTicketState.READY);   // BUMPED (within recall window → still listed)
        assertThat(service.listActiveTickets(Optional.empty())).hasSize(1);
    }
}
