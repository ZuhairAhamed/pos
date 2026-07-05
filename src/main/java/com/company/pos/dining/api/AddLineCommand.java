package com.company.pos.dining.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** {@code note} and {@code course} are optional (may be null). {@code modifierOptionIds} may be empty. */
public record AddLineCommand(String sku, BigDecimal qty, String note, CourseTag course,
        List<UUID> modifierOptionIds) {

    public AddLineCommand {
        if (modifierOptionIds == null) {
            modifierOptionIds = List.of();
        }
    }

    /** Convenience for a line with no modifiers (used by existing callers/tests). */
    public AddLineCommand(String sku, BigDecimal qty, String note, CourseTag course) {
        this(sku, qty, note, course, List.of());
    }
}
