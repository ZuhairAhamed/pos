package com.company.pos.menu.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "variant_member")
public class VariantMember {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "variant_group_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID variantGroupId;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(name = "display_label", nullable = false, length = 100)
    private String displayLabel;

    protected VariantMember() {
    }

    public VariantMember(UUID id, UUID variantGroupId, String sku, String displayLabel) {
        this.id = id;
        this.variantGroupId = variantGroupId;
        this.sku = sku;
        this.displayLabel = displayLabel;
    }

    public UUID getId() {
        return id;
    }

    public UUID getVariantGroupId() {
        return variantGroupId;
    }

    public String getSku() {
        return sku;
    }

    public String getDisplayLabel() {
        return displayLabel;
    }
}
