package com.company.pos.sales.infrastructure;

import com.company.pos.sales.domain.SaleNumberSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;

public interface SaleNumberSequenceRepository extends JpaRepository<SaleNumberSequence, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SaleNumberSequence> findById(String id);
}
