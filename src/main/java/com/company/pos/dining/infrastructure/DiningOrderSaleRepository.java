package com.company.pos.dining.infrastructure;

import com.company.pos.dining.domain.DiningOrderSale;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiningOrderSaleRepository extends JpaRepository<DiningOrderSale, UUID> {

    List<DiningOrderSale> findByOrderId(UUID orderId);
}
