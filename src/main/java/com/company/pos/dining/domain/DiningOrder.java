package com.company.pos.dining.domain;

import com.company.pos.dining.api.OrderStatus;
import com.company.pos.dining.api.ServiceType;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "dining_order")
public class DiningOrder {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "table_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID tableId;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, length = 20)
    private ServiceType serviceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "opened_by", nullable = false, length = 100)
    private String openedBy;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "sale_id", length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID saleId;

    @Version
    private long version;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id", insertable = false, updatable = false)
    private List<OrderLine> lines = new ArrayList<>();

    protected DiningOrder() {
        // JPA
    }

    public DiningOrder(UUID id, UUID tableId, ServiceType serviceType, String openedBy,
            Instant openedAt) {
        this.id = id;
        this.tableId = tableId;
        this.serviceType = serviceType;
        this.status = OrderStatus.OPEN;
        this.openedBy = openedBy;
        this.openedAt = openedAt;
    }

    public void addLine(OrderLine line) {
        lines.add(line);
    }

    public void removeLine(OrderLine line) {
        lines.remove(line);
    }

    public void close(UUID saleId, Instant when) {
        this.status = OrderStatus.CLOSED;
        this.saleId = saleId;
        this.closedAt = when;
    }

    public void voidOrder() {
        this.status = OrderStatus.VOIDED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTableId() {
        return tableId;
    }

    public ServiceType getServiceType() {
        return serviceType;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getOpenedBy() {
        return openedBy;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public UUID getSaleId() {
        return saleId;
    }

    public List<OrderLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
