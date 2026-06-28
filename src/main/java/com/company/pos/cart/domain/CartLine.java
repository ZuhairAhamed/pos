package com.company.pos.cart.domain;

import com.company.pos.common.util.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "cart_line",
        uniqueConstraints = @UniqueConstraint(name = "uq_cart_line_sku",
                columnNames = { "cart_id", "sku" }))
public class CartLine {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    protected CartLine() {
        // JPA
    }

    CartLine(Cart cart, int lineNo, String sku, String name, BigDecimal quantity,
            BigDecimal unitPrice, String currencyCode) {
        this.id = Identifiers.newId();
        this.cart = cart;
        this.lineNo = lineNo;
        this.sku = sku;
        this.name = name;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.currencyCode = currencyCode;
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

    public String getCurrencyCode() {
        return currencyCode;
    }

    void addQuantity(BigDecimal delta) {
        this.quantity = this.quantity.add(delta);
    }

    void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    void setLineNo(int lineNo) {
        this.lineNo = lineNo;
    }
}
