package com.company.pos.auth.infrastructure;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("store-server")
class JwtSecretGuard {

    private final String secret;

    JwtSecretGuard(@Value("${pos.auth.jwt.secret}") String secret) {
        this.secret = secret;
    }

    @PostConstruct
    void validate() {
        if (secret.equals("dev-only-secret-change-me-0123456789abcdef")) {
            throw new IllegalStateException(
                    "POS_JWT_SECRET must be set to a non-default value on the store-server profile");
        }
    }
}
