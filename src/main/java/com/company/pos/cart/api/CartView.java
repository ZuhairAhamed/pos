package com.company.pos.cart.api;

import java.util.List;
import java.util.UUID;

public record CartView(UUID cartId, String status, String currencyCode, UUID customerId,
        List<CartLineView> lines) {
}
