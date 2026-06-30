package com.company.pos.auth.application;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.audit.api.AuditService;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.exception.DomainException;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
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
        try {
            User user = users.findByUsername(username)
                    .filter(User::isEnabled)
                    .orElseThrow(() -> DomainException.validation("Invalid credentials"));
            if (!encoder.matches(rawPassword, user.getPasswordHash())) {
                throw DomainException.validation("Invalid credentials");
            }
            String token = jwtService.issue(user);
            audit.record(AuditAction.LOGIN_SUCCEEDED, username, username, Map.of());
            return token;
        } catch (RuntimeException ex) {
            audit.record(AuditAction.LOGIN_FAILED, username, username,
                    Map.of("reason", "invalid_credentials"));
            throw ex;
        }
    }

    public String pinLogin(String cashierCode, String pin) {
        try {
            User user = users.findByCashierCode(cashierCode)
                    .filter(User::isEnabled)
                    .orElseThrow(() -> DomainException.validation("Invalid credentials"));
            if (user.getPinHash() == null || !encoder.matches(pin, user.getPinHash())) {
                throw DomainException.validation("Invalid credentials");
            }
            String token = jwtService.issue(user);
            audit.record(AuditAction.PIN_LOGIN_SUCCEEDED, cashierCode, cashierCode, Map.of());
            return token;
        } catch (RuntimeException ex) {
            audit.record(AuditAction.PIN_LOGIN_FAILED, cashierCode, cashierCode,
                    Map.of("reason", "invalid_credentials"));
            throw ex;
        }
    }
}
