package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.ConfigApi;
import com.company.pos.terminal.api.ProblemDetail;
import com.company.pos.terminal.api.dto.SettingView;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettingsViewModelTest {

    private SettingsViewModel vm(ConfigApi api) {
        return new SettingsViewModel(api, Runnable::run);
    }

    @Test
    void loadSettingsReturnsListAndClearsError() {
        ConfigApi api = new ConfigApi(null) {
            @Override public List<SettingView> list() {
                return List.of(new SettingView("STORE_NAME", "store.name", "My Store", "My Store", "STRING"));
            }
        };
        SettingsViewModel vm = vm(api);
        assertEquals(1, vm.loadSettings().size());
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void updateRejectsInvalidValueWithoutCallingApi() {
        boolean[] called = {false};
        ConfigApi api = new ConfigApi(null) {
            @Override public void update(String name, String value) {
                called[0] = true;
            }
        };
        SettingsViewModel vm = vm(api);
        assertFalse(vm.update("VAT_RATE", "abc", "DECIMAL"));
        assertFalse(called[0]);
        assertEquals("Must be a number", vm.errorMessage().get());
    }

    @Test
    void updateValidCallsApiAndReturnsTrue() {
        boolean[] called = {false};
        ConfigApi api = new ConfigApi(null) {
            @Override public void update(String name, String value) {
                called[0] = true;
            }
        };
        SettingsViewModel vm = vm(api);
        assertTrue(vm.update("VAT_RATE", "0.15", "DECIMAL"));
        assertTrue(called[0]);
        assertEquals("", vm.errorMessage().get());
    }

    @Test
    void deferredDispatcherHoldsErrorUntilDrained() {
        ConfigApi api = new ConfigApi(null) {
            @Override public List<SettingView> list() {
                throw new ApiException(500, new ProblemDetail("Error", 500, "boom"), "HTTP 500");
            }
        };
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        SettingsViewModel vm = new SettingsViewModel(api, queue::add);
        assertNull(vm.loadSettings());
        assertEquals("", vm.errorMessage().get());   // deferred: not applied yet
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
        assertEquals("boom", vm.errorMessage().get());
    }
}
