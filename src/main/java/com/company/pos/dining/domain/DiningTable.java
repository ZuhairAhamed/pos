package com.company.pos.dining.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "dining_table")
public class DiningTable {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(nullable = false, length = 60, unique = true)
    private String label;

    @Column(nullable = false)
    private int seats;

    @Column(nullable = false)
    private boolean active = true;

    protected DiningTable() {
        // JPA
    }

    public DiningTable(UUID id, String label, int seats) {
        this.id = id;
        this.label = label;
        this.seats = seats;
    }

    public UUID getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public int getSeats() {
        return seats;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
