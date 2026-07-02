package com.company.pos.customer.infrastructure;

import com.company.pos.customer.domain.CustomerPurchase;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerPurchaseRepository extends JpaRepository<CustomerPurchase, UUID> {

    boolean existsBySaleId(UUID saleId);

    List<CustomerPurchase> findByCustomerIdOrderByOccurredAtDesc(UUID customerId);
}
