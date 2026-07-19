package com.company.pos.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.auth.api.CreateUserCommand;
import com.company.pos.auth.api.Role;
import com.company.pos.auth.api.ResetCredentialRequest;
import com.company.pos.auth.api.UpdateUserCommand;
import com.company.pos.auth.api.UserView;
import com.company.pos.auth.application.UserAdminService;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.exception.DomainException;
import com.company.pos.support.DatabaseCleaner;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class UserAdminServiceTest {

    @Autowired
    UserAdminService svc;

    @Autowired
    UserRepository repo;

    @Autowired
    DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    @Test
    void createsUserWithHashedCredentials() {
        UserView v = svc.createUser(new CreateUserCommand(
                "alice", "Alice", "pw", Set.of(Role.CASHIER), "0002", "1234"));
        assertThat(v.id()).isNotNull();
        assertThat(v.username()).isEqualTo("alice");
        var saved = repo.findByUsername("alice").orElseThrow();
        assertThat(saved.getPasswordHash()).isNotEqualTo("pw");   // hashed
        assertThat(saved.getPinHash()).isNotNull();
    }

    @Test
    void rejectsDuplicateUsername() {
        svc.createUser(new CreateUserCommand("bob", "Bob", "pw", Set.of(Role.CASHIER), null, null));
        assertThatThrownBy(() -> svc.createUser(
                new CreateUserCommand("bob", "Bob2", "pw", Set.of(Role.CASHIER), null, null)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsDuplicateCashierCode() {
        svc.createUser(new CreateUserCommand("bob", "Bob", "pw", Set.of(Role.CASHIER), "0005", null));
        assertThatThrownBy(() -> svc.createUser(
                new CreateUserCommand("carol", "Carol", "pw", Set.of(Role.CASHIER), "0005", null)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void listExcludesDisabledUnlessAsked() {
        UserView admin = svc.createUser(new CreateUserCommand("root", "Root", "pw", Set.of(Role.ADMIN), null, null));
        UserView bob = svc.createUser(new CreateUserCommand("bob", "Bob", "pw", Set.of(Role.CASHIER), null, null));
        svc.deactivate(bob.id());
        assertThat(svc.listUsers(false)).extracting(UserView::username).containsExactlyInAnyOrder("root");
        assertThat(svc.listUsers(true)).extracting(UserView::username).containsExactlyInAnyOrder("root", "bob");
        assertThat(admin).isNotNull();
    }

    @Test
    void cannotDeactivateLastAdmin() {
        UserView admin = svc.createUser(new CreateUserCommand("root", "Root", "pw", Set.of(Role.ADMIN), null, null));
        assertThatThrownBy(() -> svc.deactivate(admin.id())).isInstanceOf(DomainException.class);
    }

    @Test
    void canDeactivateAdminWhenAnotherEnabledAdminExists() {
        UserView a1 = svc.createUser(new CreateUserCommand("root", "Root", "pw", Set.of(Role.ADMIN), null, null));
        svc.createUser(new CreateUserCommand("root2", "Root2", "pw", Set.of(Role.ADMIN), null, null));
        svc.deactivate(a1.id());
        assertThat(svc.getUser(a1.id()).enabled()).isFalse();
    }

    @Test
    void updateCannotRemoveLastAdminRole() {
        UserView admin = svc.createUser(new CreateUserCommand("root", "Root", "pw", Set.of(Role.ADMIN), null, null));
        assertThatThrownBy(() -> svc.updateUser(admin.id(),
                new UpdateUserCommand("Root", Set.of(Role.CASHIER))))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void resetPinBlankClearsIt() {
        UserView v = svc.createUser(new CreateUserCommand("cara", "Cara", "pw", Set.of(Role.CASHIER), "0003", "1111"));
        svc.resetPin(v.id(), new ResetCredentialRequest(""));
        assertThat(repo.findById(v.id()).orElseThrow().getPinHash()).isNull();
    }

    @Test
    void reactivateReenablesUser() {
        svc.createUser(new CreateUserCommand("root", "Root", "pw", Set.of(Role.ADMIN), null, null));
        UserView bob = svc.createUser(new CreateUserCommand("bob", "Bob", "pw", Set.of(Role.CASHIER), null, null));
        svc.deactivate(bob.id());
        svc.reactivate(bob.id());
        assertThat(svc.getUser(bob.id()).enabled()).isTrue();
    }
}
