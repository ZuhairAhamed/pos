package com.company.pos.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "category")
public class Category {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "erp_version", nullable = false)
    private long erpVersion;

    protected Category() {
        // JPA
    }

    public Category(UUID id, String code, String name) {
        this.id = id;
        this.code = code;
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getErpVersion() {
        return erpVersion;
    }

    public void setErpVersion(long erpVersion) {
        this.erpVersion = erpVersion;
    }
}
