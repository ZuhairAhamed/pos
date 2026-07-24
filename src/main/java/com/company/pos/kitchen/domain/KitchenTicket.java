package com.company.pos.kitchen.domain;

import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.kitchen.api.KitchenTicketState;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "kitchen_ticket")
public class KitchenTicket {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "table_label", nullable = false, length = 100)
    private String tableLabel;

    @Column(nullable = false, length = 100)
    private String station;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KitchenTicketState state;

    @Column(name = "fired_at", nullable = false)
    private Instant firedAt;

    @Column(name = "preparing_at")
    private Instant preparingAt;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "bumped_at")
    private Instant bumpedAt;

    @Version
    private long version;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "ticket_id", insertable = false, updatable = false)
    private List<KitchenTicketLine> lines = new ArrayList<>();

    protected KitchenTicket() {
        // JPA
    }

    public KitchenTicket(UUID id, UUID orderId, String tableLabel, String station, Instant firedAt) {
        this.id = id;
        this.orderId = orderId;
        this.tableLabel = tableLabel;
        this.station = station;
        this.state = KitchenTicketState.FIRED;
        this.firedAt = firedAt;
    }

    public KitchenTicketLine addLine(String sku, String name, BigDecimal qty, String note,
            String course, List<String> modifiers) {
        KitchenTicketLine line = new KitchenTicketLine(Identifiers.newId(), id, sku, name, qty,
                note, course, modifiers);
        lines.add(line);
        return line;
    }

    /** Move to the next live state. {@code expected} guards concurrent taps (optimistic). */
    public void advance(KitchenTicketState expected, Instant now) {
        requireState(expected);
        switch (state) {
            case FIRED -> { state = KitchenTicketState.PREPARING; preparingAt = now; }
            case PREPARING -> { state = KitchenTicketState.READY; readyAt = now; }
            case READY -> { state = KitchenTicketState.BUMPED; bumpedAt = now; }
            default -> throw DomainException.conflict("Ticket cannot advance from " + state);
        }
    }

    /** Step back one live state, clearing the timestamp of the state left behind. */
    public void recall(KitchenTicketState expected) {
        requireState(expected);
        switch (state) {
            case BUMPED -> { state = KitchenTicketState.READY; bumpedAt = null; }
            case READY -> { state = KitchenTicketState.PREPARING; readyAt = null; }
            case PREPARING -> { state = KitchenTicketState.FIRED; preparingAt = null; }
            default -> throw DomainException.conflict("Ticket cannot be recalled from " + state);
        }
    }

    /** Kill the ticket (source order voided). Terminal. */
    public void cancel() {
        this.state = KitchenTicketState.CANCELLED;
    }

    private void requireState(KitchenTicketState expected) {
        if (state != expected) {
            throw DomainException.conflict(
                    "Ticket state changed; expected " + expected + " but was " + state);
        }
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public String getTableLabel() { return tableLabel; }
    public String getStation() { return station; }
    public KitchenTicketState getState() { return state; }
    public Instant getFiredAt() { return firedAt; }
    public Instant getPreparingAt() { return preparingAt; }
    public Instant getReadyAt() { return readyAt; }
    public Instant getBumpedAt() { return bumpedAt; }
    public List<KitchenTicketLine> getLines() { return lines; }
}
