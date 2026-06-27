package com.company.pos.product.infrastructure;

import com.company.pos.product.domain.Product;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    Optional<Product> findBySku(String sku);

    List<Product> findByNameContainingIgnoreCaseOrSkuContainingIgnoreCase(String name, String sku);
}
