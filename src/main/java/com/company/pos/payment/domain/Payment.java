package com.company.pos.payment.domain;

import com.company.pos.payment.api.PaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "sale_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID saleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentMethod method;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "amount_tendered", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountTendered;

    @Column(name = "change_due", nullable = false, precision = 19, scale = 2)
    private BigDecimal changeDue;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Payment() {
        // JPA
    }

    public Payment(UUID id, UUID saleId, PaymentMethod method, BigDecimal amount,
            BigDecimal amountTendered, BigDecimal changeDue, String currencyCode, Instant createdAt) {
        this.id = id;
        this.saleId = saleId;
        this.method = method;
        this.amount = amount;
        this.amountTendered = amountTendered;
        this.changeDue = changeDue;
        this.currencyCode = currencyCode;
        this.createdAt = createdAt;
    }

    public UUID getSaleId() {
        return saleId;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
