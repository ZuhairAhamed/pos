package com.company.pos.cart.web;

import com.company.pos.cart.api.CartService;
import com.company.pos.cart.api.CartView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CartController {

    private final CartService carts;

    CartController(CartService carts) {
        this.carts = carts;
    }

    record CartLineRequest(String sku, BigDecimal quantity) {
    }

    record QuantityRequest(BigDecimal quantity) {
    }

    @PostMapping("/carts")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> create() {
        return Map.of("cartId", carts.createCart());
    }

    @GetMapping("/carts/{cartId}")
    CartView get(@PathVariable UUID cartId) {
        return carts.getCart(cartId);
    }

    @PostMapping("/carts/{cartId}/lines")
    CartView addLine(@PathVariable UUID cartId, @RequestBody CartLineRequest body) {
        return carts.addLine(cartId, body.sku(), body.quantity());
    }

    @PutMapping("/carts/{cartId}/lines/{sku}")
    CartView updateLine(@PathVariable UUID cartId, @PathVariable String sku,
            @RequestBody QuantityRequest body) {
        return carts.updateLine(cartId, sku, body.quantity());
    }

    @DeleteMapping("/carts/{cartId}/lines/{sku}")
    CartView removeLine(@PathVariable UUID cartId, @PathVariable String sku) {
        return carts.removeLine(cartId, sku);
    }

    @DeleteMapping("/carts/{cartId}/customer")
    CartView detachCustomer(@PathVariable UUID cartId) {
        return carts.clearCustomer(cartId);
    }

    @PutMapping("/carts/{cartId}/hold")
    CartView hold(@PathVariable UUID cartId) {
        return carts.hold(cartId);
    }

    @PutMapping("/carts/{cartId}/resume")
    CartView resume(@PathVariable UUID cartId) {
        return carts.resume(cartId);
    }

    @GetMapping("/carts/held")
    List<CartView> held(@RequestParam String terminalId) {
        return carts.listHeld(terminalId);
    }
}
