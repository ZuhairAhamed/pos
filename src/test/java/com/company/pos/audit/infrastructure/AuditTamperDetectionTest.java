package com.company.pos.audit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.audit.api.AuditService;
import com.company.pos.audit.api.AuditVerifyResult;
import com.company.pos.audit.application.DefaultAuditService;
import com.company.pos.audit.domain.AuditRecord;
import com.company.pos.support.DatabaseCleaner;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class AuditTamperDetectionTest {

    @Autowired
    AuditService auditService;

    @Autowired
    DefaultAuditService defaultAuditService;

    @Autowired
    AuditRecordRepository records;

    @Autowired
    DatabaseCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner.clean();
    }

    @AfterEach
    void tearDown() {
        cleaner.clean();
    }

    @Test
    void verifyDetectsAMutatedPayload() {
        auditService.record(AuditAction.SETTING_CHANGED, "admin", "tax.rate",
                Map.of("old", "0.15", "new", "0.16"));
        auditService.record(AuditAction.SETTING_CHANGED, "admin", "store.name",
                Map.of("old", "A", "new", "B"));

        List<AuditRecord> all = records.findAll();
        AuditRecord victim = all.get(0);
        // Tamper: overwrite the payload directly, leaving the stored hash stale.
        records.save(new AuditRecord(victim.getId(), victim.getSeq(), victim.getStoreId(),
                victim.getOccurredAt(), victim.getActor(), victim.getAction(), victim.getEntityRef(),
                "{\"old\":\"0.15\",\"new\":\"0.99\"}", victim.getPrevHash(), victim.getHash()));

        AuditVerifyResult result = defaultAuditService.verify();
        assertThat(result.intact()).isFalse();
        assertThat(result.firstBrokenSeq()).isNotNull();
    }
}
