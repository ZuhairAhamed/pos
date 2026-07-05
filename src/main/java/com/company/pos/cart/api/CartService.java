package com.company.pos.cart.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface CartService {

    UUID createCart();

    CartView addLine(UUID cartId, String sku, BigDecimal quantity);

    CartView addLine(UUID cartId, String sku, BigDecimal quantity, java.util.List<UUID> modifierOptionIds);

    CartView addLinePreResolved(UUID cartId, String sku, BigDecimal quantity,
            java.util.List<CartLineModifierInput> modifiers);

    CartView updateLine(UUID cartId, UUID lineId, BigDecimal quantity);

    CartView removeLine(UUID cartId, UUID lineId);

    CartView getCart(UUID cartId);

    CartView assignCustomer(UUID cartId, UUID customerId);

    CartView clearCustomer(UUID cartId);

    CartView hold(UUID cartId);

    CartView resume(UUID cartId);

    List<CartView> listHeld(String terminalId);

    void close(UUID cartId);
}
