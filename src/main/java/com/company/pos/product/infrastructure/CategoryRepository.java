package com.company.pos.product.infrastructure;

import com.company.pos.product.domain.Category;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    Optional<Category> findByCode(String code);
}
