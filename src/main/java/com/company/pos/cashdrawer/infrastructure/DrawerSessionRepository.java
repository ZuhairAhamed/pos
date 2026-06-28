package com.company.pos.cashdrawer.infrastructure;

import com.company.pos.cashdrawer.domain.DrawerSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DrawerSessionRepository extends JpaRepository<DrawerSession, UUID> {

    Optional<DrawerSession> findByTerminalIdAndStatus(String terminalId, String status);
}
