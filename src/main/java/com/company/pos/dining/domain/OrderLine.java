package com.company.pos.dining.domain;

import com.company.pos.dining.api.CourseTag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "dining_order_line")
public class OrderLine {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "order_id", nullable = false, length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID orderId;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal qty;

    @Column(length = 500)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CourseTag course;

    @Column(name = "added_by", nullable = false, length = 100)
    private String addedBy;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    protected OrderLine() {
        // JPA
    }

    public OrderLine(UUID id, UUID orderId, String sku, BigDecimal qty, String note,
            CourseTag course, String addedBy, Instant addedAt) {
        this.id = id;
        this.orderId = orderId;
        this.sku = sku;
        this.qty = qty;
        this.note = note;
        this.course = course;
        this.addedBy = addedBy;
        this.addedAt = addedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public void setQty(BigDecimal qty) {
        this.qty = qty;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public CourseTag getCourse() {
        return course;
    }

    public void setCourse(CourseTag course) {
        this.course = course;
    }
}
