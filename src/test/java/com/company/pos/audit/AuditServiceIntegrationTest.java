package com.company.pos.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.audit.api.AuditRecordView;
import com.company.pos.audit.api.AuditService;
import com.company.pos.audit.api.AuditVerifyResult;
import com.company.pos.audit.application.DefaultAuditService;
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
class AuditServiceIntegrationTest {

    @Autowired
    AuditService auditService;

    @Autowired
    DefaultAuditService defaultAuditService;

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
    void recordsChainAndVerifyIntact() {
        auditService.record(AuditAction.LOGIN_FAILED, "alice", "alice", Map.of("reason", "bad"));
        auditService.record(AuditAction.LOGIN_SUCCEEDED, "bob", "bob", Map.of());

        List<AuditRecordView> recent = defaultAuditService.recent(10);
        assertThat(recent).extracting(AuditRecordView::action)
                .contains("LOGIN_FAILED", "LOGIN_SUCCEEDED");
        // newest first, chained
        AuditRecordView newest = recent.get(0);
        AuditRecordView prior = recent.get(1);
        assertThat(newest.seq()).isEqualTo(prior.seq() + 1);
        assertThat(newest.prevHash()).isEqualTo(prior.hash());

        AuditVerifyResult result = defaultAuditService.verify();
        assertThat(result.intact()).isTrue();
        assertThat(result.recordsChecked()).isGreaterThanOrEqualTo(2);
    }
}
