package com.company.pos.sales.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sale_number_sequence")
public class SaleNumberSequence {

    @Id
    @Column(length = 64)
    private String id;   // "{storeId}-{terminalId}"

    @Column(name = "next_value", nullable = false)
    private long nextValue;

    protected SaleNumberSequence() {
        // JPA
    }

    public SaleNumberSequence(String id) {
        this.id = id;
        this.nextValue = 1L;
    }

    public long takeNext() {
        long current = nextValue;
        nextValue = current + 1;
        return current;
    }
}
