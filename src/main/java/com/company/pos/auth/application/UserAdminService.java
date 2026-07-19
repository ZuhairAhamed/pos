package com.company.pos.auth.application;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.audit.api.AuditService;
import com.company.pos.auth.api.CreateUserCommand;
import com.company.pos.auth.api.ResetCredentialRequest;
import com.company.pos.auth.api.Role;
import com.company.pos.auth.api.UpdateUserCommand;
import com.company.pos.auth.api.UserView;
import com.company.pos.auth.domain.User;
import com.company.pos.auth.infrastructure.UserRepository;
import com.company.pos.common.exception.DomainException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Staff-user lifecycle use cases. Deliberately carries NO class-level {@code @Transactional}:
 * each method does its repository write (auto-committed) then calls {@code audit.record(...)}
 * with REQUIRED propagation, which runs without an enclosing transaction — avoiding a second
 * concurrent connection on single-writer SQLite. Uniqueness is pre-checked for a friendly
 * error and backed by DB unique constraints for the race.
 */
@Service
public class UserAdminService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public UserAdminService(UserRepository users, PasswordEncoder encoder, AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
    }

    public UserView createUser(CreateUserCommand cmd) {
        if (cmd.username() == null || cmd.username().isBlank()) {
            throw DomainException.validation("Username is required");
        }
        if (cmd.password() == null || cmd.password().isBlank()) {
            throw DomainException.validation("Password is required");
        }
        if (cmd.displayName() == null || cmd.displayName().isBlank()) {
            throw DomainException.validation("Display name is required");
        }
        if (cmd.roles() == null || cmd.roles().isEmpty()) {
            throw DomainException.validation("At least one role is required");
        }
        String username = cmd.username().trim();
        if (users.findByUsername(username).isPresent()) {
            throw DomainException.conflict("Username already in use");
        }
        String cashierCode = trimToNull(cmd.cashierCode());
        if (cashierCode != null && users.findByCashierCode(cashierCode).isPresent()) {
            throw DomainException.conflict("Cashier code already in use");
        }
        User u = new User(UUID.randomUUID(), username, cmd.displayName(),
                encoder.encode(cmd.password()), Set.copyOf(cmd.roles()));
        if (cashierCode != null) {
            u.setCashierCode(cashierCode);
        }
        String pin = trimToNull(cmd.pin());
        if (pin != null) {
            u.setPinHash(encoder.encode(pin));
        }
        users.save(u);
        audit.record(AuditAction.USER_CREATED, actor(), username, Map.of("roles", rolesStr(cmd.roles())));
        return toView(u);
    }

    public List<UserView> listUsers(boolean includeDisabled) {
        return users.findAll().stream()
                .filter(u -> includeDisabled || u.isEnabled())
                .map(this::toView)
                .toList();
    }

    public UserView getUser(UUID id) {
        return users.findById(id).map(this::toView)
                .orElseThrow(() -> DomainException.notFound("No user " + id));
    }

    public UserView updateUser(UUID id, UpdateUserCommand cmd) {
        if (cmd.displayName() == null || cmd.displayName().isBlank()) {
            throw DomainException.validation("Display name is required");
        }
        if (cmd.roles() == null || cmd.roles().isEmpty()) {
            throw DomainException.validation("At least one role is required");
        }
        User u = users.findById(id).orElseThrow(() -> DomainException.notFound("No user " + id));
        boolean losesAdmin = u.getRoles().contains(Role.ADMIN) && !cmd.roles().contains(Role.ADMIN);
        if (u.isEnabled() && losesAdmin && isLastEnabledAdmin(u)) {
            throw DomainException.validation("Cannot remove the last administrator");
        }
        u.rename(cmd.displayName());
        u.changeRoles(Set.copyOf(cmd.roles()));
        users.save(u);
        audit.record(AuditAction.USER_UPDATED, actor(), u.getUsername(), Map.of("roles", rolesStr(cmd.roles())));
        return toView(u);
    }

    public void resetPassword(UUID id, ResetCredentialRequest req) {
        if (req == null || req.value() == null || req.value().isBlank()) {
            throw DomainException.validation("Password is required");
        }
        User u = users.findById(id).orElseThrow(() -> DomainException.notFound("No user " + id));
        u.resetPassword(encoder.encode(req.value()));
        users.save(u);
        audit.record(AuditAction.USER_CREDENTIAL_RESET, actor(), u.getUsername(), Map.of("credential", "password"));
    }

    public void resetPin(UUID id, ResetCredentialRequest req) {
        User u = users.findById(id).orElseThrow(() -> DomainException.notFound("No user " + id));
        String pin = req == null ? null : trimToNull(req.value());
        u.resetPin(pin == null ? null : encoder.encode(pin));
        users.save(u);
        audit.record(AuditAction.USER_CREDENTIAL_RESET, actor(), u.getUsername(), Map.of("credential", "pin"));
    }

    public void deactivate(UUID id) {
        User u = users.findById(id).orElseThrow(() -> DomainException.notFound("No user " + id));
        if (u.isEnabled() && u.getRoles().contains(Role.ADMIN) && isLastEnabledAdmin(u)) {
            throw DomainException.validation("Cannot deactivate the last administrator");
        }
        u.disable();
        users.save(u);
        audit.record(AuditAction.USER_DEACTIVATED, actor(), u.getUsername(), Map.of());
    }

    public void reactivate(UUID id) {
        User u = users.findById(id).orElseThrow(() -> DomainException.notFound("No user " + id));
        u.enable();
        users.save(u);
        audit.record(AuditAction.USER_REACTIVATED, actor(), u.getUsername(), Map.of());
    }

    /** True when {@code candidate} is the only enabled user still holding ADMIN. */
    private boolean isLastEnabledAdmin(User candidate) {
        return users.findAll().stream()
                .filter(User::isEnabled)
                .filter(u -> u.getRoles().contains(Role.ADMIN))
                .noneMatch(u -> !u.getId().equals(candidate.getId()));
    }

    private UserView toView(User u) {
        return new UserView(u.getId(), u.getUsername(), u.getDisplayName(),
                u.getCashierCode(), u.getRoles(), u.isEnabled());
    }

    private static String rolesStr(Set<Role> roles) {
        return roles.stream().map(Role::name).sorted().collect(Collectors.joining(","));
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String actor() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null ? a.getName() : "system";
    }
}
