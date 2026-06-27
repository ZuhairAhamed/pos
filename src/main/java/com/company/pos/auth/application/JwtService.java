package com.company.pos.auth.application;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final long ttlMinutes;

    public JwtService(JwtEncoder encoder, @Value("${pos.auth.jwt.ttl-minutes}") long ttlMinutes) {
        this.encoder = encoder;
        this.ttlMinutes = ttlMinutes;
    }

    public String issue(User user) {
        Instant now = Instant.now();
        List<String> roles = user.getRoles().stream().map(Role::name).toList();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getUsername())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(ttlMinutes)))
                .claim("uid", user.getId().toString())
                .claim("roles", roles)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
