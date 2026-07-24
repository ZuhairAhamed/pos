package com.company.pos.kitchen.api;

import java.math.BigDecimal;
import java.util.List;

public record KitchenTicketLineView(String sku, String name, BigDecimal qty, String note,
        String course, List<String> modifiers) {
}
