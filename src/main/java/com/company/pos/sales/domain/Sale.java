package com.company.pos.sales.domain;

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
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sale")
public class Sale {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "receipt_number", nullable = false, unique = true, length = 40)
    private String receiptNumber;

    @Column(name = "store_id", nullable = false, length = 16)
    private String storeId;

    @Column(name = "terminal_id", nullable = false, length = 16)
    private String terminalId;

    @Column(name = "cashier_username", nullable = false, length = 100)
    private String cashierUsername;

    @Column(name = "location_code", nullable = false, length = 32)
    private String locationCode;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "tax_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal taxTotal;

    @Column(name = "grand_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal grandTotal;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "sale", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<SaleLine> lines = new ArrayList<>();

    protected Sale() {
        // JPA
    }

    public Sale(UUID id, String receiptNumber, String storeId, String terminalId,
            String cashierUsername, String locationCode, String currencyCode,
            BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal, Instant createdAt) {
        this.id = id;
        this.receiptNumber = receiptNumber;
        this.storeId = storeId;
        this.terminalId = terminalId;
        this.cashierUsername = cashierUsername;
        this.locationCode = locationCode;
        this.currencyCode = currencyCode;
        this.subtotal = subtotal;
        this.taxTotal = taxTotal;
        this.grandTotal = grandTotal;
        this.createdAt = createdAt;
        this.status = "COMPLETED";
    }

    public void addLine(SaleLine line) {
        lines.add(line);
    }

    public UUID getId() {
        return id;
    }

    public String getReceiptNumber() {
        return receiptNumber;
    }

    public String getCashierUsername() {
        return cashierUsername;
    }

    public String getLocationCode() {
        return locationCode;
    }

    public String getStatus() {
        return status;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getTaxTotal() {
        return taxTotal;
    }

    public BigDecimal getGrandTotal() {
        return grandTotal;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<SaleLine> getLines() {
        return lines;
    }
}
