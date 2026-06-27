package com.company.pos.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.util.Identifiers;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional
class UserRepositoryTest {

    @Autowired
    UserRepository users;

    @Test
    void persistsAndLooksUpUserWithRoles() {
        User u = new User(Identifiers.newId(), "alice", "Alice Manager",
                "hash", Set.of(Role.MANAGER, Role.CASHIER));
        u.setCashierCode("1001");
        users.save(u);

        User found = users.findByUsername("alice").orElseThrow();
        assertThat(found.getRoles()).containsExactlyInAnyOrder(Role.MANAGER, Role.CASHIER);
        assertThat(users.findByCashierCode("1001")).isPresent();
    }
}
