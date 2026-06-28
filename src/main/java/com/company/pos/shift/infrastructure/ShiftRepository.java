package com.company.pos.shift.infrastructure;

import com.company.pos.shift.domain.Shift;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShiftRepository extends JpaRepository<Shift, UUID> {

    Optional<Shift> findByTerminalIdAndStatus(String terminalId, String status);
}
