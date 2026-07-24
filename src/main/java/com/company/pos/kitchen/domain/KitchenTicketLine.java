package com.company.pos.kitchen.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "kitchen_ticket_line")
public class KitchenTicketLine {

    @Id
    private UUID id;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal qty;

    @Column(length = 500)
    private String note;

    @Column(length = 32)
    private String course;

    @ElementCollection
    @CollectionTable(name = "kitchen_ticket_line_modifier",
            joinColumns = @JoinColumn(name = "line_id"))
    @Column(name = "name", length = 200)
    @OrderColumn(name = "ordinal")
    private List<String> modifiers = new ArrayList<>();

    protected KitchenTicketLine() {
        // JPA
    }

    KitchenTicketLine(UUID id, UUID ticketId, String sku, String name, BigDecimal qty,
            String note, String course, List<String> modifiers) {
        this.id = id;
        this.ticketId = ticketId;
        this.sku = sku;
        this.name = name;
        this.qty = qty;
        this.note = note;
        this.course = course;
        this.modifiers = modifiers == null ? new ArrayList<>() : new ArrayList<>(modifiers);
    }

    public UUID getId() { return id; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public BigDecimal getQty() { return qty; }
    public String getNote() { return note; }
    public String getCourse() { return course; }
    public List<String> getModifiers() { return modifiers; }
}
