package com.company.pos.inventory.infrastructure;

import com.company.pos.inventory.domain.StockLevel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockLevelRepository extends JpaRepository<StockLevel, UUID> {

    Optional<StockLevel> findBySkuAndLocationCode(String sku, String locationCode);

    List<StockLevel> findBySku(String sku);
}
