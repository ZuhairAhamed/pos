package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.AuthApi;
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
}
