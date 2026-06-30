package com.company.pos.audit.infrastructure;

import com.company.pos.audit.domain.AuditChainHead;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AuditChainHeadRepository extends JpaRepository<AuditChainHead, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AuditChainHead> findByStoreId(String storeId);
}
