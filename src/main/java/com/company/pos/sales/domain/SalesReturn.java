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
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sales_return")
public class SalesReturn {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "credit_note_number", nullable = false, unique = true, length = 40)
    private String creditNoteNumber;

    @Column(name = "original_sale_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID originalSaleId;

    @Column(name = "store_id", nullable = false, length = 16)
    private String storeId;

    @Column(name = "terminal_id", nullable = false, length = 16)
    private String terminalId;

    @Column(name = "manager_username", nullable = false, length = 100)
    private String managerUsername;

    @Column(name = "location_code", nullable = false, length = 32)
    private String locationCode;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "refund_subtotal", nullable = false, precision = 19, scale = 2)
    private BigDecimal refundSubtotal;

    @Column(name = "refund_tax_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal refundTaxTotal;

    @Column(name = "refund_grand_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal refundGrandTotal;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "salesReturn", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<SalesReturnLine> lines = new ArrayList<>();

    protected SalesReturn() {
        // JPA
    }

    public SalesReturn(UUID id, String creditNoteNumber, UUID originalSaleId, String storeId,
            String terminalId, String managerUsername, String locationCode, String currencyCode,
            BigDecimal refundSubtotal, BigDecimal refundTaxTotal, BigDecimal refundGrandTotal,
            Instant createdAt) {
        this.id = id;
        this.creditNoteNumber = creditNoteNumber;
        this.originalSaleId = originalSaleId;
        this.storeId = storeId;
        this.terminalId = terminalId;
        this.managerUsername = managerUsername;
        this.locationCode = locationCode;
        this.currencyCode = currencyCode;
        this.refundSubtotal = refundSubtotal;
        this.refundTaxTotal = refundTaxTotal;
        this.refundGrandTotal = refundGrandTotal;
        this.createdAt = createdAt;
        this.status = "COMPLETED";
    }

    public void addLine(SalesReturnLine line) {
        lines.add(line);
    }

    public UUID getId() {
        return id;
    }

    public String getCreditNoteNumber() {
        return creditNoteNumber;
    }

    public UUID getOriginalSaleId() {
        return originalSaleId;
    }

    public String getStatus() {
        return status;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public BigDecimal getRefundSubtotal() {
        return refundSubtotal;
    }

    public BigDecimal getRefundTaxTotal() {
        return refundTaxTotal;
    }

    public BigDecimal getRefundGrandTotal() {
        return refundGrandTotal;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<SalesReturnLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
