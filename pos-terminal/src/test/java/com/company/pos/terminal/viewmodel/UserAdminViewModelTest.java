package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.CreateUserRequest;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.UserView;
import com.company.pos.terminal.api.UsersApi;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserAdminViewModelTest {

    private UserView sampleUser() {
        return new UserView(UUID.randomUUID(), "alice", "Alice", "0002", Set.of("CASHIER"), true);
    }

    @Test
    void loadReturnsUsersAndClearsError() {
        UsersApi api = new UsersApi(null) {
            @Override public List<UserView> list(boolean includeDisabled) { return List.of(sampleUser()); }
        };
        UserAdminViewModel vm = new UserAdminViewModel(api, Runnable::run);
        List<UserView> r = vm.load(false);
        assertEquals(1, r.size());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void createSurfacesConflictAndReturnsNull() {
        UsersApi api = new UsersApi(null) {
            @Override public UserView create(CreateUserRequest req) {
                throw new ApiException(409, new ProblemDetail("Conflict", 409, "Username already in use"), "HTTP 409");
            }
        };
        UserAdminViewModel vm = new UserAdminViewModel(api, Runnable::run);
        assertNull(vm.create(new CreateUserRequest("bob", "Bob", "pw", Set.of("CASHIER"), null, null)));
        assertEquals("Username already in use", vm.errorMessage().get());
    }

    @Test
    void deactivateReturnsTrueOnSuccess() {
        UsersApi api = new UsersApi(null) {
            @Override public void deactivate(UUID id) { /* ok */ }
        };
        UserAdminViewModel vm = new UserAdminViewModel(api, Runnable::run);
        org.junit.jupiter.api.Assertions.assertTrue(vm.deactivate(UUID.randomUUID()));
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void deferredDispatcherHoldsErrorUntilDrained() {
        UsersApi api = new UsersApi(null) {
            @Override public List<UserView> list(boolean includeDisabled) {
                throw new ApiException(500, new ProblemDetail("Error", 500, "boom"), "HTTP 500");
            }
        };
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        UserAdminViewModel vm = new UserAdminViewModel(api, queue::add);
        assertNull(vm.load(false));                  // synchronous null return
        assertEquals("", vm.errorMessage().get());   // deferred: not applied yet
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals("boom", vm.errorMessage().get());
    }
}
