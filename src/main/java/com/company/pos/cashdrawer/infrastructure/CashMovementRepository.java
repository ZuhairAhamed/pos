package com.company.pos.cashdrawer.infrastructure;

import com.company.pos.cashdrawer.domain.CashMovement;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashMovementRepository extends JpaRepository<CashMovement, UUID> {

    List<CashMovement> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);
}
