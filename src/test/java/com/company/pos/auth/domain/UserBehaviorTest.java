package com.company.pos.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.auth.api.Role;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserBehaviorTest {

    private User newUser() {
        return new User(UUID.randomUUID(), "alice", "Alice", "hash", Set.of(Role.CASHIER));
    }

    @Test
    void renameChangesDisplayName() {
        User u = newUser();
        u.rename("Alice Cooper");
        assertThat(u.getDisplayName()).isEqualTo("Alice Cooper");
    }

    @Test
    void changeRolesReplacesRoleSet() {
        User u = newUser();
        u.changeRoles(Set.of(Role.MANAGER, Role.ADMIN));
        assertThat(u.getRoles()).containsExactlyInAnyOrder(Role.MANAGER, Role.ADMIN);
    }

    @Test
    void disableThenEnableTogglesEnabled() {
        User u = newUser();
        u.disable();
        assertThat(u.isEnabled()).isFalse();
        u.enable();
        assertThat(u.isEnabled()).isTrue();
    }

    @Test
    void resetPinCanClearIt() {
        User u = newUser();
        u.resetPin("pinHash");
        assertThat(u.getPinHash()).isEqualTo("pinHash");
        u.resetPin(null);
        assertThat(u.getPinHash()).isNull();
    }

    @Test
    void resetPasswordReplacesHash() {
        User u = newUser();
        u.resetPassword("newHash");
        assertThat(u.getPasswordHash()).isEqualTo("newHash");
    }
}
