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

    @Column(name = "sale_id", nullable = false, length = 36)
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

    @Column(name = "masked_pan", length = 25)
    private String maskedPan;

    @Column(name = "auth_token", length = 64)
    private String authToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Payment() {
        // JPA
    }

    public Payment(UUID id, UUID saleId, PaymentMethod method, BigDecimal amount,
            BigDecimal amountTendered, BigDecimal changeDue, String currencyCode,
            String maskedPan, String authToken, Instant createdAt) {
        this.id = id;
        this.saleId = saleId;
        this.method = method;
        this.amount = amount;
        this.amountTendered = amountTendered;
        this.changeDue = changeDue;
        this.currencyCode = currencyCode;
        this.maskedPan = maskedPan;
        this.authToken = authToken;
        this.createdAt = createdAt;
    }

    public UUID getSaleId() {
        return saleId;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getAmountTendered() {
        return amountTendered;
    }

    public BigDecimal getChangeDue() {
        return changeDue;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public String getMaskedPan() {
        return maskedPan;
    }
}
