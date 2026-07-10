package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.AuthApi;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoginViewModelTest {

    @Test
    void successSetsLoggedIn() {
        AuthApi auth = new AuthApi(null, null) {
            @Override public void login(String u, String p) { /* success no-op */ }
        };
        LoginViewModel vm = new LoginViewModel(auth);
        vm.username().set("alice");
        vm.passwordOrPin().set("pw");
        vm.login();
        assertTrue(vm.loggedIn().get());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void failureSetsErrorMessage() {
        AuthApi auth = new AuthApi(null, null) {
            @Override public void login(String u, String p) {
                throw new ApiException(401, null, "Invalid credentials");
            }
        };
        LoginViewModel vm = new LoginViewModel(auth);
        vm.username().set("alice");
        vm.passwordOrPin().set("wrong");
        vm.login();
        assertFalse(vm.loggedIn().get());
        assertEquals("Invalid credentials", vm.errorMessage().get());
    }

    @Test
    void pinLoginSuccessSetsLoggedIn() {
        AuthApi auth = new AuthApi(null, null) {
            @Override public void pinLogin(String code, String pin) { /* success no-op */ }
        };
        LoginViewModel vm = new LoginViewModel(auth);
        vm.passwordOrPin().set("1234");
        vm.pinLogin("C01");
        assertTrue(vm.loggedIn().get());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void pinLoginFailureSetsErrorMessage() {
        AuthApi auth = new AuthApi(null, null) {
            @Override public void pinLogin(String code, String pin) {
                throw new ApiException(401, null, "Invalid PIN");
            }
        };
        LoginViewModel vm = new LoginViewModel(auth);
        vm.passwordOrPin().set("0000");
        vm.pinLogin("C01");
        assertFalse(vm.loggedIn().get());
        assertEquals("Invalid PIN", vm.errorMessage().get());
    }

    @Test
    void busyIsFalseAfterCompletion() {
        AuthApi auth = new AuthApi(null, null) {
            @Override public void login(String u, String p) { /* success no-op */ }
        };
        LoginViewModel vm = new LoginViewModel(auth);
        vm.login();
        assertFalse(vm.busy().get());
    }

    /**
     * Marshaling contract (FIX 1): every write to a bound observable property is routed through the
     * injected UI dispatcher — in production {@code Platform::runLater}, so scene-graph mutations land
     * on the FX thread. Here we inject a CAPTURING executor that records each dispatch (and still runs
     * the runnable) and assert a state-changing call both routes through it and lands the state.
     */
    @Test
    void writesAreRoutedThroughInjectedUiDispatcher() {
        List<Runnable> dispatched = new ArrayList<>();
        Consumer<Runnable> capturingUi = r -> {
            dispatched.add(r);
            r.run(); // run inline so the observable reflects the change, like Platform::runLater would
        };
        AuthApi auth = new AuthApi(null, null) {
            @Override public void login(String u, String p) { /* success no-op */ }
        };
        LoginViewModel vm = new LoginViewModel(auth, capturingUi);

        vm.login();

        // The dispatcher was invoked (writes were marshalled, not done inline on the caller thread)…
        assertFalse(dispatched.isEmpty(), "property writes must be routed through the ui dispatcher");
        // …and after running, the observable state reflects the successful login.
        assertTrue(vm.loggedIn().get());
        assertFalse(vm.busy().get());
        assertEquals("", vm.errorMessage().get());
    }
}
