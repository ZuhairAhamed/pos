package com.company.pos.cart.application;

import com.company.pos.cart.api.CartLineView;
import com.company.pos.cart.api.CartService;
import com.company.pos.cart.api.CartView;
import com.company.pos.cart.domain.Cart;
import com.company.pos.cart.infrastructure.CartRepository;
import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.product.api.ProductCatalog;
import com.company.pos.product.api.ProductView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultCartService implements CartService {

    private final CartRepository carts;
    private final ProductCatalog catalogue;

    DefaultCartService(CartRepository carts, ProductCatalog catalogue) {
        this.carts = carts;
        this.catalogue = catalogue;
    }

    @Override
    public UUID createCart() {
        Cart cart = new Cart(Identifiers.newId(), Instant.now());
        carts.save(cart);
        return cart.getId();
    }

    @Override
    public CartView addLine(UUID cartId, String sku, BigDecimal quantity) {
        requirePositive(quantity);
        Cart cart = openCart(cartId);
        ProductView product = catalogue.findBySku(sku)
                .orElseThrow(() -> DomainException.notFound("Unknown sku " + sku));
        cart.addLine(sku, product.name(), quantity, product.unitPrice(), product.currencyCode());
        return toView(cart);
    }

    @Override
    public CartView updateLine(UUID cartId, String sku, BigDecimal quantity) {
        requirePositive(quantity);
        Cart cart = openCart(cartId);
        if (cart.findLine(sku).isEmpty()) {
            throw DomainException.notFound("No line for sku " + sku);
        }
        cart.setLineQuantity(sku, quantity);
        return toView(cart);
    }

    @Override
    public CartView removeLine(UUID cartId, String sku) {
        Cart cart = openCart(cartId);
        cart.removeLine(sku);
        return toView(cart);
    }

    @Override
    @Transactional(readOnly = true)
    public CartView getCart(UUID cartId) {
        return toView(load(cartId));
    }

    @Override
    public void close(UUID cartId) {
        load(cartId).close();
    }

    private Cart load(UUID cartId) {
        return carts.findById(cartId)
                .orElseThrow(() -> DomainException.notFound("No cart " + cartId));
    }

    private Cart openCart(UUID cartId) {
        Cart cart = load(cartId);
        if (!cart.isOpen()) {
            throw DomainException.conflict("Cart " + cartId + " is not open");
        }
        return cart;
    }

    private void requirePositive(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw DomainException.validation("Quantity must be positive");
        }
    }

    private CartView toView(Cart cart) {
        List<CartLineView> lines = cart.getLines().stream()
                .map(l -> new CartLineView(l.getSku(), l.getName(), l.getQuantity(),
                        l.getUnitPrice(), l.getCurrencyCode()))
                .toList();
        return new CartView(cart.getId(), cart.getStatus(), cart.getCurrencyCode(), lines);
    }
}
