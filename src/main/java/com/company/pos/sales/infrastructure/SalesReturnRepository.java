package com.company.pos.sales.infrastructure;

import com.company.pos.sales.domain.SalesReturn;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesReturnRepository extends JpaRepository<SalesReturn, UUID> {
}
