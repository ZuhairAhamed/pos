package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code course} mirrors the server's {@code CourseTag} enum name (e.g. {@code MAIN}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderLineView(UUID id, String sku, BigDecimal qty, String note, String course,
        Instant firedAt, List<OrderLineModifierView> modifiers) {

    /** {@code firedAt != null} means the line has been fired (locked) to the kitchen. */
    public boolean fired() {
        return firedAt != null;
    }
}
