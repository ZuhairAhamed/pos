package com.company.pos.auth.application;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.audit.api.AuditService;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.exception.DomainException;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

// Deliberately NOT @Transactional: login/pinLogin only read (the user lookup runs in its own
// short repository transaction) then issue a JWT. Keeping no outer transaction means the audit
// write — AuditService.record(...) is REQUIRES_NEW — needs only ONE connection, so it commits the
// (even failed-) login record without a second concurrent connection. On single-writer SQLite
// (embedded, pool=1) a nested write while an outer connection was held would deadlock; this avoids
// it while preserving the durable-on-failure guarantee (record commits before the exception rethrows).
@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;
    private final AuditService audit;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwtService,
            AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.jwtService = jwtService;
        this.audit = audit;
    }

    public String login(String username, String rawPassword) {
        // The success audit is recorded OUTSIDE the try so a transient audit-write failure cannot be
        // caught and mislogged as LOGIN_FAILED (which would also wrongly deny a valid login).
        String token;
        try {
            User user = users.findByUsername(username)
                    .filter(User::isEnabled)
                    .orElseThrow(() -> DomainException.validation("Invalid credentials"));
            if (!encoder.matches(rawPassword, user.getPasswordHash())) {
                throw DomainException.validation("Invalid credentials");
            }
            token = jwtService.issue(user);
        } catch (RuntimeException ex) {
            audit.record(AuditAction.LOGIN_FAILED, username, username,
                    Map.of("reason", "invalid_credentials"));
            throw ex;
        }
        audit.record(AuditAction.LOGIN_SUCCEEDED, username, username, Map.of());
        return token;
    }

    public String pinLogin(String cashierCode, String pin) {
        String token;
        try {
            User user = users.findByCashierCode(cashierCode)
                    .filter(User::isEnabled)
                    .orElseThrow(() -> DomainException.validation("Invalid credentials"));
            if (user.getPinHash() == null || !encoder.matches(pin, user.getPinHash())) {
                throw DomainException.validation("Invalid credentials");
            }
            token = jwtService.issue(user);
        } catch (RuntimeException ex) {
            audit.record(AuditAction.PIN_LOGIN_FAILED, cashierCode, cashierCode,
                    Map.of("reason", "invalid_credentials"));
            throw ex;
        }
        audit.record(AuditAction.PIN_LOGIN_SUCCEEDED, cashierCode, cashierCode, Map.of());
        return token;
    }
}
