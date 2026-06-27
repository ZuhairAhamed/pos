package com.company.pos.auth.application;

import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.exception.DomainException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwtService) {
        this.users = users;
        this.encoder = encoder;
        this.jwtService = jwtService;
    }

    public String login(String username, String rawPassword) {
        User user = users.findByUsername(username)
                .filter(User::isEnabled)
                .orElseThrow(() -> DomainException.validation("Invalid credentials"));
        if (!encoder.matches(rawPassword, user.getPasswordHash())) {
            throw DomainException.validation("Invalid credentials");
        }
        return jwtService.issue(user);
    }
}
