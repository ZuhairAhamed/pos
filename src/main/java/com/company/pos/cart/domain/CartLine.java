package com.company.pos.cart.domain;

import com.company.pos.common.util.Identifiers;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "cart_line")
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

    @Column(name = "base_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal basePrice;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @OneToMany(mappedBy = "line", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CartLineModifier> modifiers = new ArrayList<>();

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
        this.basePrice = unitPrice;
        this.unitPrice = unitPrice;
        this.currencyCode = currencyCode;
    }

    public UUID getId() {
        return id;
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

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public List<CartLineModifier> getModifiers() {
        return Collections.unmodifiableList(modifiers);
    }

    void addModifier(UUID optionId, String name, BigDecimal priceDelta) {
        modifiers.add(new CartLineModifier(Identifiers.newId(), this, optionId, name, priceDelta));
    }

    /** Recompute effective unit price = base + sum of deltas. Call after modifiers change. */
    void recomputeUnitPrice() {
        BigDecimal sum = basePrice;
        for (CartLineModifier m : modifiers) {
            sum = sum.add(m.getPriceDelta());
        }
        this.unitPrice = sum;
    }

    /**
     * Single authority for the modifier merge key: sorted option-ids joined by comma.
     * An empty stream (plain line, no modifiers) produces an empty string.
     */
    static String modifierKeyOf(java.util.stream.Stream<UUID> optionIds) {
        return optionIds.map(UUID::toString).sorted()
                .collect(Collectors.joining(","));
    }

    /** Merge identity: sku + the sorted set of option ids (empty string for a plain line). */
    String modifierKey() {
        return modifierKeyOf(modifiers.stream().map(CartLineModifier::getOptionId));
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
