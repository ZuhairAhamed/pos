package com.company.pos.dining.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderLineView(UUID id, String sku, BigDecimal qty, String note, CourseTag course,
        Instant firedAt, List<OrderLineModifierView> modifiers) {
}
