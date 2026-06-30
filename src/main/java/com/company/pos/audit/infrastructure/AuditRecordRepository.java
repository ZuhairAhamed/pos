package com.company.pos.audit.infrastructure;

import com.company.pos.audit.domain.AuditRecord;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditRecordRepository extends JpaRepository<AuditRecord, String> {

    List<AuditRecord> findByStoreIdOrderBySeqAsc(String storeId);

    @Query("""
            select a from AuditRecord a
            where (:actor is null or a.actor = :actor)
              and (:action is null or a.action = :action)
              and a.occurredAt between :from and :to
            order by a.seq desc
            """)
    List<AuditRecord> search(@Param("actor") String actor, @Param("action") String action,
            @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);
}
