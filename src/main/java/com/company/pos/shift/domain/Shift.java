package com.company.pos.shift.domain;

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
@Table(name = "shift")
public class Shift {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "terminal_id", nullable = false, length = 16)
    private String terminalId;

    @Column(name = "opened_by", nullable = false, length = 100)
    private String openedBy;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "drawer_session_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID drawerSessionId;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "counted_cash", precision = 19, scale = 2)
    private BigDecimal countedCash;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected Shift() {
        // JPA
    }

    public Shift(UUID id, String terminalId, String openedBy, UUID drawerSessionId,
            String currencyCode, Instant openedAt) {
        this.id = id;
        this.terminalId = terminalId;
        this.openedBy = openedBy;
        this.drawerSessionId = drawerSessionId;
        this.currencyCode = currencyCode;
        this.openedAt = openedAt;
        this.status = "OPEN";
    }

    public void close(BigDecimal countedCash, Instant closedAt) {
        this.countedCash = countedCash;
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

    public String getOpenedBy() {
        return openedBy;
    }

    public String getStatus() {
        return status;
    }

    public UUID getDrawerSessionId() {
        return drawerSessionId;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
