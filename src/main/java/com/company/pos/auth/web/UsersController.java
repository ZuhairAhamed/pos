package com.company.pos.auth.web;

import com.company.pos.auth.api.CreateUserCommand;
import com.company.pos.auth.api.ResetCredentialRequest;
import com.company.pos.auth.api.UpdateUserCommand;
import com.company.pos.auth.api.UserView;
import com.company.pos.auth.application.UserAdminService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class UsersController {

    private final UserAdminService users;

    UsersController(UserAdminService users) {
        this.users = users;
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    UserView create(@RequestBody CreateUserCommand body) {
        return users.createUser(body);
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    List<UserView> list(@RequestParam(name = "includeDisabled", defaultValue = "false") boolean includeDisabled) {
        return users.listUsers(includeDisabled);
    }

    @GetMapping("/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    UserView get(@PathVariable UUID id) {
        return users.getUser(id);
    }

    @PutMapping("/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    UserView update(@PathVariable UUID id, @RequestBody UpdateUserCommand body) {
        return users.updateUser(id, body);
    }

    @PostMapping("/users/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void resetPassword(@PathVariable UUID id, @RequestBody ResetCredentialRequest body) {
        users.resetPassword(id, body);
    }

    @PostMapping("/users/{id}/reset-pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void resetPin(@PathVariable UUID id, @RequestBody ResetCredentialRequest body) {
        users.resetPin(id, body);
    }

    @PostMapping("/users/{id}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void deactivate(@PathVariable UUID id) {
        users.deactivate(id);
    }

    @PostMapping("/users/{id}/reactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void reactivate(@PathVariable UUID id) {
        users.reactivate(id);
    }
}
