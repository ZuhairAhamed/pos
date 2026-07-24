package com.company.pos.terminal.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record KitchenTicketLineView(String sku, String name, BigDecimal qty, String note,
        String course, List<String> modifiers) {
}
