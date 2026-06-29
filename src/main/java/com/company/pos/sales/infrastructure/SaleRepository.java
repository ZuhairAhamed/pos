package com.company.pos.sales.infrastructure;

import com.company.pos.sales.domain.Sale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaleRepository extends JpaRepository<Sale, UUID> {

    Optional<Sale> findByReceiptNumber(String receiptNumber);
}
