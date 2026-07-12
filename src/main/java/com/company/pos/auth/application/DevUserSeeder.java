package com.company.pos.auth.application;

import com.company.pos.auth.api.Role;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Dev-only seeder: creates a single operator account so the terminal can log in against a freshly
 * started backend. There is no user-registration endpoint, so without this a fresh dev run has no
 * way to authenticate. Active only under the {@code dev} profile; idempotent (skips if the
 * {@code manager} user already exists). Module-local (touches only this module's repository/entity),
 * so it does not affect {@code ModularityTests}.
 */
@Component
@Profile("dev")
class DevUserSeeder implements ApplicationRunner {

    private static final System.Logger LOG = System.getLogger(DevUserSeeder.class.getName());

    private final UserRepository users;
    private final PasswordEncoder encoder;

    DevUserSeeder(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.findByUsername("manager").isPresent()) {
            return;
        }
        User manager = new User(UUID.randomUUID(), "manager", "Manager",
                encoder.encode("manager"), Set.of(Role.CASHIER, Role.MANAGER, Role.ADMIN));
        manager.setCashierCode("0001");
        manager.setPinHash(encoder.encode("1234"));
        users.save(manager);
        LOG.log(System.Logger.Level.INFO,
                "[dev-seed] created user 'manager' (password 'manager', cashierCode 0001 / pin 1234)");
    }
}
