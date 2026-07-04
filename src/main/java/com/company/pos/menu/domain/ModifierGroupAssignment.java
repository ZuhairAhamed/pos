package com.company.pos.menu.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "modifier_group_assignment",
        uniqueConstraints = @UniqueConstraint(name = "uq_mga_group_sku",
                columnNames = { "group_id", "sku" }))
public class ModifierGroupAssignment {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "group_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID groupId;

    @Column(nullable = false, length = 64)
    private String sku;

    protected ModifierGroupAssignment() {
    }

    public ModifierGroupAssignment(UUID id, UUID groupId, String sku) {
        this.id = id;
        this.groupId = groupId;
        this.sku = sku;
    }

    public UUID getId() {
        return id;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public String getSku() {
        return sku;
    }
}
