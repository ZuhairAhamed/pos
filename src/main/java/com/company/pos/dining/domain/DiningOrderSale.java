package com.company.pos.dining.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Links a dine-in order to a sale produced by a split close (one row per sale). */
@Entity
@Table(name = "dining_order_sale")
public class DiningOrderSale {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "order_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID orderId;

    @Column(name = "sale_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID saleId;

    protected DiningOrderSale() {
        // JPA
    }

    public DiningOrderSale(UUID id, UUID orderId, UUID saleId) {
        this.id = id;
        this.orderId = orderId;
        this.saleId = saleId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getSaleId() {
        return saleId;
    }
}
