package com.company.pos.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.application.JwtService;
import com.company.pos.auth.domain.User;
import com.company.pos.common.util.Identifiers;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class JwtServiceTest {

    @Autowired
    JwtService jwtService;

    @Autowired
    JwtDecoder jwtDecoder;

    @Test
    void issuesTokenWithSubjectUidAndRoles() {
        UUID id = Identifiers.newId();
        User u = new User(id, "alice", "Alice", "hash", Set.of(Role.MANAGER));

        String token = jwtService.issue(u);
        Jwt decoded = jwtDecoder.decode(token);

        assertThat(decoded.getSubject()).isEqualTo("alice");
        assertThat(decoded.getClaimAsString("uid")).isEqualTo(id.toString());
        assertThat(decoded.getClaimAsStringList("roles")).containsExactly("MANAGER");
        assertThat(decoded.getExpiresAt()).isNotNull();
    }
}
