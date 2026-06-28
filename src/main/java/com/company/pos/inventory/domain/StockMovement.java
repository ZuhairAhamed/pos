package com.company.pos.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "stock_movement")
public class StockMovement {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(name = "location_code", nullable = false, length = 32)
    private String locationCode;

    @Column(name = "quantity_delta", nullable = false, precision = 19, scale = 3)
    private BigDecimal quantityDelta;

    @Column(nullable = false, length = 24)
    private String reason;

    @Column(name = "reference_id", length = 36)
    private String referenceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StockMovement() {
        // JPA
    }

    public StockMovement(UUID id, String sku, String locationCode, BigDecimal quantityDelta,
            String reason, String referenceId, Instant createdAt) {
        this.id = id;
        this.sku = sku;
        this.locationCode = locationCode;
        this.quantityDelta = quantityDelta;
        this.reason = reason;
        this.referenceId = referenceId;
        this.createdAt = createdAt;
    }

    public String getSku() {
        return sku;
    }

    public BigDecimal getQuantityDelta() {
        return quantityDelta;
    }

    public String getReason() {
        return reason;
    }
}
