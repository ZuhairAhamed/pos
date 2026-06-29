package com.company.pos.sales.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sales_return_line")
public class SalesReturnLine {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "sales_return_id", nullable = false)
    private SalesReturn salesReturn;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "original_line_no", nullable = false)
    private int originalLineNo;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal lineTotal;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    protected SalesReturnLine() {
        // JPA
    }

    public SalesReturnLine(UUID id, SalesReturn salesReturn, int lineNo, int originalLineNo,
            String sku, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal netAmount,
            BigDecimal taxAmount, BigDecimal lineTotal, String currencyCode) {
        this.id = id;
        this.salesReturn = salesReturn;
        this.lineNo = lineNo;
        this.originalLineNo = originalLineNo;
        this.sku = sku;
        this.name = name;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.netAmount = netAmount;
        this.taxAmount = taxAmount;
        this.lineTotal = lineTotal;
        this.currencyCode = currencyCode;
    }

    public int getLineNo() {
        return lineNo;
    }

    public int getOriginalLineNo() {
        return originalLineNo;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }
}
