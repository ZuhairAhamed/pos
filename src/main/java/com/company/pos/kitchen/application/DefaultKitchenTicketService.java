package com.company.pos.kitchen.application;

import com.company.pos.common.events.DomainEvents;
import com.company.pos.common.exception.DomainException;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.kitchen.api.KitchenTicketChanged;
import com.company.pos.kitchen.api.KitchenTicketLineView;
import com.company.pos.kitchen.api.KitchenTicketState;
import com.company.pos.kitchen.api.KitchenTicketService;
import com.company.pos.kitchen.api.KitchenTicketView;
import com.company.pos.kitchen.domain.KitchenTicket;
import com.company.pos.kitchen.domain.KitchenTicketLine;
import com.company.pos.kitchen.infrastructure.KitchenTicketRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultKitchenTicketService implements KitchenTicketService {

    private static final List<KitchenTicketState> LIVE =
            List.of(KitchenTicketState.FIRED, KitchenTicketState.PREPARING, KitchenTicketState.READY);

    private final KitchenTicketRepository repo;
    private final ConfigurationService config;
    private final DomainEvents events;

    DefaultKitchenTicketService(KitchenTicketRepository repo, ConfigurationService config,
            DomainEvents events) {
        this.repo = repo;
        this.config = config;
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public List<KitchenTicketView> listActiveTickets(Optional<String> station) {
        Instant cutoff = Instant.now().minus(
                Duration.ofSeconds(config.getInt(SettingKey.KITCHEN_TICKET_RECALL_WINDOW_SECONDS)));
        List<KitchenTicket> found = new ArrayList<>(repo.findByStateInOrderByFiredAtAsc(LIVE));
        found.addAll(repo.findByStateAndBumpedAtGreaterThanEqualOrderByFiredAtAsc(
                KitchenTicketState.BUMPED, cutoff));
        return found.stream()
                .filter(t -> station.map(s -> s.equals(t.getStation())).orElse(true))
                .sorted(Comparator.comparing(KitchenTicket::getFiredAt))
                .map(DefaultKitchenTicketService::toView)
                .toList();
    }

    @Override
    public KitchenTicketView advance(UUID ticketId, KitchenTicketState expectedState) {
        KitchenTicket t = load(ticketId);
        t.advance(expectedState, Instant.now());
        events.publish(new KitchenTicketChanged(t.getId(), t.getOrderId(), Instant.now()));
        return toView(t);
    }

    @Override
    public KitchenTicketView recall(UUID ticketId, KitchenTicketState expectedState) {
        KitchenTicket t = load(ticketId);
        t.recall(expectedState);
        events.publish(new KitchenTicketChanged(t.getId(), t.getOrderId(), Instant.now()));
        return toView(t);
    }

    private KitchenTicket load(UUID ticketId) {
        return repo.findById(ticketId)
                .orElseThrow(() -> DomainException.notFound("Kitchen ticket " + ticketId + " not found"));
    }

    static KitchenTicketView toView(KitchenTicket t) {
        List<KitchenTicketLineView> lines = t.getLines().stream()
                .map(DefaultKitchenTicketService::toLineView)
                .toList();
        return new KitchenTicketView(t.getId(), t.getOrderId(), t.getTableLabel(), t.getStation(),
                t.getState(), t.getFiredAt(), t.getPreparingAt(), t.getReadyAt(), t.getBumpedAt(), lines);
    }

    private static KitchenTicketLineView toLineView(KitchenTicketLine l) {
        return new KitchenTicketLineView(l.getSku(), l.getName(), l.getQty(), l.getNote(),
                l.getCourse(), List.copyOf(l.getModifiers()));
    }
}
