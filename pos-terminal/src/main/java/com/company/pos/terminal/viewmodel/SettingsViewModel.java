package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.ConfigApi;
import com.company.pos.terminal.api.dto.SettingView;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the store-settings screen. Synchronous like the other VMs — the controller runs it
 * off the FX thread via FxTasks and reads the return value; the only observable written off-thread is
 * {@code errorMessage}, inside the {@code ui} dispatcher.
 */
public class SettingsViewModel {

    private final ConfigApi api;
    private final Consumer<Runnable> ui;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public SettingsViewModel(ConfigApi api, Consumer<Runnable> ui) {
        this.api = api;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    public List<SettingView> loadSettings() {
        try {
            List<SettingView> list = api.list();
            ui.accept(() -> errorMessage.set(""));
            return list;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public boolean update(String name, String value, String type) {
        String err = SettingValidation.validate(type, value);
        if (err != null) {
            ui.accept(() -> errorMessage.set(err));
            return false;
        }
        try {
            api.update(name, value);
            ui.accept(() -> errorMessage.set(""));
            return true;
        } catch (ApiException e) {
            fail(e);
            return false;
        }
    }

    private void fail(ApiException e) {
        String msg = messageOf(e);
        ui.accept(() -> errorMessage.set(msg));
    }

    private String messageOf(ApiException e) {
        if (e.problem() != null) {
            if (e.problem().detail() != null && !e.problem().detail().isBlank()) {
                return e.problem().detail();
            }
            if (e.problem().title() != null && !e.problem().title().isBlank()) {
                return e.problem().title();
            }
        }
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        return "Request failed";
    }
}
