package com.company.pos.customer.domain;

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
@Table(name = "customer_purchase")
public class CustomerPurchase {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "customer_id", nullable = false, length = 36)
    private UUID customerId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "sale_id", nullable = false, unique = true, length = 36)
    private UUID saleId;

    @Column(name = "receipt_number", length = 64)
    private String receiptNumber;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "grand_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal grandTotal;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    protected CustomerPurchase() {
        // JPA
    }

    public CustomerPurchase(UUID id, UUID customerId, UUID saleId, String receiptNumber,
            Instant occurredAt, BigDecimal grandTotal, String currencyCode) {
        this.id = id;
        this.customerId = customerId;
        this.saleId = saleId;
        this.receiptNumber = receiptNumber;
        this.occurredAt = occurredAt;
        this.grandTotal = grandTotal;
        this.currencyCode = currencyCode;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getSaleId() {
        return saleId;
    }

    public String getReceiptNumber() {
        return receiptNumber;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public BigDecimal getGrandTotal() {
        return grandTotal;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }
}
