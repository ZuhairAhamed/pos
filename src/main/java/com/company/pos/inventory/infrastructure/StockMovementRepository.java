package com.company.pos.inventory.infrastructure;

import com.company.pos.inventory.domain.StockMovement;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    List<StockMovement> findBySku(String sku);
}
