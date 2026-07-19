package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.CreateUserRequest;
import com.company.pos.terminal.api.UpdateUserRequest;
import com.company.pos.terminal.api.UserView;
import com.company.pos.terminal.api.UsersApi;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for staff administration. Synchronous like the other VMs — the controller runs it off
 * the FX thread via FxTasks and reads the return value; the only observable written off-thread is
 * {@code errorMessage}, inside the {@code ui} dispatcher. Read/mutate methods return a plain value
 * (list / view / boolean) or null-or-false on failure, with {@code errorMessage} set.
 */
public class UserAdminViewModel {

    private final UsersApi api;
    private final Consumer<Runnable> ui;
    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public UserAdminViewModel(UsersApi api, Consumer<Runnable> ui) {
        this.api = api;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() {
        return errorMessage.getReadOnlyProperty();
    }

    public List<UserView> load(boolean includeDisabled) {
        try {
            List<UserView> list = api.list(includeDisabled);
            ui.accept(() -> errorMessage.set(""));
            return list;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public UserView create(CreateUserRequest req) {
        try {
            UserView v = api.create(req);
            ui.accept(() -> errorMessage.set(""));
            return v;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public UserView update(UUID id, UpdateUserRequest req) {
        try {
            UserView v = api.update(id, req);
            ui.accept(() -> errorMessage.set(""));
            return v;
        } catch (ApiException e) {
            fail(e);
            return null;
        }
    }

    public boolean resetPassword(UUID id, String value) {
        return voidCall(() -> api.resetPassword(id, value));
    }

    public boolean resetPin(UUID id, String value) {
        return voidCall(() -> api.resetPin(id, value));
    }

    public boolean deactivate(UUID id) {
        return voidCall(() -> api.deactivate(id));
    }

    public boolean reactivate(UUID id) {
        return voidCall(() -> api.reactivate(id));
    }

    private boolean voidCall(Runnable call) {
        try {
            call.run();
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
