package com.company.pos.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "stock_level",
        uniqueConstraints = @UniqueConstraint(name = "uq_stock_sku_location",
                columnNames = { "sku", "location_code" }))
public class StockLevel {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(name = "location_code", nullable = false, length = 32)
    private String locationCode;

    @Column(name = "quantity_on_hand", nullable = false, precision = 19, scale = 3)
    private BigDecimal quantityOnHand = BigDecimal.ZERO;

    @Column(name = "erp_version", nullable = false)
    private long erpVersion;

    protected StockLevel() {
        // JPA
    }

    public StockLevel(UUID id, String sku, String locationCode) {
        this.id = id;
        this.sku = sku;
        this.locationCode = locationCode;
    }

    public UUID getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getLocationCode() {
        return locationCode;
    }

    public BigDecimal getQuantityOnHand() {
        return quantityOnHand;
    }

    public void setQuantityOnHand(BigDecimal quantityOnHand) {
        this.quantityOnHand = quantityOnHand;
    }

    public long getErpVersion() {
        return erpVersion;
    }

    public void setErpVersion(long erpVersion) {
        this.erpVersion = erpVersion;
    }
}
