package com.company.pos.dining.api;

import java.math.BigDecimal;

/** {@code note} and {@code course} are optional (may be null). */
public record AddLineCommand(String sku, BigDecimal qty, String note, CourseTag course) {
}
