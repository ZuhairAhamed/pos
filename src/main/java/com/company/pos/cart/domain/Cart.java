package com.company.pos.cart.domain;

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

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<CartLine> lines = new ArrayList<>();

    protected Cart() {
        // JPA
    }

    public Cart(UUID id, Instant createdAt) {
        this.id = id;
        this.status = "OPEN";
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public String getCurrencyCode() {
        return currencyCode;
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
    }

    public void close() {
        this.status = "CHECKED_OUT";
    }

    public boolean isOpen() {
        return "OPEN".equals(status);
    }
}
