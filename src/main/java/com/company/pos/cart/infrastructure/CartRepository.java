package com.company.pos.cart.infrastructure;

import com.company.pos.cart.domain.Cart;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartRepository extends JpaRepository<Cart, UUID> {
}
