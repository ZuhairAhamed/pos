package com.company.pos.cashdrawer.domain;

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
@Table(name = "drawer_session")
public class DrawerSession {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "terminal_id", nullable = false, length = 16)
    private String terminalId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "opening_float", nullable = false, precision = 19, scale = 2)
    private BigDecimal openingFloat;

    @Column(name = "counted_amount", precision = 19, scale = 2)
    private BigDecimal countedAmount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "opened_by", nullable = false, length = 100)
    private String openedBy;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected DrawerSession() {
        // JPA
    }

    public DrawerSession(UUID id, String terminalId, BigDecimal openingFloat, String currencyCode,
            String openedBy, Instant openedAt) {
        this.id = id;
        this.terminalId = terminalId;
        this.openingFloat = openingFloat;
        this.currencyCode = currencyCode;
        this.openedBy = openedBy;
        this.openedAt = openedAt;
        this.status = "OPEN";
    }

    public void close(BigDecimal countedAmount, Instant closedAt) {
        this.countedAmount = countedAmount;
        this.closedAt = closedAt;
        this.status = "CLOSED";
    }

    public boolean isOpen() {
        return "OPEN".equals(status);
    }

    public UUID getId() {
        return id;
    }

    public String getTerminalId() {
        return terminalId;
    }

    public String getStatus() {
        return status;
    }

    public BigDecimal getOpeningFloat() {
        return openingFloat;
    }

    public BigDecimal getCountedAmount() {
        return countedAmount;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public String getOpenedBy() {
        return openedBy;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }
}
