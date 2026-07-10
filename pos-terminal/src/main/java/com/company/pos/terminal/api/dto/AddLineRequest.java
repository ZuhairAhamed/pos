package com.company.pos.terminal.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Body for {@code POST /dining/orders/{id}/lines}. Mirrors the server's {@code AddLineCommand}. */
public record AddLineRequest(String sku, BigDecimal qty, String note, String course,
        List<UUID> modifierOptionIds) {
}
