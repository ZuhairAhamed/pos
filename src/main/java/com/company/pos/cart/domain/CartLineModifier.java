package com.company.pos.cart.domain;

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
@Table(name = "cart_line_modifier")
public class CartLineModifier {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "line_id", nullable = false)
    private CartLine line;

    @Column(name = "option_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID optionId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "price_delta", nullable = false, precision = 19, scale = 4)
    private BigDecimal priceDelta;

    protected CartLineModifier() {
    }

    CartLineModifier(UUID id, CartLine line, UUID optionId, String name, BigDecimal priceDelta) {
        this.id = id;
        this.line = line;
        this.optionId = optionId;
        this.name = name;
        this.priceDelta = priceDelta;
    }

    public UUID getOptionId() {
        return optionId;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPriceDelta() {
        return priceDelta;
    }
}
