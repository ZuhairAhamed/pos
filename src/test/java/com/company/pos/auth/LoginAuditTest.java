package com.company.pos.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.audit.api.AuditRecordView;
import com.company.pos.audit.application.DefaultAuditService;
import com.company.pos.auth.application.AuthService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class LoginAuditTest {

    @Autowired
    AuthService authService;

    @Autowired
    DefaultAuditService audit;

    @Test
    void failedLoginIsAuditedEvenThoughLoginThrows() {
        assertThatThrownBy(() -> authService.login("ghost", "wrong"))
                .isInstanceOf(RuntimeException.class);

        List<AuditRecordView> recent = audit.recent(20);
        assertThat(recent).anySatisfy(r -> {
            assertThat(r.action()).isEqualTo("LOGIN_FAILED");
            assertThat(r.actor()).isEqualTo("ghost");
        });
        assertThat(audit.verify().intact()).isTrue();
    }
}
