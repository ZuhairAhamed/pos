package com.company.pos.dining.infrastructure;

import com.company.pos.dining.api.OrderStatus;
import com.company.pos.dining.domain.DiningOrder;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiningOrderRepository extends JpaRepository<DiningOrder, UUID> {

    List<DiningOrder> findByStatus(OrderStatus status);

    boolean existsByTableIdAndStatus(UUID tableId, OrderStatus status);
}
