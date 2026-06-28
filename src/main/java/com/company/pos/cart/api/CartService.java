package com.company.pos.cart.api;

import java.math.BigDecimal;
import java.util.UUID;

public interface CartService {

    UUID createCart();

    CartView addLine(UUID cartId, String sku, BigDecimal quantity);

    CartView updateLine(UUID cartId, String sku, BigDecimal quantity);

    CartView removeLine(UUID cartId, String sku);

    CartView getCart(UUID cartId);

    void close(UUID cartId);
}
