package com.company.pos.dining.infrastructure;

import com.company.pos.dining.domain.DiningTable;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiningTableRepository extends JpaRepository<DiningTable, UUID> {

    Optional<DiningTable> findByLabel(String label);
}
