package com.company.pos.cart.domain;

import com.company.pos.common.exception.DomainException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "cart")
public class Cart {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "terminal_id", length = 16)
    private String terminalId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "customer_id", length = 36)
    private UUID customerId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<CartLine> lines = new ArrayList<>();

    protected Cart() {
        // JPA
    }

    public Cart(UUID id, String terminalId, Instant createdAt) {
        this.id = id;
        this.terminalId = terminalId;
        this.status = "OPEN";
        this.createdAt = createdAt;
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

    public String getCurrencyCode() {
        return currencyCode;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public void assignCustomer(UUID customerId) {
        if (!isOpen()) {
            throw DomainException.conflict("Only an open cart can have a customer assigned");
        }
        this.customerId = customerId;
    }

    public void clearCustomer() {
        this.customerId = null;
    }

    public List<CartLine> getLines() {
        return lines;
    }

    public Optional<CartLine> findLine(String sku) {
        return lines.stream().filter(l -> l.getSku().equals(sku)).findFirst();
    }

    public void addLine(String sku, String name, BigDecimal quantity, BigDecimal unitPrice, String currency) {
        if (this.currencyCode == null) {
            this.currencyCode = currency;
        }
        findLine(sku).ifPresentOrElse(
                line -> line.addQuantity(quantity),
                () -> lines.add(new CartLine(this, lines.size() + 1, sku, name, quantity, unitPrice, currency)));
    }

    public void setLineQuantity(String sku, BigDecimal quantity) {
        lines.stream().filter(l -> l.getSku().equals(sku)).findFirst()
                .ifPresent(line -> line.setQuantity(quantity));
    }

    public void removeLine(String sku) {
        lines.removeIf(l -> l.getSku().equals(sku));
        renumber();
    }

    public Optional<CartLine> findLineById(UUID lineId) {
        return lines.stream().filter(l -> l.getId().equals(lineId)).findFirst();
    }

    public void setLineQuantityById(UUID lineId, BigDecimal quantity) {
        findLineById(lineId).ifPresent(line -> line.setQuantity(quantity));
    }

    public void removeLineById(UUID lineId) {
        lines.removeIf(l -> l.getId().equals(lineId));
        renumber();
    }

    private void renumber() {
        int lineNo = 1;
        for (CartLine line : lines) {
            line.setLineNo(lineNo++);
        }
    }

    public void hold() {
        if (!isOpen()) {
            throw DomainException.conflict("Only an open cart can be held");
        }
        this.status = "HELD";
    }

    public void resume() {
        if (!isHeld()) {
            throw DomainException.conflict("Only a held cart can be resumed");
        }
        this.status = "OPEN";
    }

    public void close() {
        this.status = "CHECKED_OUT";
    }

    public boolean isOpen() {
        return "OPEN".equals(status);
    }

    public boolean isHeld() {
        return "HELD".equals(status);
    }
}
